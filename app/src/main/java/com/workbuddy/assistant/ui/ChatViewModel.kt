package com.workbuddy.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.workbuddy.assistant.agent.ChatClient
import com.workbuddy.assistant.agent.HybridChatClient
import com.workbuddy.assistant.agent.LangChain4jChatClient
import com.workbuddy.assistant.agent.mcp.McpToolProvider
import com.workbuddy.assistant.agent.ondevice.DocSummary
import com.workbuddy.assistant.agent.ondevice.DownloadState
import com.workbuddy.assistant.agent.ondevice.EngineState
import com.workbuddy.assistant.agent.ondevice.KnowledgeStatus
import com.workbuddy.assistant.agent.ondevice.OnDeviceModel
import com.workbuddy.assistant.agent.ondevice.RetrieverState
import com.workbuddy.assistant.agent.ondevice.RoutePolicy
import com.workbuddy.assistant.agent.ondevice.RouteTarget
import com.workbuddy.assistant.model.MessageSource
import com.workbuddy.assistant.model.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ══════════════════════════════════════════════════════════════════
 * 【原理课 3】MVVM + 单向数据流 —— "状态驱动 UI"
 * ══════════════════════════════════════════════════════════════════
 *
 * 数据流永远是这个方向（Compose 官方架构）：
 *
 *   UI 事件 ──▶ ViewModel 方法 ──▶ 修改 StateFlow ──▶ Compose 重组 ──▶ 新 UI
 *   (点击发送)    (send)            (messages.value=…)   (自动)
 *
 * 关键点：ChatScreen 里没有任何一行"手动刷新列表"的代码。
 * messages 每变一次，LazyColumn 自动重画 —— 这就是"声明式 UI"。
 * 每秒几十个 token = messages 每秒变几十次 = UI 每秒重画几十次，
 * Compose 的智能重组（只重画变化的那一条 item）让它毫不卡顿。
 *
 * 【W3 变化】"请求层记忆"交给了框架：
 *   W1 时这里每次 send 都要手拼 history（system + 全部历史 role/content）；
 *   W3 换成 LangChain4j 后，这段代码整个消失了 —— ChatMemory 在框架内部
 *   做着完全一样的事（模型依然无状态！只是拼历史的活儿换人干了）。
 *
 * 【W4 变化】ViewModel → AndroidViewModel：
 *   工具（DeviceTools）要访问系统服务和 SharedPreferences，需要 Context。
 *   正确姿势：AndroidViewModel(application) —— 框架把 Application 塞给你，
 *   ViewModelProvider 反射创建时自动匹配 (Application) 构造函数。
 *
 * 【W7 变化 —— 请注意这里几乎没变】────────────────────────────
 *   看下面这行：`private val chatClient: ChatClient = hybrid`
 *   类型仍然是 W3 定义的那个 ChatClient 接口，方法签名一个字没改。
 *   我们在它后面套了一层 HybridChatClient（端云路由），
 *   ViewModel 完全不知道"端侧"这件事的存在。
 *
 *   这就是 W3 抽象出接口的**复利兑现时刻**，面试可以这样讲：
 *   "W3 我把 ChatClient 抽出来，当时的动机只是不想让 ViewModel
 *    耦合 LangChain4j。W7 加端侧时，这个决定让我在 ViewModel 和 UI
 *    里一行都不用改 —— 端侧是插进来的，不是重构出来的。"
 *
 *   如果 W3 没有这个接口，W7 就要在 ViewModel 里写一大坨 if/else
 *   判断走端还是走云，还得把两个客户端的构造、线程、异常全揉进来。
 * ══════════════════════════════════════════════════════════════════
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // W1 的 SseChatClient.kt 保留在 network 包里作为"手写参考实现"，不再接入。
    // W4：把 applicationContext 传给客户端 → 传给工具集（电量/备忘录要用）。
    private val cloudClient: ChatClient = LangChain4jChatClient(application.applicationContext)

    /**
     * 【W7】端云混合客户端 —— 它就是 ChatClient 的一个实现。
     *
     * 注意构造参数：把 cloudClient 注入进去，而不是在内部 new。
     * 这叫"组合优于继承"，也让 HybridChatClient 可以单测
     * （塞一个假的 cloudClient 进去就能测路由逻辑）。
     */
    private val hybrid = HybridChatClient(application.applicationContext, cloudClient)

    /** UI 只认识这个接口，不知道底下有端有云 */
    private val chatClient: ChatClient = hybrid

    // ══════════════════════════════════════════════════════════════
    // 【W7】端云状态 —— 直接转发 hybrid 的 StateFlow 给 UI
    // 注意：这里没有 copy、没有 map、没有转换，纯粹转发。
    // 因为状态该在哪一层聚合，就在哪一层聚合（HybridChatClient 已经聚合好了），
    // ViewModel 只做"暴露"，不做"加工" —— 这能让状态流转的链路最短最好查。
    // ══════════════════════════════════════════════════════════════

    /** 当前路由策略（自动 / 仅云端 / 优先端侧） */
    val policy: StateFlow<RoutePolicy> = hybrid.policy

    /** 端侧引擎状态、设备画像、推荐档位 */
    val deviceStatus = hybrid.deviceStatus

    /** 最近一次路由决策（UI 显示"为什么走这条路"） */
    val lastDecision = hybrid.lastDecision

    /** 最近一次端侧性能账（TTFT / 解码速度） */
    val lastStats = hybrid.lastStats

    // ══════════════════════════════════════════════════════════════
    // 【本次新增】模型下载 —— 全部转发 hybrid，UI 不再需要 adb push
    // ══════════════════════════════════════════════════════════════
    //
    // ⚠️ 注意这些字段**必须声明在 init 块之前**。因为下面的 init 会监听
    //    _downloadMessage 并写它 —— Kotlin 按声明顺序初始化字段，写反了
    //    就是昨天刚踩过的那个 NPE（构造期读到 null）。
    //    这不是"风格问题"，是硬约束。

    /** 可下载的模型清单 */
    val models: List<OnDeviceModel> = hybrid.models

    /** 下载进度 / 结果 —— UI 直接画进度条 */
    val downloadState: StateFlow<DownloadState> = hybrid.downloadState

    /**
     * 给用户看的一句话提示（下载完成 / 失败 / 已暂停）。
     *
     * 为什么不直接让 UI 从 DownloadState 推导文案？
     * 因为 DownloadState 是"机器状态"，文案是"产品决策"：
     * 同一个 Failed 状态，将来可能因为错误类型不同给出不同的引导
     * （空间不足 → 引导清理；403 → 引导换模型；超时 → 引导重试）。
     * 把文案集中在 ViewModel，UI 层保持纯粹 —— 这也是 MVVM 里
     * "ViewModel 负责准备数据，UI 只负责画"的边界。
     */
    private val _downloadMessage = MutableStateFlow<String?>(null)
    val downloadMessage: StateFlow<String?> = _downloadMessage.asStateFlow()

    // ══════════════════════════════════════════════════════════════
    // 【W7.5】端侧知识库状态
    // ══════════════════════════════════════════════════════════════

    /** 文档数 / 块数 / 当前检索模式（向量+字面 / 仅字面） */
    val knowledgeStatus: StateFlow<KnowledgeStatus> = hybrid.knowledgeStatus

    /** 向量模型状态（未下载 / 加载中 / 就绪 / 失败） */
    val retrieverState: StateFlow<RetrieverState> = hybrid.retrieverState

    /** 向量模型描述（6MB，UI 上单独一行） */
    val embeddingModel: OnDeviceModel = hybrid.embeddingModel

    /**
     * 文档列表。
     *
     * 为什么不直接从 retriever 读？
     * 因为 documents() 首次调用会读磁盘索引（几 MB JSON），
     * 而 Compose 重组路径上**绝对不能做 IO**（每帧都会调，直接卡死）。
     * 所以用 StateFlow 缓存，只在"增删文档"时刷新 —— 这是把"昂贵读取"
     * 收敛到明确事件点上的标准做法。
     */
    private val _knowledgeDocs = MutableStateFlow<List<DocSummary>>(emptyList())
    val knowledgeDocs: StateFlow<List<DocSummary>> = _knowledgeDocs.asStateFlow()

    /** 知识库操作的人话反馈（导入成功 / 失败原因） */
    private val _knowledgeMessage = MutableStateFlow<String?>(null)
    val knowledgeMessage: StateFlow<String?> = _knowledgeMessage.asStateFlow()

    /**
     * 向量模型是否已下载。
     *
     * 为什么要单独做一个 StateFlow，而不是每次调 hybrid.isEmbeddingModelInstalled()？
     * 因为那个方法会**扫磁盘**（三个候选目录 × exists()）。Compose 每次重组
     * 都会读这个值，如果直接调方法，等于每帧做一次文件 IO —— 典型的掉帧来源。
     * 缓存成状态，只在"下载完成 / 删除"这两个明确事件点刷新。
     */
    private val _embedderInstalled = MutableStateFlow(hybrid.isEmbeddingModelInstalled())
    val embedderInstalled: StateFlow<Boolean> = _embedderInstalled.asStateFlow()

    init {
        // 【W4.5】MCP 预热：连接握手 + 工具发现都是网络操作，必须离开主线程
        // （ANR 红线）。provideTools() 在对话前只读缓存，所以这里越早启动，
        // 第一条消息就越可能带全 MCP 工具。
        viewModelScope.launch(Dispatchers.IO) {
            val provider = McpToolProvider.get()
            provider.warmUp()
            provider.ensureLoaded()

            // 【W7】端侧预热 —— 为什么不放主线程、也不放构造函数里同步做：
            // 加载 1B 模型要 3-8 秒、吃几百 MB 内存。放主线程 = ANR；
            // 放 UI 首次渲染前 = 冷启动白屏。正确姿势是"启动后后台静默加载"，
            // 用户读第一条消息时模型已经就绪，完全无感。
            //
            // 只有模型文件真的存在时才预热 —— 否则就是白白尝试一次必然失败的加载。
            if (hybrid.deviceStatus.value.modelAvailable) {
                hybrid.prepareDevice()
            }
        }

        // 【本次新增】监听下载状态。
        // 为什么下载完成不能直接自动加载模型？
        //   ① 加载要吃 800MB~1.9GB 内存，必须用户明确同意（否则一进设置页
        //      就突然卡顿，甚至被系统杀后台）；
        //   ② 用户可能只是"先下好备用"。
        // 所以这里只做两件事：刷新端侧可用状态 + 给一句人话提示。
        viewModelScope.launch {
            hybrid.downloadState.collect { st ->
                when (st) {
                    is DownloadState.Completed -> {
                        // 下载完 ≠ 能用：必须重新算一次"端侧是否可用"，
                        // 否则 topbar 还显示"端侧未安装"，用户会以为下失败了。
                        hybrid.refreshDeviceStatus()
                        // 【W7.5】如果下的是向量模型，它不进 DeviceStatus（不是对话模型），
                        // 要单独刷新这个标志位，否则 UI 会一直显示"未下载"。
                        if (st.model.fileName == hybrid.embeddingModel.fileName) {
                            _embedderInstalled.value = true
                            _knowledgeMessage.value =
                                "向量模型已下载，点「启用语义检索」即可打开向量召回"
                        }
                        _downloadMessage.value =
                            "「${st.model.displayName}」下载完成，点「加载模型」即可启用端侧推理"
                    }
                    is DownloadState.Failed -> {
                        _downloadMessage.value = "下载失败：${st.message}"
                    }
                    is DownloadState.Cancelled -> {
                        _downloadMessage.value = "下载已暂停，已保留进度，可再次点击继续"
                    }
                    else -> Unit
                }
            }
        }

        // 【W7.5】首次读取知识库索引。
        // 放 IO：index.json 里存着所有块的 Base64 向量，几 MB 的解析不能让主线程做。
        viewModelScope.launch(Dispatchers.IO) {
            _knowledgeDocs.value = hybrid.knowledgeDocs()
        }
    }

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** TTFT 埋点：首个 token 延迟（毫秒），UI 会显示出来 —— W2 要优化的指标 */
    private val _ttftMs = MutableStateFlow<Long?>(null)
    val ttftMs: StateFlow<Long?> = _ttftMs.asStateFlow()

    // ══════════════════════════════════════════════════════════════
    // 【W7】给 UI 的操作入口
    // ══════════════════════════════════════════════════════════════

    fun setPolicy(p: RoutePolicy) = hybrid.setPolicy(p)

    // ══════════════════════════════════════════════════════════════
    // 【本次新增】模型下载：下载 / 取消 / 删除
    // ══════════════════════════════════════════════════════════════

    /**
     * 开始下载。
     *
     * 注意这里**没有** viewModelScope.launch —— 下载跑在 ModelRepository
     * 自带的 scope 里。原因很实际：下载 500MB 要几分钟，用户很可能
     * 退出设置页去看消息、甚至旋转屏幕；如果挂在 viewModelScope 上，
     * ViewModel 一销毁下载就断了，用户回来看到进度归零。
     * **长耗时任务的生命周期要挂在"比 UI 长"的东西上**，这是通用原则。
     */
    fun downloadModel(model: OnDeviceModel) {
        _downloadMessage.value = null
        hybrid.downloadModel(model)
    }

    fun cancelDownload() = hybrid.cancelDownload()

    fun deleteModel(model: OnDeviceModel) {
        if (hybrid.deleteModel(model)) {
            _downloadMessage.value = "已删除「${model.displayName}」，磁盘空间已释放"
        }
    }

    /** 关掉提示（用户点了"知道了"）*/
    fun dismissDownloadMessage() { _downloadMessage.value = null }

    /** 清掉"已完成/失败"的下载状态 */
    fun clearDownloadState() = hybrid.clearDownloadState()

    /** 手动加载端侧模型（设置面板里的按钮） */
    fun prepareDevice() {
        viewModelScope.launch(Dispatchers.Default) {
            val status = hybrid.prepareDevice()
            android.util.Log.i("ON_DEVICE", "端侧准备结果: ${status.state}")
        }
    }

    /** 手动释放端侧内存 */
    fun releaseDevice() = hybrid.releaseDevice()

    // ══════════════════════════════════════════════════════════════
    // 【W7.5】端侧知识库操作
    // ══════════════════════════════════════════════════════════════
    //
    // 这些方法全部走 Dispatchers.IO：切块 + 向量化 + 落盘都是重活。
    // 特别是"向量化"——每块都要跑一次 6MB 的 TFLite 模型，
    // 一篇 3000 字的笔记约 10 块，在手机上要几百毫秒到两秒。
    // 放主线程 = 用户点"导入"后界面直接冻结。

    fun refreshKnowledgeDocs() {
        _knowledgeDocs.value = hybrid.knowledgeDocs()
    }

    fun addKnowledge(title: String, text: String) {
        if (text.isBlank()) {
            _knowledgeMessage.value = "内容不能为空"
            return
        }
        _knowledgeMessage.value = "正在切块并生成向量…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = hybrid.addKnowledge(title, text)
            val name = title.ifBlank { "未命名笔记" }
            result.fold(
                onSuccess = { n -> _knowledgeMessage.value = "已导入「$name」，切成 $n 块" },
                onFailure = { e -> _knowledgeMessage.value = "导入失败：${e.message}" },
            )
            _knowledgeDocs.value = hybrid.knowledgeDocs()
        }
    }

    fun deleteKnowledge(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = hybrid.deleteKnowledge(id)
            _knowledgeMessage.value = if (ok) "已删除该文档" else "删除失败：找不到该文档"
            _knowledgeDocs.value = hybrid.knowledgeDocs()
        }
    }

    fun clearKnowledge() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = hybrid.clearKnowledge()
            _knowledgeMessage.value = "已清空 $n 篇文档"
            _knowledgeDocs.value = hybrid.knowledgeDocs()
        }
    }

    /** 加载向量模型（下完 6MB 模型后点"启用语义检索"） */
    fun loadRetriever() {
        viewModelScope.launch(Dispatchers.Default) {
            _knowledgeMessage.value = when (val st = hybrid.loadRetriever()) {
                is RetrieverState.Ready -> "向量模型就绪：${st.dim} 维，耗时 ${st.loadMs}ms"
                is RetrieverState.NoModel -> "还没下载向量模型，当前仅能做字面检索"
                is RetrieverState.Failed -> "向量模型加载失败：${st.reason}"
                else -> null
            }
        }
    }

    /** 下载 6MB 向量模型（复用同一套下载器，不是另写一份） */
    fun downloadEmbedder() {
        _downloadMessage.value = null
        hybrid.downloadModel(hybrid.embeddingModel)
    }

    fun dismissKnowledgeMessage() { _knowledgeMessage.value = null }

    fun send(text: String) {
        android.util.Log.i("SSE_CHAT", "send() 被调用: \"$text\"")
        if (text.isBlank()) { android.util.Log.w("SSE_CHAT", "输入为空，忽略"); return }
        if (_isStreaming.value) { android.util.Log.w("SSE_CHAT", "上一条还在生成中，忽略本次点击"); return }

        // 1. 先把"用户消息"和一条空的"AI 占位消息"塞进列表
        _messages.value = _messages.value + UiMessage(isUser = true, content = text)
        _messages.value = _messages.value + UiMessage(isUser = false, content = "")
        _ttftMs.value = null
        _isStreaming.value = true

        // 2. 【W3 对比点】直接把用户这句话交给客户端 ——
        //    W1 里这里的 buildList { system + 全部历史 } 整块消失了，
        //    框架的 ChatMemory 会自动带上历史和 system prompt。
        //    【W7 对比点】连"走端还是走云"也不用这里管，
        //    HybridChatClient 内部已经按内容决策完了。
        viewModelScope.launch {
            val startAt = System.currentTimeMillis()
            try {
                chatClient.streamChat(
                    text,
                    onFirstToken = { _ttftMs.value = System.currentTimeMillis() - startAt },
                    onDelta = { delta ->
                        // 3. 每个 token 到达：替换最后一条占位消息（内容追加）
                        val list = _messages.value.toMutableList()
                        if (list.isNotEmpty() && !list.last().isUser) {
                            list[list.size - 1] =
                                list.last().copy(content = list.last().content + delta)
                            _messages.value = list
                        }
                    }
                )

                // 4. 【W7】回答结束后回填"这条是谁产生的"。
                //    为什么放在结束后而不是开始时？
                //    因为端侧可能中途失败并降级到云端（HybridChatClient 内部
                //    改了 lastDecision），结束后读到的才是**真实**的来源。
                //    如果开始时打标，就会出现"标着端侧、其实是云端答的"这种
                //    误导性展示 —— 端云混合里这种细节错了整个功能就失去可信度。
                val list = _messages.value.toMutableList()
                if (list.isNotEmpty() && !list.last().isUser) {
                    val decision = hybrid.lastDecision.value
                    val stats = hybrid.lastStats.value
                    list[list.size - 1] = list.last().copy(
                        source = when (decision?.target) {
                            RouteTarget.DEVICE -> MessageSource.DEVICE
                            RouteTarget.CLOUD -> MessageSource.CLOUD
                            null -> MessageSource.NONE
                        },
                        deviceStats = stats?.let {
                            "TTFT ${it.ttftMs}ms · 解码 ${"%.1f".format(it.decodeCps)} 字/秒"
                        },
                    )
                    _messages.value = list
                }
            } catch (e: Exception) {
                // 5. 断网/超时/Key 错误：把错误渲染成一条 AI 消息（别让用户面对崩溃）
                val list = _messages.value.toMutableList()
                if (list.isNotEmpty() && !list.last().isUser) {
                    list[list.size - 1] = list.last().copy(
                        content = "⚠️ 请求失败：${e.message}",
                        isError = true
                    )
                    _messages.value = list
                }
            } finally {
                _isStreaming.value = false
            }
        }
    }

    /**
     * 【W7】ViewModel 销毁时释放端侧内存。
     *
     * 这一步**不能省**。端侧模型占的是 native 内存，JVM 的 GC 看不见它：
     * 不调 release()，进程里那几百 MB 会一直钉着，直到 App 被杀。
     * 用户看到的现象是"手机越用越卡"、"其他 App 被系统杀掉"——
     * 排查起来极其困难，因为 Android Studio 的 Profiler 默认只看 Java Heap，
     * native 内存要去 Memory Profiler 切到 "Native" 才看得见。
     */
    override fun onCleared() {
        super.onCleared()
        hybrid.releaseDevice()
        // 【W7.5】向量模型也要释放：它同样是 native 内存（7.3MB so + 模型权重），
        // 而且它和 LLM 是两套独立的 native 句柄，只释放一个等于漏一个。
        hybrid.releaseRetriever()
        // 取消在途下载（.part 文件保留，下次启动可续传 —— 这是断点续传的
        // 另一半价值：用户的流量不该因为切个页面就白花）
        hybrid.shutdown()
        android.util.Log.i("ON_DEVICE", "ViewModel 销毁，端侧资源已释放")
    }
}
