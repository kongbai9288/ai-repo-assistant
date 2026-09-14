package com.kongbai.airepo.auth

import android.content.Intent
import android.net.Uri
import com.kongbai.airepo.BuildConfig
import com.kongbai.airepo.core.Constants
import com.kongbai.airepo.core.SecureStore
import com.kongbai.airepo.data.remote.github.GhTokenResponse
import com.kongbai.airepo.data.remote.github.GitHubService
import com.kongbai.airepo.data.remote.github.GhUser
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class AuthState(
    val token: String? = null,
    val user: GhUser? = null,
    val loading: Boolean = false,
    val error: String? = null
)

@Singleton
class AuthRepository @Inject constructor(
    private val secureStore: SecureStore,
    private val service: GitHubService,
    private val ok: OkHttpClient,
    moshi: Moshi
) {
    private val tokenAdapter = moshi.adapter(GhTokenResponse::class.java)
    private val _state = MutableStateFlow(AuthState(token = secureStore.get(SecureStore.KEY_GITHUB_TOKEN)))
    val state: StateFlow<AuthState> = _state

    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    fun isLoggedIn(): Boolean = !_state.value.token.isNullOrBlank()

    /** 构造 OAuth 2.0 + PKCE 授权请求，交给系统浏览器打开（比 Custom Tabs 兼容面广） */
    fun buildAuthIntent(): Intent {
        val verifier = Pkce.createVerifier()
        val st = Pkce.state()
        pendingVerifier = verifier
        pendingState = st
        val url = Uri.parse(Constants.AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId())
            .appendQueryParameter("redirect_uri", Constants.REDIRECT_URI)
            .appendQueryParameter("scope", Constants.SCOPES)
            .appendQueryParameter("state", st)
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "consent")
            .build()
        return Intent(Intent.ACTION_VIEW, url).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
    }

    /** 应用内 WebView 登录页地址（备用入口，用户在设置里可切） */
    fun buildAuthUrl(): String {
        val verifier = Pkce.createVerifier()
        val st = Pkce.state()
        pendingVerifier = verifier
        pendingState = st
        return Uri.parse(Constants.AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId())
            .appendQueryParameter("redirect_uri", Constants.REDIRECT_URI)
            .appendQueryParameter("scope", Constants.SCOPES)
            .appendQueryParameter("state", st)
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "consent")
            .build().toString()
    }

    private fun clientId(): String = BuildConfig.GITHUB_CLIENT_ID

    /** 浏览器回跳 airepo://oauth2redirect?code=xxx&state=xxx 后调用 */
    suspend fun handleRedirect(code: String?, state: String?, error: String?): Result<Unit> {
        if (!error.isNullOrBlank()) {
            _state.value = _state.value.copy(error = "GitHub 返回错误：$error")
            return Result.failure(IllegalStateException(error))
        }
        if (code.isNullOrBlank()) {
            _state.value = _state.value.copy(error = "回调里没有 code")
            return Result.failure(IllegalStateException("no code"))
        }
        // 浏览器回调可能拉起新进程导致 pendingState 丢失，此时只在两者都非空时校验
        if (!pendingState.isNullOrBlank() && !state.isNullOrBlank() && pendingState != state) {
            _state.value = _state.value.copy(error = "state 校验失败，已阻止 CSRF")
            return Result.failure(IllegalStateException("state mismatch"))
        }
        _state.value = _state.value.copy(loading = true, error = null)
        return try {
            val token = exchange(code, pendingVerifier)
            secureStore.put(SecureStore.KEY_GITHUB_TOKEN, token)
            _state.value = AuthState(token = token)
            val me = service.me()
            _state.value = _state.value.copy(user = me, loading = false)
            Result.success(Unit)
        } catch (e: Throwable) {
            _state.value = _state.value.copy(loading = false, error = e.message)
            Result.failure(e)
        }
    }

    private suspend fun exchange(code: String, verifier: String?): String = withContext(Dispatchers.IO) {
        val json = buildString {
            append("grant_type=authorization_code")
            append("&client_id=").append(URLEncoder.encode(clientId(), "UTF-8"))
            append("&code=").append(URLEncoder.encode(code, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(Constants.REDIRECT_URI, "UTF-8"))
            if (!verifier.isNullOrBlank()) append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
            val secret = BuildConfig.GITHUB_CLIENT_SECRET
            if (secret.isNotBlank() && secret != "REPLACE_ME") append("&client_secret=").append(URLEncoder.encode(secret, "UTF-8"))
        }
        val req = Request.Builder()
            .url(Constants.TOKEN_URL)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("User-Agent", Constants.USER_AGENT)
            .post(json.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        ok.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val parsed = runCatching { tokenAdapter.fromJson(body) }.getOrNull()
            val token = parsed?.accessToken
            if (!resp.isSuccessful || token.isNullOrBlank()) {
                error(parsed?.errorDescription ?: parsed?.error ?: "换取 token 失败 HTTP ${resp.code}")
            }
            parsed?.refreshToken?.let { secureStore.put(SecureStore.KEY_REFRESH, it) }
            token
        }
    }

    /** 直接用 Personal Access Token 登录（调试/自用） */
    suspend fun loginWithToken(token: String): Result<GhUser> {
        _state.value = _state.value.copy(loading = true, error = null)
        return try {
            secureStore.put(SecureStore.KEY_GITHUB_TOKEN, token.trim())
            _state.value = AuthState(token = token.trim())
            val me = service.me()
            _state.value = _state.value.copy(user = me, loading = false)
            Result.success(me)
        } catch (e: Throwable) {
            secureStore.put(SecureStore.KEY_GITHUB_TOKEN, null)
            _state.value = AuthState(error = e.message)
            Result.failure(e)
        }
    }

    suspend fun refreshUser() {
        runCatching { service.me() }.onSuccess { u ->
            _state.value = _state.value.copy(user = u)
        }
    }

    fun logout() {
        secureStore.put(SecureStore.KEY_GITHUB_TOKEN, null)
        secureStore.put(SecureStore.KEY_REFRESH, null)
        _state.value = AuthState()
    }

    fun currentToken(): String? = _state.value.token

    /** 当前登录用户名，工具参数缺省时用它推断 owner */
    fun currentLogin(): String? = _state.value.user?.login
}
