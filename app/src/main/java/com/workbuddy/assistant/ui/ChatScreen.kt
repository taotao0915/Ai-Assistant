package com.workbuddy.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
 * 你会发现它没有任何"主动刷新"逻辑，全是"看到状态画界面"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val messages by vm.messages.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val ttft by vm.ttftMs.collectAsState()

    ChatScreenContent(
        messages = messages,
        isStreaming = isStreaming,
        ttft = ttft,
        onSend = vm::send,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(
    messages: List<UiMessage>,
    isStreaming: Boolean,
    ttft: Long?,
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
                                ttft != null -> "首字延迟 ${ttft}ms · qwen-plus · SSE 直连"
                                else -> "qwen-plus · SSE 直连"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
    }
}

// ════════════════════════════════════════════════════════════════
// Preview：假数据直接渲染无状态层，改 UI 时右侧面板秒级刷新，
// 不用 Rebuild + 真机安装（W1 我们删掉了旧 Preview，根因就是它
// 依赖 ViewModel；拆层之后这个问题不存在了）。
// ════════════════════════════════════════════════════════════════

/** 空状态：只有输入栏和标题 */
@Preview(name = "空对话", showBackground = true, heightDp = 600)
@Composable
private fun ChatScreenEmptyPreview() {
    MaterialTheme {
        ChatScreenContent(messages = emptyList(), isStreaming = false, ttft = null, onSend = {})
    }
}

/** 对话中：用户气泡 + AI 气泡（空内容 = 打字机占位）+ 错误气泡 */
@Preview(name = "对话中（流式）", showBackground = true, heightDp = 600)
@Composable
private fun ChatScreenStreamingPreview() {
    MaterialTheme {
        ChatScreenContent(
            messages = listOf(
                UiMessage(isUser = true, content = "现在几点了？"),
                UiMessage(isUser = false, content = ""),          // 空内容 → 显示 ▍ 占位
            ),
            isStreaming = true,
            ttft = null,
            onSend = {}
        )
    }
}

/** 完整对话：含工具回答和错误样式，调气泡排版时看这个 */
@Preview(name = "完整对话", showBackground = true, heightDp = 600)
@Composable
private fun ChatScreenFullPreview() {
    MaterialTheme {
        ChatScreenContent(
            messages = listOf(
                UiMessage(isUser = true, content = "帮我记一下周六早上要跑步"),
                UiMessage(isUser = false, content = "好的，已记下：周六早上要跑步。"),
                UiMessage(isUser = true, content = "我记过什么？"),
                UiMessage(isUser = false, content = "共1条备忘录：\n1. 周六早上要跑步"),
                UiMessage(isUser = false, content = "网络连接失败，请重试", isError = true),
            ),
            isStreaming = false,
            ttft = 860,
            onSend = {}
        )
    }
}
