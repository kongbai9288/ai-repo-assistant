package com.kongbai.airepo.data.tools

import com.kongbai.airepo.core.Constants
import com.kongbai.airepo.data.remote.ai.AiToolDef
import com.kongbai.airepo.data.remote.ai.AiFunctionDef
import com.kongbai.airepo.data.search.WebSearch
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Singleton

data class ToolRequest(val name: String, val args: Map<String, Any?>)

@Singleton
class ToolExecutor @Inject constructor(
    private val gh: GitHubTools,
    private val web: WebSearch,
    private val moshi: Moshi
) {
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    fun definitions(includeWeb: Boolean): List<AiToolDef> {
        val specs = if (includeWeb) ToolCatalog.webTools() + ToolCatalog.githubTools()
        else ToolCatalog.githubTools()
        return specs.map {
            AiToolDef(function = AiFunctionDef(it.name, it.description, it.toDefinition()))
        }
    }

    fun parseArgs(raw: String?): Map<String, Any?> =
        runCatching { mapAdapter.fromJson(raw ?: "{}") ?: emptyMap() }.getOrDefault(emptyMap())

    @Suppress("UNCHECKED_CAST")
    suspend fun execute(req: ToolRequest): String {
        val a = req.args
        val out = try {
            when (req.name) {
                ToolCatalog.WEB_SEARCH.name -> {
                    val q = a["query"] as? String ?: error("缺少 query")
                    val n = (a["max_results"] as? Double)?.toInt() ?: 5
                    val list = web.search(q, n)
                    if (list.isEmpty()) "没有搜索结果" else list.mapIndexed { i, r ->
                        "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
                    }.joinToString("\n")
                }
                ToolCatalog.WEB_FETCH.name -> {
                    val u = a["url"] as? String ?: error("缺少 url")
                    val n = (a["max_chars"] as? Double)?.toInt() ?: 6000
                    web.fetch(u, n)
                }
                ToolCatalog.GH_LIST_REPOS.name -> gh.listRepos((a["per_page"] as? Double)?.toInt() ?: 50)
                ToolCatalog.GH_GET_REPO.name -> gh.getRepo(a.str("owner"), a.str("repo"))
                ToolCatalog.GH_CREATE_REPO.name -> gh.createRepo(
                    a.str("name"), a["description"] as? String,
                    a["private"] as? Boolean ?: false, a["auto_init"] as? Boolean ?: true
                )
                ToolCatalog.GH_DELETE_REPO.name -> gh.deleteRepo(a.str("owner"), a.str("repo"))
                ToolCatalog.GH_LIST_DIR.name -> gh.listDir(a.str("owner"), a.str("repo"), a["path"] as? String ?: "", a["ref"] as? String)
                ToolCatalog.GH_READ_FILE.name -> gh.readFile(a.str("owner"), a.str("repo"), a.str("path"), a["ref"] as? String)
                ToolCatalog.GH_WRITE_FILE.name -> gh.writeFile(
                    a.str("owner"), a.str("repo"), a.str("path"), a.str("content"),
                    a.str("message"), a["branch"] as? String, a["sha"] as? String
                )
                ToolCatalog.GH_DELETE_FILE.name -> gh.deleteFile(
                    a.str("owner"), a.str("repo"), a.str("path"), a.str("message"),
                    a.str("sha"), a["branch"] as? String
                )
                ToolCatalog.GH_TREE.name -> gh.getTree(
                    a.str("owner"), a.str("repo"), a["ref"] as? String ?: "HEAD",
                    a["recursive"] as? Boolean ?: true
                )
                ToolCatalog.GH_BRANCHES.name -> gh.listBranches(a.str("owner"), a.str("repo"))
                ToolCatalog.GH_CREATE_BRANCH.name -> gh.createBranch(
                    a.str("owner"), a.str("repo"), a.str("branch"), a["from"] as? String
                )
                ToolCatalog.GH_COMMITS.name -> gh.listCommits(
                    a.str("owner"), a.str("repo"), a["branch"] as? String,
                    a["path"] as? String, (a["per_page"] as? Double)?.toInt() ?: 20
                )
                ToolCatalog.GH_ISSUES.name -> gh.listIssues(a.str("owner"), a.str("repo"), a["state"] as? String ?: "open")
                ToolCatalog.GH_CREATE_ISSUE.name -> gh.createIssue(
                    a.str("owner"), a.str("repo"), a.str("title"), a["body"] as? String,
                    (a["labels"] as? List<*>)?.mapNotNull { it as? String }
                )
                ToolCatalog.GH_COMMENT_ISSUE.name -> gh.commentIssue(
                    a.str("owner"), a.str("repo"), (a["number"] as? Double)?.toInt() ?: 0, a.str("body")
                )
                ToolCatalog.GH_PULLS.name -> gh.listPulls(a.str("owner"), a.str("repo"), a["state"] as? String ?: "open")
                ToolCatalog.GH_CREATE_PULL.name -> gh.createPull(
                    a.str("owner"), a.str("repo"), a.str("title"), a.str("head"), a.str("base"), a["body"] as? String
                )
                ToolCatalog.GH_MERGE_PULL.name -> gh.mergePull(
                    a.str("owner"), a.str("repo"), (a["number"] as? Double)?.toInt() ?: 0,
                    a["method"] as? String, a["title"] as? String
                )
                ToolCatalog.GH_SEARCH_CODE.name -> gh.searchCode(a.str("q"), (a["per_page"] as? Double)?.toInt() ?: 10)
                ToolCatalog.GH_SEARCH_REPOS.name -> gh.searchRepos(a.str("q"), (a["per_page"] as? Double)?.toInt() ?: 10)
                else -> "未知工具：${req.name}"
            }
        } catch (e: Throwable) { "工具执行异常：${e.message ?: e.javaClass.simpleName}" }
        return if (out.length > Constants.MAX_TOOL_OUTPUT_CHARS)
            out.take(Constants.MAX_TOOL_OUTPUT_CHARS) + "\n…(已截断)"
        else out
    }

    /** 给确认弹窗用的一句话摘要 */
    fun summarize(req: ToolRequest): String = when (req.name) {
        ToolCatalog.WEB_SEARCH.name -> "联网搜索：${req.args["query"]}"
        ToolCatalog.WEB_FETCH.name -> "抓取网页：${req.args["url"]}"
        ToolCatalog.GH_WRITE_FILE.name -> "写入 ${req.args["owner"]}/${req.args["repo"]} 的 ${req.args["path"]}" +
            (req.args["branch"]?.let { "（分支 $it）" } ?: "")
        ToolCatalog.GH_DELETE_FILE.name -> "删除文件 ${req.args["path"]}"
        ToolCatalog.GH_DELETE_REPO.name -> "删除仓库 ${req.args["owner"]}/${req.args["repo"]}"
        ToolCatalog.GH_MERGE_PULL.name -> "合并 PR #${req.args["number"]}"
        ToolCatalog.GH_CREATE_PULL.name -> "创建 PR：${req.args["title"]}"
        ToolCatalog.GH_CREATE_ISSUE.name -> "创建 Issue：${req.args["title"]}"
        ToolCatalog.GH_CREATE_REPO.name -> "创建仓库：${req.args["name"]}"
        else -> req.name
    }

    private fun Map<String, Any?>.str(k: String): String = this[k] as? String ?: ""
}
