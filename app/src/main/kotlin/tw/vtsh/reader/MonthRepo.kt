package tw.vtsh.reader

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * 单日公告数据层，走 WordPress 日期归档 /YYYY/MM/DD/feed/。
 * 供桌面小工具（WidgetData）取当天/近三日内容。
 */
object MonthRepo {

    private fun dayFile(ctx: Context, d: LocalDate) =
        File(ctx.cacheDir, "rss/day-$d.json").apply { parentFile?.mkdirs() }

    private fun loadDay(ctx: Context, d: LocalDate): List<Item>? = runCatching {
        val f = dayFile(ctx, d)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 24 * 3600_000) return null
        Rss.parseJson(f.readText())
    }.getOrNull()

    /**
     * 单日公告。归档 feed 每页仍是 10 条（实测 9/24 有 15 条），
     * 所以第 1 页满 10 条时继续翻页，直到空页或 404。
     */
    suspend fun day(ctx: Context, d: LocalDate): List<Item> {
        loadDay(ctx, d)?.let { return it }
        val url = Rss.dayFeed(d.year, d.monthValue, d.dayOfMonth)
        val all = mutableListOf<Item>()
        var firstPageFailed = false
        for (p in 1..6) {
            val page: List<Item>? = try {
                Rss.fetch(url, p)
            } catch (e: HttpStatusException) {
                // 404 = 该日无公告（WP 日期归档对空期返回 404），正常空结果
                break
            } catch (e: Exception) {
                if (p == 1) firstPageFailed = true
                null
            }
            if (page == null || page.isEmpty()) break
            all += page
            if (page.size < 10) break
        }
        if (firstPageFailed) throw java.io.IOException("連線失敗")
        runCatching { dayFile(ctx, d).writeText(Rss.toJson(all)) }
        return all
    }
}
