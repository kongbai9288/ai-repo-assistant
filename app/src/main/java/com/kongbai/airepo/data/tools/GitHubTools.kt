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

    /** AI 常把 "owner/name" 整个塞进 repo，或反过来漏 owner —— 这里统一拆开兜底 */
    private fun split(owner: String?, repo: String?): Pair<String, String> {
        val o = owner?.trim().orEmpty().trim('/')
        var r = repo?.trim().orEmpty().trim('/')
        if (r.contains("/")) {
            val parts = r.split("/").filter { it.isNotBlank() }
            if (parts.size >= 2) return parts[0] to parts[1]
            return o to parts[0]
        }
        if (o.isBlank()) {
            // 只有 "owner/name" 被塞进了 owner
            val parts = r.split("/")
            return if (parts.size >= 2) parts[0] to parts[1] else o to r
        }
        return o to r
    }

    /** path 走 @Path(encoded=true)，必须自己编码；空串视为根目录 */
    private fun enc(path: String?): String {
        val p = path?.trim().orEmpty().trim('/')
        if (p.isBlank()) return ""
        return p.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
    }


    suspend fun listRepos(perPage: Int = 50): String {
        val r = api.listMyRepos(perPage = perPage)
        return r.joinToString("\n") {
            "- ${it.fullName} [${if (it.isPrivate) "private" else "public"}] ★${it.stars} " +
                "branch=${it.defaultBranch} ${it.language ?: ""} ${it.htmlUrl}"
        }.ifBlank { "没有找到仓库" }
    }

    suspend fun getRepo(owner: String, repo: String): String {
        val (o, n) = split(owner, repo)
        val r = api.getRepo(o, n)
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
        val (o, n) = split(owner, repo)
        val r = api.deleteRepo(o, n)
        return if (r.isSuccessful) "已删除仓库 $owner/$repo" else "删除失败 HTTP ${r.code()}"
    }

    suspend fun listDir(owner: String, repo: String, path: String, ref: String?): String {
        val (o, n) = split(owner, repo)
        val resp: Response<ResponseBody> = api.getContent(o, n, enc(path), ref)
        val body = resp.body()?.string()
        if (!resp.isSuccessful || body == null) {
            if (resp.code() == 404) return "404：找不到 ${o}/${n} 的目录 \"${path}\"（注意 path 根目录用空字符串，且分支 ref 要存在）"
            return "读取目录失败 HTTP ${resp.code()}"
        }
        return try {
            val items = listAdapter.fromJson(body) ?: emptyList()
            items.joinToString("\n") { "${if (it.type == "dir") "[dir] " else "[file]"} ${it.path} (${it.size}B)" }
                .ifBlank { "空目录" }
        } catch (t: Throwable) {
            "目录解析失败：${t.message}"
        }
    }

    suspend fun readFile(owner: String, repo: String, path: String, ref: String?): String {
        val (o, n) = split(owner, repo)
        val resp: Response<ResponseBody> = api.getContent(o, n, enc(path), ref)
        val body = resp.body()?.string()
        if (!resp.isSuccessful || body == null) {
            if (resp.code() == 404) return "404：找不到文件 ${o}/${n}:${path}\n提示：先用 gh_list_dir 或 gh_get_tree 确认真实路径，路径区分大小写。"
            return "读取文件失败 HTTP ${resp.code()}"
        }
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
        message: String, branch: String?, sha: String?, contentIsBase64: Boolean = false
    ): String {
        val b64 = if (contentIsBase64) content.replace("\s".toRegex(), "")
        else Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = linkedMapOf<String, Any?>(
            "message" to message,
            "content" to b64,
            "branch" to branch,
            "sha" to sha
        ).filterValues { it != null }
        val (o, n) = split(owner, repo)
        val r = api.putContent(o, n, enc(path), body)
        return "已提交：${r.commit?.message ?: message}\n" +
            "commit=${r.commit?.sha}\nurl=${r.commit?.htmlUrl}\nfile=${r.content?.path}"
    }

    suspend fun deleteFile(owner: String, repo: String, path: String, message: String, sha: String, branch: String?): String {
        val body = linkedMapOf<String, Any?>("message" to message, "sha" to sha, "branch" to branch)
            .filterValues { it != null }
        val (o, n) = split(owner, repo)
        val r = api.deleteContent(o, n, enc(path), body)
        return "已删除 $path，commit=${r.commit?.sha}"
    }

    suspend fun getTree(owner: String, repo: String, ref: String, recursive: Boolean, limit: Int = 400): String {
        val (o, n) = split(owner, repo)
        val t = api.getTree(o, n, ref, if (recursive) 1 else 0)
        val list = t.tree.take(limit)
        return list.joinToString("\n") { "${it.type}\t${it.path}" } +
            if (t.tree.size > limit) "\n…(截断，共 ${t.tree.size} 条)" else ""
    }

    suspend fun listBranches(owner: String, repo: String): String =
        val (o, n) = split(owner, repo)
        api.listBranches(o, n).joinToString("\n") { "- ${it.name}${if (it.protected) " (protected)" else ""}" }

    suspend fun createBranch(owner: String, repo: String, branch: String, from: String?): String {
        val (o, n) = split(owner, repo)
        val base = from ?: api.getRepo(o, n).defaultBranch
        val ref = api.listBranches(o, n, 100).firstOrNull { it.name == base }
            ?: return "找不到源分支 $base"
        val sha = ref.commit?.sha ?: return "拿不到源分支 sha"
        api.createRef(o, n, mapOf("ref" to "refs/heads/$branch", "sha" to sha))
        return "已创建分支 $branch（from $base）"
    }

    suspend fun listCommits(owner: String, repo: String, branch: String?, path: String?, perPage: Int): String =
        val (o, n) = split(owner, repo)
        api.listCommits(o, n, branch, path, perPage).joinToString("\n") {
            "- ${it.sha.take(7)} ${it.commit?.message?.lines()?.firstOrNull() ?: ""} (${it.commit?.author?.name})"
        }.ifBlank { "无提交记录" }

    suspend fun listIssues(owner: String, repo: String, state: String): String =
        val (o, n) = split(owner, repo)
        api.listIssues(o, n, state).joinToString("\n") { "#${it.number} [${it.state}] ${it.title} ${it.htmlUrl}" }
            .ifBlank { "无 Issue" }

    suspend fun createIssue(owner: String, repo: String, title: String, body: String?, labels: List<String>?): String {
        val map = linkedMapOf<String, Any?>("title" to title, "body" to body)
        if (!labels.isNullOrEmpty()) map["labels"] = labels
        val (o, n) = split(owner, repo)
        val i = api.createIssue(o, n, map)
        return "已创建 Issue #${i.number} ${i.htmlUrl}"
    }

    suspend fun commentIssue(owner: String, repo: String, number: Int, body: String): String {
        val (o, n) = split(owner, repo)
        api.commentIssue(o, n, number, mapOf("body" to body))
        return "已在 #$number 留言"
    }

    suspend fun listPulls(owner: String, repo: String, state: String): String =
        val (o, n) = split(owner, repo)
        api.listPulls(o, n, state).joinToString("\n") {
            "#${it.number} [${it.state}] ${it.title} ${it.head?.ref} → ${it.base?.ref} ${it.htmlUrl}"
        }.ifBlank { "无 PR" }

    suspend fun createPull(owner: String, repo: String, title: String, head: String, base: String, body: String?): String {
        val (o, n) = split(owner, repo)
        val p = api.createPull(o, n, mapOf("title" to title, "head" to head, "base" to base, "body" to body))
        return "已创建 PR #${p.number} ${p.htmlUrl}"
    }

    suspend fun mergePull(owner: String, repo: String, number: Int, method: String?, title: String?): String {
        val m = method ?: "merge"
        val (o, n) = split(owner, repo)
        val r = api.mergePull(o, n, number, linkedMapOf<String, Any?>("merge_method" to m, "commit_title" to title)
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
