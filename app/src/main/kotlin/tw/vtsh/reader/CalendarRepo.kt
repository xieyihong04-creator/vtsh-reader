package tw.vtsh.reader

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.regex.Pattern

/** 一条校历事件 */
data class SchEvent(val date: LocalDate, val dept: String?, val text: String)

/**
 * 學校行事曆（运行时方案，方案 B）。
 *
 * 官网把整学期校历塞进一个 Word 导出的 PDF（`category/calendar` 分类唯一文章
 * 正文里的 `wp-content/.../<学期>行事曆_學生版.pdf`），没有结构化接口。
 * 这里不硬编码学期文件名，而是每次先抓 feed 从正文里正则出当前 PDF 直链——
 * 学校换学期只改 PDF，App 无需更新即可跟到最新资料。
 *
 * 解析策略：PDFTextStripper 走 setSortByPosition，把每行的
 * (page, y, x, text) 抽出来；每页内按 y 聚类成视觉行，x<110 的两个整数是
 * 「月 / 日」锚点，x≈122 起的是事件文本；某天的事件会同时印在其表头行的
 * 上方和下方（Word 表格行高、表头垂直居中），因此按"距离最近日期锚点"归日。
 * 星期字段用真实历法回推（PDF 里那个字可能因为版式错位）。
 */
object CalendarRepo {

    private const val FEED = "${NetClient.BASE}/category/calendar/feed/"
    private val PDF_RE = Pattern.compile("https://[^\"'<>\\s]+?\\.pdf")
    private val DEPT_RE = Pattern.compile("^(?:\\d+\\.)?\\s*(?:\\[([^\\]]{1,6})])?\\s*(.*)$")
    private val SEM_RE = Pattern.compile("(\\d{2,3})\\s*-\\s*(\\d)")
    private val PURE_INT = Pattern.compile("^\\d{1,2}$")
    private const val SIDEBAR = "暑輔第週行政"
    private const val Y_EPS = 4.0f          // 视觉行 y 聚类容差（pt）
    private const val ANCHOR_MAX_X = 100.0f // 月/日数字列上界
    private const val WD_MAX_X = 118.0f     // 星期列右界；再右即事件文本列
    private const val WD_CHARS = "日一二三四五六"

    private fun dir(ctx: Context) = File(ctx.cacheDir, "cal").apply { mkdirs() }
    private fun pdfFile(ctx: Context) = File(dir(ctx), "cal.pdf")
    private fun jsonFile(ctx: Context) = File(dir(ctx), "cal.json")
    private fun metaFile(ctx: Context) = File(dir(ctx), "cal.meta")

    fun init(ctx: Context) {
        runCatching { PDFBoxResourceLoader.init(ctx.applicationContext) }
    }

    fun loadCached(ctx: Context): List<SchEvent>? = runCatching {
        if (!jsonFile(ctx).exists()) return null
        readJson(jsonFile(ctx).readText())
    }.getOrNull()

    /**
     * 刷新校历。force=false 时，只要今天已刷过（meta 里 key+日期都是当天）就复用缓存。
     * 网络/解析失败但有旧缓存时返回旧缓存；两者都没就抛异常。
     */
    suspend fun refresh(ctx: Context, force: Boolean): List<SchEvent> {
        val url = try { findPdfUrl() } catch (e: Exception) {
            return loadCached(ctx) ?: throw e
        }
        val key = NetClient.cacheKey(url)
        val fresh = !force && metaFile(ctx).exists() &&
            metaFile(ctx).readText().trim() == "$key|${today()}" &&
            jsonFile(ctx).exists()
        if (fresh) loadCached(ctx)?.let { return it }

        val pdf: File = run {
            try { downloadPdf(ctx, url, key) }
            catch (e: Exception) { return loadCached(ctx) ?: throw e }
        }
        val events: List<SchEvent> = run {
            try { parse(pdf) }
            catch (e: Exception) { return loadCached(ctx) ?: throw e }
        }

        runCatching {
            writeJson(jsonFile(ctx), events)
            metaFile(ctx).writeText("$key|${today()}")
        }
        return events
    }

    private fun findPdfUrl(): String {
        NetClient.http.newCall(Request.Builder().url(FEED).header("User-Agent", NetClient.UA).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body!!.byteStream().reader(Charsets.UTF_8).readText()
                val m = PDF_RE.matcher(body)
                if (m.find()) return m.group()
                error("找不到行事曆附件")
            }
        error("unreachable")
    }

    private fun downloadPdf(ctx: Context, url: String, key: String): File {
        val f = pdfFile(ctx)
        // 只有 meta 里的 key 对得上当前 URL，才认为本地 PDF 就是最新的
        val samePdf = f.exists() && f.length() > 0 &&
            metaFile(ctx).exists() && metaFile(ctx).readText().trim().startsWith(key)
        if (samePdf) return f
        NetClient.http.newCall(Request.Builder().url(url).header("User-Agent", NetClient.UA).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                f.outputStream().use { resp.body!!.byteStream().copyTo(it) }
            }
        return f
    }

    /** 一行的几何信息 */
    private class Row { var page = 0; var y = 0f; var x = 0f; lateinit var text: String }

    private fun parse(pdf: File): List<SchEvent> {
        val rows = ArrayList<Row>()
        var curPage = 0
        PDDocument.load(pdf).use { doc ->
            val stripper = object : PDFTextStripper() {
                override fun writePageStart() { curPage++ }
                override fun writeString(s: String, positions: MutableList<TextPosition>) {
                    if (positions.isEmpty()) return
                    val f = positions[0]
                    val r = Row()
                    r.page = curPage
                    r.y = f.yDirAdj
                    r.x = f.xDirAdj
                    val sb = StringBuilder()
                    for (p in positions) sb.append(p.unicode)
                    r.text = sb.toString()
                    rows.add(r)
                }
            }
            stripper.setSortByPosition(true)
            stripper.setStartPage(1)
            stripper.setEndPage(doc.numberOfPages)
            stripper.getText(doc) // 触发 writeString 收集 rows（返回值不用）
        }

        val (startYear, isFall) = inferSemester(rows.firstOrNull { it.page == 1 }?.text ?: "")

        // 按 (page, y) 排序，页内按 y 聚类
        rows.sortWith(compareBy({ it.page }, { it.y }))
        val dayEvents = HashMap<LocalDate, ArrayList<String>>()
        var i = 0
        while (i < rows.size) {
            val pg = rows[i].page
            var j = i
            while (j < rows.size && rows[j].page == pg) j++
            val pageRows = rows.subList(i, j)

            val groups = ArrayList<Triple<Float, Float, ArrayList<Row>>>() // (ymin,ymax,rows)
            for (r in pageRows) {
                val g = groups.lastOrNull()
                if (g != null && Math.abs(r.y - g.first) <= Y_EPS) {
                    groups[groups.size - 1] = Triple(g.first, r.y, ArrayList(g.third).apply { add(r) })
                } else groups.add(Triple(r.y, r.y, arrayListOf(r)))
            }

            // 抽锚点（月/日/星期）+ 事件行
            val anchors = ArrayList<Triple<Float, String, String>>() // y, "mo/dd", 星期
            val events = ArrayList<Pair<Float, String>>()
            for (g in groups) {
                val gRows = g.third.sortedBy { it.x }
                val nums = gRows.filter {
                    it.x < ANCHOR_MAX_X && PURE_INT.matcher(it.text.trim()).matches()
                }
                val wdTok = gRows.firstOrNull {
                    it.x >= ANCHOR_MAX_X && it.x < WD_MAX_X && WD_CHARS.contains(it.text.trim())
                }?.text?.trim() ?: ""
                if (nums.size >= 2) {
                    val mo = nums[0].text.trim().toInt()
                    val dd = nums[1].text.trim().toInt()
                    if (mo in 1..12 && dd in 1..31)
                        anchors.add(Triple(g.first, "$mo/$dd", wdTok))
                }
                for (r in gRows) {
                    if (r.x < WD_MAX_X) continue
                    val t = r.text.trim()
                    if (t.isNotEmpty()) events.add(g.first to t)
                }
            }

            if (anchors.isEmpty()) { i = j; continue }
            for ((ey, t) in events) {
                if (t.length <= 3 && t.all { it in SIDEBAR }) continue
                if (t.contains("學生活動") || t.contains("事務")) continue
                val a = anchors.minByOrNull { Math.abs(it.first - ey) } ?: continue
                val d = resolveDate(startYear, isFall, a.second, a.third) ?: continue
                dayEvents.getOrPut(d) { ArrayList() }.add(t)
            }
            i = j
        }

        // 展平成 SchEvent（同日同文本去重）
        val out = ArrayList<SchEvent>()
        for ((d, list) in dayEvents) {
            val seen = HashSet<String>()
            for (raw in list) {
                if (!seen.add(raw)) continue
                val m = DEPT_RE.matcher(raw)
                if (!m.matches()) continue
                val dept = m.group(1)?.trim()?.takeIf { it.isNotEmpty() }
                val txt = m.group(2)?.trim().orEmpty()
                if (txt.isEmpty()) continue
                out.add(SchEvent(d, dept, txt))
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.text }))
    }

    /**
     * 定年：以 PDF 印出的「星期」为铁证回推年份（同一 (月,日) 只有唯一年会让星期成立），
     * 再用标题学年把候选限定在 ±1 年窗口内，防止跳到多年外。
     * 星期字段拿不到时退回「上学期 8 月起 = startYear，隔年 1~7 月 = startYear+1」。
     */
    private fun resolveDate(startYear: Int, isFall: Boolean, moDay: String, wd: String): LocalDate? {
        val slash = moDay.indexOf('/')
        val mo = moDay.substring(0, slash).toInt()
        val dd = moDay.substring(slash + 1).toInt()
        val nominal = if (isFall) (if (mo >= 7) startYear else startYear + 1) else startYear
        val widx = if (wd.isNotEmpty()) WD_CHARS.indexOf(wd) else -1
        if (widx >= 0) {
            for (off in 0..1) {
                for (y in listOf(nominal - off, nominal + off)) {
                    val d = runCatching { LocalDate.of(y, mo, dd) }.getOrNull() ?: continue
                    if (d.dayOfWeek.value % 7 == widx) return d
                }
            }
        }
        return runCatching { LocalDate.of(nominal, mo, dd) }.getOrNull()
    }

    /** 从标题里的民国学年（如 115-1）推出学期起始日历年与是否上学期 */
    private fun inferSemester(title: String): Pair<Int, Boolean> {
        val m = SEM_RE.matcher(title)
        if (m.find()) {
            val roc = m.group(1).toInt()
            val sem = m.group(2).toInt()
            val y = 1911 + roc            // 115 → 2026（学年起始年，秋季学期起）
            return if (sem == 1) y to true else (y + 1) to false
        }
        val now = LocalDate.now(ZoneId.of("Asia/Taipei"))
        return (if (now.monthValue >= 7) now.year else now.year - 1) to (now.monthValue >= 2)
    }

    private fun today(): String = LocalDate.now(ZoneId.of("Asia/Taipei")).toString()

    private fun writeJson(f: File, events: List<SchEvent>) {
        val arr = JSONArray()
        events.forEach {
            arr.put(JSONObject().apply {
                put("d", it.date.toString())
                put("u", it.dept ?: "")
                put("t", it.text)
            })
        }
        f.writeText(arr.toString())
    }

    private fun readJson(s: String): List<SchEvent> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SchEvent(
                LocalDate.parse(o.getString("d")),
                o.optString("u").ifBlank { null },
                o.getString("t"),
            )
        }
    }
}
