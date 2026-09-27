package tw.vtsh.reader

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 小工具后台刷新：抓 RSS → 写 SP → 重画所有已放置的卡片 */
object WidgetRefresher {

    // OkHttp execute() 是阻塞 IO，走 IO 池，别占满 Default 的计算线程
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 桌面真有卡片才抓网；冷启动时一张都没放就整段跳过 */
    fun runIfPlaced(ctx: Context) {
        if (!hasAnyWidget(ctx)) return
        run(ctx)
    }

    /**
     * onUpdate 专用：系统 30 分钟心跳会同时打到两个 provider，
     * 60 秒内已刷过就跳过，避免同一轮抓两份。
     */
    fun runIfStale(ctx: Context, kind: Int, minIntervalMs: Long = 60_000) {
        val sp = ctx.applicationContext
            .getSharedPreferences("vtsh_widget", Context.MODE_PRIVATE)
        val last = sp.getLong("updated", 0L)
        if (System.currentTimeMillis() - last < minIntervalMs) {
            updateAll(ctx, kind)   // 数据还新，只需重画
            return
        }
        run(ctx, kind)
    }

    fun hasAnyWidget(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return runCatching {
            mgr.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java)).isNotEmpty() ||
                mgr.getAppWidgetIds(ComponentName(ctx, FeeWidget::class.java)).isNotEmpty()
        }.getOrDefault(false)
    }

    fun run(ctx: Context, kind: Int = -1) {
        scope.launch {
            WidgetData.refresh(ctx)
            updateAll(ctx, kind)
        }
    }

    fun updateAll(ctx: Context, kind: Int = -1) {
        val mgr = AppWidgetManager.getInstance(ctx)
        if (kind != 1) push(ctx, mgr, TodayWidget::class.java, 0)
        if (kind != 0) push(ctx, mgr, FeeWidget::class.java, 1)
    }

    private fun push(
        ctx: Context, mgr: AppWidgetManager,
        cls: Class<out VtshWidgetBase>, kind: Int,
    ) {
        runCatching {
            mgr.getAppWidgetIds(ComponentName(ctx, cls)).forEach {
                VtshWidgetBase.render(ctx, mgr, it, kind)
            }
        }
    }
}
