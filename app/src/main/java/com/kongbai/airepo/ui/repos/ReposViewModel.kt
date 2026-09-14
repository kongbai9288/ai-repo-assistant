package com.kongbai.airepo.ui.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kongbai.airepo.data.remote.github.GhRepo
import com.kongbai.airepo.data.remote.github.GitHubService
import com.kongbai.airepo.data.remote.github.GhTreeEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReposViewModel @Inject constructor(private val api: GitHubService) : ViewModel() {

    private val _repos = MutableStateFlow<List<GhRepo>>(emptyList())
    val repos: StateFlow<List<GhRepo>> = _repos

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _tree = MutableStateFlow<List<GhTreeEntry>>(emptyList())
    val tree: StateFlow<List<GhTreeEntry>> = _tree

    private val _file = MutableStateFlow<Pair<String, String>?>(null)
    val file: StateFlow<Pair<String, String>?> = _file

    private var selected: GhRepo? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { api.listMyRepos(perPage = 100) }
                .onSuccess { _repos.value = it }
                .onFailure { _error.value = it.message }
            _loading.value = false
        }
    }

    fun openRepo(repo: GhRepo) {
        selected = repo
        _file.value = null
        viewModelScope.launch {
            _loading.value = true
            runCatching { api.getTree(repo.owner?.login ?: "", repo.name, repo.defaultBranch, 1) }
                .onSuccess { _tree.value = it.tree }
                .onFailure { _error.value = it.message }
            _loading.value = false
        }
    }

    fun openFile(owner: String, repo: String, path: String, ref: String) {
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                val resp = api.getContent(owner, repo, path, ref)
                val body = resp.body()?.string().orEmpty()
                val decoded = Regex("\"content\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues[1]
                if (decoded != null) {
                    String(android.util.Base64.decode(decoded.replace("\\n", ""), android.util.Base64.DEFAULT))
                } else "无法解析文件内容"
            }.onSuccess { _file.value = path to it }
                .onFailure { _error.value = it.message }
            _loading.value = false
        }
    }

    fun selectedRepo(): GhRepo? = selected
}
