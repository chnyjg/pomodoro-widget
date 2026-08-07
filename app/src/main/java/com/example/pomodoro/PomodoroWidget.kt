package com.example.pomodoro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class PomodoroWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 按钮点击现在统一走 WidgetClickProxy（透明 Activity），这里只处理系统更新
        super.onReceive(context, intent)
    }

    companion object {
        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences(TimerService.PREF, Context.MODE_PRIVATE)
            val phase = prefs.getString("phase", TimerService.PHASE_WORK) ?: TimerService.PHASE_WORK
            val remaining = prefs.getInt("remaining", TimerService.WORK_SECONDS)
            val paused = prefs.getBoolean("paused", true)

            val m = remaining / 60
            val s = remaining % 60

            val views = RemoteViews(context.packageName, R.layout.widget_pomodoro)
            views.setTextViewText(R.id.tv_time, String.format("%02d:%02d", m, s))
            views.setImageViewResource(
                R.id.tv_phase,
                if (phase == TimerService.PHASE_WORK) R.drawable.ic_tomato else R.drawable.ic_break
            )
            views.setImageViewResource(
                R.id.btn_toggle,
                if (paused) R.drawable.ic_play else R.drawable.ic_pause
            )

            // 暂停/继续：点按 → 透明代理 Activity（前台上下文）去拉起/控制前台服务
            val toggle = Intent(context, WidgetClickProxy::class.java).apply {
                action = TimerService.ACTION_TOGGLE
            }
            views.setOnClickPendingIntent(
                R.id.btn_toggle,
                PendingIntent.getActivity(
                    context, 0, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )

            // 重置：同样走代理，requestCode=1 避免与上面的 PendingIntent 冲突
            val reset = Intent(context, WidgetClickProxy::class.java).apply {
                action = TimerService.ACTION_RESET
            }
            views.setOnClickPendingIntent(
                R.id.btn_reset,
                PendingIntent.getActivity(
                    context, 1, reset,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )

            mgr.updateAppWidget(id, views)
        }
    }
}
