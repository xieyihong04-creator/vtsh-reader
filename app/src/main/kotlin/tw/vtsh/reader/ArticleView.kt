package tw.vtsh.reader

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 详情层：原生文章优先，解析失败或页面非文章时退回 WebView */
@Composable
fun DetailLayer(
    url: String?,
    screen: Offset,
    parallax: Float,
    tier: GlassTier,
    clock: Float,
    onDismiss: () -> Unit,
) {
    var article by remember { mutableStateOf<Article?>(null) }
    var state by remember { mutableStateOf("load") }   // load | native | web
    var err by remember { mutableStateOf("") }
    var lastUrl by remember { mutableStateOf(url ?: "") }
    if (url != null) lastUrl = url
    val ctx = LocalContext.current

    // 文章层吃掉返回键；PDF 预览层的 BackHandler 组合更靠内，优先级更高
    androidx.activity.compose.BackHandler(enabled = url != null, onBack = onDismiss)

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        state = "load"; article = null
        val cached = ArticleCache.get(ctx, url)
        if (cached != null) { article = cached; state = "native"; return@LaunchedEffect }
        try {
            val a = withContext(Dispatchers.IO) { ArticleRepo.fetch(url) }
            if (a.paras.isEmpty() && a.attachments.isEmpty() && a.subject.isBlank()) {
                state = "web"                       // 不像公告文章 → 交回网页
            } else {
                article = a; state = "native"
                ArticleCache.put(ctx, url, a)
            }
        } catch (e: Exception) {
            err = e.message ?: "連線失敗"; state = "web"
        }
    }

    AnimatedVisibility(
        visible = url != null,
        enter = slideInVertically {
            if (Prefs.get(ctx).reduceMotion) 0 else (it * 0.06f).toInt()
        } + fadeIn(tween(180)),
        exit = slideOutVertically {
            if (Prefs.get(ctx).reduceMotion) 0 else (it * 0.06f).toInt()
        } + fadeOut(tween(140)),
    ) {
        when {
            state == "native" && article != null -> ArticleView(
                article!!, clock, screen, parallax, tier,
                onDismiss = onDismiss,
                onOpenWeb = { state = "web" },
            )
            state == "web" -> BrowserSheet(lastUrl.takeIf { url != null }, onDismiss)
            else -> Box(Modifier.fillMaxSize().background(Color(0xE6050812)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xB3FFFFFF), strokeWidth = 2.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("解析公告…", color = Color(0xB3FFFFFF), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ArticleView(
    a: Article, clock: Float, screen: Offset, parallax: Float, tier: GlassTier,
    onDismiss: () -> Unit, onOpenWeb: () -> Unit,
) {
    val ctx = LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }
    toast?.let { msg -> LaunchedEffect(msg) { ToastOnce2(ctx, msg) }; toast = null }
    var preview by remember { mutableStateOf<Attachment?>(null) }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2050812))
            .statusBarsPadding()
    ) {
        // 工具栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            GlassButton(onClick = { onDismiss() }) {
                Text("⌄", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                a.unit.ifBlank { "衛道中學" }, color = Color(0x99FFFFFF), fontSize = 12.sp,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            GlassButton(onClick = { onOpenWeb() }) {
                Text("網頁版", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val gen = a.title   // 文章级稳定值：只在换文/重进时重播交错
            item {
                StaggerIn(0, gen, enabled = !Prefs.get(ctx).reduceMotion) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .liquidGlass(clock, screen, parallax, tier, corner = 22.dp)
                        .padding(18.dp)
                ) {
                    Text(a.title, color = Color.White, fontSize = 19.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 27.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (a.date.isNotBlank()) Chip(a.date)
                        if (a.unit.isNotBlank()) Chip(a.unit)
                        if (a.attachments.isNotEmpty())
                            Chip("${a.attachments.size} 個附件",
                                fg = Color(0xFFFFE6A3), border = Color(0x80FFD678))
                    }
                }
                }
            }
            // 序号用确定性公式（回收重组也不会漂移、不会重播）
            val hasSubject = a.subject.isNotBlank()
            if (hasSubject) item { GlassBlock(clock, screen, parallax, tier,
                index = 1, gen = gen) {
                Text("公告主旨", color = Color(0x99FFFFFF), fontSize = 11.sp,
                    letterSpacing = 1.5.sp)
                Spacer(Modifier.height(6.dp))
                Text(a.subject, color = Color.White, fontSize = 15.sp, lineHeight = 24.sp)
            } }
            a.paras.forEachIndexed { i, p ->
                item { GlassBlock(clock, screen, parallax, tier,
                    index = if (hasSubject) 2 + i else 1 + i, gen = gen) {
                    Text(p, color = Color(0xE6FFFFFF), fontSize = 15.sp, lineHeight = 25.sp)
                } }
            }
            a.images.forEachIndexed { i, u ->
                item { GlassBlock(clock, screen, parallax, tier, pad = 6,
                    index = (if (hasSubject) 2 else 1) + a.paras.size + i, gen = gen) { RemoteImage(u) } }
            }
            if (a.attachments.isNotEmpty()) {
                item { Text("附件", color = Color(0x99FFFFFF), fontSize = 12.sp,
                    letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                a.attachments.forEach { att ->
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .liquidGlass(clock, screen, parallax, tier, corner = 14.dp)
                                .pressCard {
                                    if (att.kind == Attachment.Kind.PDF) preview = att
                                    else download(ctx, att) { msg -> toast = msg }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x2EFFFFFF))
                            ) { Text(att.kind.name.take(3), color = Color(0xFFFFE6A3), fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(att.name, color = Color.White, fontSize = 14.sp,
                                    maxLines = 2, lineHeight = 20.sp)
                                if (att.size.isNotBlank())
                                    Text(att.size, color = Color(0x8CFFFFFF), fontSize = 11.sp)
                            }
                            Text(
                                if (att.kind == Attachment.Kind.PDF) "預覽" else "下載",
                                color = Color(0xCCFFFFFF), fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

        // PDF 预览层盖在文章上，返回键先关预览
        preview?.let { att ->
            androidx.activity.compose.BackHandler { preview = null }
            AttachmentViewer(
                att = att,
                onDismiss = { preview = null },
                onDownload = { download(ctx, att) { msg -> toast = msg } },
            )
        }
    }
}

private fun ToastOnce2(ctx: Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun GlassBlock(
    clock: Float, screen: Offset, parallax: Float, tier: GlassTier, pad: Int = 16,
    index: Int = -1, gen: Any = Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val body = @Composable {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .liquidGlass(clock, screen, parallax, tier, corner = 18.dp)
                .padding(pad.dp),
            content = content
        )
    }
    // 模式 3「交错入场」：正文段落按序号错峰弹入，读起来有"逐段浮现"的节奏
    if (index >= 0) StaggerIn(index, gen, enabled = !Prefs.get(ctx).reduceMotion) { body() }
    else body()
}

private fun download(ctx: Context, att: Attachment, onDone: (String) -> Unit) {
    // att.name 来自服务器 HTML 且经 URLDecoder，可能含路径分隔或 ".."，
    // 落到公共目录前只取纯文件名并去掉前导点，封死越出 Downloads/VTSH/ 的可能
    val base = att.name.substringAfterLast('/').substringAfterLast('\\').trimStart('.')
    val safe = base.ifBlank { att.kind.name.lowercase() + "_file" }
    val req = DownloadManager.Request(Uri.parse(att.url))
        .setTitle(att.name)
        .setDescription("衛道公告附件")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VTSH/" + safe)
        .setMimeType(mimeOf(att))
        .addRequestHeader("User-Agent", NetClient.UA)
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(req)
    onDone("已存入下載／VTSH／$safe")
}

private fun mimeOf(att: Attachment): String = when (att.kind) {
    Attachment.Kind.PDF -> "application/pdf"
    Attachment.Kind.DOC -> "application/msword"
    Attachment.Kind.SHEET -> "application/vnd.ms-excel"
    Attachment.Kind.SLIDE -> "application/vnd.ms-powerpoint"
    Attachment.Kind.IMAGE -> "image/jpeg"
    Attachment.Kind.ZIP -> "application/zip"
    else -> "application/octet-stream"
}

/** 极简图片加载：无 Coil 依赖，OkHttp + BitmapFactory 降采样 */
@Composable
private fun RemoteImage(url: String) {
    var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                NetClient.http.newCall(
                    okhttp3.Request.Builder().url(url).header("User-Agent", NetClient.UA).build()
                ).execute().use { r ->
                    val bytes = r.body!!.bytes()
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                    var ss = 1
                    while (opt.outWidth / (ss * 2) > 1080) ss *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = ss })
                }
            }.getOrNull()
        }
    }
    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        )
    } ?: Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
        CircularProgressIndicator(color = Color(0x66FFFFFF), strokeWidth = 2.dp)
    }
}
