package com.workbuddy.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.workbuddy.assistant.agent.DeviceStatus
import com.workbuddy.assistant.agent.ondevice.DeviceProfile
import com.workbuddy.assistant.agent.ondevice.DocSummary
import com.workbuddy.assistant.agent.ondevice.DownloadState
import com.workbuddy.assistant.agent.ondevice.EngineState
import com.workbuddy.assistant.agent.ondevice.GenStats
import com.workbuddy.assistant.agent.ondevice.KnowledgeStatus
import com.workbuddy.assistant.agent.ondevice.LoadInfo
import com.workbuddy.assistant.agent.ondevice.ModelCatalog
import com.workbuddy.assistant.agent.ondevice.ModelTier
import com.workbuddy.assistant.agent.ondevice.OnDeviceModel
import com.workbuddy.assistant.agent.ondevice.RetrieverState
import com.workbuddy.assistant.agent.ondevice.RoutePolicy
import com.workbuddy.assistant.model.MessageSource
import com.workbuddy.assistant.model.UiMessage

/**
 * 聊天界面 —— 两层结构（这是 Compose 的标准拆分法，面试常问）：
 *
 *   ChatScreen        有状态壳：连接 ViewModel，把 StateFlow 收集成 Compose 状态
 *   ChatScreenContent 无状态内容：只吃参数画界面，不知道 ViewModel 的存在
 *
 * 这样拆的红利：
 * 1. Preview 能用了 —— 无状态层不依赖 Application/ViewModel，假数据直接渲染；
 * 2. 可测试性 —— 以后写 UI 测试不需要 Android 环境；
 * 3. 复用性 —— 同样的消息列表以后能放进平板双栏布局。
 *
 * 【W7】这个拆分又收了一次利息：新增的端云状态（deviceStatus / policy）
 * 全部走参数传进 ChatScreenContent，所以 Preview 依然能渲染
 * "端侧在线 + 已走端侧"这类状态，不用连真机 —— 端侧调试最难的就是
 * 状态难以复现（模型没下、加载失败、断网），而 Preview 可以直接造这些状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val messages by vm.messages.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val ttft by vm.ttftMs.collectAsState()
    val deviceStatus by vm.deviceStatus.collectAsState()
    val policy by vm.policy.collectAsState()
    val lastStats by vm.lastStats.collectAsState()
    // 【本次新增】模型下载状态：进度、提示语
    val downloadState by vm.downloadState.collectAsState()
    val downloadMessage by vm.downloadMessage.collectAsState()
    // 【W7.5】端侧知识库
    val knowledgeStatus by vm.knowledgeStatus.collectAsState()
    val retrieverState by vm.retrieverState.collectAsState()
    val knowledgeDocs by vm.knowledgeDocs.collectAsState()
    val knowledgeMessage by vm.knowledgeMessage.collectAsState()
    val embedderInstalled by vm.embedderInstalled.collectAsState()

    var showSettings by remember { mutableStateOf(false) }

    ChatScreenContent(
        messages = messages,
        isStreaming = isStreaming,
        ttft = ttft,
        deviceStatus = deviceStatus,
        policy = policy,
        onOpenSettings = { showSettings = true },
        onSend = vm::send,
    )

    if (showSettings) {
        DeviceSettingsDialog(
            status = deviceStatus,
            policy = policy,
            stats = lastStats,
            models = vm.models,
            downloadState = downloadState,
            downloadMessage = downloadMessage,
            knowledge = KnowledgeUiState(
                status = knowledgeStatus,
                retriever = retrieverState,
                docs = knowledgeDocs,
                message = knowledgeMessage,
                embedderInstalled = embedderInstalled,
            ),
            knowledgeActions = KnowledgeActions(
                onDownloadEmbedder = vm::downloadEmbedder,
                onLoadRetriever = vm::loadRetriever,
                onAdd = vm::addKnowledge,
                onDelete = vm::deleteKnowledge,
                onClear = vm::clearKnowledge,
                onDismissMessage = vm::dismissKnowledgeMessage,
            ),
            onPolicyChange = vm::setPolicy,
            onPrepare = vm::prepareDevice,
            onRelease = vm::releaseDevice,
            onDownload = vm::downloadModel,
            onCancelDownload = vm::cancelDownload,
            onDeleteModel = vm::deleteModel,
            onDismissMessage = vm::dismissDownloadMessage,
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * 知识库 UI 状态打包。
 *
 * 为什么打包？因为直接往 DeviceSettingsDialog 塞 11 个参数会让签名失控，
 * 而且这 5 个值**永远一起变化**（导入后 docs 和 status 一起变）——
 * 一起变的东西就该放在一起，这是"高内聚"最朴素的应用。
 */
private data class KnowledgeUiState(
    val status: KnowledgeStatus,
    val retriever: RetrieverState,
    val docs: List<DocSummary>,
    val message: String?,
    val embedderInstalled: Boolean,
)

/** 知识库操作打包 —— 同理，让对话框签名保持可读 */
private data class KnowledgeActions(
    val onDownloadEmbedder: () -> Unit,
    val onLoadRetriever: () -> Unit,
    val onAdd: (String, String) -> Unit,
    val onDelete: (String) -> Unit,
    val onClear: () -> Unit,
    val onDismissMessage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(
    messages: List<UiMessage>,
    isStreaming: Boolean,
    ttft: Long?,
    deviceStatus: DeviceStatus,
    policy: RoutePolicy,
    onOpenSettings: () -> Unit,
    onSend: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新消息/新 token 到达时自动滚到底部
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Assistant", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                isStreaming -> "生成中…"
                                // 【W7】副标题从"qwen-plus · SSE 直连"升级成端云全景：
                                // 用户一眼能看到"端侧什么状态 + 当前用什么策略"
                                else -> buildString {
                                    append(deviceStatus.shortLabel)
                                    append(" · ")
                                    append(policy.label)
                                    if (ttft != null) append(" · 首字 ${ttft}ms")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 【W7】端侧设置入口：策略切换 + 模型管理
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "端云设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { msg ->
                    MessageBubble(msg)
                }
            }

            // 底部输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        onSend(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !isStreaming
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start) {
            Surface(
                color = when {
                    msg.isError -> MaterialTheme.colorScheme.errorContainer
                    msg.isUser -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (msg.isUser) 16.dp else 4.dp,
                    bottomEnd = if (msg.isUser) 4.dp else 16.dp
                )
            ) {
                Text(
                    text = msg.content + if (!msg.isUser && msg.content.isEmpty()) "▍" else "",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color(
                        if (msg.isUser) 0xFFFFFFFF else 0xDD000000
                    ).copy(alpha = if (msg.isUser) 1f else 0.9f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }

            // ── 【W7】来源徽标：这条回答是"谁"算出来的 ──
            // 端侧用绿色（本地/省流），云端用灰蓝色（联网）。
            // 下面那行小字是端侧的性能账 —— 端侧同学面试时最爱被问
            // "你端侧跑多少 token/s"，这个数字直接显示在界面上最有说服力。
            if (!msg.isUser && msg.source != MessageSource.NONE && !msg.isError) {
                Row(
                    Modifier.padding(top = 4.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (msg.source) {
                            MessageSource.DEVICE -> "📱 ${msg.source.label}推理"
                            MessageSource.CLOUD -> "☁ ${msg.source.label}"
                            MessageSource.NONE -> ""
                        },
                        fontSize = 10.sp,
                        color = when (msg.source) {
                            MessageSource.DEVICE -> Color(0xFF2E7D32)
                            else -> Color(0xFF546E7A)
                        },
                        fontWeight = FontWeight.Medium
                    )
                    msg.deviceStats?.let {
                        Text(
                            text = " · $it",
                            fontSize = 10.sp,
                            color = Color(0xFF90A4AE)
                        )
                    }
                }
            }
        }
    }
}

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7】端云设置面板
 * ══════════════════════════════════════════════════════════════════
 * 这个面板要回答用户的四个问题（本次新增第 ④ 条）：
 *   ① 我的手机能不能跑端侧？（设备画像 + 推荐档位）
 *   ② 端侧现在什么状态？（未安装 / 加载中 / 在线 / 失败）
 *   ③ 我想怎么用？（三档策略单选）
 *   ④ 【新增】模型从哪来？—— 一个内置的"模型市场"：
 *      列出可选模型、体积、中文说明，一键下载 / 暂停 / 删除。
 *
 * 第 ④ 条为什么重要：之前这里是"把 .task 放进 ondevice/ 目录，
 * adb push 也行"。对开发者这没问题，对**普通用户等于没做功能** ——
 * 端侧能力能不能真正交付，取决于这一步的体验。
 *
 * 注意"推荐档位"这块：把 DeviceProfiler 的判断结果显式告诉用户，
 * 是端侧产品的标准做法 —— 因为端侧失败的头号原因是"用户下了个跑不动的模型"。
 * 与其等它 OOM，不如提前说清楚。
 */
@Composable
private fun DeviceSettingsDialog(
    status: DeviceStatus,
    policy: RoutePolicy,
    stats: GenStats?,
    models: List<OnDeviceModel>,
    downloadState: DownloadState,
    downloadMessage: String?,
    knowledge: KnowledgeUiState,
    knowledgeActions: KnowledgeActions,
    onPolicyChange: (RoutePolicy) -> Unit,
    onPrepare: () -> Unit,
    onRelease: () -> Unit,
    onDownload: (OnDeviceModel) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: (OnDeviceModel) -> Unit,
    onDismissMessage: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 内容上限要跟着屏幕走：固定 430dp 在小屏或横屏下依然会溢出。
    // 取屏幕高度的 60%，但不超过 430dp（大屏上对话框拉太长不好看）。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxContentHeight = (screenHeight * 0.6f).coerceAtMost(430.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("端云设置") },
        text = {
            Column(
                // ⚠️ Modifier 顺序在这里是**功能性的，不是风格问题** ——
                //    Modifier 链从左到右是"外层 → 内层"，测量时外层先执行、
                //    再把约束往里传。所以必须：
                //      ① heightIn 在外：先把视口高度限死（430dp）
                //      ② verticalScroll 在内：再把内容的无限高约束放开
                //    反过来写（verticalScroll 在外）会发生什么？
                //      verticalScroll 给子节点无限高 → 子节点 heightIn 把自己
                //      封顶在 430 → verticalScroll 拿到 height=430、视口也是
                //      430 → 可滚动范围 = 0 → **滑不动，且超出部分被裁掉**。
                //    这是 Compose 里最典型的"代码能编译、UI 静默失效"的坑：
                //    不报错、不警告，只是滚不动。凡是"限高 + 可滚动"的场景
                //    （设置面板、日志页、抽屉菜单）都要按这个顺序写。
                modifier = Modifier
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ── 设备画像 ──
                SectionTitle("设备能力")
                Text(status.profile.summary, fontSize = 12.sp)
                Text(
                    text = status.recommendTier?.let { "推荐档位：${it.label}" }
                        ?: "该设备不建议运行端侧模型",
                    fontSize = 12.sp,
                    color = if (status.recommendTier == null) Color(0xFFC62828) else Color(0xFF2E7D32)
                )

                HorizontalDivider()

                // ── 模型市场 ──
                SectionTitle("端侧模型")
                models.forEach { model ->
                    ModelCard(
                        model = model,
                        installed = status.isInstalled(model),
                        active = status.state is EngineState.Ready &&
                            status.modelName == model.displayName,
                        downloadState = downloadState,
                        onDownload = { onDownload(model) },
                        onCancel = onCancelDownload,
                        onDelete = { onDeleteModel(model) },
                    )
                }

                // 下载结果提示（完成 / 失败 / 已暂停）
                downloadMessage?.let { msg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            msg,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF37474F)
                        )
                        TextButton(onClick = onDismissMessage) {
                            Text("知道了", fontSize = 10.sp)
                        }
                    }
                }

                // ── 引擎当前状态 ──
                Text(
                    text = when (val s = status.state) {
                        is EngineState.Ready ->
                            "✅ 端侧在线 · ${s.info.modelName}\n" +
                                "加载 ${s.info.loadMs}ms · 常驻约 ${s.info.residentBytes / 1024 / 1024}MB"
                        is EngineState.Loading -> "⏳ 加载中…（首次约 3-8 秒，请稍候）"
                        is EngineState.Idle -> "💤 模型已就位，未加载到内存（此时不占内存）"
                        is EngineState.NotInstalled -> "⬇️ 还没有模型 —— 选一个点「下载」即可"
                        is EngineState.Failed -> "❌ 加载失败：${s.reason}"
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF546E7A)
                )

                // 端侧性能账（有端侧回答时才显示）
                stats?.let {
                    Text(
                        "上次端侧推理：TTFT ${it.ttftMs}ms · " +
                            "解码 ${"%.1f".format(it.decodeCps)} 字/秒 · " +
                            "总耗时 ${it.totalMs}ms",
                        fontSize = 11.sp,
                        color = Color(0xFF546E7A)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPrepare,
                        // 加了 modelAvailable 判断：没下模型时点"加载模型"
                        // 只会得到一句"未安装"，不如直接把按钮置灰 ——
                        // 让不可用的操作**看起来就不可用**，比解释为什么不可用更好。
                        enabled = status.modelAvailable &&
                            status.state !is EngineState.Loading &&
                            status.state !is EngineState.Ready
                    ) { Text("加载模型", fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = onRelease,
                        enabled = status.state is EngineState.Ready ||
                            status.state is EngineState.Failed
                    ) { Text("释放内存", fontSize = 12.sp) }
                }

                HorizontalDivider()

                // ── 端侧知识库（W7.5）──
                KnowledgeSection(knowledge, knowledgeActions)

                HorizontalDivider()

                // ── 策略选择 ──
                SectionTitle("路由策略")
                RoutePolicy.entries.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPolicyChange(p) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = p == policy, onClick = { onPolicyChange(p) })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(p.label, fontSize = 13.sp)
                            Text(
                                p.description,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/**
 * ══════════════════════════════════════════════════════════════════
 * 【W7.5】端侧知识库面板
 * ══════════════════════════════════════════════════════════════════
 * 这个面板故意只做四件事，因为端侧是个"资源受限环境"，功能越多越容易失控：
 *   ① 显示能力现状（几篇、几块、走的是向量+字面还是仅字面）
 *   ② 管向量模型（下 / 启用）—— 但**不强制**：没有它照样能检索
 *   ③ 导入文本（标题 + 正文）
 *   ④ 列表 + 删除（用户必须能撤销自己导进来的脏数据）
 *
 * 注意"仅字面也能用"这句话在 UI 上是要**明说**的。
 * 端侧产品最容易犯的错是"少个组件就整个功能不可用"，
 * 而正确的姿态是：缺什么就降级什么，并且告诉用户降级了什么。
 */
@Composable
private fun KnowledgeSection(
    state: KnowledgeUiState,
    actions: KnowledgeActions,
) {
    SectionTitle("端侧知识库")

    Text(
        "${state.status.docCount} 篇 · ${state.status.chunkCount} 块 · ${state.status.mode}",
        fontSize = 11.sp,
        color = Color(0xFF546E7A),
    )

    // ── 向量模型状态 ──
    when (val r = state.retriever) {
        is RetrieverState.Ready -> Text(
            "✅ 向量模型 ${r.dim} 维 · 加载 ${r.loadMs}ms",
            fontSize = 11.sp,
            color = Color(0xFF2E7D32),
        )

        is RetrieverState.Loading -> Text("⏳ 向量模型加载中…", fontSize = 11.sp)

        is RetrieverState.Failed -> Text(
            "❌ 向量模型不可用：${r.reason}",
            fontSize = 11.sp,
            color = Color(0xFFC62828),
        )

        else -> {
            if (!state.embedderInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "向量模型未下载（6MB，可选）",
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF546E7A),
                    )
                    TextButton(onClick = actions.onDownloadEmbedder) {
                        Text("下载", fontSize = 11.sp)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "向量模型已下载，尚未加载（不占内存）",
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF546E7A),
                    )
                    TextButton(onClick = actions.onLoadRetriever) {
                        Text("启用语义检索", fontSize = 11.sp)
                    }
                }
            }
            Text(
                "不下也能用：走中文字面匹配，只是少了跨词表达的语义召回。",
                fontSize = 10.sp,
                color = Color(0xFF78909C),
            )
        }
    }

    // ── 导入区 ──
    var importing by remember { mutableStateOf(false) }
    if (!importing) {
        OutlinedButton(onClick = { importing = true }) {
            Text("＋ 导入笔记", fontSize = 12.sp)
        }
    } else {
        // 注意：这两个 remember 在 importing=true 期间存在，收起后被销毁 →
        // 下次展开是干净的空输入框。这是**想要**的行为（避免上次残留内容误提交）。
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("内容（直接粘贴笔记）", fontSize = 11.sp) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    actions.onAdd(title, body)
                    importing = false
                },
                enabled = body.isNotBlank(),
            ) { Text("导入", fontSize = 12.sp) }

            TextButton(onClick = { importing = false }) {
                Text("取消", fontSize = 12.sp)
            }
        }
    }

    // ── 操作反馈 ──
    state.message?.let { msg ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                msg,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                color = Color(0xFF37474F),
            )
            TextButton(onClick = actions.onDismissMessage) {
                Text("知道了", fontSize = 10.sp)
            }
        }
    }

    // ── 文档列表 ──
    if (state.docs.isEmpty()) {
        Text(
            "还没有内容。导入一段笔记，断网也能问它。",
            fontSize = 11.sp,
            color = Color(0xFF78909C),
        )
    } else {
        state.docs.forEach { doc ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(doc.title, fontSize = 12.sp, maxLines = 1)
                    Text(
                        buildString {
                            append("${doc.chunkCount} 块 · ${doc.chars} 字")
                            // 这一篇是"没向量模型时导入的"，只有字面特征。
                            // 必须让用户看见，否则他会疑惑"为什么这篇搜不准"。
                            if (!doc.hasVector) append(" · 仅字面")
                        },
                        fontSize = 10.sp,
                        color = Color(0xFF78909C),
                    )
                }
                TextButton(onClick = { actions.onDelete(doc.id) }) {
                    Text("删除", fontSize = 10.sp, color = Color(0xFFC62828))
                }
            }
        }
        TextButton(onClick = actions.onClear) {
            Text("清空知识库", fontSize = 10.sp, color = Color(0xFFC62828))
        }
    }
}

/**
 * 单个模型的卡片 —— 模型市场的最小单元。
 *
 * 状态优先级要理清（同一时刻只能显示一种主操作）：
 *   正在下载这个 → 进度条 + 暂停
 *   已下载       → ✅ + 删除
 *   其它         → 下载
 * 这就是"状态机驱动 UI"：不要写 if(downloading && !installed && ...) 这种
 * 条件组合，而是按状态穷举分支，互斥且完备。
 */
@Composable
private fun ModelCard(
    model: OnDeviceModel,
    installed: Boolean,
    active: Boolean,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    // 当前这个模型是不是正在下载？（DownloadState 是全局单选状态，
    // 所以必须比对 fileName 才能知道"是不是我"）
    val downloadingThis = downloadState is DownloadState.Downloading &&
        downloadState.model.fileName == model.fileName
    val preparingThis = downloadState is DownloadState.Preparing &&
        downloadState.model.fileName == model.fileName
    val verifyingThis = downloadState is DownloadState.Verifying &&
        downloadState.model.fileName == model.fileName

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 名称行 + 标签
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (model.recommended) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "推荐",
                        fontSize = 9.sp,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier
                            .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (active) {
                    Text("使用中", fontSize = 9.sp, color = Color(0xFF2E7D32))
                }
            }

            // 规格行
            Text(
                "${model.format} · ${formatMb(model.approxSizeMb)}",
                fontSize = 10.sp,
                color = Color(0xFF90A4AE)
            )

            // 说明
            if (model.note.isNotBlank()) {
                Text(
                    model.note,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                downloadingThis -> {
                    val st = downloadState as DownloadState.Downloading
                    DownloadProgressRow(st, onCancel)
                }

                preparingThis -> Text("正在检查磁盘空间…", fontSize = 11.sp, color = Color(0xFF546E7A))

                verifyingThis -> Text("正在校验文件完整性…", fontSize = 11.sp, color = Color(0xFF546E7A))

                installed -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("✅ 已下载", fontSize = 11.sp, color = Color(0xFF2E7D32))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Text("删除", fontSize = 11.sp, color = Color(0xFFC62828))
                    }
                }

                else -> Row {
                    Button(
                        onClick = onDownload,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) { Text("下载 ${formatMb(model.approxSizeMb)}", fontSize = 11.sp) }
                }
            }
        }
    }
}

/**
 * 下载进度行：进度条 + 数字 + 暂停。
 *
 * 注意这里把"速度"和"剩余时间"都显示出来。为什么不是只显示百分比？
 * 因为大文件下载里，**百分比是最没有信息量的数字**：
 * 用户看到 30% 时的真实疑问是"还要等多久"和"是不是卡住了"。
 * 速度能回答"卡住没"，ETA 能回答"等多久" —— 这两个才是用户要的。
 */
@Composable
private fun DownloadProgressRow(state: DownloadState.Downloading, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // 总长未知（服务端没给 Content-Length）时 percent = -1：
        // 此时给 0 会让进度条永远不动，看起来像卡死；给 100 又像下完了。
        // 这里退化成"不确定进度"的视觉（固定 30% 的游标），并明确提示"计算中"。
        val known = state.percent >= 0
        LinearProgressIndicator(
            progress = { if (known) state.percent / 100f else 0.3f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    if (known) append("${state.percent}%  ") else append("计算中…  ")
                    append("%.0fMB".format(state.mbRead))
                    if (state.totalBytes > 0) append(" / ${formatMb((state.totalBytes / 1024 / 1024).toInt())}")
                    if (state.speedBps > 0) append(" · ${formatSpeed(state.speedBps)}")
                    if (state.etaSec >= 0) append(" · 剩余 ${formatEta(state.etaSec)}")
                    if (state.sourceIndex > 0) append(" · 备用源")
                },
                fontSize = 10.sp,
                color = Color(0xFF546E7A),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("暂停", fontSize = 11.sp) }
        }
    }
}

// ── 数字格式化：单位换算看着简单，但写对不容易（面试小考点）──────
// 用 1024 而不是 1000：APK 体积、内存占用在 Android 生态里都是二进制单位，
// 混用会让用户看到的数字和系统设置里对不上（差 7%）。

private fun formatMb(mb: Int): String =
    if (mb >= 1024) "%.1fGB".format(mb / 1024.0) else "${mb}MB"

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024L * 1024 -> "%.1fMB/s".format(bytesPerSec / 1024.0 / 1024)
    bytesPerSec >= 1024L -> "${bytesPerSec / 1024}KB/s"
    else -> "${bytesPerSec}B/s"
}

private fun formatEta(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600}小时${(sec % 3600) / 60}分"
    sec >= 60 -> "${sec / 60}分${sec % 60}秒"
    else -> "${sec}秒"
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

// ════════════════════════════════════════════════════════════════
// Preview：假数据直接渲染无状态层，改 UI 时右侧面板秒级刷新，
// 不用 Rebuild + 真机安装。
//
// 【W7】这里能造出真机上很难复现的状态 —— 比如"端侧在线"
// 需要你先下 700MB 模型才能看到，而 Preview 一行假数据就够了。
// ════════════════════════════════════════════════════════════════

private fun fakeStatus(state: EngineState): DeviceStatus {
    // 直接从真实清单里取，避免 Preview 里的模型名和 ModelCatalog 脱节
    // （写死字符串的话，换模型后 Preview 会显示一个不存在的模型，误导调 UI 的人）
    val model = ModelCatalog.models.first()
    val ready = state is EngineState.Ready
    return DeviceStatus(
        state = state,
        profile = DeviceProfile(
            totalMemMb = 12288,
            availMemMb = 6100,
            isLowRamDevice = false,
            cpuCores = 8,
            abi = "arm64-v8a",
            is64Bit = true,
            sdkInt = 34,
        ),
        recommendTier = ModelTier.SMALL,
        modelAvailable = state !is EngineState.NotInstalled,
        modelName = if (ready) model.displayName else "",
        installedFiles = if (ready) setOf(model.fileName) else emptySet(),
        modelFileSizeMb = model.approxSizeMb,
        historyTurns = 0,
    )
}

/** 空状态：只有输入栏和标题 */
@Preview(name = "空对话", showBackground = true, heightDp = 620)
@Composable
private fun ChatScreenEmptyPreview() {
    MaterialTheme {
        ChatScreenContent(
            messages = emptyList(),
            isStreaming = false,
            ttft = null,
            deviceStatus = fakeStatus(EngineState.NotInstalled),
            policy = RoutePolicy.AUTO,
            onOpenSettings = {},
            onSend = {}
        )
    }
}

/** 流式中：空内容 → 显示 ▍ 打字机占位 */
@Preview(name = "对话中（流式）", showBackground = true, heightDp = 620)
@Composable
private fun ChatScreenStreamingPreview() {
    MaterialTheme {
        ChatScreenContent(
            messages = listOf(
                UiMessage(isUser = true, content = "现在几点了？"),
                UiMessage(isUser = false, content = ""),
            ),
            isStreaming = true,
            ttft = null,
            deviceStatus = fakeStatus(EngineState.Ready(
                LoadInfo(
                    modelName = "Qwen2.5-0.5B-Instruct",
                    fileSizeBytes = 560L * 1024 * 1024,
                    residentBytes = 980L * 1024 * 1024,
                    loadMs = 4200,
                    backend = "MediaPipe delegate",
                )
            )),
            policy = RoutePolicy.AUTO,
            onOpenSettings = {},
            onSend = {}
        )
    }
}

/** 完整对话：含端侧/云端两种来源徽标 + 错误样式，调气泡排版时看这个 */
@Preview(name = "完整对话（端云混合）", showBackground = true, heightDp = 700)
@Composable
private fun ChatScreenFullPreview() {
    MaterialTheme {
        ChatScreenContent(
            messages = listOf(
                UiMessage(isUser = true, content = "你好"),
                UiMessage(
                    isUser = false,
                    content = "你好！有什么可以帮你的？",
                    source = MessageSource.DEVICE,
                    deviceStats = "TTFT 1180ms · 解码 14.6 字/秒",
                ),
                UiMessage(isUser = true, content = "帮我记一下周六早上要跑步"),
                UiMessage(
                    isUser = false,
                    content = "好的，已记下：周六早上要跑步。",
                    source = MessageSource.CLOUD,
                ),
                UiMessage(isUser = true, content = "我记过什么？"),
                UiMessage(
                    isUser = false,
                    content = "共1条备忘录：\n1. 周六早上要跑步",
                    source = MessageSource.CLOUD,
                ),
                UiMessage(isUser = false, content = "网络连接失败，请重试", isError = true),
            ),
            isStreaming = false,
            ttft = 1180,
            deviceStatus = fakeStatus(EngineState.Ready(
                LoadInfo(
                    modelName = "Qwen2.5-0.5B-Instruct",
                    fileSizeBytes = 560L * 1024 * 1024,
                    residentBytes = 980L * 1024 * 1024,
                    loadMs = 4200,
                    backend = "MediaPipe delegate",
                )
            )),
            policy = RoutePolicy.AUTO,
            onOpenSettings = {},
            onSend = {}
        )
    }
}

/**
 * 【本次新增】设置面板预览 —— 不用下 500MB 模型也能调这块 UI。
 *
 * 端侧 UI 最难的就是**状态复现**：想看到"下载中"的进度条，
 * 真机上得先真的下一个 500MB 文件；想看到"下载完成"，
 * 还得等下完。而 Preview 一行假数据就同时给你两种状态并列对比。
 * 这就是 W7 把界面拆成"无状态内容层"的收益兑现。
 */
@Preview(name = "设置面板（下载中）", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun DeviceSettingsDownloadingPreview() {
    val model = ModelCatalog.models.first()
    MaterialTheme {
        DeviceSettingsDialog(
            status = fakeStatus(EngineState.NotInstalled),
            policy = RoutePolicy.AUTO,
            stats = null,
            models = ModelCatalog.models,
            downloadState = DownloadState.Downloading(
                model = model,
                bytesRead = 218L * 1024 * 1024,
                totalBytes = model.exactSizeBytes,
                speedBps = 1_200_000,
            ),
            downloadMessage = null,
            knowledge = KnowledgeUiState(
                status = KnowledgeStatus(docCount = 0, chunkCount = 0, mode = "仅字面（未下载向量模型）"),
                retriever = RetrieverState.NoModel,
                docs = emptyList(),
                message = null,
                embedderInstalled = false,
            ),
            knowledgeActions = FakeKnowledgeActions,
            onPolicyChange = {}, onPrepare = {}, onRelease = {},
            onDownload = {}, onCancelDownload = {}, onDeleteModel = {},
            onDismissMessage = {}, onDismiss = {},
        )
    }
}

@Preview(name = "设置面板（已就绪）", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun DeviceSettingsReadyPreview() {
    MaterialTheme {
        DeviceSettingsDialog(
            status = fakeStatus(
                EngineState.Ready(
                    LoadInfo(
                        modelName = "Qwen2.5-0.5B-Instruct",
                        fileSizeBytes = 546L * 1024 * 1024,
                        residentBytes = 800L * 1024 * 1024,
                        loadMs = 3200,
                        backend = "MediaPipe delegate",
                    )
                )
            ),
            policy = RoutePolicy.AUTO,
            stats = GenStats(prefillMs = 640, decodeMs = 4200, outputChars = 62),
            models = ModelCatalog.models,
            downloadState = DownloadState.Completed(ModelCatalog.models.first()),
            downloadMessage = "「Qwen2.5-0.5B-Instruct」下载完成，点「加载模型」即可启用端侧推理",
            knowledge = KnowledgeUiState(
                status = KnowledgeStatus(
                    docCount = 2, chunkCount = 7,
                    vectorReady = true, mode = "向量 + 字面双路",
                ),
                retriever = RetrieverState.Ready(dim = 512, loadMs = 480L),
                docs = listOf(
                    DocSummary("1", "学习计划", 1200, 4, 0L, hasVector = true),
                    DocSummary("2", "面试准备", 800, 3, 0L, hasVector = false),
                ),
                message = "已导入「学习计划」，切成 4 块",
                embedderInstalled = true,
            ),
            knowledgeActions = FakeKnowledgeActions,
            onPolicyChange = {}, onPrepare = {}, onRelease = {},
            onDownload = {}, onCancelDownload = {}, onDeleteModel = {},
            onDismissMessage = {}, onDismiss = {},
        )
    }
}

/** Preview 专用：知识库动作的空实现（Preview 里点了没反应是正常的） */
private val FakeKnowledgeActions = KnowledgeActions(
    onDownloadEmbedder = {},
    onLoadRetriever = {},
    onAdd = { _, _ -> },
    onDelete = {},
    onClear = {},
    onDismissMessage = {},
)
