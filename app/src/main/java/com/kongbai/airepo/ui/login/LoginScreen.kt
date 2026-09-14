package com.kongbai.airepo.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kongbai.airepo.auth.AuthRepository

@Composable
fun LoginScreen(
    vm: LoginViewModel = hiltViewModel(),
    auth: AuthRepository,
    externalError: String? = null,
    onCustomTabsLogin: () -> Unit = {},
    onWebLogin: () -> Unit = {},
    onBrowserLogin: () -> Unit = {}
) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var showCreds by remember { mutableStateOf(false) }
    var clientId by remember(auth.savedClientId()) { mutableStateOf(auth.savedClientId()) }
    var clientSecret by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Surface {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Default.SmartToy, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("AI 仓库助手", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "用 OAuth 2.0（PKCE）授权后，AI 就能直接读写你的仓库：改文件、提交、开 Issue 与 PR，还能联网查资料。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onCustomTabsLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) { Text("GitHub 登录") }
            Text(
                "在浏览器标签页打开 GitHub 授权页，点授权后自动跳回本应用；OAuth 登录必须先在下方填 Client Secret",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onWebLogin,
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                ) { Text("应用内网页") }
                OutlinedButton(
                    onClick = onBrowserLogin,
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                ) { Text("系统浏览器") }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { showToken = !showToken }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showToken) "收起 Token 登录" else "用 Personal Access Token 登录")
            }
            if (showToken) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("ghp_ / github_pat_ …") },
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.loginWithToken(token) },
                    enabled = token.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("连接") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showCreds = !showCreds }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showCreds) "收起 OAuth 凭据设置" else "填写 Client ID / Secret（OAuth 登录必需）")
            }
            if (showCreds) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "GitHub 的 token 交换端点强制要求 client_secret，PKCE 不能替代。\n" +
                        "去 github.com/settings/developers 打开你的 OAuth App，复制 Client ID 和 " +
                        "Client Secret（点 Generate a new client secret），回调地址填 airepo://oauth2redirect",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it },
                    label = { Text("Client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = clientSecret, onValueChange = { clientSecret = it },
                    label = { Text("Client Secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        scope.launch {
                            auth.saveCredentials(clientId, clientSecret)
                            vm.setFallback("凭据已保存，现在点上面的「GitHub 登录」")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存凭据") }
            }
            if (busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }
            (error ?: externalError)?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text("登录失败：$msg", color = MaterialTheme.colorScheme.error)
                Text(
                    "若应用内授权页打不开：检查网络能否访问 github.com，或改用系统浏览器 / Token 登录。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(12.dp))
                Text(" Token 只存本机加密存储，不上传任何第三方", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
