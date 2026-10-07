package com.workbuddy.assistant.agent.ondevice

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7.5 原理课】端侧 RAG —— 让"离线查资料"真正成立
 * ══════════════════════════════════════════════════════════════════
 *
 * ── 先破一个概念误会 ────────────────────────────────────────────
 * RAG 的全名是 Retrieval-Augmented Generation，"检索增强生成"。
 * 它描述的是**一个模式**：先找资料，再把资料塞进 prompt 让模型基于资料回答。
 * 这个模式跟"检索跑在哪台机器上"没有任何关系。
 *
 * 所以按检索执行位置，RAG 有三种拓扑：
 *
 *   L0 纯云端     手机发问 → 云端检索+生成        离线完全不可用
 *   L1 混合       手机发问 → 服务端检索 → 云端生成  离线完全不可用
 *   L2 全端侧     手机内检索 + 手机内生成          离线可用 ★ 本类
 *
 * 我们之前做的是 L1（检索在 PC 的 FastAPI 里）。它不是"假 RAG"，
 * 但确实**不是端侧实现**，断网就废。
 *
 * ── L2 要三件套，缺一件都不成立 ─────────────────────────────────
 *   ① 本地 embedder      把文字变成向量     ← 本类负责
 *   ② 本地向量索引       存向量 + 算相似度   ← 本类负责
 *   ③ 本地 LLM          拿片段生成答案      ← W7 的 MediaPipeLlmEngine
 * 之前只有 ③，所以离线只能闲聊，一查资料就得联网。
 *
 * ── 为什么不能只做"向量检索"（这是本类最核心的设计）──────────────
 * Google 官方的 MediaPipe TextEmbedder 只托管两个模型，我都核实过：
 *   · universal_sentence_encoder.tflite   6.1MB  ← 英文语料训练
 *   · average_word_embedder.tflite        774KB  ← 词向量平均，更差
 * 而 multilingual 版本我用三个可能的路径探测，**全部 404，官方不存在**。
 *
 * 后果很直接：拿一个英文语料训出来的 512 维空间去编码中文笔记，
 * 语义相似度是**噪声**。你搜"我的学习计划"，它可能把"周末买菜"排前面。
 *
 * 所以本类做了**双路召回 + RRF 融合**：
 *   路 A 向量：USE 编码，擅长跨词表达（"怎么提升记忆" ↔ "组块化技巧"）
 *   路 B 字面：中文 bigram 倒排，擅长精确命中（"学习计划" ↔ 含"学习计划"的段）
 *
 * 中文场景实际主要靠 B 路兜底 —— 这不是妥协，这是**在约束下做对的事**。
 * 面试讲这段比讲"我调了 embedding API"有说服力得多：你懂检索为什么这么设计。
 *
 * ── 为什么用 RRF 融合，而不是把两个分数加权相加 ─────────────────
 * 余弦相似度范围 [-1,1]，bigram 余弦范围 [0,1]，两个分布形状完全不同。
 * 直接 `0.7*vec + 0.3*lex` 是错的：你等于假设两个分数"1 分代表的含义相同"，
 * 而它们根本不可比（就像把身高和体重加权求和）。
 *
 * RRF（Reciprocal Rank Fusion）只看**排名**不看分数：
 *     score = Σ  w_i / (k + rank_i)     k 通常取 60
 * 这样天然免疫量纲差异，是工业界多路召回的默认做法，实现还只有几行。
 * ══════════════════════════════════════════════════════════════════
 */

/** 向量模型的状态 —— 和 EngineState 同构，因为失败原因和应对方式一样 */
sealed interface RetrieverState {
    /** 向量模型文件不在（这是常态，不是错误：字面检索照样能用） */
    data object NoModel : RetrieverState
    data object Idle : RetrieverState
    data object Loading : RetrieverState
    data class Ready(val dim: Int, val loadMs: Long) : RetrieverState
    data class Failed(val reason: String) : RetrieverState
}

/** 命中方式 —— UI 上显示"靠什么找到的"，排查检索质量时非常有用 */
enum class MatchVia { VECTOR, LEXICAL, BOTH }

/** 一条检索结果 */
data class RetrievalHit(
    val title: String,
    val text: String,
    val score: Double,
    val via: MatchVia,
)

/** 给 UI 的知识库快照 */
data class KnowledgeStatus(
    val docCount: Int = 0,
    val chunkCount: Int = 0,
    /** 向量模型是否就绪（false 表示只有字面检索） */
    val vectorReady: Boolean = false,
    /** 一句话说明当前检索能力 */
    val mode: String = "仅字面检索",
)

/**
 * 端侧检索器。
 *
 * 生命周期与 OnDeviceLlm 对齐：懒加载、可释放、失败不抛异常。
 */
class OnDeviceRetriever(
    context: Context,
    private val repository: ModelRepository,
) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 索引读写锁：加文档 / 删文档 / 落盘 必须串行，否则两个协程会互相覆盖 */
    private val ioMutex = Mutex()

    /**
     * MediaPipe 的 Task 对象**不是线程安全的**。
     * embed() 会被多个协程调用（加文档时逐块编码），必须显式串行化。
     */
    @Volatile
    private var embedder: TextEmbedder? = null

    @Volatile
    private var dim: Int = 0

    /** 内存索引。磁盘上的 index.json 是它的序列化形态 */
    @Volatile
    private var index: List<StoredDoc> = emptyList()

    @Volatile
    private var indexLoaded: Boolean = false

    private val _state = MutableStateFlow<RetrieverState>(RetrieverState.NoModel)
    val state: StateFlow<RetrieverState> = _state.asStateFlow()

    private val _status = MutableStateFlow(KnowledgeStatus())
    val status: StateFlow<KnowledgeStatus> = _status.asStateFlow()

    // ══════════════════════════════════════════════════════════════
    // 模型加载
    // ══════════════════════════════════════════════════════════════

    fun isModelInstalled(): Boolean = repository.isAvailable(ModelCatalog.embedder)

    /**
     * 加载向量模型。失败返回状态而不是抛异常 —— 上层据此降级到"仅字面检索"。
     *
     * 注意这里的力量对比：**加载失败 ≠ 功能不可用**。
     * 字面检索是纯 Kotlin 实现，不依赖任何模型，永远可用。
     * 这就是为什么端侧要把"必需能力"和"增强能力"分开 ——
     * 增强能力挂掉时，必需能力还能兜住体验下限。
     */
    suspend fun ensureLoaded(): RetrieverState = withContext(Dispatchers.IO) {
        (_state.value as? RetrieverState.Ready)?.let { return@withContext it }
        if (embedder != null && dim > 0) {
            return@withContext RetrieverState.Ready(dim, 0)
        }

        // ── ABI 硬门槛 ──
        // tasks-text 的 aar 里 jni/ 只有 arm64-v8a（我解包数过：7.3MB）。
        // 没有 armeabi-v7a，也没有 x86_64 —— 32 位真机和模拟器都用不了。
        // 不预检查就会在 createFromOptions 里抛 UnsatisfiedLinkError，
        // 那是 Error 不是 Exception，catch(Exception) 抓不到，直接崩。
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            val reason = "当前 ABI 为 ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}，" +
                "向量模型仅支持 arm64-v8a（64 位真机）"
            Log.w(TAG, reason)
            _state.value = RetrieverState.Failed(reason)
            return@withContext RetrieverState.Failed(reason)
        }

        val file = repository.resolve(ModelCatalog.embedder) ?: run {
            _state.value = RetrieverState.NoModel
            return@withContext RetrieverState.NoModel
        }

        _state.value = RetrieverState.Loading
        val t0 = SystemClock.elapsedRealtime()
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(file.absolutePath)
                .build()

            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                // L2 归一化：把向量模长压成 1。
                // 之后"余弦相似度"就退化成"点积"，省掉两次开方和一次除法 ——
                // 检索要遍历上千个块，这个优化是实打实的。
                .setL2Normalize(true)
                .setQuantize(false)
                .build()

            val e = TextEmbedder.createFromOptions(appContext, options)

            // 探针：先跑一次拿到真实维度。
            // 为什么要探？因为不同 embedder 输出维度不同（USE 是 512），
            // 而我们的索引是按维度存的 —— 维度变了说明换了模型，旧索引必须重建。
            val probe = e.embed("probe").embeddingResult().embeddings().firstOrNull()
                ?.floatEmbedding()
            if (probe == null || probe.isEmpty()) {
                e.close()
                throw IllegalStateException("模型输出为空，可能文件损坏")
            }

            embedder = e
            dim = probe.size
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "向量模型加载完成: dim=$dim, ${ms}ms")
            val ready = RetrieverState.Ready(dim, ms)
            _state.value = ready
            refreshStatus(vectorReady = true)
            ready
        } catch (e: Throwable) {
            // 捕 Throwable 而不是 Exception：native 层抛的是 UnsatisfiedLinkError
            val reason = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "向量模型加载失败: $reason", e)
            _state.value = RetrieverState.Failed(reason)
            refreshStatus(vectorReady = false)
            RetrieverState.Failed(reason)
        }
    }

    fun release() {
        runCatching { embedder?.close() }
        embedder = null
        dim = 0
        _state.value = if (isModelInstalled()) RetrieverState.Idle else RetrieverState.NoModel
        refreshStatus(vectorReady = false)
        Log.i(TAG, "向量模型已释放")
    }

    // ══════════════════════════════════════════════════════════════
    // 知识库：写入
    // ══════════════════════════════════════════════════════════════

    /**
     * 添加一篇文档：切块 → 编码 → 落盘。
     *
     * @return 实际生成的块数
     */
    suspend fun addDocument(title: String, text: String): Result<Int> =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                ensureIndexLoaded()
                val clean = text.trim()
                if (clean.length < MIN_DOC_CHARS) {
                    return@withLock Result.failure(
                        IllegalArgumentException("内容太短（至少 $MIN_DOC_CHARS 字），检索没有意义")
                    )
                }

                val pieces = buildChunks(clean)
                if (pieces.isEmpty()) {
                    return@withLock Result.failure(IllegalArgumentException("切块后为空"))
                }

                // 尽力而为地编码：模型没加载就存空向量，只走字面检索。
                // 这样"没下向量模型"的用户也能用知识库（只是少了语义召回）。
                if (embedder == null && isModelInstalled()) ensureLoaded()

                val storedChunks = pieces.map { piece ->
                    StoredChunk(text = piece, vector = encodeVector(embedSync(piece)))
                }

                val doc = StoredDoc(
                    id = UUID.randomUUID().toString(),
                    title = title.trim().ifBlank { "未命名笔记" },
                    addedAt = System.currentTimeMillis(),
                    chars = clean.length,
                    chunks = storedChunks,
                )

                index = index + doc
                persistIndex()
                refreshStatus()
                Log.i(TAG, "入库完成: ${doc.title} / ${storedChunks.size} 块")
                Result.success(storedChunks.size)
            }
        }

    suspend fun deleteDocument(id: String): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            ensureIndexLoaded()
            val next = index.filterNot { it.id == id }
            if (next.size == index.size) return@withLock false
            index = next
            persistIndex()
            refreshStatus()
            true
        }
    }

    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            ensureIndexLoaded()
            val n = index.size
            index = emptyList()
            persistIndex()
            refreshStatus()
            n
        }
    }

    /** 文档列表（不含向量，给 UI 用） */
    fun documents(): List<DocSummary> {
        if (!indexLoaded) loadIndexIfNeeded()
        return index.map {
            DocSummary(
                id = it.id,
                title = it.title,
                chars = it.chars,
                chunkCount = it.chunks.size,
                addedAt = it.addedAt,
                hasVector = it.chunks.any { c -> c.vector.isNotEmpty() },
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 知识库：检索（本类的核心）
    // ══════════════════════════════════════════════════════════════

    /**
     * 检索 topK 个最相关片段。
     *
     * 纯内存计算，不联网、不阻塞网络 —— 单次耗时在几十毫秒量级，
     * 因为它只是"几千个 512 维点积 + 几千次集合交集"。
     * 这就是端侧检索能做实时（每次提问前都跑一遍）的原因。
     */
    fun search(query: String, topK: Int = DEFAULT_TOP_K): List<RetrievalHit> {
        if (!indexLoaded) loadIndexIfNeeded()
        val q = query.trim()
        if (q.isEmpty() || index.isEmpty()) return emptyList()

        val started = SystemClock.elapsedRealtime()

        val qGrams = bigrams(q)
        val qVec = embedSync(q)?.takeIf { dim > 0 && it.size == dim }

        // 扁平化成一张表，方便按下标做排名
        data class Cell(val doc: StoredDoc, val chunk: StoredChunk, val lex: Double, val vec: Double)
        val cells = ArrayList<Cell>()
        for (doc in index) {
            for (ch in doc.chunks) {
                val lex = lexicalScore(qGrams, bigrams(ch.text))
                val vec = if (qVec != null && ch.vector.isNotEmpty()) {
                    dot(qVec, decodeVector(ch.vector))
                } else 0.0
                cells += Cell(doc, ch, lex, vec)
            }
        }
        if (cells.isEmpty()) return emptyList()

        // ── RRF 融合 ──
        // 只看排名、不看分数，天然免疫两路量纲不同（见类注释）
        val byVector = cells.indices.sortedByDescending { cells[it].vec }
        val byLexical = cells.indices.sortedByDescending { cells[it].lex }

        val fused = HashMap<Int, Double>(cells.size)
        byVector.forEachIndexed { rank, i ->
            if (cells[i].vec >= VEC_MIN) {
                fused[i] = (fused[i] ?: 0.0) + W_VECTOR / (RRF_K + rank)
            }
        }
        byLexical.forEachIndexed { rank, i ->
            if (cells[i].lex >= LEX_MIN) {
                fused[i] = (fused[i] ?: 0.0) + W_LEXICAL / (RRF_K + rank)
            }
        }
        if (fused.isEmpty()) {
            Log.i(TAG, "检索无命中（词面/语义都低于阈值）: $q")
            return emptyList()
        }

        // ── 同一文档最多取 2 块 ──
        // 不加这个限制，一篇长文档的相邻块会因为"内容重叠"全部命中，
        // 喂给模型的 3 段全是同一段话的复制品 —— 上下文被浪费，答案反而更差。
        // 这是"chunk 重叠"策略的副作用，必须在这里收口。
        val perDoc = HashMap<String, Int>()
        val hits = ArrayList<RetrievalHit>(topK)

        for ((i, score) in fused.entries.sortedByDescending { it.value }) {
            val c = cells[i]
            if (hits.size >= topK) break
            val used = perDoc[c.doc.id] ?: 0
            if (used >= MAX_CHUNKS_PER_DOC) continue
            perDoc[c.doc.id] = used + 1

            val via = when {
                c.vec >= VEC_MIN && c.lex >= LEX_MIN -> MatchVia.BOTH
                c.vec >= VEC_MIN -> MatchVia.VECTOR
                else -> MatchVia.LEXICAL
            }
            hits += RetrievalHit(
                title = c.doc.title,
                text = c.chunk.text,
                score = score,
                via = via,
            )
        }

        Log.i(
            TAG,
            "检索完成: \"$q\" → ${hits.size} 块 / ${SystemClock.elapsedRealtime() - started}ms" +
                "（${if (qVec != null) "向量+字面" else "仅字面"}）"
        )
        return hits
    }

    /**
     * 把检索结果拼成给端侧小模型的上下文。
     *
     * 【为什么这个拼接方式很重要】
     * 1B 模型对"格式"极其敏感：没有明确分隔符，它会把多段资料当成一段，
     * 然后开始自由发挥。用 [资料1] 这样的标记 + 显式指令（"只依据资料回答"），
     * 是在小模型有限指令遵循能力下最稳的写法。
     *
     * 另外**必须给"不知道"的出口**。不给出口，模型一定会编 ——
     * 这是 RAG 系统里最常见的失败模式（检索没命中，模型硬答）。
     */
    fun buildContext(hits: List<RetrievalHit>, maxChars: Int = MAX_CONTEXT_CHARS): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        var used = 0
        for ((i, h) in hits.withIndex()) {
            if (used >= maxChars) break
            val budget = (maxChars - used).coerceAtLeast(80)
            val body = h.text.take(budget)
            sb.append("【资料${i + 1}·${h.title}】\n").append(body).append("\n\n")
            used += body.length
        }
        return sb.toString().trim()
    }

    // ══════════════════════════════════════════════════════════════
    // 向量编码
    // ══════════════════════════════════════════════════════════════

    /** 串行化的编码调用；模型未就绪时返回 null（调用方据此跳过向量路） */
    @Synchronized
    private fun embedSync(text: String): FloatArray? {
        val e = embedder ?: return null
        if (text.isBlank()) return null
        return runCatching {
            e.embed(text).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
        }.onFailure { Log.w(TAG, "编码失败: ${it.message}") }.getOrNull()
    }

    // ══════════════════════════════════════════════════════════════
    // 切块
    // ══════════════════════════════════════════════════════════════

    /**
     * 中文文档切块：段落 → 句子 → 贪心装箱 → 加重叠。
     *
     * 【为什么端侧要"小"块】
     * 块越大，单块信息越全，但塞进 prompt 的 token 越多 → 小模型 prefill 越慢
     * （端侧 prefill 与 prompt 长度近似线性）。而且 1B 模型的注意力本来就弱，
     * 塞 2000 字它只会看开头和结尾。
     * 320 字是"信息完整度"和"小模型能消化"之间的折中值。
     */
    private fun buildChunks(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n")
        val chunks = ArrayList<String>()

        for (para in normalized.split(Regex("\n{2,}"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            for (piece in packSentences(splitSentences(p))) {
                chunks += piece
            }
        }
        return addOverlap(chunks).filter { it.isNotBlank() }
    }

    /**
     * 按句末标点切。中文用 。！？，英文用 .!?
     *
     * ⚠️ 英文的 "." 只在"后面跟空格"时才当句末，否则 "3.14" / "e.g." 会被切碎。
     * 这是中文场景下难得要小心的地方 —— 反过来也成立：
     * 中文没有空格，所以中文标点可以无条件切。
     */
    private fun splitSentences(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            sb.append(ch)
            val isCnEnd = ch in "。！？；"
            val isEnEnd = ch == '.' || ch == '!' || ch == '?' || ch == ';'
            val nextIsSpace = i + 1 >= s.length || s[i + 1].isWhitespace() || s[i + 1] == '\n'
            if (isCnEnd || (isEnEnd && nextIsSpace) || ch == '\n') {
                out += sb.toString().trim()
                sb.clear()
            }
            i++
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /** 贪心装箱：把短句合并到接近 MAX_CHUNK，超长单句硬切 */
    private fun packSentences(sentences: List<String>): List<String> {
        val chunks = ArrayList<String>()
        val cur = StringBuilder()

        fun flush() {
            if (cur.isNotBlank()) {
                chunks += cur.toString().trim()
                cur.clear()
            }
        }

        for (s in sentences) {
            if (s.length >= MAX_CHUNK) {
                flush()
                s.chunked(MAX_CHUNK).forEach { chunks += it }
                continue
            }
            if (cur.isNotEmpty() && cur.length + s.length > MAX_CHUNK) flush()
            cur.append(s)
        }
        flush()
        return chunks
    }

    /**
     * 给相邻块加重叠。
     *
     * 为什么必须重叠：一句话被切在两个块中间时，左边块"没说完"、右边块"没头没尾"，
     * 两块的向量和字面特征都不完整，检索两头落空。
     * 重叠 60 字（约一句话）保证任何一句话至少完整出现在一个块里。
     *
     * 代价：索引体积增加约 20%，检索时同文档容易多块命中（已用 MAX_CHUNKS_PER_DOC 收口）。
     */
    private fun addOverlap(chunks: List<String>): List<String> {
        if (chunks.size <= 1) return chunks
        return chunks.mapIndexed { i, c ->
            if (i == 0) c
            else {
                val prev = chunks[i - 1]
                val tail = prev.takeLast(OVERLAP_CHARS.coerceAtMost(prev.length / 2))
                tail + c
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 字面检索：中文 bigram
    // ══════════════════════════════════════════════════════════════

    /**
     * 把文本切成"相邻两字"的集合（bigram）。
     *
     * 【为什么是 bigram 而不是分词】
     * 中文没有空格，要做词级检索必须先分词。端侧分词有两条路，都不划算：
     *   · 带词典（jieba 移植）：词典本身几 MB，还要处理未登录词；
     *   · 跑模型：慢，且为了检索再加载一个模型不值得。
     * bigram 是"零依赖的近似分词"：不需要词典、天然覆盖未登录词
     * （"端侧RAG"这种新词也能命中），代价只是召回略高。
     *
     * 顺带一句：这也是很多搜索引擎对中文的默认处理方式之一，
     * 不是"土办法"，是经过权衡的工程选择。
     */
    private fun bigrams(s: String): Set<String> {
        val norm = s.lowercase().filter { it.isLetterOrDigit() }
        if (norm.isEmpty()) return emptySet()
        if (norm.length == 1) return setOf(norm)
        val set = HashSet<String>(norm.length)
        for (i in 0..norm.length - 2) {
            set += norm.substring(i, i + 2)
        }
        return set
    }

    /**
     * 字面相似度：查询包含度（query containment）
     *
     *     |Q ∩ C| / |Q|
     *
     * 【为什么不能按余弦的几何平均算（曾经的真实 bug）】
     * 旧实现是 hit / sqrt(|Q| × |C|)。块是 320 字（约 300 个 bigram），
     * 提问通常 10 字（约 8 个 bigram）。哪怕命中了 3 个关键词 bigram：
     *     3 / sqrt(8 × 299) ≈ 0.06  < 阈值 0.08 → miss！
     * 笔记明明导进去了，检索却一个都不返回 —— 用户看到的现象就是
     * "导入了笔记，一问三不知"。这叫**长文档稀释**：
     * 分母里有 |C|，文档越长，同样的命中数分数越低。
     *
     * 查询包含度把分母固定为 |Q|：
     *     3 / 8 = 0.375 —— 稳稳过线。
     * 代价是"文档越短越占便宜"，但检索场景里 query 短、文档长是常态，
     * 短 query 的命中更可信，这个偏置方向是对的。
     */
    private fun lexicalScore(qGrams: Set<String>, cGrams: Set<String>): Double {
        if (qGrams.isEmpty() || cGrams.isEmpty()) return 0.0
        var hit = 0
        for (g in qGrams) if (g in cGrams) hit++
        if (hit == 0) return 0.0
        return hit.toDouble() / qGrams.size
    }

    /** 归一化向量的点积 = 余弦相似度（省掉开方和除法） */
    private fun dot(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var sum = 0.0
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }

    // ══════════════════════════════════════════════════════════════
    // 持久化
    // ══════════════════════════════════════════════════════════════

    /**
     * 向量为什么不存 JSON 数字数组，而存 Base64？
     *
     * 一个 float 用 JSON 文本表示平均要 10-12 字节（"0.123456789,"），
     * 存成 4 字节小端二进制只要 4 字节 —— **差 3 倍**。
     * 512 维 × 1000 块：JSON 约 6MB，Base64 约 700KB（Base64 自身膨胀 4/3）。
     *
     * 更关键的是解析：`Json.decodeFromString` 解析 50 万个浮点数字符串，
     * 在手机上要好几秒；Base64 解码是 memcpy 级别的操作。
     * 这是"存储格式选错，冷启动慢到用户以为卡死"的典型案例。
     */
    private fun encodeVector(v: FloatArray?): String {
        if (v == null || v.isEmpty()) return ""
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) buf.putFloat(f)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    private fun decodeVector(s: String): FloatArray {
        if (s.isEmpty()) return FloatArray(0)
        return runCatching {
            val bytes = Base64.decode(s, Base64.NO_WRAP)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(bytes.size / 4)
            for (i in out.indices) out[i] = buf.float
            out
        }.getOrDefault(FloatArray(0))
    }

    private fun indexFile(): File =
        File(appContext.filesDir, "knowledge").apply { if (!exists()) mkdirs() }
            .let { File(it, "index.json") }

    private fun loadIndexIfNeeded() {
        if (indexLoaded) return
        val f = indexFile()
        if (!f.exists()) {
            indexLoaded = true
            refreshStatus()
            return
        }
        runCatching {
            val stored = json.decodeFromString<StoredIndex>(f.readText())
            index = stored.docs
            Log.i(TAG, "索引加载: ${index.size} 篇 / ${index.sumOf { it.chunks.size }} 块")
        }.onFailure {
            // 索引损坏不能崩 App：知识库是增强功能，坏了就当空的，
            // 让用户重新导入即可。这里记录日志但不清理文件（便于排查）。
            Log.e(TAG, "索引损坏，按空库处理: ${it.message}", it)
            index = emptyList()
        }
        indexLoaded = true
        refreshStatus()
    }

    /** 构造期/首次访问时的懒加载入口（避免构造期做 IO 触发启动耗时） */
    private fun ensureIndexLoaded() {
        if (!indexLoaded) loadIndexIfNeeded()
    }

    private fun persistIndex() {
        runCatching {
            val f = indexFile()
            // 先写临时文件再 rename：和下载器同样的道理，
            // 写到一半被杀进程不会留下"看起来合法的残缺 JSON"。
            val tmp = File(f.parentFile, "index.json.tmp")
            tmp.writeText(json.encodeToString(StoredIndex(docs = index)))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }.onFailure { Log.e(TAG, "索引落盘失败: ${it.message}", it) }
    }

    private fun refreshStatus(vectorReady: Boolean = _state.value is RetrieverState.Ready) {
        val chunks = index.sumOf { it.chunks.size }
        _status.value = KnowledgeStatus(
            docCount = index.size,
            chunkCount = chunks,
            vectorReady = vectorReady,
            mode = when {
                vectorReady -> "向量 + 字面双路"
                isModelInstalled() -> "仅字面（向量模型未加载）"
                else -> "仅字面（未下载向量模型）"
            },
        )
    }

    companion object {
        private const val TAG = "ON_DEVICE_RAG"

        /** 单块上限（字）。见 buildChunks 注释 */
        private const val MAX_CHUNK = 320
        private const val OVERLAP_CHARS = 60

        /** 文档最短长度：太短的内容检索没有意义，也容易污染索引 */
        private const val MIN_DOC_CHARS = 20

        /**
         * 喂给端侧模型的资料总预算（字）。
         *
         * 为什么是 600 而不是 1500？
         * Qwen2.5 的 .task 名字里写着 `ekv1280` —— KV Cache 只能装 1280 个 token，
         * 而且这个容量**同时包含 prompt 和生成的内容**。
         * 中文约 1.5 字/token：
         *   600 字资料 ≈ 400 token，加历史 1 轮 ≈ 130 token，加问题 ≈ 50 token
         *   → prompt 约 580 token，剩下 ~700 给生成（maxTokens 512）刚好够。
         * 贪心塞满 2000 字的结果是：模型刚"读完"资料就被截断，
         * 或者根本没有预算生成答案 —— 表现为"回答到一半突然停住"。
         */
        private const val MAX_CONTEXT_CHARS = 600

        const val DEFAULT_TOP_K = 3

        /** 同一文档最多贡献几块，防止相邻块刷屏 */
        private const val MAX_CHUNKS_PER_DOC = 2

        /**
         * 阈值与权重 —— 这四个数是**经验值，必须靠评估集调**。
         *
         * 我在注释里写清楚它们的含义，因为"调检索"本质上就是调这几个数：
         *   VEC_MIN  余弦下限。中文用英文 USE，分数普遍偏低，所以放到 0.30；
         *            再高会把大量正确结果挡掉。
         *   LEX_MIN  查询包含度下限（分母是 |Q|，见 lexicalScore 的注释）。
         *            含义是"提问里至少 15% 的 bigram 在资料中出现过"。
         *            提高它的目的：挡掉"只碰巧共享 1 个 bigram"的噪声命中
         *            （10 字提问碰上 1 个 → 0.10 < 0.15，正确地被挡掉）。
         *   W_*      两路在 RRF 里的权重。中文场景字面更可靠，所以 1.2 : 1.0。
         *   RRF_K    平滑常数。越大越"温和"（排名差距影响变小），60 是文献默认值。
         */
        private const val VEC_MIN = 0.30
        private const val LEX_MIN = 0.15
        private const val W_VECTOR = 1.0
        private const val W_LEXICAL = 1.2
        private const val RRF_K = 60.0
    }
}

/** 文档摘要（UI 列表用，不含向量，避免把几 MB 数据带进 Compose） */
data class DocSummary(
    val id: String,
    val title: String,
    val chars: Int,
    val chunkCount: Int,
    val addedAt: Long,
    val hasVector: Boolean,
)

// ══════════════════════════════════════════════════════════════════
// 磁盘格式（只在本文件内部使用）
// ══════════════════════════════════════════════════════════════════

@Serializable
private data class StoredIndex(
    val version: Int = 1,
    val docs: List<StoredDoc> = emptyList(),
)

@Serializable
private data class StoredDoc(
    val id: String,
    val title: String,
    val addedAt: Long,
    val chars: Int,
    val chunks: List<StoredChunk>,
)

@Serializable
private data class StoredChunk(
    val text: String,
    /** Base64(float32 little-endian)；空串表示这个块只有字面特征 */
    val vector: String = "",
)
