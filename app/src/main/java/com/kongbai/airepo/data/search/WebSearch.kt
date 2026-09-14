package com.kongbai.airepo.data.search

import com.kongbai.airepo.core.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import javax.inject.Inject
import javax.inject.Singleton

data class SearchResultItem(val title: String, val url: String, val snippet: String)

@Singleton
class WebSearch @Inject constructor(private val ok: OkHttpClient) {

    /** DuckDuckGo Lite：无需 Key，适合客户端直连；失败时回退 Bing */
    suspend fun search(query: String, maxResults: Int = 5): List<SearchResultItem> =
        withContext(Dispatchers.IO) {
            runCatching { ddg(query, maxResults) }.getOrElse {
                runCatching { bing(query, maxResults) }.getOrDefault(emptyList())
            }
        }

    private fun ddg(query: String, maxResults: Int): List<SearchResultItem> {
        val url = "https://lite.duckduckgo.com/lite/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = get(url) ?: return emptyList()
        val doc = Jsoup.parse(html)
        val out = mutableListOf<SearchResultItem>()
        val links = doc.select("a[href]")
        for (a in links) {
            val href = a.attr("abs:href")
            if (href.isBlank() || href.contains("duckduckgo.com")) continue
            val title = a.text().trim()
            if (title.isBlank() || title.length < 3) continue
            var snip = ""
            var n = a.parent()?.nextElementSibling()
            var guard = 0
            while (n != null && snip.isBlank() && guard++ < 3) { snip = n.text().trim(); n = n.nextElementSibling() }
            if (snip.isBlank()) snip = a.parent()?.text()?.trim().orEmpty()
            out.add(SearchResultItem(title.take(160), href, snip.take(400)))
            if (out.size >= maxResults) break
        }
        return out
    }

    private fun bing(query: String, maxResults: Int): List<SearchResultItem> {
        val url = "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&count=$maxResults"
        val html = get(url) ?: return emptyList()
        val doc = Jsoup.parse(html)
        val out = mutableListOf<SearchResultItem>()
        doc.select("li.b_algo").forEach { li ->
            val a = li.selectFirst("h2 a") ?: return@forEach
            val t = a.text().trim()
            val u = a.attr("abs:href")
            val s = li.selectFirst(".b_caption p")?.text()?.trim().orEmpty()
            if (t.isNotBlank() && u.isNotBlank()) out.add(SearchResultItem(t.take(160), u, s.take(400)))
            if (out.size >= maxResults) return@forEach
        }
        return out
    }

    /** 抓取网页纯文本，供 AI 阅读 */
    suspend fun fetch(url: String, maxChars: Int = 6000): String = withContext(Dispatchers.IO) {
        val html = get(url) ?: return@withContext "抓取失败：无法获取 $url"
        val doc = Jsoup.parse(html)
        doc.select("script,style,nav,footer,header,aside,form").remove()
        val text = Jsoup.clean(doc.body().html(), "", Safelist.none())
            .replace(Regex("\n{3,}"), "\n\n").trim()
        text.take(maxChars)
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36")
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get().build()
        ok.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }
}
