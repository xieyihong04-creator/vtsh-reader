package tw.vtsh.reader

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 文章磁盘缓存（3 天有效），离线也能看已读过的公告 */
object ArticleCache {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "articles").apply { mkdirs() }
    private fun f(ctx: Context, url: String) = File(dir(ctx), NetClient.cacheKey(url) + ".json")

    fun put(ctx: Context, url: String, a: Article) {
        runCatching {
            val o = JSONObject().apply {
                put("t", a.title); put("s", a.subject); put("u", a.unit); put("d", a.date)
                put("p", JSONArray(a.paras)); put("i", JSONArray(a.images))
                put("a", JSONArray().apply {
                    a.attachments.forEach {
                        put(JSONObject().put("n", it.name).put("z", it.size).put("l", it.url))
                    }
                })
            }
            f(ctx, url).writeText(o.toString())
        }
    }

    fun get(ctx: Context, url: String, ttlMs: Long = 3 * 24 * 3600_000): Article? {
        val file = f(ctx, url)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > ttlMs) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("a")
            Article(
                title = o.getString("t"), subject = o.getString("s"),
                unit = o.getString("u"), date = o.getString("d"),
                paras = o.getJSONArray("p").let { p -> (0 until p.length()).map { p.getString(it) } },
                images = o.getJSONArray("i").let { p -> (0 until p.length()).map { p.getString(it) } },
                attachments = (0 until arr.length()).map {
                    val a = arr.getJSONObject(it)
                    Attachment(a.getString("n"), a.getString("l"), a.getString("z"))
                },
                link = url,
            )
        }.getOrNull()
    }
}

@Composable
fun ToastOnce(ctx: Context, msg: String) {
    LaunchedEffect(msg) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}
