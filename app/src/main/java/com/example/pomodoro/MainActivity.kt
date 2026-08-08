package com.example.pomodoro

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQ_OVERLAY = 100
    private val REQ_NOTIF = 101
    private lateinit var prefs: SharedPreferences

    private var workMin = 30
    private var breakMin = 5
    private var bgMode = TimerService.BG_MODE_NORMAL
    private var todayCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(TimerService.PREF, MODE_PRIVATE)
        workMin = prefs.getInt("workMin", 30)
        breakMin = prefs.getInt("breakMin", 5)

        val switchRing = findViewById<Switch>(R.id.switch_ring)
        val switchVib = findViewById<Switch>(R.id.switch_vibrate)
        val switchEdge = findViewById<Switch>(R.id.switch_edge_light)
        switchRing.isChecked = prefs.getBoolean("prefRing", true)
        switchVib.isChecked = prefs.getBoolean("prefVibrate", true)
        switchEdge.isChecked = prefs.getBoolean("showEdgeLight", true)
        findViewById<Switch>(R.id.switch_knock).isChecked = prefs.getBoolean("prefKnock", true)

        val tvWork = findViewById<TextView>(R.id.tv_work)
        val tvBreak = findViewById<TextView>(R.id.tv_break)
        tvWork.text = workMin.toString()
        tvBreak.text = breakMin.toString()

        // 工作用时：以 5 分钟为单位调整
        findViewById<Button>(R.id.btn_work_minus).setOnClickListener {
            if (workMin > 5) { workMin -= 5; tvWork.text = workMin.toString() }
        }
        findViewById<Button>(R.id.btn_work_plus).setOnClickListener {
            if (workMin < 180) { workMin += 5; tvWork.text = workMin.toString() }
        }
        findViewById<Button>(R.id.btn_break_minus).setOnClickListener {
            if (breakMin > 1) { breakMin--; tvBreak.text = breakMin.toString() }   // 下限 1 分钟，避免休息时长为 0
        }
        findViewById<Button>(R.id.btn_break_plus).setOnClickListener {
            if (breakMin < 60) { breakMin++; tvBreak.text = breakMin.toString() }
        }

        // 悬浮窗背景模式：点击循环切换 普通 → 透明 → 玻璃
        // 兼容旧版本的 transparent 布尔 key
        bgMode = if (prefs.contains("bgMode")) prefs.getInt("bgMode", TimerService.BG_MODE_NORMAL)
                 else if (prefs.getBoolean("transparent", false)) TimerService.BG_MODE_TRANSPARENT
                 else TimerService.BG_MODE_NORMAL
        bgMode = bgMode.coerceIn(0, TimerService.BG_MODE_NAMES.size - 1)  // 防脏值越界
        val btnBgMode = findViewById<Button>(R.id.btn_bgmode)
        btnBgMode.text = TimerService.BG_MODE_NAMES[bgMode]
        btnBgMode.setOnClickListener {
            bgMode = (bgMode + 1) % TimerService.BG_MODE_NAMES.size
            btnBgMode.text = TimerService.BG_MODE_NAMES[bgMode]
        }

        val tvToday = findViewById<TextView>(R.id.tv_today)
        todayCount = TimerService.todayTomatoes(prefs)
        tvToday.text = todayCount.toString()
        findViewById<Button>(R.id.btn_today_minus).setOnClickListener {
            if (todayCount > 0) { todayCount--; tvToday.text = todayCount.toString() }
        }
        findViewById<Button>(R.id.btn_today_plus).setOnClickListener {
            if (todayCount < 99) { todayCount++; tvToday.text = todayCount.toString() }
        }

        refreshButton()
    }

    private fun hasOverlay() = Settings.canDrawOverlays(this)
    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refreshButton() {
        val ok = hasOverlay() && hasNotif()
        val tvPerm = findViewById<TextView>(R.id.tv_perm)
        val btnSave = findViewById<Button>(R.id.btn_save)
        tvPerm.visibility = if (ok) View.GONE else View.VISIBLE
        btnSave.text = if (ok) "保存并启动悬浮窗" else "① 授予必要权限"
        btnSave.setOnClickListener {
            if (ok) saveAndLaunch() else requestPerms()
        }
    }

    private fun requestPerms() {
        when {
            !hasOverlay() -> startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            !hasNotif() -> ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        }
    }

    private fun saveAndLaunch() {
        prefs.edit().apply {
            putInt("workMin", workMin)
            putInt("breakMin", breakMin)
            putBoolean("prefRing", findViewById<Switch>(R.id.switch_ring).isChecked)
            putBoolean("prefVibrate", findViewById<Switch>(R.id.switch_vibrate).isChecked)
            putBoolean("showEdgeLight", findViewById<Switch>(R.id.switch_edge_light).isChecked)
            putBoolean("prefKnock", findViewById<Switch>(R.id.switch_knock).isChecked)
            putInt("bgMode", bgMode)
            // 今日番茄数：以手动编辑值覆盖当日计数基准（跨日时同步刷新日期）
            putString("tomatoDate", TimerService.todayStr())
            putInt("tomatoCount", todayCount)
        }.apply()
        launchService()
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) refreshButton()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) refreshButton()
    }

    private fun launchService() {
        try {
            val i = Intent(this, TimerService::class.java).apply { action = TimerService.ACTION_CONFIGURE }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
