package tw.vtsh.reader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf

class VtshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.ctx = applicationContext
        Certs.init(applicationContext)
        CalendarRepo.init(applicationContext)
        // 桌面已放置小工具时才顺手更新一轮数据（没放就省掉全部请求）
        WidgetRefresher.runIfPlaced(applicationContext)
    }
}

object App { lateinit var ctx: Context }

fun Context.openExternal(url: String) = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
} catch (_: Exception) { }

class MainActivity : ComponentActivity() {

    /** 小工具点进来的公告链接，消费后清空 */
    private val pendingUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebViewDebug.init(this)
        pendingUrl.value = intent?.urlExtra()
        setContent {
            Home(deepLink = pendingUrl.value, onDeepLinkUsed = { pendingUrl.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.urlExtra()?.let { pendingUrl.value = it }
    }

    companion object {
        const val EXTRA_URL = "tw.vtsh.reader.URL"

        fun intent(ctx: Context) = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

private fun Intent?.urlExtra(): String? = this?.getStringExtra(MainActivity.EXTRA_URL)

/** debuggable 构建才开 WebView 远程调试 */
object WebViewDebug {
    fun init(ctx: Context) {
        try {
            val flags = ctx.applicationInfo.flags
            if (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            }
        } catch (_: Exception) { }
    }
}
