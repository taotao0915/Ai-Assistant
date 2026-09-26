package com.workbuddy.assistant.agent

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W4 原理课 6】@Tool —— 给模型装上"手"，聊天机器人变 Agent
 * ══════════════════════════════════════════════════════════════════
 *
 * 一个 @Tool 方法 = 一件模型本来做不到、但手机能做到的事。
 * 模型自己：只知道训练数据里的知识，不知道"现在几点""电量多少"——
 *   它根本没有"现在"，也没有"这台手机"。
 * 有了工具：模型在需要时"提出请求"（"帮我调 getBatteryLevel"），
 *   LangChain4j 反射执行真机代码，把结果喂回去，模型再组织语言回答。
 *
 * ── 注解怎么读 ──────────────────────────────────────────────────
 * @Tool("描述")        → 描述是给【模型】看的选型说明书。模型靠它决定
 *                        什么时候用这个工具。写"什么时候该用+返回什么"，
 *                        比写"怎么实现"重要十倍。
 * @P("参数描述")        → 告诉模型这个参数该填什么。模型会自己从
 *                        用户的自然语言里抽取出来填进去。
 *
 * ── Android 专项纪律 ───────────────────────────────────────────
 * 1. 工具方法跑在 OkHttp 的后台线程（流式回调线程），不能碰 UI！
 *    要更新界面就用 ViewModel/StateFlow（或 post 到主线程）。
 * 2. 工具要"快"——它的耗时直接叠进用户等待时间。
 * 3. 返回值是 String，写给模型看：信息密度高一点，别返回一堆对象 toString。
 */
class DeviceTools(private val context: Context) {

    /** 备忘录的持久化：W4 用最简单的 SharedPreferences（W5 换云数据库） */
    private val prefs = context.getSharedPreferences("device_memos", Context.MODE_PRIVATE)

    /**
     * 工具 1：当前时间。没有任何参数——模型知道"不知道几点"就该调它。
     * 为什么这工具重要：LLM 没有时钟，"现在"对它是不存在的概念。
     */
    @Tool("查询当前手机的日期和时间，包括星期几。当用户询问现在几点、今天日期、星期几，或需要基于当前时间做判断时使用")
    fun getCurrentTime(): String {
        val now = LocalDateTime.now()
        // DateTimeFormatter 属于 java.time —— 全靠 W3 配好的 desugaring 才能跑
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm（EEEE）", Locale.CHINA)
        val text = now.format(formatter)
        Log.i("SSE_CHAT", "工具执行: getCurrentTime() -> $text")
        return "当前时间是 $text"
    }

    /**
     * 工具 2：电量。用 Android 标准的 BatteryManager 服务——
     * 这段代码和"AI"毫无关系，就是普通 Android 开发；AI 只负责"决定何时调它"。
     * 这个分层正是 Agent 的精髓：决策归模型，执行归设备。
     */
    @Tool("查询手机当前电量百分比。当用户询问电量、是否需要充电时使用")
    fun getBatteryLevel(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val text = "当前电量为 $level%"
        Log.i("SSE_CHAT", "工具执行: getBatteryLevel() -> $text")
        return text
    }

    /**
     * 工具 3：记备忘（带参数的示范）。注意 @P —— 模型会从用户的话里
     * 自动抽取内容填进来，比如"帮我记一下明天交周报"→ content="明天交周报"。
     * 返回确认文案给模型，模型会转述给用户。
     */
    @Tool("保存一条备忘录到手机。当用户说'帮我记一下/记住/别忘了'某事时使用")
    fun saveMemo(@P("要记住的备忘内容，原样保留用户表达") content: String): String {
        val key = "memo_${System.currentTimeMillis()}"
        prefs.edit().putString(key, content).apply()
        Log.i("SSE_CHAT", "工具执行: saveMemo($content)")
        return "已保存备忘录：$content"
    }

    /**
     * 工具 4：读备忘。和 saveMemo 成对——模型会根据用户意图自己二选一，
     * 这就是"工具描述写清楚"的价值：描述就是模型的选择题选项。
     */
    @Tool("读取手机上已保存的全部备忘录。当用户询问'我记过什么/我的备忘'时使用")
    fun listMemos(): String {
        val memos = prefs.all.entries.sortedBy { it.key }
        Log.i("SSE_CHAT", "工具执行: listMemos() -> ${memos.size} 条")
        if (memos.isEmpty()) return "还没有任何备忘录"
        return memos.mapIndexed { index, entry -> "${index + 1}. ${entry.value}" }
            .joinToString("\n", prefix = "共${memos.size}条备忘录：\n")
    }
}
