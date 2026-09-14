package com.kongbai.airepo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kongbai.airepo.auth.AuthRepository
import com.kongbai.airepo.data.prefs.AiSettings
import com.kongbai.airepo.data.prefs.SettingsRepository
import com.kongbai.airepo.data.remote.ai.AiRest
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
    val auth: AuthRepository
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
    fun consumeInfo() { _info.value = null }
}
