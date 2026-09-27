package tw.vtsh.reader

import android.util.Xml
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class Item(
    val title: String,
    val link: String,
    val unit: String,
    val date: ZonedDateTime?,
    val cats: List<String>,
    val hasAtt: Boolean,
)

/** 带 HTTP 状态码的异常：404 对日期归档 feed 意味着「该期无内容」，不是连线失败 */
class HttpStatusException(val code: Int) : Exception("HTTP $code")

/** WordPress RSS 通道：官网唯一可用的结构化数据口（REST 被 WAF 403） */
object Rss {
    val CHANNELS = listOf(
        "全部" to "${NetClient.BASE}/category/news/feed/",
        "行政公告" to "${NetClient.BASE}/category/news/news01/feed/",
        "招生訊息" to "${NetClient.BASE}/category/news/news02/feed/",
        "營隊資訊" to "${NetClient.BASE}/category/news/news03/feed/",
        "榮譽榜" to "${NetClient.BASE}/category/news/news04/feed/",
        "活動競賽" to "${NetClient.BASE}/category/news/news05/feed/",
        "升學資訊" to "${NetClient.BASE}/category/news/news10/feed/",
        "獎助學金" to "${NetClient.BASE}/category/news/news06/feed/",
        "教師研習" to "${NetClient.BASE}/category/news/news08/feed/",
        "衛教園地" to "${NetClient.BASE}/category/news/news11/feed/",
        "來文宣導" to "${NetClient.BASE}/category/news/news12/feed/",
        "新生專區" to "${NetClient.BASE}/category/enroll/feed/",
        "總務處" to "${NetClient.BASE}/category/office/div_400/feed/",
    )

    fun searchUrl(q: String) =
        "${NetClient.BASE}/?s=${java.net.URLEncoder.encode(q, "UTF-8")}&feed=rss2"

    /** WP 日期归档 feed：官网除分类 feed 外唯一稳定的结构化入口，支持 ?paged=N */
    fun dayFeed(y: Int, m: Int, d: Int) = "${NetClient.BASE}/$y/%02d/%02d/feed/".format(m, d)

    /** paged 从 1 起：WP feed 支持 ?paged=N（P0 实测） */
    fun fetch(url: String, paged: Int = 1): List<Item> {
        val full = if (paged > 1) "$url?paged=$paged" else url
        NetClient.http.newCall(
            Request.Builder().url(full).header("User-Agent", NetClient.UA).build()
        ).execute().use { resp ->
            if (resp.code == 404) throw HttpStatusException(404)
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body!!.byteStream().use { return parse(it) }
        }
    }

    private fun parse(input: java.io.InputStream): List<Item> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val items = mutableListOf<Item>()
        var inItem = false
        var title = ""; var link = ""; var creator = ""; var pub = ""
        var body = ""; val cats = mutableListOf<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""; link = ""; creator = ""; pub = ""; body = ""
                        cats.clear()
                    }
                    "title" -> if (inItem) title = parser.nextText()
                    "link" -> if (inItem && link.isEmpty()) link = parser.nextText()
                    "dc:creator" -> if (inItem) creator = parser.nextText()
                    "pubDate" -> if (inItem) pub = parser.nextText()
                    "category" -> if (inItem) cats.add(parser.nextText())
                    "content:encoded" -> if (inItem) body = parser.nextText()
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" && inItem) {
                    inItem = false
                    if (title.isNotBlank()) {
                        items.add(
                            Item(
                                title = unescape(title.trim()),
                                link = link.trim(),
                                unit = creator.trim(),
                                date = zoned(pub.trim()),
                                cats = cats.toList(),
                                hasAtt = body.contains("file_link"),
                            )
                        )
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    // &amp; 必须最后替换：否则原文里的字面 "&amp;lt;" 会被二次解码成 "<"
    private fun unescape(s: String): String = s
        .replace("&#8211;", "–").replace("&#8217;", "’").replace("&#8216;", "‘")
        .replace("&#8220;", "“").replace("&#8221;", "”").replace("&#8230;", "…")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun zoned(s: String): ZonedDateTime? = try {
        ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
    } catch (_: Exception) { null }

    // ---- 极简 JSON 往返（月历日缓存复用，避免再引序列化库）----

    fun toJson(list: List<Item>): String = org.json.JSONArray().apply {
        list.forEach { i ->
            put(org.json.JSONObject().apply {
                put("t", i.title); put("l", i.link); put("u", i.unit)
                put("d", i.date?.toEpochSecond()?.toString() ?: "")
                put("c", org.json.JSONArray(i.cats)); put("a", i.hasAtt)
            })
        }
    }.toString()

    fun parseJson(s: String): List<Item> {
        val arr = org.json.JSONArray(s)
        val zone = java.time.ZoneId.of("Asia/Taipei")
        return (0 until arr.length()).map { n ->
            val o = arr.getJSONObject(n)
            Item(
                title = o.getString("t"), link = o.getString("l"), unit = o.getString("u"),
                date = o.optString("d").toLongOrNull()
                    ?.let { java.time.Instant.ofEpochSecond(it).atZone(zone) },
                cats = o.getJSONArray("c").let { c -> (0 until c.length()).map { c.getString(it) } },
                hasAtt = o.getBoolean("a"),
            )
        }
    }
}
