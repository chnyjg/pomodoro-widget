package com.example.pomodoro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 透明代理 Activity：小部件按钮的点按经由它来启动/控制前台服务。
 * 关键原因：Android 12+ 禁止“后台”直接 startForegroundService，
 * 而经由一个 Activity（即便透明、瞬间 finish）启动则算“前台”，可正常拉起服务。
 * 同时在这里一次性申请“通知”权限，避免 startForeground 抛 SecurityException。
 */
class WidgetClickProxy : Activity() {

    companion object {
        const val REQ_POST_NOTIF = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 无布局、完全透明，用户看不到任何界面
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIF
            )
            // 权限弹窗结束后会在 onRequestPermissionsResult 里继续
        } else {
            startServiceFromForeground()
        }
    }

    private fun startServiceFromForeground() {
        try {
            val action = intent?.action ?: TimerService.ACTION_TOGGLE
            ContextCompat.startForegroundService(
                this,
                Intent(this, TimerService::class.java).apply { this.action = action }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 无论授权与否都尝试启动（未授权时服务内部会兜底，不崩溃）
        startServiceFromForeground()
    }
}
