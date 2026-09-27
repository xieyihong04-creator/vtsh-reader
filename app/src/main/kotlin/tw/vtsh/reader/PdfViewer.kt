package tw.vtsh.reader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 页面位图缓存，上限为进程堆的 1/5。不主动 recycle：
 * Android 8 起位图像素在 native heap，交给 GC 即可，误回收会让正在显示的画布崩。
 */
private val pageCache = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 5).toInt()
) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** PdfRenderer 非线程安全，同一文档的渲染必须串行 */
private val renderLock = Mutex()

/** 放大档相对屏宽的倍数：够看清表格小字，又不至于单页吃太多内存 */
private const val ZOOM = 2.1f

/**
 * 附件预览层。PDF 用系统 PdfRenderer 原生逐页渲染（学校附件实测为
 * Word 导出的文字版 PDF，无加密），其余格式直接落到下载。
 */
@Composable
fun AttachmentViewer(
    att: Attachment,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val screenWdp = cfg.screenWidthDp
    val screenWpx = with(LocalDensity.current) { cfg.screenWidthDp.dp.toPx() }.toInt()

    var phase by remember { mutableStateOf("load") }        // load | ready | error
    var progress by remember { mutableIntStateOf(-1) }
    var err by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(0) }
    var fromCache by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var doc by remember { mutableStateOf<PdfDoc?>(null) }

    LaunchedEffect(att.url, retry) {
        phase = "load"; progress = -1; err = ""
        doc?.close(); doc = null
        fromCache = PdfStore.cached(ctx, att.url)
        try {
            withContext(Dispatchers.IO) { PdfStore.ensure(ctx, att.url) { progress = it } }
            val d = withContext(Dispatchers.IO) { PdfStore.open(ctx, att.url) }
            if (d.pageCount <= 0) { d.close(); error("空文件") }
            doc = d
            pages = d.pageCount
            phase = "ready"
        } catch (e: Exception) {
            doc?.close(); doc = null
            err = e.message ?: "連線失敗"
            phase = "error"
        }
    }

    DisposableEffect(att.url) { onDispose { doc?.close(); doc = null } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF7050812))
            .statusBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            GlassButton(onClick = onDismiss) {
                Text("⌄", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(att.name, color = Color.White, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        phase == "ready" -> "共 $pages 頁" +
                            if (fromCache) "・已快取" else ""
                        phase == "error" -> "無法預覽"
                        else -> att.size.ifBlank { "載入中" }
                    },
                    color = Color(0x8CFFFFFF), fontSize = 11.sp
                )
            }
            if (phase == "ready") {
                Bar(if (zoomed) "適寬" else "放大") { zoomed = !zoomed }
            }
            Bar("存檔") { onDownload() }
        }

        when {
            phase == "ready" && doc != null ->
                PageList(doc!!, att.url, pages, screenWdp, screenWpx, zoomed)

            phase == "error" -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("無法開啟：$err", color = Color(0xCCFFB4B4),
                        fontSize = 13.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("可能是掃描檔、加密 PDF 或格式不支援",
                        color = Color(0x8CFFFFFF), fontSize = 11.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("重試") { retry++ }
                        Pill("存檔後開啟") { onDownload() }
                        Pill("用瀏覽器開") { ctx.openExternal(att.url) }
                    }
                }
            }

            else -> Box(
                Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (progress > 0) {
                        // 模式 6「弹簧步进进度」：进度值用弹簧追平目标，
                        // 网络抖动时圆环平滑追赶而不是跳格
                        val ring = remember { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(progress) {
                            ring.animateTo(progress / 100f,
                                androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                    stiffness = 220f))
                        }
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
                            CircularProgressIndicator(
                                progress = { ring.value },
                                color = Color(0xB3FFFFFF), strokeWidth = 2.dp,
                                modifier = Modifier.size(46.dp)
                            )
                            Text("${(ring.value * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                        }
                    } else {
                        CircularProgressIndicator(
                            color = Color(0xB3FFFFFF), strokeWidth = 2.dp,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        when {
                            fromCache && progress < 0 -> "讀取快取…"
                            progress > 0 -> "下載附件… $progress%"
                            else -> "下載附件… ${att.size}"
                        },
                        color = Color(0xB3FFFFFF), fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PageList(
    doc: PdfDoc, url: String, pages: Int,
    screenWdp: Int, screenWpx: Int, zoomed: Boolean,
) {
    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = PaddingValues(
            horizontal = if (zoomed) 0.dp else 14.dp, vertical = 10.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(pages, key = { "p$it" }, contentType = { "page" }) { i ->
            PageRow(doc, url, i, screenWdp, screenWpx, pages, zoomed)
        }
    }
}

@Composable
private fun PageRow(
    doc: PdfDoc, url: String, index: Int,
    screenWdp: Int, screenWpx: Int, total: Int, zoomed: Boolean,
) {
    var bmp by remember(url, index, zoomed) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url, index, zoomed) { mutableStateOf(false) }
    val key = "$url#$index#${if (zoomed) 'z' else 'f'}"

    LaunchedEffect(key) {
        pageCache.get(key)?.let { bmp = it; return@LaunchedEffect }
        // 適寬 1.3 倍屏宽（物理像素）≈ A4 140 DPI，正文小字够读；放大档再细看表格
        val px = (screenWpx * (if (zoomed) ZOOM else 1.3f)).toInt().coerceAtMost(PdfStore.MAX_W)
        val out = renderLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    doc.render(index, px).also { pageCache.put(key, it) }
                }.getOrNull()
            }
        }
        if (out == null) failed = true else bmp = out
    }

    // 適寬：位图缩进容器宽（渲染按 1.3 倍屏宽，等于超采样，小字更锐）
    // 放大：按物理像素 1:1 铺出（÷density 换 dp），撑出容器由横向滚动接管；
    //       字是该分辨率真渲染出来的，不是位图硬放大
    val d = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (zoomed) Modifier.horizontalScroll(rememberScrollState()) else Modifier
            )
            .clip(RoundedCornerShape(if (zoomed) 0.dp else 10.dp))
            .background(Color.White)
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "第 ${index + 1} 頁",
                contentScale = if (zoomed) ContentScale.None else ContentScale.FillWidth,
                modifier = if (zoomed)
                    Modifier.size((b.width / d).dp, (b.height / d).dp)
                else Modifier.fillMaxWidth()
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center
            ) {
                if (failed) Text("這頁渲染失敗", color = Color(0x66000000), fontSize = 12.sp)
                else CircularProgressIndicator(
                    color = Color(0x33000000), strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text("${index + 1}/$total", color = Color.White, fontSize = 10.sp) }
    }
}

@Composable
private fun Bar(text: String, onClick: () -> Unit) {
    GlassButton(onClick = onClick) {
        Text(text, color = Color.White, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    GlassButton(onClick = onClick, bg = Color(0x2EFFFFFF)) {
        Text(text, color = Color.White, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
