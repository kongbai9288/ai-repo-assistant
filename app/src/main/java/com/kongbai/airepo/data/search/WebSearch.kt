package com.kongbai.airepo.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

data class SearchResultItem(val title: String, val url: String, val snippet: String)

/** 明确区分「成功但没结果」和「彻底失败」，失败原因要回喂给 AI，让它能换办法 */
sealed class SearchOutcome {
    data class Ok(val items: List<SearchResultItem>, val engine: String) : SearchOutcome()
    data class Failed(val reason: String) : SearchOutcome()
}

data class EngineReport(val provider: SearchProvider, val ok: Boolean, val detail: String)

private const val UA_DESKTOP =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
private const val UA_MOBILE =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

@Singleton
class WebSearch @Inject constructor(private val ok: OkHttpClient) {

    /** 主入口：按 provider 走，AUTO 则按链依次尝试，第一个非空结果胜出 */
    suspend fun search(
        query: String,
        maxResults: Int = 5,
        provider: SearchProvider = SearchProvider.AUTO,
        endpoint: String = "",
        apiKey: String = ""
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchOutcome.Failed("搜索词为空")

        val chain = when (provider) {
            SearchProvider.AUTO -> listOf(
                SearchProvider.BING_CN, SearchProvider.BING,
                SearchProvider.DUCKDUCKGO, SearchProvider.SEARX
            )
            else -> listOf(provider)
        }

        val failures = mutableListOf<String>()
        for (p in chain) {
            if (p.needsKey && apiKey.isBlank()) {
                failures.add("${p.label}: 未填 API Key")
                continue
            }
            val (items, err) = runEngine(p, query, maxResults, endpoint, apiKey)
            if (err == null && items.isNotEmpty()) {
                return@withContext SearchOutcome.Ok(items, p.label)
            }
            failures.add("${p.label}: ${err ?: "返回空结果"}")
        }
        SearchOutcome.Failed(
            "全部搜索源都失败了（${failures.joinToString("；")}）。" +
                "可以：① 换关键词重试；② 直接用 web_fetch 抓已知网址；③ 到设置里换搜索源。"
        )
    }

    /** 设置页自检用：把每个源都跑一遍，报出各自状态 */
    suspend fun diagnose(
        query: String = "jetpack compose",
        endpoint: String = "",
        apiKey: String = ""
    ): List<EngineReport> = withContext(Dispatchers.IO) {
        SearchProvider.values().filter { it != SearchProvider.AUTO }.map { p ->
            if (p.needsKey && apiKey.isBlank()) {
                EngineReport(p, false, "未填 API Key")
            } else {
                val (items, err) = runEngine(p, query, 3, endpoint, apiKey)
                if (err == null && items.isNotEmpty()) EngineReport(p, true, "可用，取到 ${items.size} 条")
                else EngineReport(p, false, err ?: "返回空")
            }
        }
    }

    private fun runEngine(
        p: SearchProvider, q: String, n: Int, endpoint: String, key: String
    ): Pair<List<SearchResultItem>, String?> = try {
        val items = when (p) {
            SearchProvider.BING_CN -> bing("https://cn.bing.com/search", q, n)
            SearchProvider.BING -> bing("https://www.bing.com/search", q, n)
            SearchProvider.DUCKDUCKGO -> {
                val a = ddgPost(q, n)
                if (a.isNotEmpty()) a else ddgLite(q, n)
            }
            SearchProvider.SEARX -> searx(endpoint, q, n)
            SearchProvider.TAVILY -> tavily(q, n, key)
            SearchProvider.SERPER -> serper(q, n, key)
            SearchProvider.BOCHA -> bocha(q, n, key)
            SearchProvider.AUTO -> emptyList()
        }
        items to null
    } catch (e: UnknownHostException) {
        emptyList<SearchResultItem>() to "域名解析失败（网络不通或该域名被屏蔽）"
    } catch (e: SocketTimeoutException) {
        emptyList<SearchResultItem>() to "请求超时"
    } catch (e: IOException) {
        emptyList<SearchResultItem>() to "网络错误：${e.message ?: "IO 异常"}"
    } catch (e: Exception) {
        emptyList<SearchResultItem>() to "解析/请求异常：${e.message ?: e.javaClass.simpleName}"
    }

    // ---------- 具体实现 ----------

    private fun bing(base: String, q: String, n: Int): List<SearchResultItem> {
        val url = "$base?q=" + URLEncoder.encode(q, "UTF-8") +
            "&setlang=zh-CN&count=$n"
        val html = get(url) ?: return emptyList()
        val doc = Jsoup.parse(html)
        val out = mutableListOf<SearchResultItem>()
        for (li in doc.select("li.b_algo")) {
            val a = li.selectFirst("h2 a") ?: li.selectFirst("a[href^=http]") ?: continue
            val href = a.attr("abs:href")
            val title = a.text().trim()
            if (href.isBlank() || title.isBlank()) continue
            val snip = li.selectFirst(".b_caption p")?.text()?.trim()
                ?: li.selectFirst(".b_lineclamp1, .b_lineclamp2, .b_lineclamp3, .b_lineclamp4")?.text()?.trim()
                ?: li.selectFirst("p")?.text()?.trim().orEmpty()
            out.add(SearchResultItem(title.take(160), href, snip.take(400)))
            if (out.size >= n) break
        }
        return out
    }

    private fun ddgPost(q: String, n: Int): List<SearchResultItem> {
        val body = FormBody.Builder().add("q", q).add("b", "").build()
        val req = Request.Builder().url("https://html.duckduckgo.com/html/")
            .addHeader("User-Agent", UA_DESKTOP)
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9")
            .post(body).build()
        val html = exec(req) ?: return emptyList()
        return parseDdg(html, n)
    }

    private fun ddgLite(q: String, n: Int): List<SearchResultItem> {
        val html = get("https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(q, "UTF-8"))
            ?: return emptyList()
        val out = mutableListOf<SearchResultItem>()
        val doc = Jsoup.parse(html)
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href")
            if (href.isBlank() || href.contains("duckduckgo.com")) continue
            val title = a.text().trim()
            if (title.length < 3) continue
            val snip = a.parent()?.parent()?.text()?.trim().orEmpty()
            out.add(SearchResultItem(title.take(160), href, snip.take(400)))
            if (out.size >= n) break
        }
        return out
    }

    private fun parseDdg(html: String, n: Int): List<SearchResultItem> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<SearchResultItem>()
        for (r in doc.select(".result, .web-result")) {
            val a = r.selectFirst(".result__a, a[href^=http]") ?: continue
            val href = a.attr("abs:href")
            val title = a.text().trim()
            if (href.isBlank() || title.isBlank()) continue
            val snip = r.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            out.add(SearchResultItem(title.take(160), href, snip.take(400)))
            if (out.size >= n) break
        }
        if (out.isEmpty()) {
            for (a in doc.select("a[href^=http]")) {
                val href = a.attr("abs:href")
                if (href.contains("duckduckgo.com")) continue
                val t = a.text().trim()
                if (t.length < 3) continue
                out.add(SearchResultItem(t.take(160), href, ""))
                if (out.size >= n) break
            }
        }
        return out
    }

    private fun searx(endpoint: String, q: String, n: Int): List<SearchResultItem> {
        val base = endpoint.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        val url = "$base/search?q=" + URLEncoder.encode(q, "UTF-8") + "&format=json&language=zh-CN"
        val raw = get(url) ?: return emptyList()
        val arr = JSONObject(raw).optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<SearchResultItem>()
        for (i in 0 until minOf(arr.length(), n)) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SearchResultItem(
                    o.optString("title").take(160),
                    o.optString("url"),
                    o.optString("content").take(400)
                )
            )
        }
        return out
    }

    private fun tavily(q: String, n: Int, key: String): List<SearchResultItem> {
        val json = JSONObject().apply {
            put("api_key", key)
            put("query", q)
            put("max_results", n)
            put("search_depth", "basic")
        }.toString()
        val req = Request.Builder().url("https://api.tavily.com/search")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.Companion.create(json, null)).build()
        val raw = exec(req) ?: return emptyList()
        val arr = JSONObject(raw).optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<SearchResultItem>()
        for (i in 0 until minOf(arr.length(), n)) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SearchResultItem(
                    o.optString("title").take(160),
                    o.optString("url"),
                    o.optString("content").take(400)
                )
            )
        }
        return out
    }

    private fun serper(q: String, n: Int, key: String): List<SearchResultItem> {
        val json = JSONObject().apply { put("q", q); put("num", n) }.toString()
        val req = Request.Builder().url("https://google.serper.dev/search")
            .addHeader("X-API-KEY", key)
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.Companion.create(json, null)).build()
        val raw = exec(req) ?: return emptyList()
        val arr = JSONObject(raw).optJSONArray("organic") ?: JSONArray()
        val out = mutableListOf<SearchResultItem>()
        for (i in 0 until minOf(arr.length(), n)) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SearchResultItem(
                    o.optString("title").take(160),
                    o.optString("link"),
                    o.optString("snippet").take(400)
                )
            )
        }
        return out
    }

    private fun bocha(q: String, n: Int, key: String): List<SearchResultItem> {
        val json = JSONObject().apply {
            put("query", q)
            put("count", n)
            put("summary", true)
        }.toString()
        val req = Request.Builder().url("https://api.bochaai.com/v1/web-search")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.Companion.create(json, null)).build()
        val raw = exec(req) ?: return emptyList()
        val arr = JSONObject(raw).optJSONObject("data")
            ?.optJSONArray("webPages")?.optJSONObject(0)?.optJSONArray("value") ?: JSONArray()
        val out = mutableListOf<SearchResultItem>()
        for (i in 0 until minOf(arr.length(), n)) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SearchResultItem(
                    o.optString("name").take(160),
                    o.optString("url"),
                    o.optString("summary").ifBlank { o.optString("snippet") }.take(400)
                )
            )
        }
        return out
    }

    /** 抓取网页正文，供 AI 阅读；失败同样返回可诊断的原因 */
    suspend fun fetch(url: String, maxChars: Int = 8000): String = withContext(Dispatchers.IO) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return@withContext "URL 不合法：$u"
        try {
            val req = Request.Builder().url(u)
                .addHeader("User-Agent", UA_DESKTOP)
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get().build()
            ok.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext "抓取失败：HTTP ${r.code} $u"
                val ct = r.header("Content-Type").orEmpty()
                val body = r.body?.string().orEmpty()
                if (ct.contains("json") && !ct.contains("html")) {
                    return@withContext "JSON 响应：\n" + body.take(maxChars)
                }
                val doc = Jsoup.parse(body, u)
                doc.select("script,style,nav,footer,header,aside,form,iframe,noscript").remove()
                val text = Jsoup.clean(doc.body().html(), "", Safelist.none())
                    .replace(Regex("[ \\t]+"), " ")
                    .replace(Regex("\n{3,}"), "\n\n").trim()
                if (text.isBlank()) "页面无可读文本（可能是纯 JS 渲染或需要登录）：$u"
                else "来源：$u\n" + text.take(maxChars)
            }
        } catch (e: UnknownHostException) {
            "抓取失败：域名解析不了 $u（网络不通或被屏蔽）"
        } catch (e: SocketTimeoutException) {
            "抓取失败：超时 $u"
        } catch (e: Exception) {
            "抓取失败：${e.message ?: e.javaClass.simpleName} $u"
        }
    }

    // ---------- 底层 HTTP ----------

    private fun get(url: String): String? {
        val req = Request.Builder().url(url)
            .addHeader("User-Agent", UA_DESKTOP)
            .addHeader("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get().build()
        return exec(req)
    }

    private fun exec(req: Request): String? {
        ok.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }
}
