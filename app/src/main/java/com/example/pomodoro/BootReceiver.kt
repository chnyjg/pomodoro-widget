package com.example.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 仅当用户关闭前仍在计时（running），才在开机后恢复服务；
            // 否则不启动，避免通知栏常驻一个静止的 "30:00 ⏸" 通知
            val prefs = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
            if (prefs.getBoolean("running", false)) {
                ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
            }
        }
    }
}
