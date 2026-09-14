package com.kongbai.airepo.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kongbai.airepo.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun loginWithToken(token: String) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val r = auth.loginWithToken(token)
            r.onFailure { _error.value = it.message ?: "Token 无效" }
            _busy.value = false
        }
    }

    fun consumeError() { _error.value = null }
}
