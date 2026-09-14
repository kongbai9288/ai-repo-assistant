package com.kongbai.airepo.oauth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kongbai.airepo.auth.AuthRepository
import com.kongbai.airepo.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 浏览器拿到授权码后跳到 airepo://oauth2redirect?code=…&state=…，
 * 这个透明 Activity 负责用 code + code_verifier 换 token，然后回到主页。
 */
@AndroidEntryPoint
class OAuthCallbackActivity : ComponentActivity() {

    @Inject lateinit var auth: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        lifecycleScope.launch {
            if (data != null) {
                auth.handleRedirect(
                    code = data.getQueryParameter("code"),
                    state = data.getQueryParameter("state"),
                    error = data.getQueryParameter("error")
                )
            }
            startActivity(
                Intent(this@OAuthCallbackActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }
}
