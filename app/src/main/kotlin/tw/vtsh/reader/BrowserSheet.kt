package tw.vtsh.reader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 详情层。整页 WebView 以顶层文件加载，X-Frame-Options 只约束 iframe 嵌套，
 * 因此官网 SAMEORIGIN 标头在此不构成阻碍。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserSheet(url: String?, onDismiss: () -> Unit) {
    val current by rememberUpdatedState(url)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("") }

    AnimatedVisibility(
        visible = url != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF2050812))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "⌄", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color(0x24FFFFFF))
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title.ifBlank { "衛道中學" },
                    color = Color.White, fontSize = 13.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "↗", color = Color.White, fontSize = 17.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color(0x24FFFFFF))
                        .clickable { current?.let { App.ctx.openExternal(it) } }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                factory = { c ->
                    WebView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            textZoom = 100
                        }
                        setBackgroundColor(0xFFFFFFFF.toInt())
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView, u: String?, b: Bitmap?) {}
                            override fun shouldOverrideUrlLoading(
                                v: WebView, req: WebResourceRequest
                            ): Boolean {
                                val host = req.url.host ?: return false
                                val internal = listOf(
                                    "vtsh.tc.edu.tw", "k12ea.gov.tw",
                                    "moe.edu.tw", "moe.gov.tw", "gtc.edu.tw"
                                ).any { host == it || host.endsWith(".$it") }
                                if (!internal) {
                                    App.ctx.openExternal(req.url.toString()); return true
                                }
                                return false
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(v: WebView, t: String) {
                                if (!t.startsWith("http")) title = t
                            }
                        }
                        webView = this
                        current?.let { loadUrl(it) }
                    }
                },
                update = { wv ->
                    val u = current
                    if (u != null && wv.url != u) wv.loadUrl(u)
                }
            )
        }
    }

    DisposableEffect(webView) { onDispose { webView?.destroy() } }
    if (url != null) {
        BackHandler {
            val wv = webView
            if (wv != null && wv.canGoBack()) wv.goBack() else onDismiss()
        }
    }
}
