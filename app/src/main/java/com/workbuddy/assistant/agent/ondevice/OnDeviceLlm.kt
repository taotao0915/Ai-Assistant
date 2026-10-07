package com.workbuddy.assistant.agent.ondevice

import android.content.Context

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7 原理课 1】端侧 LLM 的抽象契约 —— 为什么先写接口再写实现
 * ══════════════════════════════════════════════════════════════════
 *
 * W3 我们给"云端对话"抽象了 ChatClient 接口，于是 LangChain4j 能换进来。
 * W7 面对的是同一个问题，但对象换成了"端侧推理引擎"，而且更棘手：
 *
 *   云端引擎：DashScope 一个 REST 端点，全行业协议统一（OpenAI 兼容）。
 *   端侧引擎：MNN / MediaPipe / llama.cpp / LiteRT ……
 *             每家的 SDK 形态、模型格式、API 命名全都不一样，
 *             甚至同一家不同版本都会改 API。
 *
 * 结论：端侧比云端更需要"接口隔离"。把变化锁在实现类里，
 * 上层的路由、UI、ViewModel 一行都不用动 —— 这就是依赖倒置的价值。
 *
 * ── 与 ChatClient 的关键差异（面试考点）──────────────────────────
 *   1. 多了一个"生命周期"：端侧模型要"加载进内存"，耗时秒级，还会失败。
 *      云端没有这个概念（服务端常驻）。所以这里有 ensureLoaded() / release()。
 *   2. 生成过程是"阻塞的本地计算"，不是"等网络回包"。
 *      所以接口是普通函数（调用方自己扔到 IO 线程），不是 suspend。
 *   3. 必须产出性能指标（prefill / decode），这是端侧调优的唯一依据。
 * ══════════════════════════════════════════════════════════════════
 */

/**
 * 引擎状态机 —— 端侧独有的"四态"。
 *
 * 为什么不用简单的 Boolean isReady？
 * 因为"没就绪"有好几种截然不同的原因，UI 和路由的应对也不同：
 *   NotInstalled → 提示用户去下模型（不是错误，是待办）
 *   Loading      → 显示进度，禁止并发请求
 *   Ready        → 可以走端侧
 *   Failed       → 打印根因，自动降级云端
 * 把它们压成一个 false，就丢掉了"该怎么办"的信息。
 */
sealed interface EngineState {
    /** 模型文件不在本地 —— 这是最常见的情况，且不是错误 */
    data object NotInstalled : EngineState

    /** 模型文件在，但还没加载进内存（懒加载，省内存） */
    data object Idle : EngineState

    /** 正在加载：读文件 + 建计算图 + 分配权重，秒级 */
    data object Loading : EngineState

    /** 可用 */
    data class Ready(val info: LoadInfo) : EngineState

    /** 加载失败：文件损坏 / 内存不足 / ABI 不匹配 */
    data class Failed(val reason: String) : EngineState
}

/**
 * 一次加载的账 —— 端侧冷启动成本。
 *
 * @param modelName      模型标识（tier + 量化）
 * @param fileSizeBytes  模型文件体积（磁盘）
 * @param residentBytes  加载后常驻内存（权重 + KV Cache 预留），端侧最该盯的数
 * @param loadMs         加载耗时
 * @param backend        实际使用的后端："CPU" / "GPU(OpenCL)" / "NPU"
 */
data class LoadInfo(
    val modelName: String,
    val fileSizeBytes: Long,
    val residentBytes: Long,
    val loadMs: Long,
    val backend: String,
)

/**
 * 采样参数 —— 端侧和云端语义完全一致（温度、topK、最大生成长度）。
 * 端侧多了一层含义：这些参数直接决定"用户等多久"。
 * maxTokens 越小越快，温度越高越"发散"但也越容易跑偏。
 */
data class GenParams(
    val maxTokens: Int = 512,
    val topK: Int = 40,
    val temperature: Float = 0.8f,
    val seed: Int = 0,
)

/**
 * 一次生成的性能账 —— 端侧优化的唯一依据（面试必问，背下来）。
 *
 * 为什么把 prefill / decode 分开统计？
 * 因为这是 LLM 推理的两个阶段，瓶颈性质完全不同：
 *
 *   Prefill（预填充）：把输入 prompt 全部 token 并行跑一遍，建 KV Cache。
 *     → 算力密集（compute-bound）。GPU/NPU 的强项。
 *     → 决定 TTFT（首字延迟）。长 system prompt 时它是主要耗时。
 *
 *   Decode（解码）：逐个生成 token，每生成一个都要重跑一遍模型。
 *     → 访存密集（memory-bound）。因为每产 1 个 token 就要把
 *       全部权重从内存读一遍，算力反而用不满。
 *     → 决定 TPOT（每个 token 的耗时）/ 吞吐 t/s。这才是"打字机速度"。
 *
 * 记住这个结论（面试高频）：**端侧优化 decode 靠减少访存（量化、算子融合），
 * 优化 prefill 靠增加算力（GPU/NPU 卸载）。方向不同，别搞混。**
 */
data class GenStats(
    val prefillMs: Long,      // = TTFT 的量级
    val decodeMs: Long,       // 纯解码总耗时
    val outputChars: Int,     // 产出字符数（中文约 1 token ≈ 1-1.5 字，粗略换算）
) {
    /** 首字延迟：用户从点击到看见第一个字要等多久 */
    val ttftMs: Long get() = prefillMs

    /** 解码速度：字符/秒。端侧 1B int4 在骁龙 8 系上大约 10-25 字/秒 */
    val decodeCps: Double
        get() = if (decodeMs <= 0) 0.0 else outputChars * 1000.0 / decodeMs

    /** 总耗时 */
    val totalMs: Long get() = prefillMs + decodeMs
}

/**
 * 所有端侧推理引擎的统一外观。
 *
 * 实现约定（很重要，写实现类时必须遵守）：
 *  1. streamGenerate 是**阻塞**的，调用方负责放到 Dispatchers.IO。
 *  2. streamGenerate 产出的增量直接回调 onDelta，自己不要做 UI 更新。
 *  3. release() 必须真正释放 native 内存 —— 这是端侧最容易漏的坑：
 *     native 内存不受 JVM GC 管理，不显式释放就是几百 MB 的泄漏。
 */
interface OnDeviceLlm {

    /** 引擎唯一标识，用于路由决策与埋点，如 "mediapipe" / "mnn" */
    val id: String

    /** 给人看的名字，显示在 UI 徽标上 */
    val displayName: String

    /** 当前状态（只读快照） */
    val state: EngineState

    /**
     * 确保模型已加载。幂等 —— 已 Ready 就直接返回，不重复加载。
     * 加载失败返回 Failed（不抛异常），让上层能优雅降级到云端。
     */
    suspend fun ensureLoaded(): EngineState

    /**
     * 流式生成。阻塞调用。
     * @return 本次的性能账
     */
    fun streamGenerate(
        prompt: String,
        params: GenParams = GenParams(),
        onDelta: (String) -> Unit,
    ): GenStats

    /** 释放 native 资源。VM 销毁或切换引擎时必须调。 */
    fun release()
}

/**
 * 引擎工厂 —— 把"选哪个引擎"这件事也抽象掉。
 *
 * 为什么要工厂？因为端侧引擎的可用性依赖运行时环境（模型文件在不在、
 * ABI 是否匹配、系统版本够不够），构造本身就可能失败。
 * 工厂返回 null 表示"当前设备无可用的端侧引擎"，上层据此走云端。
 */
interface OnDeviceLlmFactory {
    /** 尝试为当前设备创建最合适的引擎；不可用返回 null */
    fun create(context: Context): OnDeviceLlm?
}

/**
 * ══════════════════════════════════════════════════════════════════
 * 模型的对话格式（prompt template）—— 端侧最容易被忽视、但错一下效果就崩的一环
 * ══════════════════════════════════════════════════════════════════
 *
 * 云端用 OpenAI 兼容协议时，我们只管发 messages 数组，**格式由服务端负责**。
 * 端侧没有这一层：模型要的是"一段拼好的纯文本"，拼错了它也能输出，
 * 但会一本正经地乱答 —— 这是最阴险的 bug 类型（不报错、只是变笨）。
 *
 * 每个模型家族有自己的特殊标记：
 *   · Qwen / ChatML：<|im_start|>role ... <|im_end|>
 *   · Gemma：        <start_of_turn>user ... <end_of_turn>
 *   · 纯基座模型：     没有标记，直接接续文本
 *
 * 把这些标记写进枚举而不是散在代码里，好处是"换模型 = 换一个枚举值"，
 * 而且一眼能看出我们支持哪几种格式。
 *
 * ── 举个真实的错法（面试可以讲成"我踩过"）────────────────────
 * 用 Qwen 的权重却按 Gemma 的格式拼 prompt：
 *   模型把 "<start_of_turn>user" 当成普通文本理解 → 它可能"续写"出
 *   更多的对话文本，而不是回答你的问题。输出看起来像模像样，其实是幻觉。
 * 排查时你会怀疑量化、怀疑参数、怀疑内存 —— 都不会怀疑到格式上。
 */
enum class PromptStyle(
    val label: String,
    val userPrefix: String,
    val userSuffix: String,
    val modelPrefix: String,
    val modelSuffix: String,
    val systemPrefix: String,
    val systemSuffix: String,
) {
    /** Qwen 系列（含 Qwen2.5 / Qwen3）用的 ChatML */
    QWEN(
        label = "ChatML (Qwen)",
        userPrefix = "<|im_start|>user\n",
        userSuffix = "<|im_end|>\n<|im_start|>assistant\n",
        modelPrefix = "",
        modelSuffix = "<|im_end|>\n",
        systemPrefix = "<|im_start|>system\n",
        systemSuffix = "<|im_end|>\n",
    ),

    /** Gemma 系列的 turn 标记。注意它没有独立的 system 角色 */
    GEMMA(
        label = "Gemma turns",
        userPrefix = "<start_of_turn>user\n",
        userSuffix = "<end_of_turn>\n<start_of_turn>model\n",
        modelPrefix = "",
        modelSuffix = "<end_of_turn>\n",
        systemPrefix = "",
        systemSuffix = "",
    ),

    /**
     * 无标记格式 —— 交回 MediaPipe 的默认处理。
     * 用于官方 .task 内部已自带模板、或我们不确定格式的模型：
     * 宁可"不指定"，也不要"指定错"。指定错的破坏力比不指定大得多。
     */
    DEFAULT(
        label = "引擎默认",
        userPrefix = "",
        userSuffix = "",
        modelPrefix = "",
        modelSuffix = "",
        systemPrefix = "",
        systemSuffix = "",
    ),
}
