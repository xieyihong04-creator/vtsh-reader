package tw.vtsh.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 轻量 JSON 磁盘缓存（官网 no-store、无 CDN，缴费高峰易超时 → 离线兜底）。
 * 公告类内容 10 分钟内视为新鲜，过期后先给旧数据再后台刷新。
 */
class RssCache(ctx: Context) {
    private val dir = File(ctx.cacheDir, "rss").apply { mkdirs() }

    private fun keyOf(url: String) = NetClient.cacheKey(url)

    fun save(url: String, page: Int, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(JSONObject().apply {
                put("t", i.title); put("l", i.link); put("u", i.unit)
                put("d", i.date?.toEpochSecond()?.toString() ?: "")
                put("c", JSONArray(i.cats)); put("a", i.hasAtt)
            })
        }
        runCatching { File(dir, "${keyOf(url)}-$page.json").writeText(arr.toString()) }
    }

    fun load(url: String, page: Int): List<Item>? {
        val f = File(dir, "${keyOf(url)}-$page.json")
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                Item(
                    title = o.getString("t"), link = o.getString("l"),
                    unit = o.getString("u"),
                    date = o.optString("d").toLongOrNull()?.let {
                        java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.of("Asia/Taipei"))
                    },
                    cats = o.getJSONArray("c").let { c -> (0 until c.length()).map { c.getString(it) } },
                    hasAtt = o.getBoolean("a"),
                )
            }
        }.getOrNull()
    }

    fun fresh(url: String, page: Int, ttlMs: Long = 10 * 60_000): List<Item>? =
        load(url, page).takeIf {
            File(dir, "${keyOf(url)}-$page.json").let { f -> f.exists() && System.currentTimeMillis() - f.lastModified() < ttlMs }
        }
}
