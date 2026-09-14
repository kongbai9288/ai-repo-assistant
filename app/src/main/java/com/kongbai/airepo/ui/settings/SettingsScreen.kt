package com.kongbai.airepo.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kongbai.airepo.data.search.SearchProvider
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel(), onLoggedOut: () -> Unit = {}) {
    val s by vm.settingsFlow.collectAsState()
    val models by vm.models.collectAsState()
    val info by vm.info.collectAsState()
    val authState by vm.auth.state.collectAsState()

    var baseUrl by remember(s.baseUrl) { mutableStateOf(s.baseUrl) }
    var model by remember(s.model) { mutableStateOf(s.model) }
    var key by remember(s.apiKey) { mutableStateOf(s.apiKey) }
    var prompt by remember(s.systemPrompt) { mutableStateOf(s.systemPrompt) }
    var temp by remember(s.temperature) { mutableStateOf(s.temperature) }
    var searchEndpoint by remember(s.searchEndpoint) { mutableStateOf(s.searchEndpoint) }
    var searchKey by remember(s.searchKey) { mutableStateOf(s.searchKey) }
    val diag by vm.diag.collectAsState()
    val diagnosing by vm.diagnosing.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("设置") },
            actions = {
                IconButton(onClick = { vm.fetchModels() }) { Icon(Icons.Default.Refresh, null) }
            }
        )
    }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // GitHub 账号
            SectionTitle("GitHub")
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = authState.user?.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(authState.user?.login ?: "未登录", style = MaterialTheme.typography.titleSmall)
                        Text(
                            authState.user?.name ?: "授权后可操作仓库",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { vm.logout(); onLoggedOut() }) {
                        Icon(Icons.Default.Logout, null)
                        Text(" 退出")
                    }
                }
            }

            // AI
            SectionTitle("AI 接口（OpenAI 兼容）")
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("模型名") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text("API Key（本地 Ollama 可留空）") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = { vm.saveAi(baseUrl, model, key) }) { Text("保存") }
            }
            Text(
                "已内置 OpenAI / DeepSeek / 通义 / Moonshot / 智谱 / 本地 Ollama 等兼容端点，只要填对 Base URL 与模型名即可。",
                style = MaterialTheme.typography.bodySmall
            )
            if (models.isNotEmpty()) {
                Text("可用模型：${models.take(12).joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }

            SectionTitle("能力开关")
            SwitchRow("让 AI 联网搜索（web_search / web_fetch）", s.webSearchEnabled) { vm.setWebSearch(it) }
            SwitchRow("允许删文件 / 删库 / 合并 PR（仍需逐个确认）", s.allowDangerousTools) { vm.setAllowDangerous(it) }
            SwitchRow("流式输出", s.streamEnabled) { vm.setStream(it) }

            SectionTitle("联网搜索")
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${s.searchProvider.label}  ${s.searchProvider.note}", maxLines = 1)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    SearchProvider.values().forEach { p ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("${p.label}  ${p.note}") },
                            onClick = { vm.setSearchProvider(p); expanded = false }
                        )
                    }
                }
            }
            if (s.searchProvider.needsEndpoint) {
                OutlinedTextField(
                    value = searchEndpoint, onValueChange = { searchEndpoint = it; vm.setSearchEndpoint(it) },
                    label = { Text("实例地址，如 https://searx.be") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
            if (s.searchProvider.needsKey) {
                OutlinedTextField(
                    value = searchKey, onValueChange = { searchKey = it; vm.setSearchKey(it) },
                    label = { Text("${s.searchProvider.label} 的 API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.diagnoseSearch() }, enabled = !diagnosing) {
                    Text("测试联网搜索")
                }
                if (diagnosing) CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp
                )
            }
            diag.forEach { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (r.ok) Icons.Default.CheckCircle else Icons.Default.Error,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = if (r.ok) Color(0xFF2DA44E) else MaterialTheme.colorScheme.error
                    )
                    Text(
                        " ${r.provider.label}：${r.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            SectionTitle("温度：${"%.2f".format(temp)}")
            Slider(value = temp, onValueChange = { temp = it; vm.setTemperature(it) }, valueRange = 0f..1.2f)

            SectionTitle("系统提示词")
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it; vm.setSystemPrompt(it) },
                modifier = Modifier.fillMaxWidth(), minLines = 5
            )

            info?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Text(
                "图标来自 Material Symbols（Apache 2.0）；GitHub API 直连 api.github.com。",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
