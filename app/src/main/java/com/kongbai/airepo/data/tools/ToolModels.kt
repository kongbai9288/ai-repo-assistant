package com.kongbai.airepo.data.tools

data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Any?>,
    val required: List<String> = emptyList()
) {
    fun toDefinition() = mapOf(
        "type" to "object",
        "properties" to properties,
        "required" to required,
        "additionalProperties" to false
    )
}

object ToolCatalog {

    private fun s(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun i(desc: String, def: Int? = null) =
        if (def == null) mapOf("type" to "integer", "description" to desc)
        else mapOf("type" to "integer", "description" to desc, "default" to def)
    private fun b(desc: String) = mapOf("type" to "boolean", "description" to desc)

    val WEB_SEARCH = ToolSpec(
        "web_search",
        "联网搜索，返回标题、链接、摘要。只要涉及版本/API/报错/最新实践/不确定的技术细节，就必须先调用它，不要凭记忆作答。搜到链接后用 web_fetch 读原文。",
        mapOf(
            "query" to s("搜索关键词，尽量具体"),
            "max_results" to i("返回条数，默认 5", 5)
        ),
        listOf("query")
    )

    val WEB_FETCH = ToolSpec(
        "web_fetch",
        "抓取指定网页正文（去掉脚本样式）。用来读搜索结果里的具体页面、官方文档、GitHub 文件原文。web_search 之后通常要跟一次 web_fetch。",
        mapOf(
            "url" to s("完整 URL"),
            "max_chars" to i("最大字符数，默认 6000", 6000)
        ),
        listOf("url")
    )

    val GH_LIST_REPOS = ToolSpec(
        "gh_list_repos",
        "列出当前登录用户有权限的仓库（含私有、组织仓库）。",
        mapOf("per_page" to i("数量，默认 50", 50))
    )

    val GH_GET_REPO = ToolSpec(
        "gh_get_repo",
        "查看单个仓库的元信息：默认分支、是否私有、描述、星标等。",
        mapOf("owner" to s("仓库所有者用户名，如 kongbai9288；不要带斜杠"),
            "repo" to s("仓库名，如 ai-repo-assistant；只传名字，不要传 owner/name 这种完整名")),
        listOf("owner", "repo")
    )

    val GH_CREATE_REPO = ToolSpec(
        "gh_create_repo",
        "创建一个新仓库。",
        mapOf(
            "name" to s("仓库名"),
            "description" to s("描述"),
            "private" to b("是否私有，默认 false"),
            "auto_init" to b("是否初始化 README，默认 true")
        ),
        listOf("name")
    )

    val GH_DELETE_REPO = ToolSpec(
        "gh_delete_repo",
        "删除仓库（不可逆，需用户确认）。",
        mapOf("owner" to s("所有者"), "repo" to s("仓库名")),
        listOf("owner", "repo")
    )

    val GH_LIST_DIR = ToolSpec(
        "gh_list_dir",
        "列出仓库中某个目录的内容（文件/子目录及大小）。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"),
            "path" to s("目录路径，根目录用空字符串"), "ref" to s("分支或 commit，可选")
        ),
        listOf("owner", "repo")
    )

    val GH_READ_FILE = ToolSpec(
        "gh_read_file",
        "读取仓库中某个文件的完整内容（自动 base64 解码）。改文件前务必先读。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "path" to s("文件路径"),
            "ref" to s("分支或 commit，可选")
        ),
        listOf("owner", "repo", "path")
    )

    val GH_WRITE_FILE = ToolSpec(
        "gh_write_file",
        "新建或更新文件并直接产生一次提交。更新已有文件时要带上 sha（用 gh_read_file 获取）。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "path" to s("文件路径"),
            "content" to s("文件完整内容（纯文本）"), "message" to s("提交信息，建议 Conventional Commits"),
            "branch" to s("目标分支，不填则默认分支"), "sha" to s("更新已有文件时必填的 blob sha"),
            "content_base64" to b("content 是否已经是 base64（上传二进制文件时用 true），默认 false")
        ),
        listOf("owner", "repo", "path", "content", "message")
    )

    val GH_DELETE_FILE = ToolSpec(
        "gh_delete_file",
        "删除仓库中的文件并产生提交（需用户确认）。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "path" to s("文件路径"),
            "message" to s("提交信息"), "sha" to s("文件 sha"), "branch" to s("分支，可选")
        ),
        listOf("owner", "repo", "path", "message", "sha")
    )

    val GH_TREE = ToolSpec(
        "gh_get_tree",
        "一次性拿到仓库某分支的完整文件树（可递归），比逐层列目录更快。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "ref" to s("分支或 commit"),
            "recursive" to b("是否递归，默认 true")
        ),
        listOf("owner", "repo")
    )

    val GH_BRANCHES = ToolSpec(
        "gh_list_branches",
        "列出仓库所有分支。",
        mapOf("owner" to s("所有者"), "repo" to s("仓库名")),
        listOf("owner", "repo")
    )

    val GH_CREATE_BRANCH = ToolSpec(
        "gh_create_branch",
        "基于某个分支创建新分支（写改动前先开分支是好习惯）。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "branch" to s("新分支名"),
            "from" to s("源分支，不填则用默认分支")
        ),
        listOf("owner", "repo", "branch")
    )

    val GH_COMMITS = ToolSpec(
        "gh_list_commits",
        "查看提交历史，可按分支或文件路径过滤。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "branch" to s("分支，可选"),
            "path" to s("文件路径，可选"), "per_page" to i("条数，默认 20", 20)
        ),
        listOf("owner", "repo")
    )

    val GH_ISSUES = ToolSpec(
        "gh_list_issues",
        "列出仓库的 Issue。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"),
            "state" to s("open / closed / all，默认 open")
        ),
        listOf("owner", "repo")
    )

    val GH_CREATE_ISSUE = ToolSpec(
        "gh_create_issue",
        "创建 Issue。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "title" to s("标题"),
            "body" to s("正文，支持 Markdown"), "labels" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "标签")
        ),
        listOf("owner", "repo", "title")
    )

    val GH_COMMENT_ISSUE = ToolSpec(
        "gh_comment_issue",
        "给 Issue 或 PR 添加评论。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "number" to i("Issue/PR 编号"),
            "body" to s("评论内容")
        ),
        listOf("owner", "repo", "number", "body")
    )

    val GH_PULLS = ToolSpec(
        "gh_list_pulls",
        "列出仓库的 PR。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "state" to s("open / closed / all")
        ),
        listOf("owner", "repo")
    )

    val GH_CREATE_PULL = ToolSpec(
        "gh_create_pull",
        "创建 Pull Request。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "title" to s("标题"),
            "head" to s("源分支"), "base" to s("目标分支"), "body" to s("描述")
        ),
        listOf("owner", "repo", "title", "head", "base")
    )

    val GH_MERGE_PULL = ToolSpec(
        "gh_merge_pull",
        "合并 PR（需用户确认）。",
        mapOf(
            "owner" to s("所有者用户名"), "repo" to s("仓库名，只传名字不要带 owner/"), "number" to i("PR 编号"),
            "method" to s("merge / squash / rebase，默认 merge"), "title" to s("合并提交标题")
        ),
        listOf("owner", "repo", "number")
    )

    val GH_SEARCH_CODE = ToolSpec(
        "gh_search_code",
        "在 GitHub 上搜索代码，例如 q=\"org: JetBrains language:Kotlin\"。",
        mapOf("q" to s("GitHub 代码搜索语法"), "per_page" to i("条数，默认 10", 10)),
        listOf("q")
    )

    val GH_SEARCH_REPOS = ToolSpec(
        "gh_search_repos",
        "搜索 GitHub 仓库。",
        mapOf("q" to s("搜索语法"), "per_page" to i("条数，默认 10", 10)),
        listOf("q")
    )

    fun githubTools() = listOf(
        GH_LIST_REPOS, GH_GET_REPO, GH_CREATE_REPO, GH_DELETE_REPO, GH_LIST_DIR, GH_READ_FILE,
        GH_WRITE_FILE, GH_DELETE_FILE, GH_TREE, GH_BRANCHES, GH_CREATE_BRANCH, GH_COMMITS,
        GH_ISSUES, GH_CREATE_ISSUE, GH_COMMENT_ISSUE, GH_PULLS, GH_CREATE_PULL, GH_MERGE_PULL,
        GH_SEARCH_CODE, GH_SEARCH_REPOS
    )

    fun webTools() = listOf(WEB_SEARCH, WEB_FETCH)
}
