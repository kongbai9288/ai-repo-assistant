package com.kongbai.airepo.core

object Constants {
    const val GITHUB_API = "https://api.github.com/"
    const val AUTH_URL = "https://github.com/login/oauth/authorize"
    const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val REDIRECT_URI = "airepo://oauth2redirect"
    const val REDIRECT_HOST = "oauth2redirect"
    const val REDIRECT_SCHEME = "airepo"

    /** 你要全权限，所以这里一次性申请全部常用 scope（含删库、工作流、包管理） */
    const val SCOPES = "repo,repo:status,repo_deployment,public_repo,repo:invite," +
        "security_events,admin:repo_hook,write:repo_hook,read:repo_hook," +
        "admin:org,write:org,read:org,gist,notifications,user,read:user,user:email,user:follow," +
        "delete_repo,write:discussion,read:discussion,write:packages,read:packages," +
        "delete:packages,admin:gpg_key,admin:ssh_signing_key,project,admin:public_key,codespace"

    const val USER_AGENT = "AiRepoAssistant/1.0 (Android)"
    const val DEFAULT_AI_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_AI_MODEL = "gpt-4o-mini"
    const val DEFAULT_TEMPERATURE = 0.3f
    const val MAX_TOOL_OUTPUT_CHARS = 12_000
    const val MAX_AGENT_STEPS = 12
    const val REQUEST_TIMEOUT_SECONDS = 120L

    /** 需要用户二次确认的高危操作 */
    val DANGEROUS_TOOLS = setOf(
        "gh_delete_file", "gh_delete_repo", "gh_merge_pull", "gh_force_push"
    )
}
