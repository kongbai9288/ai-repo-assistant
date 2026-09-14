package com.kongbai.airepo.data.search

/** 搜索后端。国内网络下 Bing 最稳，DDG 常被拦，所以默认链是 bing_cn → bing → ddg_html → ddg_lite */
enum class SearchProvider(
    val label: String,
    val needsKey: Boolean,
    val needsEndpoint: Boolean,
    val note: String
) {
    AUTO("自动（依次尝试）", false, false, "bing_cn → bing → duckduckgo，任一成功即用"),
    BING_CN("Bing 国内", false, false, "cn.bing.com，国内直连最稳"),
    BING("Bing 国际", false, false, "www.bing.com"),
    DUCKDUCKGO("DuckDuckGo", false, false, "html/lite 端点，境外网络更稳"),
    SEARX("SearXNG 实例", false, true, "填你自己或公共实例，如 https://searx.be"),
    TAVILY("Tavily API", true, false, "https://api.tavily.com，注册送额度"),
    SERPER("Serper API", true, false, "https://google.serper.dev"),
    BOCHA("博查 AI 搜索", true, false, "https://api.bochaai.com，国内可用")
}
