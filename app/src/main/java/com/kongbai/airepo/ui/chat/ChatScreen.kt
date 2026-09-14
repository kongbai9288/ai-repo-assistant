package com.kongbai.airepo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.InputChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kongbai.airepo.data.local.ConversationEntity
import com.kongbai.airepo.data.local.MessageEntity
import com.kongbai.airepo.data.prefs.NetMode
import com.kongbai.airepo.data.tools.PickedFile
import com.kongbai.airepo.data.tools.ToolRequest
import com.kongbai.airepo.util.MdBlock
import com.kongbai.airepo.util.parseMarkdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit
) {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris -> uris.forEach { vm.attach(it) } }
    val messages by vm.messages.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val confirm by vm.confirmRequest.collectAsState()
    val toast by vm.toast.collectAsState()
    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val netMode by vm.netMode.collectAsState()
    val attachments by vm.attachments.collectAsState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(toast) { if (toast != null) vm.consumeToast() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("会话", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                NavigationDrawerItem(
                    label = { Text("新建对话") },
                    selected = false,
                    icon = { Icon(Icons.Default.Add, null) },
                    onClick = {
                        vm.newConversation()
                        scope.launch { drawerState.close() }
                    }
                )
                androidx.compose.foundation.lazy.LazyColumn {
                    items(conversations) { c: ConversationEntity ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1) },
                            selected = false,
                            onClick = {
                                vm.selectConversation(c.id)
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI 仓库助手") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.AutoAwesome, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null) }
                    }
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth()) {
                    // 选择性联网 + 附件区
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NetModeChip(current = netMode) { vm.setNetMode(it) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { launcher.launch("*/*")  // 任意格式 }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "附加本地文件")
                        }
                    }
                    if (attachments.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            attachments.forEach { f ->
                                AttachmentChip(f) { vm.removeAttachment(f) }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(max = 160.dp),
                            placeholder = { Text("让 AI 改仓库，例如：给 README 补充安装说明并提交") },
                            maxLines = 6
                        )
                        Spacer(Modifier.size(8.dp))
                        if (streaming) {
                            IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, null) }
                        } else {
                            IconButton(onClick = { vm.send(input); input = "" }) {
                                Icon(Icons.AutoMirrored.Filled.Send, null)
                            }
                        }
                    }
                }
            }
        ) { pad ->
            if (messages.isEmpty()) {
                EmptyState(modifier = Modifier.padding(pad))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { m ->
                        when (m.role) {
                            "user" -> UserBubble(m)
                            "tool" -> ToolCard(m)
                            else -> AssistantBubble(m)
                        }
                    }
                }
            }
        }
    }

    confirm?.let { req ->
        ConfirmDialog(req, onAllow = { vm.answerConfirm(true) }, onDeny = { vm.answerConfirm(false) })
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("直接说人话，AI 帮你动仓库", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "例：搜一下 Compose Material3 最新版依赖，然后把 app/build.gradle.kts 升级并提交 PR",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UserBubble(m: MessageEntity) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(m.content)
            }
        }
    }
}

@Composable
private fun AssistantBubble(m: MessageEntity) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                if (m.content.isBlank() && m.status == "running") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("思考中…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    MarkdownText(m.content)
                }
                if (m.status == "running" && m.content.isNotBlank()) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun ToolCard(m: MessageEntity) {
    val running = m.status == "running"
    val denied = m.status == "denied"
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        denied -> Icons.Default.Error
                        running -> Icons.Default.Handyman
                        else -> Icons.Default.CheckCircle
                    },
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = when {
                        denied -> MaterialTheme.colorScheme.error
                        running -> MaterialTheme.colorScheme.primary
                        else -> Color(0xFF2DA44E)
                    }
                )
                Spacer(Modifier.size(8.dp))
                Text(m.toolName ?: "tool", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                if (running) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            }
            Text(m.content, style = MaterialTheme.typography.bodySmall)
            if (expanded && m.toolResult != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    m.toolResult ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x11000000), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Text -> Text(b.content, style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x14000000), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(b.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                is MdBlock.Quote -> Text(
                    "“${b.content}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(req: ToolRequest, onAllow: () -> Unit, onDeny: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("确认执行高危操作？") },
        text = {
            Column {
                Text("工具：${req.name}")
                Spacer(Modifier.height(6.dp))
                Text(
                    req.args.entries.joinToString("\n") { "${it.key} = ${it.value}" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("执行") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("拒绝") } }
    )
}


@Composable
private fun NetModeChip(current: NetMode, onPick: (NetMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NetMode.values().forEach { m ->
            val selected = current == m
            FilterChip(
                selected = selected,
                onClick = { onPick(m) },
                label = { Text(m.label, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(
                        when (m) {
                            NetMode.ON -> Icons.Default.Public
                            NetMode.OFF -> Icons.Default.CloudOff
                            NetMode.AUTO -> Icons.Default.TravelExplore
                        },
                        null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                }
            )
        }
    }
}

@Composable
private fun AttachmentChip(f: PickedFile, onRemove: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onRemove,
        label = {
            Text(
                "${f.name} · ${if (f.size > 0) "${f.size / 1024}KB" else "?"}${if (f.isBinary) " · 二进制" else ""}",
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall
            )
        },
        trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp)) }
    )
}
