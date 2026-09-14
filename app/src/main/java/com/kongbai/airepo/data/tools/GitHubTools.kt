package com.kongbai.airepo.data.tools

import android.util.Base64
import com.kongbai.airepo.data.remote.github.GitHubService
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.kongbai.airepo.data.remote.github.GhContent
import okhttp3.ResponseBody
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubTools @Inject constructor(
    private val api: GitHubService,
    moshi: Moshi
) {
    private val listAdapter = moshi.adapter<List<GhContent>>(
        Types.newParameterizedType(List::class.java, GhContent::class.java)
    )
    private val oneAdapter = moshi.adapter(GhContent::class.java)

    suspend fun me() = api.me()

    suspend fun listRepos(perPage: Int = 50): String {
        val r = api.listMyRepos(perPage = perPage)
        return r.joinToString("\n") {
            "- ${it.fullName} [${if (it.isPrivate) "private" else "public"}] ★${it.stars} " +
                "branch=${it.defaultBranch} ${it.language ?: ""} ${it.htmlUrl}"
        }.ifBlank { "没有找到仓库" }
    }

    suspend fun getRepo(owner: String, repo: String): String {
        val r = api.getRepo(owner, repo)
        return "full_name=${r.fullName}\ndefault_branch=${r.defaultBranch}\nprivate=${r.isPrivate}\n" +
            "description=${r.description}\nstars=${r.stars}\nupdated=${r.updatedAt}\nurl=${r.htmlUrl}"
    }

    suspend fun createRepo(name: String, description: String?, private: Boolean, autoInit: Boolean): String {
        val r = api.createRepo(
            mapOf(
                "name" to name,
                "description" to description,
                "private" to private,
                "auto_init" to autoInit
            )
        )
        return "已创建仓库：${r.fullName} ${r.htmlUrl}"
    }

    suspend fun deleteRepo(owner: String, repo: String): String {
        val r = api.deleteRepo(owner, repo)
        return if (r.isSuccessful) "已删除仓库 $owner/$repo" else "删除失败 HTTP ${r.code()}"
    }

    suspend fun listDir(owner: String, repo: String, path: String, ref: String?): String {
        val resp: Response<ResponseBody> = api.getContent(owner, repo, path, ref)
        val body = resp.body()?.string()
        if (!resp.isSuccessful || body == null) return "读取目录失败 HTTP ${resp.code()}"
        return try {
            val items = listAdapter.fromJson(body) ?: emptyList()
            items.joinToString("\n") { "${if (it.type == "dir") "[dir] " else "[file]"} ${it.path} (${it.size}B)" }
                .ifBlank { "空目录" }
        } catch (t: Throwable) {
            "目录解析失败：${t.message}"
        }
    }

    suspend fun readFile(owner: String, repo: String, path: String, ref: String?): String {
        val resp: Response<ResponseBody> = api.getContent(owner, repo, path, ref)
        val body = resp.body()?.string()
        if (!resp.isSuccessful || body == null) return "读取文件失败 HTTP ${resp.code()}"
        return try {
            val c = oneAdapter.fromJson(body)
            if (c?.content != null) {
                val decoded = String(
                    Base64.decode(c.content.replace("\n", ""), Base64.DEFAULT),
                    Charsets.UTF_8
                )
                "文件：$path\nsha=${c.sha}\n---\n$decoded"
            } else "该路径不是文件或是目录：$path"
        } catch (t: Throwable) {
            "文件解析失败：${t.message}"
        }
    }

    suspend fun writeFile(
        owner: String, repo: String, path: String, content: String,
        message: String, branch: String?, sha: String?
    ): String {
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = linkedMapOf<String, Any?>(
            "message" to message,
            "content" to b64,
            "branch" to branch,
            "sha" to sha
        ).filterValues { it != null }
        val r = api.putContent(owner, repo, path, body)
        return "已提交：${r.commit?.message ?: message}\n" +
            "commit=${r.commit?.sha}\nurl=${r.commit?.htmlUrl}\nfile=${r.content?.path}"
    }

    suspend fun deleteFile(owner: String, repo: String, path: String, message: String, sha: String, branch: String?): String {
        val body = linkedMapOf<String, Any?>("message" to message, "sha" to sha, "branch" to branch)
            .filterValues { it != null }
        val r = api.deleteContent(owner, repo, path, body)
        return "已删除 $path，commit=${r.commit?.sha}"
    }

    suspend fun getTree(owner: String, repo: String, ref: String, recursive: Boolean, limit: Int = 400): String {
        val t = api.getTree(owner, repo, ref, if (recursive) 1 else 0)
        val list = t.tree.take(limit)
        return list.joinToString("\n") { "${it.type}\t${it.path}" } +
            if (t.tree.size > limit) "\n…(截断，共 ${t.tree.size} 条)" else ""
    }

    suspend fun listBranches(owner: String, repo: String): String =
        api.listBranches(owner, repo).joinToString("\n") { "- ${it.name}${if (it.protected) " (protected)" else ""}" }

    suspend fun createBranch(owner: String, repo: String, branch: String, from: String?): String {
        val base = from ?: api.getRepo(owner, repo).defaultBranch
        val ref = api.listBranches(owner, repo, 100).firstOrNull { it.name == base }
            ?: return "找不到源分支 $base"
        val sha = ref.commit?.sha ?: return "拿不到源分支 sha"
        api.createRef(owner, repo, mapOf("ref" to "refs/heads/$branch", "sha" to sha))
        return "已创建分支 $branch（from $base）"
    }

    suspend fun listCommits(owner: String, repo: String, branch: String?, path: String?, perPage: Int): String =
        api.listCommits(owner, repo, branch, path, perPage).joinToString("\n") {
            "- ${it.sha.take(7)} ${it.commit?.message?.lines()?.firstOrNull() ?: ""} (${it.commit?.author?.name})"
        }.ifBlank { "无提交记录" }

    suspend fun listIssues(owner: String, repo: String, state: String): String =
        api.listIssues(owner, repo, state).joinToString("\n") { "#${it.number} [${it.state}] ${it.title} ${it.htmlUrl}" }
            .ifBlank { "无 Issue" }

    suspend fun createIssue(owner: String, repo: String, title: String, body: String?, labels: List<String>?): String {
        val map = linkedMapOf<String, Any?>("title" to title, "body" to body)
        if (!labels.isNullOrEmpty()) map["labels"] = labels
        val i = api.createIssue(owner, repo, map)
        return "已创建 Issue #${i.number} ${i.htmlUrl}"
    }

    suspend fun commentIssue(owner: String, repo: String, number: Int, body: String): String {
        api.commentIssue(owner, repo, number, mapOf("body" to body))
        return "已在 #$number 留言"
    }

    suspend fun listPulls(owner: String, repo: String, state: String): String =
        api.listPulls(owner, repo, state).joinToString("\n") {
            "#${it.number} [${it.state}] ${it.title} ${it.head?.ref} → ${it.base?.ref} ${it.htmlUrl}"
        }.ifBlank { "无 PR" }

    suspend fun createPull(owner: String, repo: String, title: String, head: String, base: String, body: String?): String {
        val p = api.createPull(owner, repo, mapOf("title" to title, "head" to head, "base" to base, "body" to body))
        return "已创建 PR #${p.number} ${p.htmlUrl}"
    }

    suspend fun mergePull(owner: String, repo: String, number: Int, method: String?, title: String?): String {
        val m = method ?: "merge"
        val r = api.mergePull(owner, repo, number, linkedMapOf<String, Any?>("merge_method" to m, "commit_title" to title)
            .filterValues { it != null })
        return "合并结果：${r["message"] ?: r["merged"] ?: "ok"}"
    }

    suspend fun searchCode(q: String, perPage: Int = 10): String {
        val r = api.searchCode(q, perPage)
        return r.items.joinToString("\n") { "- ${it.repository?.fullName}: ${it.path} ${it.htmlUrl}" }
            .ifBlank { "没搜到代码（注意：代码搜索要求仓库已被索引且有权限）" }
    }

    suspend fun searchRepos(q: String, perPage: Int = 10): String =
        api.searchRepos(q, perPage).items.joinToString("\n") { "- ${it.fullName} ★${it.stars} ${it.htmlUrl}" }
            .ifBlank { "没搜到仓库" }
}
