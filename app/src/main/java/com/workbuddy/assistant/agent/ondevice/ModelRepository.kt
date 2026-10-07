package com.workbuddy.assistant.agent.ondevice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7 原理课 3】模型文件分发 —— 端侧最不"AI"但最要命的一环
 * ══════════════════════════════════════════════════════════════════
 *
 * 第一次做端侧的人都会问："模型放 assets 目录不就行了？"
 * 不行，而且这是面试很能体现工程素养的一个回答：
 *
 * ── 为什么不放 assets ──────────────────────────────────────────
 *  1. APK 体积：1B int8 约 500MB，加上现有体积直接破 1GB。
 *     用户装个助手 App 要下 1GB，商店审核和留存都过不了。
 *  2. 安装耗时：assets 会在安装时解压到 /data，安装时间 ×10。
 *  3. 无法按需：4GB 手机不该下 1.5B 模型，assets 做不到机型差异化。
 *  4. 更新成本：换模型 = 发新版 APK，用户重下 500MB。
 *
 * ── 业界的四种下发策略 ─────────────────────────────────────────
 *  ① 应用内下载（我们采用）：启动后引导下载到私有目录。
 *     优点：可控、能断点续传、能按机型选档。缺点：要自己写下载器。
 *  ② Play Asset Delivery（PAD）：Google Play 的分包下发，按需加载。
 *     优点：商店托管、可增量。缺点：绑死 Google Play（国内不可用）。
 *  ③ 随包分发（assets）：只适合 100MB 以内的小模型（如 embedding）。
 *     我们 W6 的向量检索如果做端侧，就适合这条路。
 *  ④ 多 App 共享：放共享存储，多个 App 共用一份，省空间。
 *     缺点：要处理文件锁与并发读。
 *
 * ── 我们代码里的落地 ───────────────────────────────────────────
 *  存储位置优先级（三处都找，方便调试和迁移）：
 *    1. getExternalFilesDir()/ondevice/  ← 主选。adb push 可直接写，
 *       卸载 App 时自动清理，不需要任何存储权限。
 *    2. filesDir/ondevice/               ← 备用（内部存储，adb 要用 run-as）
 *    3. /sdcard/Download/ondevice/       ← 兼容用户手动下载后丢进来
 *
 *  注意 1：我们**不申请任何存储权限**。上面第 1、2 条属于应用私有目录，
 *         走的是"自己的沙箱"，Android 10+ 的 Scoped Storage 完全放行。
 *  注意 2：模型文件用 .part 后缀写，下完再 rename。
 *         这样"下载中断"和"下载完成"在文件系统层面就是两种状态，
 *         不需要额外记数据库 —— 这是下载器的通用小技巧。
 *
 * ── 一个真机下载器必须处理的五件事（少一件用户就会来骂你）──────
 *  ① 断点续传：500MB 文件在 4G 上要下几分钟，中途切后台/断网是常态。
 *     靠 HTTP Range 头实现（见 downloadFrom 的注释）。
 *  ② 完整性校验：网络中断后文件可能"看起来下完了"。必须核对字节数，
 *     否则 MediaPipe 加载到损坏文件会直接崩（native 层，没有友好报错）。
 *  ③ 多源回退：HF 官方站在国内经常连不上，必须准备镜像源并自动切换。
 *  ④ 磁盘空间预检：下到 90% 发现空间不够，是最差的用户体验。
 *  ⑤ 可取消：用户点错了要能停下来，且保留已下载的部分（下次接着下）。
 * ══════════════════════════════════════════════════════════════════
 */

/**
 * 一个可下发的端侧模型。
 *
 * @param engineId       归属引擎（mediapipe / mnn），不同引擎吃不同格式
 * @param fileName       磁盘上的文件名（也是存在性判断的依据）
 * @param tier           对应档位，路由时用来和机型匹配
 * @param approxSizeMb   预估体积（MB，用于 UI 粗略显示）
 * @param exactSizeBytes 精确字节数（**从真实 URL 的 Content-Length 核实过**）。
 *                       用途：① 进度条在服务端不返回总长时仍能算百分比；
 *                       ② 下载完核对文件大小，捕捉"静默截断"。
 *                       传 0 表示未知，代码会自动跳过相关校验。
 * @param format         格式说明（.task / .mnn 目录），写进 UI 让人看懂
 * @param promptStyle    该模型的对话格式标记（拼错 prompt 会让效果崩，见 PromptStyle）
 * @param urls           下载地址列表，**按优先级排列，失败自动逐个回退**。
 *                       第一个是国内镜像（hf-mirror），第二个是官方源。
 * @param note           一句话说明，显示给用户做选择
 * @param recommended    是否推荐给当前设备（UI 上打"推荐"标）
 * @param minValidBytes  "这个文件算下完了"的体积下限。
 *
 *   ⚠️ 这个字段是 W7.5 加向量模型时才暴露出来的坑：
 *   原来的校验写死了 `length > 10MB`（为了挡掉下了一半的 .task 残骸），
 *   而 USE 向量模型只有 6MB —— 它会被**误判成残骸**，导致：
 *     下载成功 → rename 成功 → isAvailable() 返回 false → UI 说"未安装"
 *   用户看到的是"下完了但还是没下"，没有任何报错，极难排查。
 *   所以下限必须按模型声明，不能全局写死一个数字。
 */
data class OnDeviceModel(
    val engineId: String,
    val displayName: String,
    val fileName: String,
    val tier: ModelTier,
    val approxSizeMb: Int,
    val exactSizeBytes: Long,
    val format: String,
    val promptStyle: PromptStyle,
    val urls: List<String>,
    val note: String = "",
    val recommended: Boolean = false,
    val minValidBytes: Long = 10L * 1024 * 1024,
) {
    /** 主下载源（UI 提示用） */
    val primaryUrl: String get() = urls.firstOrNull() ?: ""
}

/**
 * 模型清单。
 *
 * ══ 这里的每一项都是**核实过的**，不是从博客抄的 ══════════════════
 * 选型过程（面试可以当成"技术调研"来讲，比罗列参数有说服力）：
 *
 *  1) 为什么不用 Google 官方的 Gemma-3 1B？
 *     MediaPipe 官方文档指向 Kaggle 下载，而 Kaggle **必须登录 + 接受许可**。
 *     我们核实过 HuggingFace 上的 `litert-community/Gemma3-1B-IT`：`gated: auto`
 *     → 直接 curl 返回 403 "Access to model is restricted"。
 *     **App 内下载不可能带上用户的账号凭据**，所以这条路对"自动下载"是死的。
 *
 *  2) 为什么选 Qwen2.5？
 *     · 同组织的 `litert-community/Qwen2.5-0.5B-Instruct` 是 `gated: false`（Apache 2.0），
 *       任何人都能直接下。
 *     · README 明确写了 "ready for deployment on Android using the
 *       MediaPipe LLM Inference API" —— 与我们的 tasks-genai 引擎同源。
 *     · **中文效果远好于同尺寸的 Gemma / SmolLM**，这对中文助手是决定性的。
 *
 *  3) 为什么是 q8 而不是 f32？
 *     同仓库有 f32 版本，体积是 q8 的近 4 倍，而手机上 f32 的收益极小
 *     （端侧瓶颈在访存带宽，量化收益远大于精度损失）。
 *
 *  4) 文件名里的 `multi-prefill-seq_q8_ekv1280` 是什么意思？
 *     · multi-prefill-seq：支持多轮 prefill（对多轮对话的 TTFT 有优化）
 *     · q8：权重 8bit 量化
 *     · ekv1280：KV Cache 容量 1280 token —— 这是**硬上限**，
 *       prompt + 生成长度超过它就会被截断。所以我们的 maxTokens 要控在它之下。
 *
 * ⚠️ 换模型时注意：HF 的 `resolve/main/` 指向分支最新，模型更新后
 *    exactSizeBytes 会过期。代码对此的处理是"只警告不失败"（见 downloadFrom），
 *    所以不会因此下不下来，但建议偶尔核对一下。
 */
object ModelCatalog {

    /** 国内镜像源（实测可直连，速度最好）。hf-mirror 是 HF 的反向代理 */
    private const val MIRROR = "https://hf-mirror.com/litert-community"

    /** 官方源（备用；国内常连不上，但作为兜底保留） */
    private const val OFFICIAL = "https://huggingface.co/litert-community"

    /** 拼出"镜像优先、官方兜底"的双源列表 */
    private fun sources(repo: String, file: String): List<String> = listOf(
        "$MIRROR/$repo/resolve/main/$file",
        "$OFFICIAL/$repo/resolve/main/$file",
    )

    private const val QWEN_05B_FILE = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private const val QWEN_15B_FILE = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

    /**
     * 全部可选模型。UI 直接遍历这个列表渲染"模型市场"。
     */
    val models: List<OnDeviceModel> = listOf(
        OnDeviceModel(
            engineId = "mediapipe",
            displayName = "Qwen2.5-0.5B-Instruct",
            fileName = QWEN_05B_FILE,
            tier = ModelTier.TINY,
            approxSizeMb = 521,
            exactSizeBytes = 546_660_344L,
            format = ".task · int8",
            promptStyle = PromptStyle.QWEN,
            urls = sources("Qwen2.5-0.5B-Instruct", QWEN_05B_FILE),
            note = "中文流畅、体积小、几乎所有 4GB 以上手机都能跑。推荐首选。",
            recommended = true,
        ),
        OnDeviceModel(
            engineId = "mediapipe",
            displayName = "Qwen2.5-1.5B-Instruct",
            fileName = QWEN_15B_FILE,
            tier = ModelTier.MEDIUM,
            approxSizeMb = 1524,
            exactSizeBytes = 1_597_913_616L,
            format = ".task · int8",
            promptStyle = PromptStyle.QWEN,
            urls = sources("Qwen2.5-1.5B-Instruct", QWEN_15B_FILE),
            note = "回答质量明显更好，但需要 8GB 以上机型和 1.5GB 存储。",
            recommended = false,
        ),
    )

    /** 兼容旧调用：MediaPipe 引擎能吃的模型 */
    val mediaPipeModels: List<OnDeviceModel> get() = models.filter { it.engineId == "mediapipe" }

    /**
     * ── W7.5 新增：端侧 RAG 用的向量模型 ──────────────────────────────
     *
     * 它**故意不放进 models 列表**，原因有两个：
     *  ① 列表是给 UI 的"模型市场"渲染的，用户会以为它和 Qwen 一样是"对话模型"，
     *     点"加载"却发现对话没变化 —— 概念混淆。
     *  ② 它不参与路由、不占档位（6MB 任何机器都跑得动），塞进去会污染
     *     `pickFor(tier)` 的挑选逻辑。
     *
     * ── 为什么选 USE，以及它的局限（必须知道）──────────────────────
     * Google 官方的 MediaPipe TextEmbedder 只托管了两个模型：
     *   · universal_sentence_encoder.tflite  6.1MB ← 我们用的
     *   · average_word_embedder.tflite       774KB（只是词向量平均，更差）
     * 我逐个探测过 `universal_sentence_encoder_multilingual` 的三个可能路径，
     * **全部 404 —— 官方根本没有多语言版本**。
     *
     * 这意味着：USE 是英文语料训练的，**中文语义能力弱**。
     * 所以端侧检索不能只靠它 —— OnDeviceRetriever 里做了"向量 + 中文字面"
     * 双路召回，中文场景实际主要靠字面那一路兜底（详见该类注释）。
     */
    val embedder: OnDeviceModel = OnDeviceModel(
        engineId = "mediapipe-embedder",
        displayName = "USE 文本向量模型",
        fileName = "universal_sentence_encoder.tflite",
        tier = ModelTier.TINY,
        approxSizeMb = 6,
        exactSizeBytes = 6_120_274L,
        format = ".tflite · float32",
        promptStyle = PromptStyle.DEFAULT,
        urls = listOf(
            "https://storage.googleapis.com/mediapipe-models/text_embedder/" +
                "universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite",
        ),
        note = "把文字变成向量，供端侧知识库检索使用。仅 6MB。" +
            "（源在 Google 服务器，国内网络可能需要代理；" +
            "下载不了也不影响使用 —— 字面检索仍然可用）",
        minValidBytes = 5L * 1024 * 1024,
    )

    /** 需要一起纳入"是否已下载"判断的全部文件（含向量模型） */
    val allDownloadable: List<OnDeviceModel> get() = models + embedder

    /** MNN 引擎的模型（引擎还没接，留作插槽；这里不展示给用户） */
    val mnnModels: List<OnDeviceModel> = emptyList()

    fun all(): List<OnDeviceModel> = models

    fun forTier(tier: ModelTier): List<OnDeviceModel> = models.filter { it.tier == tier }

    fun byFileName(name: String): OnDeviceModel? = models.firstOrNull { it.fileName == name }

    /**
     * 按设备推荐档位挑模型。
     *
     * 注意"**向下兼容**"这个动作：设备推荐的是 1.5B 档，但我们可能没配 1.5B
     * 的模型；这时应该退到更小的档位，而不是返回 null 让端侧彻底不可用。
     * 这和路由里的"非对称代价"是同一个思路：宁可用小模型，也别不能用。
     */
    fun pickFor(tier: ModelTier?): OnDeviceModel? {
        if (tier == null) return null
        val index = ModelTier.ascending.indexOf(tier)
        // 从推荐档位往下找第一个有模型的档位
        for (i in index downTo 0) {
            forTier(ModelTier.ascending[i]).firstOrNull()?.let { return it }
        }
        return null
    }
}

/**
 * 下载状态机 —— UI 只认这个。
 *
 * 为什么要有独立的状态机，而不是让 UI 直接看"进度百分比"？
 * 因为"下载"这件事有多个**性质不同**的阶段，UI 该显示的东西完全不同：
 *   Preparing   → 检查空间、找源（用户应该看到"准备中"，而不是卡在 0%）
 *   Downloading → 有进度、速度、剩余时间
 *   Verifying   → 下载完了但**还不能用**（校验/落盘），显示"校验中"
 *   Completed   → 可以加载了
 *   Failed      → 要显示**为什么**（是网络？是空间？是 403？），并且可重试
 *   Cancelled   → 用户主动停的，要提示"已暂停，可继续"
 * 如果压成一个 `Int` 百分比，这些信息全丢了 —— 用户在 100% 卡住时会以为 App 坏了。
 */
sealed interface DownloadState {

    /** 没有进行中的任务 */
    data object Idle : DownloadState

    /** 正在做前置检查：磁盘空间、目录创建 */
    data class Preparing(val model: OnDeviceModel) : DownloadState

    /**
     * 下载中。
     * @param bytesRead   已写入字节（含本次之前续传的部分）
     * @param totalBytes  总字节（来自 Content-Length；未知时用清单里的 exactSizeBytes，仍未知则 -1）
     * @param speedBps    实时速度（字节/秒），约 500ms 刷新一次
     * @param sourceIndex 当前用的是第几个源（0 = 主源/镜像，>0 = 已回退到备用源）
     */
    data class Downloading(
        val model: OnDeviceModel,
        val bytesRead: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val sourceIndex: Int = 0,
    ) : DownloadState {

        /** 进度百分比；总长未知时返回 -1，UI 应显示"不确定进度"的动画条 */
        val percent: Int
            get() = if (totalBytes <= 0) -1 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)

        /** 剩余秒数；速度未知或总长未知时返回 -1 */
        val etaSec: Long
            get() = if (speedBps <= 0 || totalBytes <= 0) -1 else {
                ((totalBytes - bytesRead).coerceAtLeast(0) / speedBps)
            }

        /** 已下载 MB（保留一位小数给 UI） */
        val mbRead: Double get() = bytesRead / 1024.0 / 1024.0
    }

    /** 校验与落盘（.part → 正式名）*/
    data class Verifying(val model: OnDeviceModel) : DownloadState

    /** 完成，可以加载了 */
    data class Completed(val model: OnDeviceModel) : DownloadState

    /** 失败：message 直接给用户看，所以文案要人话 */
    data class Failed(val model: OnDeviceModel, val message: String) : DownloadState

    /** 用户取消；.part 已保留，可再次点击"继续下载" */
    data class Cancelled(val model: OnDeviceModel) : DownloadState
}

/**
 * 模型仓库 —— 负责"模型文件在哪 / 在不在 / 下下来"。
 *
 * 单一职责：它不管推理，只管文件。推理交给 OnDeviceLlm。
 * 这种拆法让"换引擎"和"换存储位置"互不影响。
 *
 * ── 为什么下载状态放在这里，而不是放 ViewModel？──────────────────
 * 因为"同时只能有一个下载任务"这条约束属于**文件系统**（两个任务写同一个
 * .part 会互相踩），不属于 UI。放在 repository 里，约束天然成立；
 * 放 ViewModel 里，将来多一个入口（比如通知栏的"继续下载"）就会出现
 * 两个下载器并发写同一个文件的经典 bug。
 */
class ModelRepository(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // 大文件下载，不设读超时
        .build()

    /**
     * 内部协程作用域。
     *
     * 为什么 repository 自带 scope，而不是让调用方传 scope 进来？
     * 因为下载必须**在 ViewModel 之外存活**：用户退出设置页、屏幕旋转、
     * 甚至切到别的 Activity，下载都不该断（几百 MB 重下代价太高）。
     * 用 ViewModel 的 scope 下载，一次配置变更就前功尽弃。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var downloadJob: Job? = null

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** 三个候选目录，按优先级排列 */
    private fun searchDirs(): List<File> {
        val dirs = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let { dirs += File(it, "ondevice") }
        dirs += File(context.filesDir, "ondevice")
        dirs += File("/sdcard/Download/ondevice")
        return dirs
    }

    /** 主存储目录（下载落点）：外部私有目录优先 */
    fun primaryDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "ondevice").apply { if (!exists()) mkdirs() }
    }

    /**
     * 定位模型。找到返回 File，找不到返回 null。
     *
     * 这里的"存在性判断"按格式分两种，别写死成 file.exists()：
     *   · .task 单文件 → 看文件本身
     *   · MNN 目录      → 看目录里的 config.json（下载中途也会有目录，但没有 config）
     */
    fun resolve(model: OnDeviceModel): File? {
        for (dir in searchDirs()) {
            val candidate = File(dir, model.fileName)
            if (isValid(model, candidate)) {
                Log.i(TAG, "模型已就位: ${candidate.absolutePath}")
                return candidate
            }
        }
        return null
    }

    fun isAvailable(model: OnDeviceModel): Boolean = resolve(model) != null

    /**
     * 文件是否可用。
     *
     * 注意这里**故意宽松**：只挡"明显是残骸"的情况（下了一半、几 MB）。
     * 为什么不拿 exactSizeBytes 精确比对？
     * 因为模型在 HF 上会更新（同一个 resolve/main 路径指向新文件），
     * 一旦我们用精确值卡死，模型一更新，用户手机上**已经下好的**文件
     * 就会被判定为"无效"并被迫重下 —— 这是很糟糕的体验。
     * 精确校验只在"下载刚结束"这一刻做（那时服务端刚告诉我们真实长度）。
     */
    private fun isValid(model: OnDeviceModel, f: File): Boolean {
        if (!f.exists()) return false
        return if (model.fileName.endsWith(".task") || f.isFile) {
            // 单文件模型：体积太小说明是下了一半的残骸。
            // 下限来自模型自己声明（minValidBytes）—— 别写死 10MB，
            // 否则 6MB 的向量模型会被永久判定为"未安装"（见 OnDeviceModel 注释）。
            f.length() >= model.minValidBytes
        } else {
            // MNN 目录模型：必须有 config.json
            File(f, "config.json").exists()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 下载
    // ══════════════════════════════════════════════════════════════

    /**
     * 启动下载（非阻塞）。
     *
     * 幂等保护：已有任务在跑就直接忽略，避免用户连点两下产生两个协程
     * 写同一个 .part 文件 —— 结果是文件内容交错、两倍流量、必然损坏。
     */
    fun startDownload(model: OnDeviceModel) {
        if (downloadJob?.isActive == true) {
            Log.w(TAG, "已有下载任务在进行，忽略本次请求")
            return
        }
        downloadJob = scope.launch { runDownload(model) }
    }

    /** 取消下载。已下载的 .part 会保留，下次可续传 */
    fun cancelDownload() {
        downloadJob?.cancel()
        Log.i(TAG, "用户取消下载")
    }

    private suspend fun runDownload(model: OnDeviceModel) {
        try {
            // ── 前置检查：磁盘空间 ──
            _downloadState.value = DownloadState.Preparing(model)
            val needMb = model.approxSizeMb + RESERVE_MB
            val freeMb = DeviceProfiler.freeDiskMb(primaryDir())
            if (freeMb in 0 until needMb) {
                _downloadState.value = DownloadState.Failed(
                    model,
                    "存储空间不足：需要约 ${needMb}MB，当前可用 ${freeMb}MB"
                )
                return
            }

            val target = File(primaryDir(), model.fileName)
            val part = File(primaryDir(), model.fileName + ".part")

            // ── 多源回退 ──
            var lastError: Throwable? = null
            for ((index, url) in model.urls.withIndex()) {
                try {
                    if (index > 0) {
                        Log.w(TAG, "主源失败，回退到源 ${index + 1}: $url")
                        // 换源时丢弃已下载的分片：不同源的 ETag/分片边界可能不同，
                        // 续传上来的字节混进新源的数据 = 文件损坏且**下载时看不出来**。
                        // 宁可多花点流量，也不能产出损坏文件。
                        if (part.exists()) part.delete()
                    }
                    downloadFrom(url, model, part, index)
                    // downloadFrom 正常返回 = 下载完成
                    _downloadState.value = DownloadState.Verifying(model)
                    finish(model, part, target)
                    return
                } catch (e: CancellationException) {
                    // 取消不是错误：保留 .part 供下次续传
                    _downloadState.value = DownloadState.Cancelled(model)
                    Log.i(TAG, "下载已取消，已保留 ${part.length() / 1024 / 1024}MB 供续传")
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    Log.w(TAG, "源 ${index + 1} 下载失败: ${e.message}")
                }
            }

            _downloadState.value = DownloadState.Failed(
                model,
                "所有下载源都失败：${lastError?.message ?: "未知原因"}\n" +
                    "可检查网络后重试，或暂时切到「仅云端」模式继续使用。"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "下载异常", e)
            _downloadState.value = DownloadState.Failed(model, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 从单个源下载到 .part 文件（支持断点续传）。
     *
     * 【断点续传原理】HTTP 的 Range 头：告诉服务端"我要从第 N 字节开始"，
     * 服务端返回 206 Partial Content。我们把已有 .part 的大小当 N 传过去。
     *
     * 注意：服务端不一定支持（可能返回 200 而不是 206），
     * 所以必须判断响应码，不能盲目 append —— 否则文件会损坏。
     */
    private fun downloadFrom(url: String, model: OnDeviceModel, part: File, sourceIndex: Int) {
        val downloaded = if (part.exists()) part.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply { if (downloaded > 0) header("Range", "bytes=$downloaded-") }
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 403 是最需要单独说清的一种：它通常意味着模型变成了 gated（需授权），
                // 这时候"重试"是没用的，必须让用户知道原因，而不是显示"网络错误"。
                val hint = if (resp.code == 403) "（该模型需要授权，或地址已失效）" else ""
                throw IllegalStateException("HTTP ${resp.code}$hint")
            }

            // 关键判断：只有 206 才说明服务端接受了续传，否则必须从头写
            val append = resp.code == 206 && downloaded > 0
            if (downloaded > 0 && !append) {
                Log.w(TAG, "服务端不支持续传（返回 ${resp.code}），从头下载")
            }

            val body = resp.body ?: throw IllegalStateException("响应体为空")
            val total = body.contentLength().let { len ->
                when {
                    len <= 0 -> model.exactSizeBytes          // 服务端没给长度，退回清单值
                    append -> len + downloaded                 // 206 的 length 是"剩余部分"
                    else -> len
                }
            }

            var written = if (append) downloaded else 0L
            var lastTickAt = System.currentTimeMillis()
            var lastTickBytes = written
            var speed = 0L

            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(64 * 1024)

                    // 先推一次状态，让 UI 立刻显示"开始下载"，而不是等第一个 500ms
                    _downloadState.value = DownloadState.Downloading(
                        model, written, total, 0L, sourceIndex
                    )

                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        written += read

                        // ── 节流上报 ──
                        // 每读一次 64KB 就推一个 StateFlow 会让 Compose 每秒重组几十次
                        // （大文件时甚至上百次），界面直接卡住。
                        // 500ms 是人眼分辨率的甜点值：进度条看起来是连续的，
                        // 又能留出足够的 UI 线程时间。这类"高频事件节流"是通用模式。
                        val now = System.currentTimeMillis()
                        val dt = now - lastTickAt
                        if (dt >= TICK_MS) {
                            speed = (written - lastTickBytes) * 1000 / dt
                            lastTickAt = now
                            lastTickBytes = written
                            _downloadState.value = DownloadState.Downloading(
                                model, written, total, speed, sourceIndex
                            )
                        }
                    }
                    output.flush()
                }
            }

            // ── 完整性校验 ──
            // 这是下载器最容易被省掉、又最不能省的一步。
            // socket 被中途掐断时，很多 HTTP 客户端**不会抛异常**，只是提前
            // 结束流 —— 你会得到一个"看起来成功"的残缺文件。
            // 放行它 → MediaPipe 加载时在 native 层崩，报错完全看不懂。
            //
            // ⚠️ 下限必须用 model.minValidBytes（按模型声明），不能写死全局常量。
            // 教训：W7.5 加 USE 向量模型（6.1MB）时只改了 isValid()，
            // 这里漏了 —— 结果下载 100% 完成，却在最后一步被判成
            // "下载内容异常（仅 6120274 字节）"，用户看到的是"怎么下都失败"。
            if (total > 0 && written < total) {
                throw IllegalStateException("下载不完整：$written/$total 字节（已保存，可重试续传）")
            }
            if (written < model.minValidBytes) {
                throw IllegalStateException("下载内容异常（仅 $written 字节）")
            }
        }
    }

    /**
     * 收尾：把 .part 原子性改名成正式文件。
     *
     * 为什么必须"先写 .part 再 rename"？
     * 因为这样"下载中"和"下载完"在文件系统层面就是**两种状态**。
     * 否则 App 被杀在下载中途，下次启动看到一个半截文件，还可能被
     * 当成"已就绪"直接送去加载 —— 崩溃点离真正的原因十万八千里。
     * rename 在同一文件系统内是原子操作，不存在"改了一半"的中间态。
     */
    private fun finish(model: OnDeviceModel, part: File, target: File) {
        val size = part.length()

        // 与"这个模型算下完了"的下限对不上 → 文件有问题，别让它污染正式路径。
        // 同样按模型声明的 minValidBytes 判断（见 downloadFrom 内的教训注释）：
        // 写死 10MB 会把 6MB 的向量模型在最后一步拦下。
        if (size < model.minValidBytes) {
            part.delete()
            _downloadState.value = DownloadState.Failed(model, "文件校验失败：体积异常（$size 字节）")
            return
        }

        // 与清单里的精确大小不一致时**只警告不失败**：模型可能在 HF 上更新了。
        // 这种"可自愈的偏差"不该拦住用户。
        if (model.exactSizeBytes > 0 && size != model.exactSizeBytes) {
            Log.w(
                TAG,
                "文件大小与清单不一致（可能模型已更新）：清单 ${model.exactSizeBytes}，实际 $size"
            )
        }

        if (target.exists()) target.delete()
        if (part.renameTo(target)) {
            Log.i(TAG, "模型下载完成: ${target.absolutePath} (${size / 1024 / 1024}MB)")
            _downloadState.value = DownloadState.Completed(model)
        } else {
            _downloadState.value = DownloadState.Failed(
                model, "文件重命名失败：${part.absolutePath}"
            )
        }
    }

    /** 重置状态（用户关掉提示后调用），不影响磁盘上的文件 */
    fun clearState() {
        if (_downloadState.value !is DownloadState.Downloading) {
            _downloadState.value = DownloadState.Idle
        }
    }

    /** 删除模型（释放磁盘）。已加载到内存的引擎不会因此释放，需上层先 release */
    fun delete(model: OnDeviceModel): Boolean {
        primaryDir().let { dir ->
            File(dir, model.fileName + ".part").takeIf { it.exists() }?.delete()
        }
        val f = resolve(model) ?: return false
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    /** 释放：取消在途下载（App 退出时调用） */
    fun shutdown() {
        downloadJob?.cancel()
    }

    private companion object {
        const val TAG = "ON_DEVICE"

        /** 进度上报节流间隔（毫秒）*/
        const val TICK_MS = 500L

        /** 下载前额外预留的磁盘（MB）：日志、临时文件、系统余量 */
        const val RESERVE_MB = 300

        // 注意：这里**没有**全局的"最小有效体积"常量 —— 该下限已按模型
        // 声明为 OnDeviceModel.minValidBytes（默认 10MB，向量模型 5MB）。
        // 曾经在这里放过一个写死的 10MB 常量，结果把 6MB 的向量模型
        // 在下载完成后的校验环节拦下（见 downloadFrom 内的教训注释）。
    }
}
