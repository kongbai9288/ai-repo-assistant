package com.kongbai.airepo.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Token / API Key 一律只走这里，绝不写日志、绝不备份到云端 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "airepo_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        context.getSharedPreferences("airepo_secure_plain", Context.MODE_PRIVATE)
    }

    fun get(key: String): String? = prefs.getString(key, null)?.ifBlank { null }

    fun put(key: String, value: String?) {
        if (value == null) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, value).apply()
    }

    companion object {
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_AI_KEY = "ai_api_key"
        const val KEY_REFRESH = "github_refresh"
    }
}
