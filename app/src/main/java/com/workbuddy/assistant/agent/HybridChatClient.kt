package com.workbuddy.assistant.agent

import android.content.Context
import android.util.Log
import com.workbuddy.assistant.agent.ondevice.DeviceProfiler
import com.workbuddy.assistant.agent.ondevice.DeviceProfile
import com.workbuddy.assistant.agent.ondevice.DocSummary
import com.workbuddy.assistant.agent.ondevice.DownloadState
import com.workbuddy.assistant.agent.ondevice.EngineRouter
import com.workbuddy.assistant.agent.ondevice.EngineState
import com.workbuddy.assistant.agent.ondevice.GenParams
import com.workbuddy.assistant.agent.ondevice.GenStats
import com.workbuddy.assistant.agent.ondevice.KnowledgeStatus
import com.workbuddy.assistant.agent.ondevice.MediaPipeEngineFactory
import com.workbuddy.assistant.agent.ondevice.ModelCatalog
import com.workbuddy.assistant.agent.ondevice.ModelRepository
import com.workbuddy.assistant.agent.ondevice.ModelTier
import com.workbuddy.assistant.agent.ondevice.OnDeviceLlm
import com.workbuddy.assistant.agent.ondevice.OnDeviceModel
import com.workbuddy.assistant.agent.ondevice.OnDeviceRetriever
import com.workbuddy.assistant.agent.ondevice.RetrievalHit
import com.workbuddy.assistant.agent.ondevice.RetrieverState
import com.workbuddy.assistant.agent.ondevice.RouteDecision
import com.workbuddy.assistant.agent.ondevice.RoutePolicy
import com.workbuddy.assistant.agent.ondevice.RouteTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7 原理课 6】HybridChatClient —— 端云混合的"总装车间"
 * ══════════════════════════════════════════════════════════════════
 *
 * 这个类是整个 W7 的收口，它把前面所有零件缝在一起：
 *
 *   DeviceProfiler  ──┐
 *   ModelRepository ──┤
 *   MediaPipeEngine ──┼──▶  HybridChatClient  ──▶  ChatClient 接口（不变）
 *   EngineRouter    ──┘
 *
 * ── 最妙的一点：ChatViewModel 完全不知道端侧的存在 ─────────────
 * 它拿到的仍然是 W3 定义的 `ChatClient` 接口，方法签名一个字没改：
 *
 *     interface ChatClient {
 *         suspend fun streamChat(userText, onFirstToken, onDelta): String
 *     }
 *
 * 这就是 W3 那个接口的价值兑现时刻（面试可以这样讲）：
 * "W3 我抽象出 ChatClient 时只是不想让 ViewModel 依赖 LangChain4j，
 *  W7 加端侧时，这个接口让我在 ViewModel 里一行代码都不用改 ——
 *  这就是依赖倒置的复利。"
 *
 * ── 三条必须做对的工程细节 ─────────────────────────────────────
 *  ① 降级必须无缝：端侧加载失败/超时，不能抛给用户"加载失败"就完了，
 *     要自动接上云端，让用户完全无感（顶多 UI 徽标变一下）。
 *     端侧是"增强"，不是"依赖" —— 这个定位决定了所有错误处理的方向。
 *  ② 加载要加锁：用户可能连点发送，两次并发调 ensureLoaded() 会
 *     加载两份 700MB 模型 → 直接 OOM。用 Mutex 串行化。
 *  ③ 端侧历史要手搓：端侧不走 LangChain4j，没有 ChatMemory 帮我们拼历史。
 *     这里我们自己维护一个极短的滑动窗口（2 轮），因为 1B 模型的
 *     上下文极其宝贵，塞太多历史会让它彻底跑偏。
 * ══════════════════════════════════════════════════════════════════
 */
class HybridChatClient(
    private val context: Context,
    private val cloud: ChatClient,
) : ChatClient {

    private val repository = ModelRepository(context)

    /**
     * 【W7.5】端侧知识库检索器。
     *
     * 放在 repository 之后（构造函数按声明顺序赋值，retriever 的构造需要 repository）。
     * 注意：它的构造**不做 IO、不加载模型** —— 索引和向量模型都是懒加载，
     * 否则 App 冷启动会多出几百毫秒，而绝大多数用户根本不点知识库。
     */
    private val retriever = OnDeviceRetriever(context, repository)

    private val router = EngineRouter(context)
    private val profile: DeviceProfile = DeviceProfiler.profile(context)

    /** 端侧引擎（懒创建：只有真的要走端侧时才加载模型） */
    @Volatile
    private var engine: OnDeviceLlm? = null

    /**
     * 当前引擎加载的是哪个模型。
     *
     * 为什么要记这个？因为"删除模型"必须知道"哪个文件正被 native 句柄引用着" ——
     * 删一个正在被 LlmInference 使用的文件，行为是未定义的（Linux 上文件会
     * 删除但仍被持有，直到 close；但下次加载会找不到文件）。
     * 正确顺序永远是：先 release() 释放 native 句柄，再删文件。
     */
    @Volatile
    private var loadedModel: OnDeviceModel? = null

    /** 加载锁：防止并发加载导致内存翻倍 */
    private val loadMutex = Mutex()

    private val prefs = context.getSharedPreferences("on_device", Context.MODE_PRIVATE)

    /**
     * 端侧对话历史（手搓的最小滑动窗口，最多 2 轮）。
     *
     * ⚠️⚠️ 【声明位置不是随手写的，动它之前先读完这段】⚠️⚠️
     * Kotlin 的类字段**按声明顺序在构造函数里逐个赋值**（等价于 Java 里
     * 一长串 this.x = ... 从上往下排）。而下面的 _deviceStatus 在**初始化
     * 那一刻**就会调用 buildInitialStatus()，那个方法里读了这个字段。
     *
     * 如果把它写在 _deviceStatus 后面（我就是这么写的，真机直接崩）：
     *   _deviceStatus 执行时 deviceHistory 还是引用类型的默认值 null，
     *   读 .size 立刻 NPE：Attempt to invoke virtual method
     *   'int kotlin.collections.ArrayDeque.size()' on a null object reference
     * 而且崩溃堆栈指向的是 buildInitialStatus() 那一行，不是字段声明处，
     * 第一眼很容易误以为是 MediaPipe / 端侧模型的问题。
     *
     * 【规则】**被构造期代码（属性初始化、init 块）读到的字段，必须声明在
     * 读取它的那个属性之前**。Kotlin 只对"局部变量前向引用"报编译错，
     * 对成员字段的前向引用是合法的 —— 编译器不会救你，只有运行时会崩。
     */
    private val deviceHistory = ArrayDeque<Pair<String, String>>()

    private val _policy = MutableStateFlow(
        runCatching { RoutePolicy.valueOf(prefs.getString(KEY_POLICY, null) ?: "") }
            .getOrDefault(RoutePolicy.AUTO)
    )
    val policy: StateFlow<RoutePolicy> = _policy.asStateFlow()

    /** 端侧状态：UI 拿它画徽标、决定是否显示"下载模型"入口 */
    private val _deviceStatus = MutableStateFlow(buildInitialStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    /** 最近一次路由决策 —— UI 显示"为什么走这条路" */
    private val _lastDecision = MutableStateFlow<RouteDecision?>(null)
    val lastDecision: StateFlow<RouteDecision?> = _lastDecision.asStateFlow()

    /** 最近一次端侧性能账（prefill/decode），只有端侧回答时才非空 */
    private val _lastStats = MutableStateFlow<GenStats?>(null)
    val lastStats: StateFlow<GenStats?> = _lastStats.asStateFlow()

    // ══════════════════════════════════════════════════════════════
    // 对外：策略与状态管理
    // ══════════════════════════════════════════════════════════════

    fun setPolicy(p: RoutePolicy) {
        _policy.value = p
        prefs.edit().putString(KEY_POLICY, p.name).apply()
        Log.i(TAG, "路由策略切换: ${p.label}")
    }

    /**
     * 预加载端侧模型。
     *
     * 为什么单独暴露这个方法而不在构造里做？
     * 因为加载是 3-8 秒的重操作，不能拖慢 App 冷启动。
     * 正确时机是"用户主动开启端侧"或"进入设置页"时，由 UI 触发。
     */
    suspend fun prepareDevice(): DeviceStatus {
        val before = _deviceStatus.value
        if (before.state is EngineState.Ready) return before

        return loadMutex.withLock {
            // 双重检查：等锁期间别人可能已经加载好了
            engine?.let {
                val s = it.state
                if (s is EngineState.Ready) return@withLock updateStatus(s)
            }

            val eng = engine ?: MediaPipeEngineFactory(repository).create(context)?.also {
                engine = it
                // 记下工厂实际挑中的模型：删模型时要靠它判断"是否正在被使用"。
                // 这里和工厂内部各挑了一次（同样的 pickFor 逻辑，结果一致）——
                // 之所以不把工厂的返回值改成"带模型信息"，是为了保持
                // OnDeviceLlmFactory 这个契约足够简单（W8 接 MNN 时它会更好用）。
                loadedModel = resolveUsableModel()
            } ?: return@withLock updateStatus(EngineState.NotInstalled)

            _deviceStatus.value = _deviceStatus.value.copy(state = EngineState.Loading)
            val result = eng.ensureLoaded()
            updateStatus(result)
        }
    }

    /** 释放端侧内存（切到"仅云端"或 App 退到后台时调用） */
    fun releaseDevice() {
        engine?.release()
        engine = null
        loadedModel = null
        Log.i(TAG, "端侧引擎已释放")
        _deviceStatus.value = _deviceStatus.value.copy(state = EngineState.Idle)
    }

    /**
     * 构造期就执行的方法（被 _deviceStatus 的初始化调用）。
     *
     * 【约束】它只能读**声明在 _deviceStatus 之前**的字段 ——
     * 也就是 repository / profile / deviceHistory 这三个。
     * 后面那些（_lastDecision / _lastStats / engine）此刻全是 null，
     * 一旦在这里读它们就是同样的 NPE。
     *
     * 这类"构造期方法"的最佳实践是**只做无副作用的纯计算**，
     * 别在里面发起网络、读文件、碰引擎 —— 那些都该等 UI 触发。
     */
    private fun buildInitialStatus(): DeviceStatus {
        val tier = DeviceProfiler.recommendTier(profile)
        val model = resolveUsableModel(tier)
        val available = model != null
        // 构造期必然是 0（历史只在对话中增长），但要写成动态读取，
        // 这样将来别处重构复用时不会悄悄拿到过期的值。
        val historyCount = deviceHistory.size
        return DeviceStatus(
            state = if (available) EngineState.Idle else EngineState.NotInstalled,
            profile = profile,
            recommendTier = tier,
            modelAvailable = available,
            modelName = model?.displayName ?: "",
            installedFiles = scanInstalledFiles(),
            modelFileSizeMb = model?.approxSizeMb ?: 0,
            historyTurns = historyCount,
        )
    }

    /**
     * 扫描磁盘上已下载好的模型。
     *
     * 这个方法是**纯磁盘查询**（每个模型看几个路径），很快，
     * 但也不要放进 Compose 的重组路径里 —— 所以结果缓存在 DeviceStatus。
     */
    private fun scanInstalledFiles(): Set<String> =
        ModelCatalog.models.filter { repository.isAvailable(it) }
            .map { it.fileName }
            .toSet()

    /**
     * 找出当前设备上一个"已下载好、可以直接加载"的模型。
     *
     * 两级查找（顺序有意义，别反过来）：
     *   ① 先按设备推荐档位挑 —— 尊重内存约束，避免用户下大了跑不动；
     *   ② 挑不到就接受任意已下载的模型 —— 用户可能下的是另一档，
     *      "有得用"永远优于"严格匹配档位却没有模型"。
     */
    private fun resolveUsableModel(
        tier: ModelTier? = DeviceProfiler.recommendTier(profile),
    ): OnDeviceModel? =
        ModelCatalog.pickFor(tier)?.takeIf { repository.isAvailable(it) }
            ?: ModelCatalog.mediaPipeModels.firstOrNull { repository.isAvailable(it) }

    private fun updateStatus(state: EngineState): DeviceStatus {
        _deviceStatus.value = _deviceStatus.value.copy(state = state)
        return _deviceStatus.value
    }

    // ══════════════════════════════════════════════════════════════
    // 【本次新增】模型下载 —— 用户不必再用 adb push
    // ══════════════════════════════════════════════════════════════
    //
    // 这一块刻意做得很"薄"：真正下文件的逻辑全在 ModelRepository，
    // 这里只做三件事 —— 暴露清单、暴露进度、在合适的时机刷新端侧状态。
    //
    // 为什么不在下载完成时直接这里自动加载模型？
    // 因为"下载"和"加载"是两件应该分开的事：
    //   · 下载是网络 + 磁盘操作，用户可能想先下好、晚点再用；
    //   · 加载要吃掉 800MB~1.9GB 内存，**必须由用户明确触发**
    //     （否则用户一进设置页就莫名卡顿，甚至被系统杀后台）。
    // 端侧产品里这个"延迟加载"是硬要求，不是优化。

    /** 可选模型清单（UI 的"模型市场"） */
    val models: List<OnDeviceModel> get() = ModelCatalog.models

    /** 下载状态：UI 直接 collect 它画进度条 */
    val downloadState: StateFlow<DownloadState> get() = repository.downloadState

    /** 开始/继续下载（若已有任务在跑，内部会忽略） */
    fun downloadModel(model: OnDeviceModel) = repository.startDownload(model)

    /** 取消下载（保留已下载部分，可续传） */
    fun cancelDownload() = repository.cancelDownload()

    /** 清掉"已完成/已失败/已取消"的提示状态 */
    fun clearDownloadState() = repository.clearState()

    /**
     * 删除模型文件。
     *
     * 顺序**不能反**：先释放 native 句柄，再删文件。
     * 顺序反了在 Linux 上不会立刻出错（文件被删除但句柄仍有效），
     * 但下次加载就会"文件不存在"，而用户明明刚看到它在列表里 ——
     * 这种延迟出现的错位极难排查。
     */
    fun deleteModel(model: OnDeviceModel): Boolean {
        if (loadedModel?.fileName == model.fileName) {
            releaseDevice()
        }
        val ok = repository.delete(model)
        if (ok) refreshDeviceStatus()
        return ok
    }

    /**
     * 重新计算端侧可用状态 —— 下载完成、删除模型后调用。
     *
     * 注意保留已有的 Ready 状态：如果模型正加载在内存里，
     * 不能因为"重新算了一遍"就把状态打回 Idle（那会让 UI 显示"待启动"，
     * 而实际上引擎好好的）。
     */
    fun refreshDeviceStatus(): DeviceStatus {
        val model = resolveUsableModel()
        val available = model != null
        val current = _deviceStatus.value.state
        _deviceStatus.value = _deviceStatus.value.copy(
            state = when {
                current is EngineState.Ready || current is EngineState.Loading -> current
                available -> EngineState.Idle
                else -> EngineState.NotInstalled
            },
            modelAvailable = available,
            modelName = model?.displayName ?: "",
            installedFiles = scanInstalledFiles(),
            modelFileSizeMb = model?.approxSizeMb ?: 0,
        )
        Log.i(TAG, "端侧状态刷新: available=$available, state=${_deviceStatus.value.state}")
        return _deviceStatus.value
    }

    /** App 退出：取消在途下载（半截文件保留，下次续传） */
    fun shutdown() = repository.shutdown()

    // ══════════════════════════════════════════════════════════════
    // 【W7.5】端侧知识库 —— 这一块全部是"薄转发"
    // ══════════════════════════════════════════════════════════════
    //
    // 为什么 HybridChatClient 要当二传手，而不让 UI 直接持有 Retriever？
    // 因为 UI 一旦直接依赖 OnDeviceRetriever，将来换检索引擎（比如 W8 换成
    // MNN 自带的 embedding、或接 sqlite-vec）就要改 UI。
    // 保持"UI → HybridChatClient → Retriever"的单向依赖，换实现时只动中间一层。
    // 这和 W3 抽 ChatClient 是同一个动机，只是颗粒度更细。

    val knowledgeStatus: StateFlow<KnowledgeStatus> get() = retriever.status
    val retrieverState: StateFlow<RetrieverState> get() = retriever.state

    /** 向量模型（6MB），UI 上单独一行，不放进"对话模型"列表 */
    val embeddingModel: OnDeviceModel get() = ModelCatalog.embedder

    fun isEmbeddingModelInstalled(): Boolean = retriever.isModelInstalled()

    fun knowledgeDocs(): List<DocSummary> = retriever.documents()

    suspend fun addKnowledge(title: String, text: String): Result<Int> =
        retriever.addDocument(title, text)

    suspend fun deleteKnowledge(id: String): Boolean = retriever.deleteDocument(id)

    suspend fun clearKnowledge(): Int = retriever.clearAll()

    /** 手动加载向量模型（用户下完模型后点"启用语义检索"） */
    suspend fun loadRetriever(): RetrieverState = retriever.ensureLoaded()

    fun releaseRetriever() = retriever.release()


    // ══════════════════════════════════════════════════════════════
    // 核心：ChatClient 接口实现 —— 路由分发
    // ══════════════════════════════════════════════════════════════

    override suspend fun streamChat(
        userText: String,
        onFirstToken: () -> Unit,
        onDelta: (String) -> Unit,
    ): String {
        val engineReady = engine?.state is EngineState.Ready

        // ══════════════════════════════════════════════════════════
        // 【W7.5】本地知识库优先 —— 这里解决了 RAG 路由的一个真矛盾
        // ══════════════════════════════════════════════════════════
        // 矛盾：EngineRouter 看到"我的笔记""资料"这类词会把请求判给**云端**，
        // 理由是"1B 模型做不了复杂检索"。但端侧知识库建好之后，
        // 云端**根本访问不到**手机里的索引 —— 判给云端 = 必然答不出来。
        //
        // 所以顺序要反过来：先问本地知识库"你认识这个问题吗"。
        //
        // ⚠️ 检索必须**无条件执行**（曾经的真实 bug：套在 engineReady 里）。
        // 旧写法是 `if (engineReady) search else emptyList()`，后果：
        //   · App 刚启动、引擎还在加载的那几秒；
        //   · 端侧模型加载失败；
        //   · 用户还没下载对话模型；
        // 这三种情况下检索被整体跳过，笔记问题裸奔到云端，
        // 而云端没有索引 → 用户看到"导入了笔记却一问三不知"。
        //
        // 正确姿势是把"检索"和"生成"彻底解耦：
        //   检索（确定性算法，几十 ms） → 永远在本地跑，不依赖引擎状态
        //   生成（重计算）             → 端侧就绪走端侧；否则**带着资料**走云端
        // "本地查资料 + 云端写答案"依然是合法的 RAG ——
        // 隐私边界由"把哪几段文本发了出去"决定，而不是"哪个进程算的"。
        val hits: List<RetrievalHit> =
            withContext(Dispatchers.IO) { retriever.search(userText) }

        val decision = if (hits.isNotEmpty()) {
            if (engineReady) {
                RouteDecision(
                    RouteTarget.DEVICE,
                    "命中本地知识库 ${hits.size} 段，走端侧生成",
                )
            } else {
                RouteDecision(
                    RouteTarget.CLOUD,
                    "命中本地知识库 ${hits.size} 段，但端侧未就绪，携资料走云端",
                )
            }
        } else {
            router.decide(
                text = userText,
                policy = _policy.value,
                deviceReady = engineReady,
                deviceReason = deviceUnavailableReason(),
            )
        }
        _lastDecision.value = decision
        Log.i(TAG, "路由决策 → ${decision.target}（${decision.reason}）")

        return when (decision.target) {
            RouteTarget.DEVICE -> {
                // ── 端侧路径 ──
                runCatching {
                    deviceStream(userText, hits, onFirstToken, onDelta)
                }.getOrElse { e ->
                    // 【关键】端侧失败静默降级云端 —— 用户不该因为"模型加载失败"
                    // 就用不了 App。端侧永远是增强项，不是依赖项。
                    // 注意：降级时**必须带着已检索到的资料**（withKnowledgeContext），
                    // 否则用户会看到"刚才还能答笔记问题，突然又答不上"的诡异抖动。
                    Log.w(TAG, "端侧推理失败，自动降级云端: ${e.message}", e)
                    _lastDecision.value = RouteDecision(
                        RouteTarget.CLOUD,
                        "端侧失败已回退云端（${e.javaClass.simpleName}）"
                    )
                    _lastStats.value = null
                    cloud.streamChat(withKnowledgeContext(userText, hits), onFirstToken, onDelta)
                }
            }

            RouteTarget.CLOUD -> {
                _lastStats.value = null
                cloud.streamChat(withKnowledgeContext(userText, hits), onFirstToken, onDelta)
            }
        }
    }

    /**
     * 给云端的输入拼上本地检索结果。
     *
     * 为什么不用 buildDevicePrompt（端侧那套）？
     * 云端模型指令遵循能力强，不需要"用户：/助手："这种手搓格式
     * （那是 LangChain4j 的 ChatMemory 在管），只要把资料以清晰的
     * 结构放进同一条消息即可。两个 prompt 构造器并存是有意的：
     * 端侧 prompt 的每条"铁律"都是被 1B 模型的能力倒逼出来的，
     * 照搬到云端反而是不必要的枷锁。
     *
     * 没有命中时原样返回 —— 对不走知识库的普通问题零侵入。
     */
    private fun withKnowledgeContext(userText: String, hits: List<RetrievalHit>): String {
        if (hits.isEmpty()) return userText
        return buildString {
            append("请优先依据下面的资料回答用户问题；资料里没有的信息再用你自己的知识，")
            append("并注明哪些是资料之外的补充。\n\n")
            append(retriever.buildContext(hits, 2400))   // 云端的上下文预算可以放宽
            append("\n\n问题：").append(userText)
        }
    }

    /**
     * 端侧生成。
     *
     * 线程安排（和云端完全不同的原因）：
     *   云端 streamChat 靠 OkHttp 的异步回调，挂在协程上等就行；
     *   端侧 streamGenerate 是**同步阻塞的本地计算**，必须显式切到 IO 线程，
     *   否则主线程会被 3-20 秒的推理彻底冻住 → ANR。
     */
    private suspend fun deviceStream(
        userText: String,
        hits: List<RetrievalHit>,
        onFirstToken: () -> Unit,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: throw IllegalStateException("端侧引擎未初始化")

        // 兜底加载：路由说端侧就绪，但引擎可能已被系统回收
        val st = eng.ensureLoaded()
        if (st !is EngineState.Ready) {
            throw IllegalStateException("端侧引擎不可用: $st")
        }

        val prompt = buildDevicePrompt(userText, hits)
        var firstFired = false
        val buffer = StringBuilder()

        val stats = eng.streamGenerate(prompt, GenParams()) { delta ->
            if (!firstFired) {
                firstFired = true
                onFirstToken()
            }
            buffer.append(delta)
            onDelta(delta)
        }

        _lastStats.value = stats
        Log.i(
            TAG,
            "端侧生成完成: TTFT=${stats.ttftMs}ms, decode=${String.format("%.1f", stats.decodeCps)} 字/秒" +
                if (hits.isEmpty()) "" else ", 参考资料 ${hits.size} 段"
        )

        // 写回端侧历史（滑动窗口）
        deviceHistory.addLast(userText to buffer.toString())
        while (deviceHistory.size > DEVICE_HISTORY_TURNS) deviceHistory.removeFirst()
        _deviceStatus.value = _deviceStatus.value.copy(historyTurns = deviceHistory.size)

        buffer.toString()
    }

    /**
     * 构造端侧 prompt。
     *
     * 【端侧 prompt 工程 3 条铁律】
     *  1. **短**：小模型的 prefill 开销和指令遵循是反比 —— 越长越慢越不听话。
     *  2. **结构化**：用明确的角色标记（用户：/助手：），别用自然语言描述格式。
     *  3. **截断**：历史要限轮数、限长度。这里 2 轮 × 200 字是一条硬线。
     *
     * 【RAG 拼接的 4 个要点】
     *  ① 资料必须**带标题和编号**：小模型分不清"哪段是哪段"，编号能让它引用时对得上。
     *  ② 指令必须给"不知道"的出口：不给出口，检索没命中时它一定会编。
     *  ③ 有资料时历史只带 1 轮：KV Cache 一共 1280 token，资料已经吃掉大半，
     *     再塞 2 轮历史会导致模型"还没读完就被截断"。
     *  ④ 资料放前面、问题放后面：小模型对"最后出现的内容"注意力最强，
     *     问题放在末尾比放在开头命中率高（这也是长上下文里"lost in the middle"的朴素应用）。
     */
    private fun buildDevicePrompt(userText: String, hits: List<RetrievalHit>): String {
        val sb = StringBuilder()

        if (hits.isNotEmpty()) {
            sb.append("请只依据下面资料回答。资料里没有的信息，直接说「资料里没有提到」。\n\n")
            sb.append(retriever.buildContext(hits))
            sb.append("\n\n")
        }

        // 有 RAG 资料时只保留 1 轮历史 —— 见上面第 ③ 条
        val turns = if (hits.isEmpty()) deviceHistory else deviceHistory.takeLast(1)
        for ((u, a) in turns) {
            sb.append("用户：").append(u.take(MAX_HISTORY_CHARS)).append('\n')
            sb.append("助手：").append(a.take(MAX_HISTORY_CHARS)).append('\n')
        }
        sb.append("用户：").append(userText).append('\n')
        sb.append("助手：")
        return sb.toString()
    }

    private fun deviceUnavailableReason(): String = when (val s = _deviceStatus.value.state) {
        is EngineState.Ready -> "端侧就绪"
        is EngineState.Loading -> "端侧加载中，本次走云端"
        is EngineState.Idle, EngineState.NotInstalled -> "端侧模型未安装，走云端"
        is EngineState.Failed -> "端侧加载失败（${s.reason}），走云端"
    }

    private companion object {
        const val TAG = "ON_DEVICE"
        const val KEY_POLICY = "route_policy"
        const val DEVICE_HISTORY_TURNS = 2
        const val MAX_HISTORY_CHARS = 200
    }
}

/**
 * 端侧状态快照 —— 给 UI 用的一个"打包数据"。
 *
 * 为什么不直接把几个 StateFlow 拆开给 UI？
 * 因为 UI 关心的是"我现在该显示什么"，这是一个整体判断：
 * 有模型吗？设备跑得动吗？加载到哪一步了？
 * 拆成 5 个 StateFlow 会让 Compose 层做 5 次 collect 和一堆 when ——
 * **在数据源头聚合，而不是在 UI 层聚合**，是状态管理的通用原则。
 */
data class DeviceStatus(
    val state: EngineState,
    val profile: DeviceProfile,
    val recommendTier: ModelTier?,
    val modelAvailable: Boolean,
    /** 当前"首选可用"的模型名（没下载任何模型时为空串） */
    val modelName: String = "",
    /**
     * 已下载好的模型文件名集合。
     *
     * 为什么要把整个集合给 UI，而不是只给"当前用的那个"？
     * 因为设置页是一个**模型市场**：用户要看到每个模型的下载状态
     * （未下载 / 已下载 / 删除），才能自己决定装哪个。
     * 这个集合是 UI 渲染列表的唯一依据 —— 让 UI 自己一个个去查
     * repository 会让 Compose 每帧做 IO，那是性能事故。
     */
    val installedFiles: Set<String> = emptySet(),
    val modelFileSizeMb: Int,
    val historyTurns: Int,
) {
    /** 某个模型是否已下载到本地 */
    fun isInstalled(model: OnDeviceModel): Boolean = model.fileName in installedFiles

    val isReady: Boolean get() = state is EngineState.Ready

    /** 一行摘要，给 TopAppBar 副标题用 */
    val shortLabel: String
        get() = when (state) {
            is EngineState.Ready -> "端侧在线"
            is EngineState.Loading -> "端侧加载中…"
            is EngineState.Idle -> "端侧待启动"
            is EngineState.NotInstalled -> "端侧未安装"
            is EngineState.Failed -> "端侧异常"
        }
}
