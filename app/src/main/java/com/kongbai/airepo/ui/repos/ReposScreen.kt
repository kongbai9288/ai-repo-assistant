package com.kongbai.airepo.ui.repos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kongbai.airepo.data.remote.github.GhRepo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(vm: ReposViewModel = hiltViewModel(), onPickRepo: (String) -> Unit) {
    val repos by vm.repos.collectAsState()
    val tree by vm.tree.collectAsState()
    val file by vm.file.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    var selected by remember { mutableStateOf<GhRepo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.fullName ?: "我的仓库") },
                actions = {
                    IconButton(onClick = { selected = null; vm.refresh() }) { Icon(Icons.Default.Refresh, null) }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading) CircularProgressIndicator(Modifier.padding(12.dp))
            error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            file?.let { (path, content) ->
                Column(
                    Modifier
                        .weight(1f)
                        .padding(12.dp)
                ) {
                    Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                    Text(
                        content.take(8000),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } ?: if (selected == null) {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(repos) { r -> RepoRow(r) { selected = r; vm.openRepo(r); onPickRepo(r.fullName) } }
                }
            } else {
                val repo = selected!!
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    items(tree.take(300)) { e ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (e.type == "blob") vm.openFile(
                                        repo.owner?.login ?: "", repo.name, e.path, repo.defaultBranch
                                    )
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (e.type == "tree") Icons.Default.Folder else Icons.Default.Description,
                                null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                e.path,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (e.type == "blob") Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: GhRepo, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (repo.isPrivate) Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp))
                Text(repo.fullName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
            }
            repo.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Default.Star, null, modifier = Modifier.size(12.dp))
                Text(" ${repo.stars}", style = MaterialTheme.typography.labelSmall)
                Text("  ${repo.language ?: ""}", style = MaterialTheme.typography.labelSmall)
                Text("  默认分支 ${repo.defaultBranch}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
