package com.kongbai.airepo.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kongbai.airepo.core.Constants
import com.kongbai.airepo.core.SecureStore
import com.kongbai.airepo.data.search.SearchProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AiSettings(
    val baseUrl: String = Constants.DEFAULT_AI_BASE_URL,
    val model: String = Constants.DEFAULT_AI_MODEL,
    val apiKey: String = "",
    val temperature: Float = Constants.DEFAULT_TEMPERATURE,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val webSearchEnabled: Boolean = true,
    val allowDangerousTools: Boolean = false,
    val streamEnabled: Boolean = true,
    val searchProvider: SearchProvider = SearchProvider.AUTO,
    val searchEndpoint: String = "",
    val searchKey: String = ""
) {
    companion object {
        val DEFAULT_SYSTEM_PROMPT = """
你是「AI 仓库助手」——一个可以直接动手操作 GitHub 仓库的 AI 代理，运行在 Android 客户端里。
能力边界：
1. 通过 github_* 工具读写仓库：读文件、列目录、改文件并提交、建分支、开 Issue/PR、合 PR、搜索代码。
2. 通过 web_search / web_fetch 工具联网查资料。遇到下列情况【必须先 web_search 再动手】：
   - 涉及版本号、依赖升级、API 变更、最新最佳实践
   - 出现报错信息、异常堆栈、你无法确定的技术细节
   - 用户问到某个库/框架/工具的现状或用法
   - 你手上的信息可能过时（训练截止之后的变化）
   搜到相关链接后用 web_fetch 读正文，再给出结论。搜索失败就换关键词重试一次，仍失败则明确告诉用户"联网失败+原因"。
   禁止在不确定的情况下凭记忆编造版本号、API 名称或文件路径。
工作准则：
- 修改文件前先读一遍现有内容（gh_read_file），保持原有风格与缩进，不要无谓重写整个文件。
- gh_* 工具的 owner 和 repo 必须分开传：owner 是用户名，repo 只是仓库名，绝不要把 "owner/name" 塞进 repo。
- 文件路径不确定时，先用 gh_get_tree 或 gh_list_dir 确认真实路径再读，路径区分大小写；读不到就换路径重试，别硬猜。
- 一次改动尽量小而精确，提交信息用 Conventional Commits（如 feat: / fix: / docs:）。
- 遇到信息不足先问用户或先搜索，不要编造。
- 回答用中文，简洁；说明你做了什么、改了哪些文件、给出 commit / PR 链接。
""".trimIndent()
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStore: SecureStore
) {
    private object Keys {
        val BASE_URL = stringPreferencesKey("ai_base_url")
        val MODEL = stringPreferencesKey("ai_model")
        val TEMPERATURE = floatPreferencesKey("ai_temperature")
        val SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")
        val WEB_SEARCH = booleanPreferencesKey("web_search_enabled")
        val ALLOW_DANGEROUS = booleanPreferencesKey("allow_dangerous_tools")
        val STREAM = booleanPreferencesKey("stream_enabled")
        val SEARCH_PROVIDER = stringPreferencesKey("search_provider")
        val SEARCH_ENDPOINT = stringPreferencesKey("search_endpoint")
    }

    val settings: Flow<AiSettings> = context.settingsDataStore.data.map { p ->
        AiSettings(
            baseUrl = p[Keys.BASE_URL] ?: Constants.DEFAULT_AI_BASE_URL,
            model = p[Keys.MODEL] ?: Constants.DEFAULT_AI_MODEL,
            apiKey = secureStore.get(SecureStore.KEY_AI_KEY).orEmpty(),
            temperature = p[Keys.TEMPERATURE] ?: Constants.DEFAULT_TEMPERATURE,
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: AiSettings.DEFAULT_SYSTEM_PROMPT,
            webSearchEnabled = p[Keys.WEB_SEARCH] ?: true,
            allowDangerousTools = p[Keys.ALLOW_DANGEROUS] ?: false,
            streamEnabled = p[Keys.STREAM] ?: true,
            searchProvider = runCatching { SearchProvider.valueOf(p[Keys.SEARCH_PROVIDER] ?: "AUTO") }
                .getOrDefault(SearchProvider.AUTO),
            searchEndpoint = p[Keys.SEARCH_ENDPOINT].orEmpty(),
            searchKey = secureStore.get(SecureStore.KEY_SEARCH_KEY).orEmpty()
        )
    }

    suspend fun current(): AiSettings = settings.first()

    suspend fun setAiEndpoint(baseUrl: String, model: String, apiKey: String) {
        secureStore.put(SecureStore.KEY_AI_KEY, apiKey.trim())
        context.settingsDataStore.edit {
            it[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            it[Keys.MODEL] = model.trim()
        }
    }

    suspend fun setTemperature(v: Float) = context.settingsDataStore.edit { it[Keys.TEMPERATURE] = v }
    suspend fun setSystemPrompt(v: String) = context.settingsDataStore.edit { it[Keys.SYSTEM_PROMPT] = v }
    suspend fun setWebSearch(v: Boolean) = context.settingsDataStore.edit { it[Keys.WEB_SEARCH] = v }
    suspend fun setAllowDangerous(v: Boolean) = context.settingsDataStore.edit { it[Keys.ALLOW_DANGEROUS] = v }
    suspend fun setStream(v: Boolean) = context.settingsDataStore.edit { it[Keys.STREAM] = v }

    suspend fun setSearchProvider(v: SearchProvider) =
        context.settingsDataStore.edit { it[Keys.SEARCH_PROVIDER] = v.name }

    suspend fun setSearchEndpoint(v: String) =
        context.settingsDataStore.edit { it[Keys.SEARCH_ENDPOINT] = v.trim() }

    suspend fun setSearchKey(v: String) { secureStore.put(SecureStore.KEY_SEARCH_KEY, v.trim()) }
}
