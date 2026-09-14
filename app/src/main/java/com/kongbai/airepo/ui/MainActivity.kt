package com.kongbai.airepo.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kongbai.airepo.auth.AuthRepository
import com.kongbai.airepo.ui.chat.ChatScreen
import com.kongbai.airepo.ui.chat.ChatViewModel
import com.kongbai.airepo.ui.login.LoginScreen
import com.kongbai.airepo.ui.login.WebLoginScreen
import com.kongbai.airepo.ui.repos.ReposScreen
import com.kongbai.airepo.ui.settings.SettingsScreen
import com.kongbai.airepo.ui.theme.AiRepoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var auth: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiRepoTheme {
                val state by auth.state.collectAsState()
                // 默认走应用内 WebView 登录，不再依赖外部浏览器回跳
                var showWebLogin by remember { mutableStateOf(false) }
                var authUrl by remember { mutableStateOf("") }
                var loginError by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(state.error) { state.error?.let { loginError = it } }

                when {
                    showWebLogin -> WebLoginScreen(
                        authUrl = authUrl,
                        onCode = { code, st, err ->
                            lifecycleScope.launch {
                                val r = auth.handleRedirect(code, st, err)
                                if (r.isFailure) {
                                    loginError = auth.state.value.error ?: "授权失败"
                                }
                                showWebLogin = false
                            }
                        },
                        onBack = { showWebLogin = false }
                    )
                    state.token.isNullOrBlank() -> LoginScreen(
                        auth = auth,
                        externalError = loginError,
                        onWebLogin = {
                            loginError = null
                            authUrl = auth.buildAuthUrl()
                            showWebLogin = true
                        },
                        onBrowserLogin = { auth.launchBrowser(this@MainActivity) }
                    )
                    else -> MainScaffold(onLoggedOut = { auth.logout() })
                }
            }
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    CHAT("chat", "对话", Icons.Default.Chat),
    REPOS("repos", "仓库", Icons.Default.Folder),
    SETTINGS("settings", "设置", Icons.Default.Settings)
}

@Composable
private fun MainScaffold(onLoggedOut: () -> Unit = {}) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.values().forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(navController = nav, startDestination = Tab.CHAT.route, modifier = Modifier.padding(pad)) {
            composable(Tab.CHAT.route) {
                val vm: ChatViewModel = hiltViewModel()
                androidx.compose.runtime.LaunchedEffect(Unit) { vmHolder.chatVm = vm }
                ChatScreen(vm = vm, onOpenSettings = {
                    nav.navigate(Tab.SETTINGS.route) { launchSingleTop = true }
                })
            }
            composable(Tab.REPOS.route) {
                ReposScreen(onPickRepo = { fullName ->
                    vmHolder?.setDefaultRepo(fullName)
                    nav.navigate(Tab.CHAT.route) { launchSingleTop = true }
                })
            }
            composable(Tab.SETTINGS.route) { SettingsScreen(onLoggedOut = onLoggedOut) }
        }
    }
}

/** 供仓库页把「当前仓库」传给对话页 */
object vmHolder {
    var chatVm: ChatViewModel? = null
    fun setDefaultRepo(fullName: String) { chatVm?.setDefaultRepo(fullName) }
}
