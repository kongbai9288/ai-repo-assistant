package com.kongbai.airepo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kongbai.airepo.auth.AuthRepository
import com.kongbai.airepo.core.CrashHandler
import com.kongbai.airepo.data.prefs.AiSettings
import com.kongbai.airepo.data.prefs.SettingsRepository
import com.kongbai.airepo.data.remote.ai.AiRest
import com.kongbai.airepo.data.search.EngineReport
import com.kongbai.airepo.data.search.SearchProvider
import com.kongbai.airepo.data.search.WebSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val ai: AiRest,
    val auth: AuthRepository,
    private val web: WebSearch,
    private val crash: CrashHandler
) : ViewModel() {

    val settingsFlow: StateFlow<AiSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiSettings())

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info

    fun saveAi(baseUrl: String, model: String, key: String) {
        viewModelScope.launch {
            settings.setAiEndpoint(baseUrl, model, key)
            _info.value = "已保存"
        }
    }

    fun setTemperature(v: Float) = viewModelScope.launch { settings.setTemperature(v) }
    fun setSystemPrompt(v: String) = viewModelScope.launch { settings.setSystemPrompt(v) }
    fun setWebSearch(v: Boolean) = viewModelScope.launch { settings.setWebSearch(v) }
    fun setAllowDangerous(v: Boolean) = viewModelScope.launch { settings.setAllowDangerous(v) }
    fun setStream(v: Boolean) = viewModelScope.launch { settings.setStream(v) }

    fun fetchModels() {
        viewModelScope.launch {
            val s = settings.current()
            _info.value = "拉取中…"
            val r = ai.listModels(s.baseUrl, s.apiKey)
            r.onSuccess {
                _models.value = it
                _info.value = "共 ${it.size} 个模型"
            }.onFailure { _info.value = "拉取失败：${it.message}" }
        }
    }

    fun logout() = auth.logout()
    private val _diag = MutableStateFlow<List<EngineReport>>(emptyList())
    val diag: StateFlow<List<EngineReport>> = _diag

    private val _diagnosing = MutableStateFlow(false)
    val diagnosing: StateFlow<Boolean> = _diagnosing

    fun setSearchProvider(v: SearchProvider) = viewModelScope.launch { settings.setSearchProvider(v) }
    fun setSearchEndpoint(v: String) = viewModelScope.launch { settings.setSearchEndpoint(v) }
    fun setSearchKey(v: String) = viewModelScope.launch { settings.setSearchKey(v) }

    /** 一键把每个搜索源都试一遍，直接看出哪个能通 */
    fun diagnoseSearch() {
        viewModelScope.launch {
            _diagnosing.value = true
            _diag.value = emptyList()
            val s = settings.current()
            _diag.value = web.diagnose("jetpack compose", s.searchEndpoint, s.searchKey)
            _info.value = "自检完成，绿色为可用"
            _diagnosing.value = false
        }
    }

    private val _crashLog = MutableStateFlow("")
    val crashLog: StateFlow<String> = _crashLog

    fun loadCrashLog() {
        viewModelScope.launch { _crashLog.value = crash.read() }
    }

    fun clearCrashLog() {
        viewModelScope.launch { crash.clear(); _crashLog.value = "" }
    }

    fun consumeInfo() { _info.value = null }
}
