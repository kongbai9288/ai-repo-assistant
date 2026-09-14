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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onWebLogin: () -> Unit = {},
    onBrowserLogin: () -> Unit = {}
) {
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

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
                onClick = onWebLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) { Text("GitHub 登录（应用内授权）") }
            Text(
                "在应用内打开 GitHub 授权页，授权后自动回到本应用 —— 不依赖外部浏览器跳转",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onBrowserLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) { Text("用系统浏览器授权（备用）") }
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
