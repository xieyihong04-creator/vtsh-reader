package tw.vtsh.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import okhttp3.Request

/**
 * 附件 PDF 的原生预览。
 *
 * 学校附件实测是 Word 导出的文字版 PDF（Type0/CIDFontType2 内嵌子集字体、
 * 无 /Encrypt），因此可以用系统自带的 PdfRenderer（底层 PDFium）直接渲染，
 * 不必引第三方库，也不必先转图。
 *
 * 注意：PdfRenderer 只能从真实文件打开，所以先整份落到 cacheDir 再逐页渲染；
 * 它本身非线程安全，渲染必须串行。
 */
object PdfStore {

    /** 单页渲染宽度上限：再大平板/折叠屏上会明显吃内存。渲染与调用方共用此值 */
    const val MAX_W = 1600

    private fun dir(ctx: Context) = File(ctx.cacheDir, "pdf").apply { mkdirs() }

    private fun fileFor(ctx: Context, url: String): File =
        File(dir(ctx), NetClient.cacheKey(url) + ".pdf")

    /** 下载到缓存；已存在直接返回。onProgress 给 0..100，大小未知时给 -1 */
    suspend fun ensure(
        ctx: Context, url: String, onProgress: (Int) -> Unit,
    ): File {
        val f = fileFor(ctx, url)
        if (f.exists() && f.length() > 0) return f
        NetClient.http.newCall(
            Request.Builder().url(url).header("User-Agent", NetClient.UA).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            val tmp = File(f.parentFile, f.name + ".part")
            resp.body!!.byteStream().use input@{ ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) (done * 100 / total).toInt() else -1)
                    }
                }
            }
            if (!tmp.renameTo(f)) {
                // 已有同名文件（并发预览）时 renameTo 可能失败，直接用它
                if (!f.exists()) { tmp.delete(); error("寫入快取失敗") }
            }
            tmp.delete()
        }
        evict(ctx)
        return f
    }

    /** 缓存最多留 6 份，按最后修改时间淘汰 */
    private fun evict(ctx: Context) = runCatching {
        dir(ctx).listFiles { f -> f.isFile && !f.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(6)
            ?.forEach { it.delete() }
    }

    fun cached(ctx: Context, url: String): Boolean =
        fileFor(ctx, url).let { it.exists() && it.length() > 0 }

    fun open(ctx: Context, url: String): PdfDoc =
        PdfDoc(fileFor(ctx, url))
}

/** 一份已打开的 PDF：串行渲染页面位图 */
class PdfDoc(file: File) : AutoCloseable {

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    val pageCount: Int = renderer.pageCount

    /** 渲染第 index 页，宽度按 targetWidthPx 等比缩放（上限见 PdfStore.MAX_W） */
    fun render(index: Int, targetWidthPx: Int): Bitmap = synchronized(renderer) {
        renderer.openPage(index).use { page ->
            val w = targetWidthPx.coerceAtMost(PdfStore.MAX_W)
            val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // 白底：PDF 页面本身无背景色，透明区在深色界面上会看不见
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
