package com.workbuddy.assistant.agent.ondevice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7 原理课 2】端侧内存账 —— 你的手机到底能跑多大的模型？
 * ══════════════════════════════════════════════════════════════════
 *
 * 云端部署模型时你算的是"显存够不够放权重"；端侧要算的账复杂得多，
 * 因为手机内存要和微信、相机、系统一起抢。峰值内存三块：
 *
 *   峰值 ≈ 权重 + KV Cache + 运行时缓冲
 *
 * ── 第 1 块：权重 ───────────────────────────────────────────────
 *   权重字节数 ≈ 参数量 × 量化位宽 / 8
 *   例：1.5B 参数 @ int4 → 1.5e9 × 4 / 8 = 750 MB
 *       但实际会更大，因为有两处"高精度例外"：
 *         - embedding / lm_head 层通常保留 fp16（占参数量的 10-15%）
 *         - 量化是"分组"的（group=128），每组带 scale/zero 元数据
 *       经验值：int4 的**实际**占用 ≈ 参数量 × 0.7 字节
 *         → 1.5B ≈ 1.0 GB，1B ≈ 0.7 GB，0.5B ≈ 0.35 GB
 *
 * ── 第 2 块：KV Cache（真正的隐形大户）──────────────────────────
 *   公式：2(K和V) × 层数 × kv_heads × head_dim × 2字节(fp16) × 上下文长度
 *   以 Qwen2.5-1.5B 为例（28 层、2 个 KV head、head_dim=128）：
 *     单 token = 2 × 28 × 2 × 128 × 2 = 28,672 字节 ≈ 28 KB
 *     2048 上下文 → 28KB × 2048 ≈ 57 MB
 *   看起来不大，但注意：**它随上下文线性增长**。
 *   如果换个 7B 模型（32 层、8 个 KV head），单 token 就是 128KB，
 *   2048 上下文 → 256 MB。这就是为什么高端机也不敢开长上下文。
 *
 * ── 第 3 块：运行时缓冲 ─────────────────────────────────────────
 *   算子临时张量、tokenizer、图片/音频预处理（多模态时更大）。
 *   经验值：留 150-250 MB。
 *
 * ── 安全系数（工程经验，别省）──────────────────────────────────
 *   模型加载后手机还要继续用（接电话、切 App、系统收内存）。
 *   所以可用内存必须 ≥ 需求 × 1.5，否则系统 LMK 会在后台把你干掉。
 *   这就是端侧最真实的约束：**不是"能不能跑起来"，而是"能不能不被杀"。**
 * ══════════════════════════════════════════════════════════════════
 */

/** 设备能力快照 —— 一次探测，全局复用 */
data class DeviceProfile(
    val totalMemMb: Long,
    val availMemMb: Long,
    val isLowRamDevice: Boolean,
    val cpuCores: Int,
    val abi: String,
    val is64Bit: Boolean,
    val sdkInt: Int,
) {
    /** 单行摘要，用于 UI / 日志 */
    val summary: String
        get() = "内存 ${totalMemMb / 1024}GB(可用${availMemMb}MB) · " +
            "${cpuCores}核 · $abi · API$sdkInt"
}

/** 模型档位 —— 我们支持的模型规模分级 */
enum class ModelTier(
    val label: String,
    /** 权重 + KV(1280) + 缓冲 的估算峰值，单位 MB */
    val peakMemMb: Long,
    /** 建议的最低总内存，单位 MB */
    val minTotalMemMb: Long,
) {
    // ── 下面这些数字是**按实际模型算出来的**，不是拍的 ──────────────
    // 模型已从 int4 计划改为真正可下载的 Qwen2.5 int8 版本，所以内存账重算：
    //
    //  Qwen2.5-0.5B q8（文件 521MB）
    //    权重 546MB（含 tokenizer/元数据）+ KV 15MB（24层×2head×64dim）
    //    + 缓冲 200MB ≈ 761MB → 取 800
    //  Qwen2.5-1.5B q8（文件 1.49GB）
    //    权重 1524MB + KV 36MB（28层×2head×128dim）+ 缓冲 250MB ≈ 1810MB → 取 1900
    //
    // 注意 int8 的"实际占用 ≈ 文件体积"这条经验法则（权重是定长的，
    // 不像 int4 那样有额外的高精度例外层），比 int4 好估。
    TINY("0.5B 级", peakMemMb = 800, minTotalMemMb = 4 * 1024L),
    SMALL("1B 级", peakMemMb = 1200, minTotalMemMb = 6 * 1024L),
    MEDIUM("1.5B 级", peakMemMb = 1900, minTotalMemMb = 8 * 1024L);

    companion object {
        /** 从小到大排列，供"往上/往下选一档"使用 */
        val ascending: List<ModelTier> = listOf(TINY, SMALL, MEDIUM)
    }
}

/**
 * 设备能力探测器。
 *
 * 这是端侧**必须做**的一件事（面试可以讲的价值点）：
 * 同一个 APK 要装到 4GB 千元机和 16GB 旗舰上，模型不能写死。
 * 正确姿势是运行时探测 → 选档 → 告诉用户"你的设备推荐这个"。
 * Google 的 AICore / Gemini Nano 也是这套逻辑。
 */
object DeviceProfiler {

    private const val TAG = "ON_DEVICE"

    /** 安全系数：可用内存必须是峰值的 1.5 倍，否则 Linux 低内存杀手会干掉我们 */
    private const val SAFETY_FACTOR = 1.5

    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        // 端侧推理库（MNN/MediaPipe）都是 64 位产物。
        // 32 位进程的地址空间上限约 3-4GB，光模型就吃掉大半，必然 OOM。
        // 所以"是否 64 位"是端侧的硬门槛，不是建议。
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

        return DeviceProfile(
            totalMemMb = mi.totalMem / (1024 * 1024),
            availMemMb = mi.availMem / (1024 * 1024),
            isLowRamDevice = am.isLowRamDevice,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abi = abi,
            is64Bit = is64Bit,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    /**
     * 推荐档位：能跑得动的**最大**那一档；都不行返回 null。
     *
     * 判断是"双条件"——总内存够 且 当前可用内存够。
     * 只看总内存会翻车：8GB 手机看着够，但后台挂着 30 个 App 时
     * 可用可能只剩 1.2GB，加载即被杀。
     */
    fun recommendTier(profile: DeviceProfile): ModelTier? {
        if (!profile.is64Bit) {
            Log.w(TAG, "32 位进程，端侧推理不可用（地址空间不足）")
            return null
        }
        if (profile.isLowRamDevice) {
            Log.w(TAG, "系统标记为低内存设备，不建议跑端侧 LLM")
            return null
        }

        return ModelTier.ascending.lastOrNull { tier ->
            profile.totalMemMb >= tier.minTotalMemMb &&
                profile.availMemMb >= (tier.peakMemMb * SAFETY_FACTOR).toLong()
        }
    }

    /**
     * 档位是否"物理可行"（只校验总内存，不看当前可用量）。
     * 用途：用户手动选了一个高配档，我们提示"能跑但可能被系统杀"。
     */
    fun canRun(tier: ModelTier, profile: DeviceProfile): Boolean =
        profile.is64Bit && profile.totalMemMb >= tier.minTotalMemMb

    /** 磁盘空间合计（模型几百 MB，装不下就是装不下） */
    fun freeDiskMb(dir: File): Long {
        return try {
            dir.usableSpace / (1024 * 1024)
        } catch (e: Exception) {
            -1
        }
    }
}
