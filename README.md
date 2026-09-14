# AI 仓库助手（Android）

一个能**自己动手改 GitHub 仓库**的 AI 客户端：OAuth 2.0 + PKCE 授权登录，AI 通过 Function Calling 调 GitHub API（读文件、改文件并提交、建分支、开 Issue/PR、合 PR、搜代码），还能用内置 `web_search` / `web_fetch` 联网查资料后再动手。

- 语言：Kotlin + Jetpack Compose + Material3
- 架构：Hilt + Room + DataStore + Retrofit/Moshi + OkHttp SSE
- 图标：Material Icons（Apache 2.0）+ 自适应启动器图标

---

## 1. 先做的事（重要）

1. **把你泄露的那个 `ghp_...` Token 吊销**：GitHub → Settings → Developer settings → Personal access tokens → Delete。它已经出现在对话里，等于公开。
2. **创建 OAuth App**：<https://github.com/settings/developers> → OAuth Apps → New OAuth App
   - Homepage URL：随便填，如 `https://github.com/`
   - **Authorization callback URL 必须填 `airepo://oauth2redirect`**
   - 生成后把 Client ID 填进工程（下面两种方式任选）
3. **填 Client ID**（二选一，推荐环境变量，别提交到仓库）：
   - 环境变量：`export GITHUB_CLIENT_ID=Iv1.xxxx` `export GITHUB_CLIENT_SECRET=xxxx`
   - 或改 `app/build.gradle.kts` 里 `buildConfigField("String", "GITHUB_CLIENT_ID", ...)` 的默认值
   - GitHub App / OAuth App 支持 PKCE，所以**可以不填 Secret**（Secret 在客户端是不安全的）；代码里只在 Secret 非空时才带上。
4. 权限：一次性申请了 `repo / delete_repo / workflow / admin:org / project / gist / write:packages` 等全部常用 scope（见 `Constants.SCOPES`），你已知晓风险。

## 2. AI 接口配置（OpenAI 兼容）

设置页填 Base URL + 模型名 + Key，实测可直接用：

| 服务 | Base URL | 模型示例 |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` / `gpt-4o` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 月之暗面 | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| 本地 Ollama | `http://192.168.x.x:11434/v1` | `qwen2.5:7b`（Key 留空） |

要求：模型**支持 Function Calling / tools**，否则 AI 只能聊天不能操作仓库。点设置页右上角刷新图标可拉取 `/models` 列表现有模型。

## 3. AI 能用的工具（22 个）

**联网**：`web_search`（DuckDuckGo Lite，失败回退 Bing）、`web_fetch`（Jsoup 抽取正文）

**仓库**：
- `gh_list_repos` `gh_get_repo` `gh_create_repo` `gh_delete_repo`
- `gh_list_dir` `gh_read_file` `gh_write_file` `gh_delete_file` `gh_get_tree`
- `gh_list_branches` `gh_create_branch` `gh_list_commits`
- `gh_list_issues` `gh_create_issue` `gh_comment_issue`
- `gh_list_pulls` `gh_create_pull` `gh_merge_pull`
- `gh_search_code` `gh_search_repos`

安全设计（对齐 Cursor / Copilot Workspace 的习惯）：
- `gh_delete_file` / `gh_delete_repo` / `gh_merge_pull` 属于高危操作，**必须弹窗逐个确认**，用户拒绝后 AI 会收到"用户拒绝"的反馈并改走别的方案
- Token 与 API Key 只存 `EncryptedSharedPreferences`，不写日志、不备份到云端
- 单轮最多 12 步工具调用，工具输出截断到 12000 字符

## 4. 目录结构

```
app/src/main/java/com/kongbai/airepo/
├── auth/        OAuth 2.0 + PKCE（授权 Intent、code 换 token、state 校验）
├── core/        Constants、加密存储 SecureStore
├── data/
│   ├── local/   Room：会话与消息
│   ├── prefs/   DataStore：AI 设置
│   ├── remote/  GitHubService（Retrofit）/ AiClient（OkHttp SSE 流式 + tools）
│   ├── search/  WebSearch：DDG + Bing + 正文抽取
│   ├── tools/   ToolCatalog（22 个工具的 JSON Schema）+ ToolExecutor
│   └── repo/    AgentRepository：ReAct 循环
├── oauth/       OAuthCallbackActivity（airepo://oauth2redirect）
├── ui/          MainActivity / Chat / Repos / Settings / Login + 轻量 Markdown 渲染
└── di/          Hilt 模块（GitHub OkHttp 自动挂 Bearer Token）
```

## 5. 构建

1. Android Studio（Koala 及以上）→ Open → 选本目录
2. 复制 `local.properties.example` 为 `local.properties`，填 `sdk.dir`
3. 注入 Client ID 后 Sync & Run；minSdk 26，targetSdk 34

没有 gradle-wrapper.jar 也没关系，Android Studio 打开时会自动下载 Gradle 8.7；命令行构建可先执行 `gradle wrapper`。

## 6. 典型用法

- "搜一下 Compose BOM 最新稳定版，把 app/build.gradle.kts 的依赖升级并提交到新分支 fix/deps，再开个 PR"
- "读一下 README.md，补一段中文安装说明，commit 用 docs:"
- "这个仓库最近的 10 次提交都改了啥？总结成 Issue 发出来"
- "帮我在 kongbai/demo 建一个 .github/workflows/android.yml，跑 assembleDebug"

## 7. 已知限制

- 代码搜索走 GitHub Search API，仓库需被索引且有权访问，速率限制较严
- 二进制文件（图片等）读写暂不支持，只处理文本
- 流式输出下若模型同时吐文本和工具调用，部分兼容层实现可能只回其中一种，代码已做"流式失败自动退化为非流式"的兜底
