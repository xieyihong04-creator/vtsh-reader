package tw.vtsh.reader

import okhttp3.Request
import java.util.zip.ZipFile

data class Attachment(
    val name: String,
    val url: String,
    val size: String,
) {
    enum class Kind { PDF, DOC, SHEET, SLIDE, IMAGE, ZIP, OTHER }
    val kind: Kind get() = when (url.substringAfterLast('.').substringBefore('?').lowercase()) {
        "pdf" -> Kind.PDF
        "doc", "docx" -> Kind.DOC
        "xls", "xlsx", "csv" -> Kind.SHEET
        "ppt", "pptx" -> Kind.SLIDE
        "jpg", "jpeg", "png", "gif", "webp" -> Kind.IMAGE
        "zip", "rar", "7z" -> Kind.ZIP
        else -> Kind.OTHER
    }
}

data class Article(
    val title: String,
    val subject: String,
    val unit: String,
    val date: String,
    val paras: List<String>,
    val images: List<String>,
    val attachments: List<Attachment>,
    val link: String,
)

/**
 * 文章抓取与解析（结构已对真实页面验证）：
 * - 标题：<title> 去掉站名后缀
 * - 元数据表：newsth/newstd 键值对（公告类别、发布单位、发布日期）
 * - 附件：官网 HTML 自带 modal 表格 —— modal_name 原始档名、
 *   modal_size 大小、modal_download 内真实直链（P0 验证可 Range 直读），
 *   不依赖 file_link 的 JS 占位锚点
 * - 正文：公告內容 td 内的 .content 块
 */
object ArticleRepo {

    fun fetch(url: String): Article {
        val html = NetClient.http.newCall(
            Request.Builder().url(url).header("User-Agent", NetClient.UA).build()
        ).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body!!.string()
        }
        return parse(html, url)
    }

    private val pageTitleRe = Regex("<title>(.*?)\\s*\\|\\s*衛道高級中學</title>")
    private val kvRe = Regex(
        "<th[^>]*class=\"newsth\"[^>]*>(.*?)</th>\\s*<td[^>]*class=\"newstd\"[^>]*>(.*?)</td>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val attRe = Regex(
        """modal_name"[^>]*>([^<]+)</span>.*?modal_size"[^>]*>([^<]+)</span>.*?modal_download"[^>]*>\s*<a href="(https://[^"]+)"""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val imgRe = Regex("<img[^>]+src=\"([^\"]+)\"")
    private val fileUrlRe = Regex(
        """(https://www\.vtsh\.tc\.edu\.tw/wp-content/uploads/[^"'\\\s)]+?\.(?:pdf|docx?|xlsx?|pptx?|zip|rar))""",
        RegexOption.IGNORE_CASE
    )

    fun parse(html: String, url: String): Article {
        val title = pageTitleRe.find(html)?.groupValues?.get(1)?.trim()
            ?: "文章"

        var subject = ""; var unit = ""; var date = ""; var body = ""
        kvRe.findAll(html).forEach { m ->
            val k = stripTags(m.groupValues[1]).trim()
            val v = m.groupValues[2]
            when {
                k.contains("主旨") -> subject = stripTags(v).trim()
                k.contains("單位") -> unit = stripTags(v).trim()
                k.contains("日期") -> date = stripTags(v).trim().take(10)
                k.contains("內容") -> body = v
            }
        }
        // 正文剔掉附件弹窗噪音（modal 区），只留说明文字
        body = body.substringBefore("<div id=\"", body)

        // 附件：优先 modal 表格；无则退回全文扫 uploads 文件直链
        var atts = attRe.findAll(html).map { m ->
            Attachment(
                name = decode(m.groupValues[1].trim()),
                size = m.groupValues[2].trim(),
                url = unescAmp(m.groupValues[3]),
            )
        }.toList()
        if (atts.isEmpty()) {
            val seen = LinkedHashSet<String>()
            fileUrlRe.findAll(html).forEach { seen.add(it.groupValues[1]) }
            atts = seen.map { Attachment(decode(it.substringAfterLast('/')), "", it) }
        }
        // 去重（同一档案可能多处出现）
        atts = atts.distinctBy { it.url }

        val imgs = imgRe.findAll(body).map { it.groupValues[1] }
            .filter { u ->
                val e = u.substringAfterLast('.', "").lowercase()
                e in setOf("jpg", "jpeg", "png", "gif", "webp") && !u.contains("icon")
            }.toList()
        val paras = body
            .replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</(p|div|li|tr|h[1-6])>"), "\n")
            .let { stripTags(it) }
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        return Article(title, subject, unit, date, paras, imgs, atts, url)
    }

    private fun unescAmp(s: String) = s.replace("&#038;", "&").replace("&amp;", "&")
    private fun decode(s: String) =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun stripTags(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#8211;", "–").replace("&#8217;", "’").replace("&#8216;", "‘")
        .replace("&#8220;", "“").replace("&#8221;", "”").replace("&#8230;", "…")
        .replace(Regex("\\s{2,}"), " ")
}
