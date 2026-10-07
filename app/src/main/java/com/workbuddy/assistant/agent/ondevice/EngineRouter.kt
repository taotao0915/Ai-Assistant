package com.workbuddy.assistant.agent.ondevice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7 原理课 5】端云路由 —— 什么时候必须在手机上跑？
 * ══════════════════════════════════════════════════════════════════
 *
 * 很多人做端侧的第一步就错了：把端侧当成"云端的廉价替代品"，
 * 想着"省点 API 费用"。这个动机撑不起一个功能，面试官一听就知道你没做真。
 *
 * 端侧真正的、不可替代的价值只有四条（背下来，面试直接说这个）：
 *
 *  ① 隐私（Privacy）：数据根本不出设备。
 *     手机号、身份证、体检报告、聊天记录 —— 这类内容让用户
 *     "先上传到一个云服务器再回答"，本身就是原罪。
 *     端侧不是"更省钱"，是"唯一合法合规的选择"。
 *
 *  ② 离线（Offline）：没网也能用。
 *     地铁、飞机、电梯、境外漫游。云端方案在无网时是"完全不可用"，
 *     端侧是"降级可用" —— 这是从 0 到 1 的差别。
 *
 *  ③ 延迟（Latency）：网络往返消失。
 *     云端最佳情况 TTFT 也要 300-800ms（含 RTT + 排队）；
 *     端侧 prefill 完即出字，稳定性还远高于云端（不受服务端排队影响）。
 *
 *  ④ 成本（Cost）：高频轻任务不消耗 token。
 *     严格说这是"顺带收益"，不是核心动机。
 *
 * 反过来说，端侧**做不到**什么（1B int4 模型的真实边界）：
 *   · 没有工具调用能力（Function Calling 遵循率极低）
 *   · 没有知识库（KV Cache 塞不下几篇文档）
 *   · 长上下文一塌糊涂（>1K 就开始胡言乱语）
 *   · 复杂推理基本不行（数学、代码、多步规划）
 *
 * 所以正确的问题不是"用端侧还是云端"，而是
 * **"这一句话，交给谁"** —— 这就是路由层要解决的事。
 * ══════════════════════════════════════════════════════════════════
 */

/**
 * 路由策略 —— 用户可见的三档。
 *
 * 注意"自动"才是默认：端云混合的产品不该让用户每次自己选，
 * 用户根本不知道哪个问题端侧答得好。策略是给极客的逃生舱。
 */
enum class RoutePolicy(val label: String, val description: String) {
    CLOUD_ONLY(
        "仅云端",
        "全部交给 qwen-plus。能力最强，但需要网络，数据会离开设备。"
    ),
    AUTO(
        "自动（端云混合）",
        "按内容智能择路：隐私与短问答走端侧，需要工具/知识库的走云端。"
    ),
    DEVICE_PREFERRED(
        "优先端侧",
        "尽量在手机上回答，端侧不可用时自动回退云端。"
    ),
}

/** 本次请求被送去哪里 */
enum class RouteTarget { CLOUD, DEVICE }

/**
 * 路由决策 —— 不止是"走哪"，还要带上"为什么"。
 *
 * 为什么要存 reason？两个用途：
 *   1. UI 上显示"因隐私内容，已在本地回答" —— 用户能感知到价值，
 *      这是端侧功能最重要的心智建设（否则用户根本不知道端侧在干活）。
 *   2. 调试与埋点时能复盘：路由规则写错没、误判率多高。
 * 一个只有 target 没有 reason 的路由器，是没法调优的。
 */
data class RouteDecision(
    val target: RouteTarget,
    val reason: String,
)

/**
 * 端云路由器。
 *
 * 设计上刻意做成"**无状态纯函数 + 一次网络查询**"：
 * 同样的输入必然得到同样的输出，方便单测（W6 我们给 RAG 写了冒烟测试，
 * 路由同样应该可测 —— 这是工程化意识的延续）。
 */
class EngineRouter(private val context: Context) {

    /**
     * 决策入口。
     *
     * @param text           用户这一句原文
     * @param policy         当前策略
     * @param deviceReady    端侧引擎是否已加载完成
     * @param deviceReason   端侧不可用的原因（用于 UI 解释"为什么没用端侧"）
     */
    fun decide(
        text: String,
        policy: RoutePolicy,
        deviceReady: Boolean,
        deviceReason: String = "端侧模型未安装",
    ): RouteDecision {
        val trimmed = text.trim()

        // 策略层先短路：用户明确只要云端，那就不折腾了
        if (policy == RoutePolicy.CLOUD_ONLY) {
            return RouteDecision(RouteTarget.CLOUD, "策略：仅云端")
        }
        if (deviceReady.not()) {
            return RouteDecision(RouteTarget.CLOUD, deviceReason)
        }

        val online = isOnline()

        // ── 规则 1：无网 → 端侧（端侧最硬的价值，优先级最高）──
        if (!online) {
            return RouteDecision(RouteTarget.DEVICE, "当前无网络，离线模式回答")
        }

        // ── 规则 2：工具 / 检索意图 → 云端 ──
        // 端侧 1B 模型调用工具的能力基本为零，知识库更塞不下。
        // 这类请求必须给云端，否则用户会得到一本正经的胡说。
        if (matchesAny(trimmed, TOOL_INTENT)) {
            return RouteDecision(RouteTarget.CLOUD, "需要工具或知识库检索，云端执行")
        }

        // ── 规则 3：隐私内容 → 端侧（数据不出设备）──
        if (matchesAny(trimmed, PRIVACY_INTENT)) {
            return RouteDecision(RouteTarget.DEVICE, "涉及隐私内容，本地推理不上传")
        }

        // ── 策略 DEVICE_PREFERRED：除工具类外都尽量本地 ──
        if (policy == RoutePolicy.DEVICE_PREFERRED) {
            return RouteDecision(RouteTarget.DEVICE, "策略：优先端侧（本地推理）")
        }

        // ── 规则 4：短问答 → 端侧（省 token，延迟也更低）──
        // 阈值 40 字：低于这个长度的多为闲聊、寒暄、简单事实问题，
        // 1B 模型够用。超过就明显是"需要思考"的任务了。
        if (trimmed.length <= SHORT_QUERY_MAX_CHARS) {
            return RouteDecision(RouteTarget.DEVICE, "短问答，本地推理更快更省")
        }

        // ── 默认：云端 ──
        return RouteDecision(RouteTarget.CLOUD, "复杂任务，交由云端大模型")
    }

    /**
     * 真·联网检测。
     *
     * 为什么不用 `NetworkInfo.isConnected`？它在 API 29 已废弃，
     * 而且"连上了 WiFi"不等于"能上网"（酒店 Portal、连了但没出口）。
     * 正确做法是看 **NetworkCapabilities 有没有 INTERNET 能力位**。
     */
    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return true   // 拿不到服务就当有网，走云端
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.w(TAG, "网络状态检测失败，保守走云端", e)
            true
        }
    }

    private fun matchesAny(text: String, keywords: List<String>): Boolean =
        keywords.any { text.contains(it) }

    private companion object {
        const val TAG = "ON_DEVICE"
        const val SHORT_QUERY_MAX_CHARS = 40

        /**
         * 工具 / 检索 / 实时信息意图。
         *
         * 这份词表就是"路由的召回"：宁可多判给云端。
         * 因为误判成端侧的代价（答错、答不了）远大于误判成云端的代价（多花点 token）。
         * 【面试可讲】这是策略设计里的**非对称代价**思维 ——
         * 两边判错的损失不一样时，阈值就不该取中间值。
         */
        val TOOL_INTENT = listOf(
            // 本地工具（DeviceTools，需要云端模型来调度）
            "几点", "时间", "电量", "备忘录", "记一下", "帮我记", "记下", "提醒我",
            // 云端知识库与 RAG
            "知识库", "文档", "资料", "笔记", "检索", "搜索", "查一下", "查查", "找找",
            // MCP 外部工具
            "天气", "新闻", "股票", "机票", "汇率", "MCP",
            // 开放式任务
            "帮我写", "写一个", "做个方案", "总结一下", "翻译",
        )

        /**
         * 隐私意图。
         *
         * 注意"我的"这个词很宽泛（"我的手机几点关机"也会命中），
         * 但它前面已经被 TOOL_INTENT 拦掉了大部分 —— 这正是规则顺序的意义：
         * **先判工具，再判隐私**，因为工具类请求即便涉及隐私，端侧也做不了，
         * 硬走端侧只会得到一个错误答案，反而更伤害用户体验。
         */
        val PRIVACY_INTENT = listOf(
            "我的", "私人", "身份证", "手机号", "银行卡", "密码", "工资", "薪资",
            "简历", "体检", "病历", "家庭住址", "私密",
        )
    }
}
