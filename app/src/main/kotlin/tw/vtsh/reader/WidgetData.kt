package tw.vtsh.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 小部件数据层：
 *   · 今日公告 → 当日日期归档 /YYYY/MM/DD/feed/（一请求即当天全部）
 *   · 缴费提醒 → 官网搜索 "?s=繳費&feed=rss2"，按发布时间取最近三条
 * 结果写进 SharedPreferences，RemoteViews 直接读，刷新不必再解析。
 */
object WidgetData {

    private const val SP = "vtsh_widget"
    private val zone = ZoneId.of("Asia/Taipei")

    data class Entry(val title: String, val unit: String, val date: String, val link: String)

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 后台刷新；返回是否抓到数据（离线时保留旧值） */
    suspend fun refresh(ctx: Context): Boolean {
        val today = LocalDate.now(zone)
        var ok = false

        // 当天常无公告（学校多在上、下午发），回退找最近 3 天，避免小部件整块空白。
        // 走 MonthRepo.day：单日超过 10 则时会翻页取全（实测 9/24 有 15 则）
        var items: List<Item>? = null
        var date: LocalDate = today
        for (back in 0..3) {
            val d = today.minusDays(back.toLong())
            val got = runCatching { MonthRepo.day(ctx, d) }.getOrNull()
            if (got == null) break            // 网络不通，保留旧值
            if (got.isNotEmpty()) { items = got; date = d; break }
        }
        items?.let {
            val label = if (date == today) "今日" else
                "${date.monthValue}/${date.dayOfMonth}"
            write(ctx, "today", it.take(3).map { n -> entry(n) })
            write(ctx, "today_total", it.size.toString())
            write(ctx, "today_label", label)
            ok = true
        }

        // 缴费：搜索通道直接命中，不必扫全量 feed 关键字
        if (Prefs.get(ctx).feeAlerts) {
            runCatching {
                Rss.fetch(Rss.searchUrl("繳費")).filter { n ->
                    n.title.contains("繳") && n.title.contains("費") ||
                        n.title.contains("費用") || n.title.contains("註冊費")
                }.sortedByDescending { it.date }
            }.getOrNull()?.let { items ->
                write(ctx, "fee", items.take(3).map { entry(it) })
                ok = true
            }
        }

        sp(ctx).edit().putLong("updated", System.currentTimeMillis()).apply()
        return ok
    }

    private fun write(ctx: Context, key: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("t", e.title).put("u", e.unit)
                .put("d", e.date).put("l", e.link))
        }
        sp(ctx).edit().putString(key, arr.toString()).apply()
    }

    private fun write(ctx: Context, key: String, value: String) =
        sp(ctx).edit().putString(key, value).apply()

    fun todayEntries(ctx: Context): List<Entry> = read(ctx, "today")
    fun feeEntries(ctx: Context): List<Entry> = read(ctx, "fee")
    fun todayCount(ctx: Context): String = sp(ctx).getString("today_total", "0") ?: "0"

    /** 「今日」或回退后的实际日期标签 */
    fun todayLabel(ctx: Context): String = sp(ctx).getString("today_label", "今日") ?: "今日"

    private fun read(ctx: Context, key: String): List<Entry> = runCatching {
        val arr = JSONArray(sp(ctx).getString(key, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("t"), o.getString("u"), o.getString("d"), o.getString("l"))
        }
    }.getOrDefault(emptyList())

    fun timeLabel(ctx: Context): String {
        val ms = sp(ctx).getLong("updated", 0L)
        if (ms == 0L) return "尚未更新"
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return "更新 ${t.monthValue}/${t.dayOfMonth} %02d:%02d".format(t.hour, t.minute)
    }

    fun entry(i: Item): Entry = Entry(
        title = i.title,
        unit = i.unit,
        date = i.date?.withZoneSameInstant(zone)?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "",
        link = i.link,
    )
}
