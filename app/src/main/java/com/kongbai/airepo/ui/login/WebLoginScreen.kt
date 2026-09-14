package com.kongbai.airepo.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kongbai.airepo.core.Constants

/**
 * 应用内 WebView 登录：完全不依赖外部浏览器和自定义 scheme 回跳，
 * GitHub 授权后会跳转 airepo://oauth2redirect?code=...，
 * 这里在 shouldOverrideUrlLoading 里直接拦下，因此不会出现「弹窗后回不到应用」的问题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLoginScreen(
    authUrl: String,
    onCode: (code: String?, state: String?, error: String?) -> Unit,
    onBack: () -> Unit
) {
    var progress by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var handled by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub 授权") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setupWebSettings(settings)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                    loading = newProgress < 100
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url ?: return false
                                    if (url.scheme == Constants.REDIRECT_SCHEME &&
                                        url.host == Constants.REDIRECT_HOST
                                    ) {
                                        if (!handled) {
                                            handled = true
                                            onCode(
                                                url.getQueryParameter("code"),
                                                url.getQueryParameter("state"),
                                                url.getQueryParameter("error")
                                            )
                                        }
                                        return true
                                    }
                                    return false
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    val u = url?.let { Uri.parse(it) } ?: return false
                                    if (u.scheme == Constants.REDIRECT_SCHEME && u.host == Constants.REDIRECT_HOST) {
                                        if (!handled) {
                                            handled = true
                                            onCode(
                                                u.getQueryParameter("code"),
                                                u.getQueryParameter("state"),
                                                u.getQueryParameter("error")
                                            )
                                        }
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    loading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                }
                            }
                            loadUrl(authUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (loading && progress < 30) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun setupWebSettings(s: WebSettings) {
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.loadsImagesAutomatically = true
    s.useWideViewPort = true
    s.loadWithOverviewMode = true
    s.builtInZoomControls = true
    s.displayZoomControls = false
    s.cacheMode = WebSettings.LOAD_NO_CACHE
    s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    // 用桌面版 UA，避免 GitHub 移动端页面布局异常导致授权按钮点不到
    s.userAgentString =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
