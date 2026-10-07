package com.workbuddy.assistant.agent.ondevice

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7 原理课 4】MediaPipe LLM Inference —— 第一个真实端侧引擎
 * ══════════════════════════════════════════════════════════════════
 *
 * ── 为什么首选它？（选型逻辑，面试可讲）────────────────────────
 * 端侧三大方案的实际集成成本差别巨大 —— 下面这些是我逐个核实过的事实：
 *
 *   引擎         分发形态                       集成成本
 *   ──────────────────────────────────────────────────────────────
 *   MediaPipe    Google Maven 的 AAR            ⭐ 一行 gradle 依赖
 *   llama.cpp    无官方 Android SDK             ⭐⭐⭐⭐ 自己写 JNI + CMake
 *   MNN          C++ 库 + 完整 App 工程         ⭐⭐⭐ 编 so / 搬 app 层封装
 *
 * 【MNN 的真实情况，我去官方仓库查过】MNN 不在 Maven 上发布 aar，只有两条路：
 *   a) 用 NDK 编译 libMNN.so（project/android/build_64.sh，需 NDK 27+）
 *   b) 下 GitHub Releases 的预编译包（mnn_3.6.1_android_armv7_armv8_cpu_opencl_vulkan.zip）
 * 更麻烦的是 MNN 的 Java API 不是干净的库 —— 官方示例的 LlmSession.kt
 * 直接 import Gson / Timber / ChatDataItem / ModelConfig / QnnModule，
 * 想复用得把半个 demo 工程搬过来。
 *
 * 【结论，这才是真正的工程判断】**先用 MediaPipe 把端云混合的链路跑通，
 * 再按需把 MNN 作为第二个实现插进来** —— 这正是 OnDeviceLlm 接口存在的意义。
 * 面试时讲"我对比了三个引擎，先选集成成本最低的验证链路，再用接口隔离换引擎"，
 * 比"我会用 MNN"有说服力得多。
 *
 * ── 【重要】API 是核实过的，不是猜的 ──────────────────────────
 * 把 tasks-genai-0.10.27.aar 拉下来 javap 反编译，实测得到（W3 那套
 * "jar 考古"方法论的复用，省掉了好几轮编译报错）：
 *
 *   LlmInference.LlmInferenceOptions.builder()   ← 静态入口
 *     .setModelPath(String) .setMaxTokens(int) .setMaxTopK(int)
 *     .setPreferredBackend(Backend)              ← 只有 DEFAULT/CPU/GPU
 *     ⚠️ 没有 setTopK / setTemperature / setRandomSeed / setResultListener
 *
 *   LlmInference.generateResponseAsync(String, ProgressListener<String>)
 *     ← 流式就靠它。ProgressListener 是单方法接口 run(OutputT, boolean)
 *
 *   LlmInferenceSession.LlmInferenceSessionOptions.builder()
 *     .setTopK(int) .setTopP(float) .setTemperature(float)
 *     .setRandomSeed(int) .setPromptTemplates(...)  ← 采样参数在这一层！
 *
 *   LlmInferenceSession.createFromOptions(LlmInference, SessionOptions)
 *     .addQueryChunk(String) .generateResponseAsync(ProgressListener)
 *     .close()    ← 实现 AutoCloseable
 *
 * 官方文档的 Kotlin 示例写的是 setTopK / setResultListener（那是更早或更晚
 * 版本的 API），直接照抄必然编译失败。**永远以你实际依赖的那个版本的
 * aar 为准** —— 这是我在 W3 用三轮真机崩溃换来的教训。
 *
 * ── 包体代价（要提前知道）─────────────────────────────────────
 *   aar 里带 4 个 ABI 的 libllm_inference_engine_jni.so：
 *   arm64-v8a 12.8MB / armeabi-v7a 8.3MB / x86 17.2MB / x86_64 14.7MB
 *   全打进 APK 约 +53MB。生产环境必须用 abiFilters 或 AAB 分包裁掉。
 *   我们保留 arm64 + armeabi-v7a 即可（真机就这两种）。
 * ══════════════════════════════════════════════════════════════════
 */
class MediaPipeLlmEngine(
    private val context: Context,
    private val modelFile: File,
    /**
     * 模型元数据（从 ModelCatalog 传进来）。
     *
     * 为什么引擎要拿到整个模型对象，而不是只拿一个文件路径？
     * 因为**推理参数和模型的对话格式是绑定的** —— 见下面 promptTemplates 的用法。
     * 只传路径的话，引擎就无从知道这个 .task 是 Qwen 还是 Gemma，
     * 只能靠猜（或写死），换模型时必然出错。
     */
    private val model: OnDeviceModel,
) : OnDeviceLlm {

    override val id: String = ENGINE_ID
    override val displayName: String = "MediaPipe · ${model.displayName}"

    /** 模型本体（重，加载一次，常驻） */
    @Volatile
    private var inference: LlmInference? = null

    @Volatile
    private var _state: EngineState = EngineState.Idle
    override val state: EngineState get() = _state

    /**
     * 加载模型。幂等：已 Ready 直接返回。
     *
     * 用 Dispatchers.Default 而不是 IO：加载是 **CPU 密集**
     * （建计算图、分配张量、mmap 权重）而非磁盘 IO 密集。
     */
    override suspend fun ensureLoaded(): EngineState = withContext(Dispatchers.Default) {
        inference?.let { return@withContext _state }
        if (!modelFile.exists()) {
            _state = EngineState.NotInstalled
            return@withContext _state
        }

        _state = EngineState.Loading
        val t0 = System.currentTimeMillis()
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                // maxTokens 是"输入+输出"的总预算。给太大 → KV Cache 膨胀；
                // 给太小 → 长对话直接截断。1024 是 1B 模型在手机上的甜点值。
                .setMaxTokens(MAX_TOKENS)
                // 注意是 setMaxTopK（采样时最多考虑多少个候选），不是 setTopK
                .setMaxTopK(MAX_TOP_K)
                // DEFAULT 让 MediaPipe 自己挑后端（GPU 可用则走 GPU，否则 CPU）
                .setPreferredBackend(LlmInference.Backend.DEFAULT)
                .build()

            val engine = LlmInference.createFromOptions(context, options)
            inference = engine

            val loadMs = System.currentTimeMillis() - t0
            _state = EngineState.Ready(
                LoadInfo(
                    modelName = model.displayName,
                    fileSizeBytes = modelFile.length(),
                    // 常驻估算：MediaPipe 不暴露精确值，用"文件体积 × 1.3 + KV 预留"估。
                    // 真实值要 adb shell dumpsys meminfo <pkg> 看 Native Heap。
                    residentBytes = (modelFile.length() * 1.3).toLong() + (256L * 1024 * 1024),
                    loadMs = loadMs,
                    backend = "MediaPipe delegate",
                )
            )
            Log.i(TAG, "端侧模型加载完成: ${model.displayName}, ${loadMs}ms")
            _state
        } catch (e: Throwable) {
            // 捕获 Throwable 而非 Exception：native 加载失败常抛 Error
            // （UnsatisfiedLinkError：ABI 不匹配 / so 缺失）
            Log.e(TAG, "端侧模型加载失败", e)
            _state = EngineState.Failed("${e.javaClass.simpleName}: ${e.message}")
            inference = null
            _state
        }
    }

    /**
     * 流式生成（阻塞）。
     *
     * ── 【核心设计】每轮新建 Session，而不是复用 ──────────────────
     * MediaPipe 有两层对象，职责完全不同：
     *
     *   LlmInference（模型）    权重 + 计算图。加载一次要 3-8 秒、占几百 MB。
     *                           → 必须常驻，绝不能每轮重建。
     *   LlmInferenceSession（会话）  KV Cache + 采样参数（温度/topK/种子）。
     *                           新建很轻（只是分配一块 cache）。
     *                           → 每轮新建完全没问题。
     *
     * 我们把"历史拼接"放在上层做了（HybridChatClient 手搓 2 轮窗口），
     * 所以会话必须是**无状态**的：每轮新建 → 灌入完整 prompt → 生成 → 关闭。
     * 如果复用 session，它会自己累积上下文，和上层拼的历史重复，
     * 小模型立刻开始胡言乱语 —— 这是端侧多轮最容易踩的坑。
     */
    override fun streamGenerate(
        prompt: String,
        params: GenParams,
        onDelta: (String) -> Unit,
    ): GenStats {
        val engine = inference
            ?: throw IllegalStateException("端侧引擎未加载，请先调用 ensureLoaded()")

        val t0 = System.currentTimeMillis()
        var firstTokenAt = 0L
        var chars = 0

        // 采样参数只在 Session 层可配（LlmInferenceOptions 里没有）
        val sessionBuilder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(params.topK)
            .setTopP(TOP_P)
            .setTemperature(params.temperature)
            .setRandomSeed(params.seed)

        // ══════════════════════════════════════════════════════════
        // 【W7 补充】注入模型的对话格式标记 —— 不写这段，效果会明显变差
        // ══════════════════════════════════════════════════════════
        // MediaPipe 对 Gemma 系列内置了默认模板，但对 Qwen 这样的"非 Google 系"
        // 模型，默认模板是不匹配的。表现为：模型能输出、不报错，但会开始
        // **续写对话文本**（比如把你上一轮的"助手：xxx"继续编下去），
        // 而不是回答你 —— 一个极度迷惑人的 bug。
        //
        // 原理：模型是在"特殊标记"上训练的。Qwen 见过的是
        //     <|im_start|>user\n问题<|im_end|>\n<|im_start|>assistant\n
        // 你给它一段没有任何标记的裸文本，它只能按"网页文本续写"的模式工作。
        //
        // 所以必须显式告诉引擎：每个角色用什么前缀/后缀包裹。
        // 数据来自 PromptStyle（枚举），换模型只需换枚举值 —— 这就是
        // "把易变的格式知识集中到一处"的好处。
        //
        // 注意 DEFAULT 分支**故意不设置**：AutoValue 的 Builder 要求
        // 六个字段全部非 null，乱填空字符串可能导致引擎按"空气模板"拼出
        // 错误的 prompt。宁可不指定，让引擎用它自带的默认。
        if (model.promptStyle != PromptStyle.DEFAULT) {
            val s = model.promptStyle
            sessionBuilder.setPromptTemplates(
                PromptTemplates.builder()
                    .setUserPrefix(s.userPrefix)
                    .setUserSuffix(s.userSuffix)
                    .setModelPrefix(s.modelPrefix)
                    .setModelSuffix(s.modelSuffix)
                    .setSystemPrefix(s.systemPrefix)
                    .setSystemSuffix(s.systemSuffix)
                    .build()
            )
            Log.i(TAG, "prompt 模板已注入: ${s.label}")
        }

        val sessionOptions = sessionBuilder.build()

        val latch = CountDownLatch(1)
        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

        try {
            // ── 顺便量一下 prompt 的真实 token 数 ──
            // 这个数很关键：端侧"塞不塞得下"全靠它。
            // 中文大约 1 字 = 0.6-1 token（取决于 tokenizer），
            // 用 API 拿真值比拍脑袋估算靠谱。
            val promptTokens = runCatching { session.sizeInTokens(prompt) }.getOrDefault(-1)
            Log.i(TAG, "端侧 prompt: ${prompt}... (${promptTokens} tokens)")
            if (promptTokens > MAX_TOKENS * 0.6) {
                Log.w(TAG, "prompt 偏长（$promptTokens tokens），prefill 会明显变慢")
            }

            session.addQueryChunk(prompt)
            session.generateResponseAsync(object : ProgressListener<String> {
                override fun run(partialResult: String, done: Boolean) {
                    // ⚠️ 这个回调**不在主线程**（native 推理线程）。直接更新 UI 会崩。
                    if (partialResult.isNotEmpty()) {
                        if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                        chars += partialResult.length
                        onDelta(partialResult)
                    }
                    if (done) latch.countDown()
                }
            })

            // 超时兜底：端侧大约 5-15 字/秒，按 3 字/秒保守估算，
            // 避免 native 卡死时协程永远挂着。
            val timeoutSec = (params.maxTokens / 3).coerceAtLeast(30).toLong()
            if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
                Log.w(TAG, "端侧生成超时（${timeoutSec}s），强制收尾")
            }
        } finally {
            // Session 也要关：它持有的 KV Cache 是 native 内存，GC 看不见
            runCatching { session.close() }
        }

        val endAt = System.currentTimeMillis()
        val prefill = if (firstTokenAt > 0) firstTokenAt - t0 else endAt - t0
        val decode = if (firstTokenAt > 0) endAt - firstTokenAt else 0L

        return GenStats(prefillMs = prefill, decodeMs = decode, outputChars = chars)
    }

    /**
     * 释放 native 资源。
     *
     * 【端侧最容易漏的坑】LlmInference 持有的是 native C++ 内存，
     * JVM 的 GC 完全看不见它。不显式 close()，即使 ViewModel 被回收，
     * 那几百 MB 依然钉在进程里 —— 直到 App 被杀。
     * 表现：切几次页面后系统开始杀后台，或者第二次加载直接 OOM。
     */
    override fun release() {
        try {
            inference?.close()
            Log.i(TAG, "端侧模型已释放: ${model.displayName}")
        } catch (e: Exception) {
            Log.w(TAG, "释放端侧模型时异常", e)
        } finally {
            inference = null
            _state = EngineState.Idle
        }
    }

    companion object {
        const val ENGINE_ID = "mediapipe"
        private const val TAG = "ON_DEVICE"

        private const val MAX_TOKENS = 1024
        private const val MAX_TOP_K = 40
        private const val TOP_P = 0.95f
    }
}

/**
 * MediaPipe 引擎工厂。
 *
 * 工厂里做的事就是"运行时择路"：
 *   找得到 .task 模型 → 造引擎；找不到 → 返回 null，上层自动走云端。
 * 这不是"优雅"，这是端侧必须有的降级路径 ——
 * 用户没下模型、下了一半、手机 ABI 不对，App 都得照常能用。
 *
 * 【两种选模型的顺序，别搞反】
 *   ① 先按**设备推荐档位**挑（DeviceProfiler 算出来的），且必须已经下好；
 *   ② 挑不到就退而求其次：**任意一个已下载好的模型**。
 * 为什么②重要？因为用户可能下了 1.5B 但设备只推荐 0.5B 档 —— 这时
 * 明明有可用的模型，却因为"档位不匹配"而走云端，是白丢能力。
 * 端侧是增强项，能跑就跑，别用规则把它卡死。
 */
class MediaPipeEngineFactory(private val repository: ModelRepository) : OnDeviceLlmFactory {

    override fun create(context: Context): OnDeviceLlm? {
        val tier = DeviceProfiler.recommendTier(DeviceProfiler.profile(context))

        val model = ModelCatalog.pickFor(tier)?.takeIf { repository.isAvailable(it) }
            ?: ModelCatalog.mediaPipeModels.firstOrNull { repository.isAvailable(it) }
            ?: return null

        val file = repository.resolve(model) ?: return null
        return MediaPipeLlmEngine(context, file, model)
    }
}
