# ═══════════════════════════════════════════════════════════════════
# 【W3 教学点】R8 混淆与 LangChain4j 的恩怨
# ═══════════════════════════════════════════════════════════════════
#
# LangChain4j 的两个核心机制都依赖"运行时反射"：
# 1. AiServices 用 java.lang.reflect.Proxy 动态实现你的 Assistant 接口，
#    并读取接口方法上的 @SystemMessage/@UserMessage/@MemoryId 注解
# 2. 内部用 Jackson 序列化请求，靠反射读字段名
#
# R8（release 混淆）会把"没人直接调用"的类改名/删除、注解也可能被剥掉，
# 运行时反射找不到目标 → 运行到那行才崩（不是编译期报错！最阴险的一类 bug）。
#
# 当前 debug 构建 minifyEnabled=false 不受影响；将来开 release 混淆前必须配好：
-keep class dev.langchain4j.** { *; }
-keep class com.workbuddy.assistant.agent.Assistant { *; }   # AiServices 反射的目标接口
-dontwarn dev.langchain4j.**
-dontwarn org.slf4j.**    # langchain4j 可选的日志依赖，Android 上没有，忽略警告
