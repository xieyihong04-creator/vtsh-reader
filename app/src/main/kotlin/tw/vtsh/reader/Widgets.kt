package tw.vtsh.reader

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * 桌面小工具：今日公告 / 缴费提醒。
 * 刻意用 RemoteViews 而非 Glance —— 不引入新依赖，也不受 Compose 预览限制，
 * 系统每 30 分钟（updatePeriodMillis 下限）自动刷新一次。
 */
class TodayWidget : VtshWidgetBase()
class FeeWidget : VtshWidgetBase() {
    override fun kind() = 1
}

abstract class VtshWidgetBase : AppWidgetProvider() {

    open fun kind() = 0

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // 先拿旧数据立刻画一版，再后台抓新数据重画（首次放置时 SP 为空，不抓会一直空白）
        ids.forEach { id -> render(ctx, mgr, id, kind()) }
        WidgetRefresher.runIfStale(ctx, kind())
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            // 先抓新数据（异步），完成后统一重画已放置的卡片
            WidgetRefresher.run(ctx, kind = intent.getIntExtra(EXTRA_KIND, -1))
        }
    }

    companion object {
        const val ACTION_REFRESH = "tw.vtsh.reader.REFRESH_WIDGET"
        const val EXTRA_KIND = "kind"

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int, kind: Int) {
            val isFee = kind == 1
            val views = RemoteViews(ctx.packageName, R.layout.widget_news)
            val entries = if (isFee) WidgetData.feeEntries(ctx) else WidgetData.todayEntries(ctx)

            views.setTextViewText(
                R.id.w_title,
                if (isFee) "繳費提醒" else "${WidgetData.todayLabel(ctx)}公告"
            )
            views.setTextViewText(
                R.id.w_sub,
                if (!isFee && WidgetData.todayEntries(ctx).isNotEmpty() &&
                    WidgetData.todayLabel(ctx) == "今日")
                    "${WidgetData.timeLabel(ctx)}・共 ${WidgetData.todayCount(ctx)} 則"
                else WidgetData.timeLabel(ctx)
            )

            // 三行槽位，没有内容就填占位
            val slots = intArrayOf(R.id.w_l1, R.id.w_l2, R.id.w_l3)
            val subs = intArrayOf(R.id.w_s1, R.id.w_s2, R.id.w_s3)
            for (i in slots.indices) {
                val e = entries.getOrNull(i)
                if (e == null) {
                    views.setTextViewText(slots[i], if (i == 0)
                        (if (isFee) "近期未見繳費公告" else "今天暫無公告") else "")
                    views.setViewVisibility(subs[i], android.view.View.GONE)
                    views.setViewVisibility(slots[i],
                        if (i == 0) android.view.View.VISIBLE else android.view.View.INVISIBLE)
                } else {
                    views.setTextViewText(slots[i], clamp(e.title))
                    views.setViewVisibility(slots[i], android.view.View.VISIBLE)
                    views.setTextViewText(
                        subs[i],
                        listOf(e.unit, e.date).filter { it.isNotBlank() }.joinToString(" · ")
                    )
                    views.setViewVisibility(subs[i], android.view.View.VISIBLE)
                    views.setOnClickPendingIntent(slots[i], openIntent(ctx, e.link, i))
                }
            }

            // 整卡点开进 App；刷新按钮走自广播
            views.setOnClickPendingIntent(R.id.w_head, tap(ctx, MainActivity.intent(ctx)))
            views.setOnClickPendingIntent(R.id.w_refresh,
                refreshIntent(ctx, kind))
            mgr.updateAppWidget(id, views)
        }

        private fun clamp(s: String) = if (s.length <= 34) s else s.take(33) + "…"

        private fun tap(ctx: Context, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                ctx, intent.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun openIntent(ctx: Context, url: String, code: Int): PendingIntent {
            val i = MainActivity.intent(ctx).apply {
                putExtra(MainActivity.EXTRA_URL, url)
            }
            return PendingIntent.getActivity(
                ctx, 200 + code, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun refreshIntent(ctx: Context, kind: Int): PendingIntent {
            val i = Intent(ctx, TodayWidget::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_KIND, kind)
                // 让两个 provider 都收到
                component = ComponentName(ctx,
                    if (kind == 1) FeeWidget::class.java else TodayWidget::class.java)
            }
            return PendingIntent.getBroadcast(
                ctx, 900 + kind, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
