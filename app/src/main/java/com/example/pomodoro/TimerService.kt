package com.example.pomodoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.SoundPool
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var componentName: ComponentName

    // 悬浮窗
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var edgeLight: EdgeLightView? = null   // 边缘跑马灯光点层
    // 壁纸配色变化监听（API 27+）。用 Any? 存放，避免低版本类加载校验问题
    private var wallpaperListener: Any? = null
    // 壁纸是否偏亮。getWallpaperColors() 是跨进程 IPC，不能每秒调用，故缓存
    private var wallpaperLight = false
    // 上次已应用的配色标识，避免每秒重复 setTextColor / setShadowLayer
    private var lastColorKey = ""

    // ---- 计时状态 ----
    private var phase = PHASE_WORK      // "work" 或 "break"
    private var remaining = WORK_SECONDS // 剩余秒数
    private var paused = true           // 是否暂停
    private var running = false         // 服务是否在跑表
    private var bgMode = BG_MODE_NORMAL  // 0=普通 1=透明 2=玻璃

    // ---- 可配置项（来自设置页） ----
    private var workSec = WORK_SECONDS   // 工作时长（秒）
    private var breakSec = BREAK_SECONDS // 休息时长（秒）
    private var prefRing = true          // 时间到是否响铃
    private var prefVibrate = true       // 时间到是否震动
    private var showEdgeLight = true     // 是否显示边缘跑马灯光点

    // ---- 响铃状态 ----
    private var ringtone: Ringtone? = null  // 当前正在播放的铃声（需保留引用以便停止）
    private var alerting = false            // 是否正处于“时间到”提醒中（响铃/震动）
    private val ALERT_TIMEOUT_MS = 15_000L  // 响铃自动停止的超时（毫秒），避免一直循环响
    private val alertTimeout = Runnable { stopAlerting() }  // 超时后自动止铃/止震

    // ---- 木鱼动画 ----
    private var fishIdle = true              // 当前显示的是否为"抬起"帧
    private var fishAnimRunning = false      // 动画是否在循环（用于停止）
    private val FISH_INTERVAL_MS = 500L      // 翻帧间隔（毫秒），每帧停留 0.5 秒
    private val fishRunnable = Runnable { tickFish() }  // 翻帧任务：引用成员函数 tickFish，避免自引用初始化递归

    // ---- 木鱼音效 ----
    private var soundPool: SoundPool? = null
    private var knockSoundId = 0          // SoundPool.load 后返回的音效 ID
    private var knockStreamId = 0         // 最近一次 play() 返回的流 ID（maxStreams=1 时无需 stop，保留以备）
    private var soundLoaded = false       // 资源是否加载完成（未完成前 play 会静默丢音）
    private var knockEnabled = true       // 木鱼敲击声开关（对应设置页 prefKnock）

    /** 翻一帧并调度下一次（运行中循环调用） */
    private fun tickFish() {
        if (!fishAnimRunning) return         // 已停止则不再调度
        if (phase != PHASE_WORK) {           // 非工作阶段不翻木鱼，回退静态汉堡
            stopFishAnim()
            applyPhaseIcon()
            return
        }
        fishIdle = !fishIdle
        floatingView?.findViewById<ImageView>(R.id.tv_phase)?.setImageResource(
            if (fishIdle) R.drawable.fish_idle else R.drawable.fish_hit
        )
        if (!fishIdle) playKnock()   // 显示"敲击"帧时配合木鱼声（同回调内触发，与画面同步）
        handler.postDelayed(fishRunnable, FISH_INTERVAL_MS)  // 用 token，可被 removeCallbacks
    }

    /** 仅刷新当前应显示的静态帧（每秒 updateFloating 调用，不启停循环） */
    private fun applyPhaseIcon() {
        val iv = floatingView?.findViewById<ImageView>(R.id.tv_phase) ?: return
        // 工作阶段：木鱼两帧；休息阶段：静态汉堡
        if (phase == PHASE_WORK) {
            iv.setImageResource(if (fishIdle) R.drawable.fish_idle else R.drawable.fish_hit)
        } else {
            iv.setImageResource(R.drawable.ic_break)
        }
    }

    /** 启动/重启翻帧循环（多处调用安全：先清旧链再起新链，保证全局只有一条） */
    private fun startFishAnim() {
        if (phase != PHASE_WORK) {           // 休息阶段：静态汉堡，不播放木鱼动画
            fishAnimRunning = false
            handler.removeCallbacks(fishRunnable)
            applyPhaseIcon()
            return
        }
        fishAnimRunning = true
        fishIdle = true                     // 从"抬起"帧开始
        applyPhaseIcon()
        handler.removeCallbacks(fishRunnable)   // 关键：清掉任何残留旧链
        handler.postDelayed(fishRunnable, FISH_INTERVAL_MS)
    }

    private fun stopFishAnim() {
        fishAnimRunning = false
        handler.removeCallbacks(fishRunnable)   // 关键：立即取消已 post 的任务
    }

    /** 播放木鱼敲击声（短音效，SoundPool 预加载，近乎瞬时）；开关关闭或资源未就绪则静默不崩 */
    private fun playKnock() {
        if (!knockEnabled || !soundLoaded) return
        try {
            knockStreamId = soundPool?.play(knockSoundId, 1f, 1f, 1, 0, 1f) ?: 0
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- 番茄计数 ----
    private var tomatoDate = ""          // 最近一次计数的本地日期 YYYY-MM-DD
    private var tomatoCount = 0          // 当日已完成番茄数

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE)
        appWidgetManager = AppWidgetManager.getInstance(this)
        componentName = ComponentName(this, PomodoroWidget::class.java)
        createChannel()   // 通知渠道创建是幂等的，放 onCreate 一次即可，不必每次 onStartCommand 重复执行
        loadState()

        // 木鱼音效：SoundPool 预加载一次，之后 play() 近乎瞬时（<20ms），解决此前 MediaPlayer 每击新建导致的滞后/丢音
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)   // 木鱼声不允许叠加，最多同时 1 声
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        knockSoundId = soundPool!!.load(this, R.raw.knock_wood, 1)
        soundPool!!.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId == knockSoundId) soundLoaded = true
        }
        // knockEnabled 已在 loadState()->applyConfig() 中读取，此处无需重复
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Android 13+ 未授予“通知”权限时此处会抛 SecurityException，必须兜底
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.e("Pomodoro", "startForeground 失败（可能缺少通知权限）", e)
            // 无通知权限时前台服务会被系统杀死：提示用户授权，避免悬浮窗莫名消失
            try {
                Toast.makeText(this, "请授予通知权限，否则番茄钟可能后台被系统停止", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_RESET -> reset()
            // 保存并启动：应用配置后恢复已有计时（解除暂停）或开启新计时，确保倒计时真正在跑
            ACTION_CONFIGURE -> {
                applyConfig()
                applyFloatingBackground()   // 让背景模式改动在保存后即时生效
                applyEdgeLight()            // 关键：让边缘光点开关改动即时生效（关闭时 GONE + clearSnow 清掉雪花）
                startRunning()
            }
            else -> if (running && !paused) startRunning() else { updateWidget(); updateFloating() }
        }
        addFloatingWindow()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 控制逻辑 ----------
    private fun startRunning() {
        running = true
        paused = false
        saveState()
        tickLoop()
        startFishAnim()          // 开始运行即启动木鱼翻帧
        edgeLight?.setActive(true)   // 计时运行中：跑马灯亮起
        updateWidget()
        updateFloating()
        updateNotification()
    }

    private fun toggle() {
        if (!running) { startRunning(); return }
        paused = !paused
        if (paused) { stopFishAnim(); edgeLight?.setActive(false) }   // 暂停：停木鱼翻帧 + 停跑马灯
        else { startFishAnim(); edgeLight?.setActive(true) }          // 恢复：重启木鱼 + 跑马灯
        saveState()
        updateWidget()
        updateFloating()
        updateNotification()
    }

    private fun reset() {
        phase = PHASE_WORK
        remaining = workSec
        paused = false
        running = true
        saveState()
        tickLoop()
        startFishAnim()              // 重置后重启木鱼翻帧（此前遗漏，导致重置后木鱼不动）
        edgeLight?.setActive(true)
        updateWidget()
        updateFloating()
        updateNotification()
    }

    // 每秒走表
    private var saveCounter = 0   // 节流：每 5 秒落盘一次（关键节点仍立即 saveState）
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (running && !paused) {
                remaining -= 1
                if (remaining <= 0) switchPhase()
                if (++saveCounter >= 5) {   // 普通递减每 5 秒存一次，降低 SharedPreferences 写入频率
                    saveState()
                    saveCounter = 0
                }
                updateWidget()
                updateFloating()
                updateNotification()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun tickLoop() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 1000)
    }

    private fun switchPhase() {
        if (phase == PHASE_WORK) {
            recordTomato()          // 完成一个工作番茄
            phase = PHASE_BREAK
            remaining = breakSec
        } else {
            phase = PHASE_WORK
            remaining = workSec
        }
        // 阶段切换：工作→木鱼动画，休息→静态汉堡；并立即刷新配色（工作白 / 休息绿）
        if (phase == PHASE_WORK) startFishAnim() else { stopFishAnim(); applyPhaseIcon() }
        applyTextColor(true)
        alert()
        saveState()   // 阶段切换属关键节点，立即落盘（与普通递减的 5 秒节流解耦）
    }

    private var isCompensating = false   // 补偿历史流逝时间中：临时静默 alert()，避免连响

    // 时间到：按设置决定震动 / 响铃
    private fun alert() {
        if (isCompensating) return   // 补偿历史流逝时间时不响铃/震动，避免一次性连响多次
        if (prefVibrate) {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, 300, 150, 300, 150, 500), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 300, 150, 300, 150, 500), -1)
            }
        }
        if (prefRing) {
            try {
                // 先停掉可能仍在播放的旧铃声，避免叠加
                ringtone?.stop()
                var uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply { play() }
            } catch (_: Exception) { }
        }
        // 只要开启了任一提醒（响铃或震动），就标记正在提醒：
        // 此时点数字只止铃/止震，不暂停计时
        if (prefRing || prefVibrate) {
            alerting = true
            handler.removeCallbacks(alertTimeout)
            handler.postDelayed(alertTimeout, ALERT_TIMEOUT_MS)  // 到点无人处理则自动停止
        }
    }

    // 停止“时间到”提醒：只止住响铃与震动，不改变计时/暂停状态
    private fun stopAlerting() {
        if (!alerting) return
        handler.removeCallbacks(alertTimeout)  // 取消待触发的自动超时
        ringtone?.stop()
        ringtone = null
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (_: Exception) { }
        alerting = false
    }

    // ---------- 悬浮窗 ----------
    private fun addFloatingWindow() {
        if (floatingView != null) return
        if (!Settings.canDrawOverlays(this)) return  // 没权限则不添加（引导页已负责请求）
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_widget, null)

        floatingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 水平居中：x 作为相对屏幕中心的偏移量，0 即正对中心
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 300
        }

        floatingView!!.apply {
            findViewById<View>(R.id.btn_reset).setOnClickListener {
                if (alerting) stopAlerting()    // 响铃时先止铃，再重置
                reset()
            }
            findViewById<View>(R.id.btn_close).setOnClickListener {
                if (alerting) stopAlerting()    // 响铃时先止铃，再关闭
                // 关闭即退出：持久化"未在运行"，下次开机自启/重新启动不会自动恢复计时
                running = false
                paused = true
                saveState()
                removeFloatingWindow()
                stopSelf()
            }
            // 时间数字：轻点暂停/继续；双击打开设置页。若正在响铃/震动提醒，轻点只止住提醒
            findViewById<View>(R.id.tv_time).setDragListener(
                onClick = {
                    if (alerting) stopAlerting() else toggle()
                },
                onDoubleTap = { openSettings() }
            )
            // 阶段图标：显示工作木鱼 / 休息汉堡；轻点循环切换背景模式（普通/透明/玻璃）
            // 若正在响铃/震动提醒，轻点只止住提醒、不切换背景
            findViewById<View>(R.id.tv_phase).setDragListener {
                if (alerting) stopAlerting() else cycleBgMode()
            }
            setDragListener()
        }
        edgeLight = floatingView!!.findViewById(R.id.edge_light)
        windowManager!!.addView(floatingView, floatingParams)
        // overlay 用 wrap_content，测量阶段不会把父 FrameLayout 反撑大；布局完成后把它的尺寸
        // 同步成内容视图的真实尺寸，使其精确铺满窗口、又能沿外框画光点。
        val fv = floatingView!!   // 捕获局部引用，避免用户快速关闭导致 post 回调内 NPE
        fv.post {
            val content = fv.findViewById<View>(R.id.floating_content)
            val v = edgeLight
            if (content != null && v != null && content.measuredWidth > 0) {
                v.layoutParams.width = content.measuredWidth
                v.layoutParams.height = content.measuredHeight
                v.requestLayout()
                v.invalidate()
            }
        }
        edgeLight?.invalidate()   // 触发首帧，随后自行按 VSYNC 续帧
        refreshWallpaperLight()
        registerWallpaperListener()
        if (running && !paused) startFishAnim() else applyPhaseIcon()  // 运行中启循环，否则只设静态帧
        edgeLight?.setActive(running && !paused)   // 运行中跑马灯亮，暂停/未启则暗
        updateFloating()
        applyFloatingBackground()
        applyEdgeLight()            // 按开关决定光点层是否可见
    }

    // 壁纸更换时自动刷新文字配色（API 27+）
    private fun registerWallpaperListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        if (wallpaperListener != null) return
        try {
            val l = WallpaperManager.OnColorsChangedListener { _, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                    refreshWallpaperLight()
                    applyTextColor(force = true)
                }
            }
            WallpaperManager.getInstance(this).addOnColorsChangedListener(l, handler)
            wallpaperListener = l
        } catch (_: Exception) {}
    }

    private fun unregisterWallpaperListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            (wallpaperListener as? WallpaperManager.OnColorsChangedListener)?.let {
                try { WallpaperManager.getInstance(this).removeOnColorsChangedListener(it) }
                catch (_: Exception) {}
            }
        }
        wallpaperListener = null
    }

    private fun removeFloatingWindow() {
        unregisterWallpaperListener()   // 监听随窗口销毁而注销，避免持有 Context 造成泄漏
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            floatingView = null
            edgeLight = null
            lastColorKey = ""   // 下次重建窗口时必须重新上色
        }
    }

    // 拖动悬浮窗；传入 onClick 时，位移小于 touchSlop 且时长 <400ms 视为一次点击。
    // 说明：给子 View 绑 OnClickListener 会消费 DOWN 事件，导致该区域无法拖动，
    // 所以时间数字用「拖动 + 点击判定」合一的监听，而不是 setOnClickListener。
    private val DOUBLE_TAP_MS = 300L   // 双击判定窗口：两次点击间隔小于此值视为双击

    // 双击打开设置：仅在时间数字上启用（onDoubleTap），阶段图标不传，保持原单击切背景
    private fun View.setDragListener(
        onClick: (() -> Unit)? = null,
        onDoubleTap: (() -> Unit)? = null
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downAt = 0L
        var moved = false
        var lastTapTime = 0L
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams!!.x
                    initialY = floatingParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downAt = System.currentTimeMillis()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        floatingParams!!.x = initialX + dx.toInt()
                        floatingParams!!.y = initialY + dy.toInt()
                        windowManager!!.updateViewLayout(floatingView, floatingParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - downAt < 400) {
                        // 单击立即响应（无延迟）；若带 onDoubleTap 且在双击窗口内，额外触发双击
                        val isDouble = onDoubleTap != null && onClick != null &&
                            System.currentTimeMillis() - lastTapTime < DOUBLE_TAP_MS
                        onClick?.invoke()
                        if (isDouble) onDoubleTap?.invoke()
                        lastTapTime = System.currentTimeMillis()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 双击时间数字：打开设置页（MainActivity）。Service 上下文启动 Activity 必须带 NEW_TASK */
    private fun openSettings() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        } catch (_: Exception) { }
    }

    private fun updateFloating() {
        floatingView?.let {
            it.findViewById<TextView>(R.id.tv_time).apply {
                text = fmt()
                // 暂停键已取消：用半透明表示已暂停，全不透明表示运行中
                alpha = if (paused) 0.45f else 1f
            }
            applyPhaseIcon()
            // 数字颜色区分工作 / 休息；阶段图标随阶段切换；内部有去重守卫，每秒调用无开销
            applyTextColor()
        }
    }

    // 按当前模式设置悬浮窗根背景（三态：普通 / 透明 / 玻璃）
    private fun applyFloatingBackground() {
        val root = floatingView?.findViewById<View>(R.id.floating_root) ?: return
        root.setBackgroundResource(
            when (bgMode) {
                BG_MODE_NORMAL -> R.drawable.widget_bg
                BG_MODE_TRANSPARENT -> android.R.color.transparent
                BG_MODE_GLASS -> R.drawable.widget_bg_glass
                else -> R.drawable.widget_bg
            }
        )
        applyTextColor(force = true)
    }

    // 边缘光点开关：按需显示/隐藏。隐藏(GONE)时不参与绘制与命中测试，零开销；
    // 重新打开时 invalidate 让动画从当前时刻继续（进度由系统时间计算，无缝）。
    private fun applyEdgeLight() {
        val v = edgeLight ?: return
        if (showEdgeLight) {
            v.visibility = View.VISIBLE
            v.invalidate()
        } else {
            v.visibility = View.GONE
            v.clearSnow()      // 关闭边缘光点时一并清空雪花，避免重新打开时残留突现
        }
    }

    // 轻点悬浮窗阶段图标：循环切换背景模式 普通 → 透明 → 玻璃，并写回 prefs
    // 与设置页按钮共用同一 bgMode，两侧改动互相可见
    private fun cycleBgMode() {
        bgMode = (bgMode + 1) % 3
        prefs.edit().putInt("bgMode", bgMode).apply()
        applyFloatingBackground()   // 切根背景并强制重设文字/图标配色
        updateWidget()             // 同步小部件（HyperOS 不显示但保留）
    }

    // 时间数字取色：工作态白/深灰，休息态绿（亮绿或深绿），随背景明暗切换
    // force=true 用于背景模式或壁纸变化后强制重设；否则靠 lastColorKey 去重
    private fun applyTextColor(force: Boolean = false) {
        val v = floatingView ?: return
        // 普通态自带深色实底，恒定用深底配色；只有透明 / 玻璃态才跟随壁纸明暗
        val lightBg = bgMode != BG_MODE_NORMAL && wallpaperLight

        val key = "$phase|$lightBg"
        if (!force && key == lastColorKey) return
        lastColorKey = key

        val fg = when {
            phase == PHASE_BREAK && lightBg -> TIME_BREAK_ON_LIGHT
            phase == PHASE_BREAK -> TIME_BREAK_ON_DARK
            lightBg -> TEXT_ON_LIGHT
            else -> TEXT_ON_DARK
        }
        // 反色柔光阴影：即便明暗判断失准，文字仍有轮廓可读
        val shadow = if (lightBg) 0x80FFFFFF.toInt() else 0xB3000000.toInt()

        v.findViewById<TextView>(R.id.tv_time).apply {
            setTextColor(fg)
            setShadowLayer(6f, 0f, 1f, shadow)
        }
        // 阶段图标着色：工作木鱼跟随阶段色（适应壁纸明暗），休息汉堡保持矢量图默认原色
        v.findViewById<ImageView>(R.id.tv_phase)?.apply {
            if (phase == PHASE_WORK) setColorFilter(fg, PorterDuff.Mode.SRC_IN)
            else clearColorFilter()
        }
        // 边缘跑马灯：光点颜色与文字一致（工作白/休息绿，含明暗自适应）
        edgeLight?.setGlowColor(fg)
    }

    // 用系统壁纸主色判断背景是否偏亮（API 27+，无需任何权限）
    // 内含跨进程 IPC，只在窗口创建 / 壁纸变化 / 配置变更时调用，结果缓存在 wallpaperLight
    private fun refreshWallpaperLight() {
        wallpaperLight = computeWallpaperLight()
    }

    private fun computeWallpaperLight(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return false
        return try {
            val colors = WallpaperManager.getInstance(this)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return false
            // 系统已算好的提示：壁纸适合深色文字，即壁纸偏亮（API 31+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
            ) return true
            // 兜底：按主色的感知亮度判断
            val c = colors.primaryColor.toArgb()
            val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
            lum > 0.6
        } catch (_: Exception) {
            false
        }
    }

    // ---------- 持久化与恢复 ----------
    private fun loadState() {
        // 先读取可配置项，供后续 remaining 默认值使用
        applyConfig()

        phase = prefs.getString("phase", PHASE_WORK) ?: PHASE_WORK
        remaining = prefs.getInt("remaining", workSec)
        paused = prefs.getBoolean("paused", true)
        running = prefs.getBoolean("running", false)
        tomatoDate = prefs.getString("tomatoDate", "") ?: ""
        tomatoCount = prefs.getInt("tomatoCount", 0)

        // 服务被系统杀死后重启：补偿流逝的时间，避免进度"冻住"
        val last = prefs.getLong("lastTick", 0L)
        val now = System.currentTimeMillis()
        if (running && !paused && last > 0L) {
            isCompensating = true   // 进入补偿：此间 alert() 全部静默，避免连响
            var elapsed = ((now - last) / 1000).toInt()
            while (elapsed > 0 && remaining > 0) {
                remaining -= 1
                elapsed -= 1
                if (remaining <= 0) {
                    if (phase == PHASE_WORK) {
                        recordTomato()          // 补偿期间跨越的工作番茄也要计数，与 switchPhase() 一致
                        phase = PHASE_BREAK
                        remaining = breakSec
                    } else {
                        phase = PHASE_WORK
                        remaining = workSec
                    }
                    alert()   // 补偿期间被 isCompensating 静默，仅推进阶段
                }
            }
            isCompensating = false
        }
    }

    // 从 SharedPreferences 重新读取可配置项（设置页保存后调用，无需重建服务）
    // 背景模式的唯一读取入口：悬浮窗上已无切换按钮，改由设置页写入 prefs
    private fun applyConfig() {
        val wm = prefs.getInt("workMin", 30)
        val bm = prefs.getInt("breakMin", 5)
        workSec = wm * 60
        breakSec = (bm * 60).coerceAtLeast(60)   // 兜底：至少 1 分钟，防止 breakMin=0 致休息瞬间结束+连续 alert
        prefRing = prefs.getBoolean("prefRing", true)
        prefVibrate = prefs.getBoolean("prefVibrate", true)
        showEdgeLight = prefs.getBoolean("showEdgeLight", true)   // 读取边缘光点开关，否则永远停在默认 true（即使设置页关掉了）
        knockEnabled = prefs.getBoolean("prefKnock", true)         // 木鱼敲击声开关
        bgMode = if (prefs.contains("bgMode")) prefs.getInt("bgMode", BG_MODE_NORMAL)
                 else if (prefs.getBoolean("transparent", false)) BG_MODE_TRANSPARENT
                 else BG_MODE_NORMAL
        // 今日番茄计数：重新读取，使设置页手动编辑的值（覆盖安装/保存）生效，
        // 后续自动完成的番茄在此基础上累加，而非从服务内存里的旧值覆盖
        tomatoDate = prefs.getString("tomatoDate", "") ?: ""
        tomatoCount = prefs.getInt("tomatoCount", 0)
        // 重新判定壁纸明暗；窗口背景/边缘光点统一在 addFloatingWindow 末尾应用
        // （此处 floatingView 尚为 null，调用 applyFloatingBackground/applyEdgeLight 是空操作，故省略）
        refreshWallpaperLight()
    }

    // 完成一个工作番茄：按本地日期累计当日数量
    private fun recordTomato() {
        val t = todayStr()
        if (tomatoDate == t) tomatoCount++
        else { tomatoDate = t; tomatoCount = 1 }
        prefs.edit().putString("tomatoDate", tomatoDate).putInt("tomatoCount", tomatoCount).apply()
    }

    private fun saveState() {
        saveCounter = 0   // 关键节点已落盘，重置节流计数，避免紧接着又立即再存一次
        prefs.edit().apply {
            putString("phase", phase)
            putInt("remaining", remaining)
            putBoolean("paused", paused)
            putBoolean("running", running)
            // bgMode 由设置页独占写入，此处不回写，避免覆盖用户刚保存的新值
            putLong("lastTick", System.currentTimeMillis())
        }.apply()
    }

    // ---------- 渲染（小部件兼容，HyperOS 不显示但保留） ----------
    private fun fmt(): String {
        val m = remaining / 60
        val s = remaining % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun phaseLabel(): String =
        if (phase == PHASE_WORK) "🍅" else "☕"

    private fun updateWidget() {
        val views = RemoteViews(packageName, R.layout.widget_pomodoro)
        views.setTextViewText(R.id.tv_time, fmt())
        views.setInt(
            R.id.widget_root, "setBackgroundResource",
            if (bgMode == BG_MODE_TRANSPARENT) android.R.color.transparent else R.drawable.widget_bg
        )
        // 小部件为静态 RemoteViews，不支持逐帧动画：工作显示番茄、休息显示汉堡，
        // 与 PomodoroWidget.update() 的图标逻辑保持一致
        views.setImageViewResource(
            R.id.tv_phase,
            if (phase == PHASE_WORK) R.drawable.ic_tomato else R.drawable.ic_break
        )
        views.setImageViewResource(
            R.id.btn_toggle,
            if (paused) R.drawable.ic_play else R.drawable.ic_pause
        )

        val toggle = Intent(this, WidgetClickProxy::class.java).apply { action = ACTION_TOGGLE }
        views.setOnClickPendingIntent(
            R.id.btn_toggle,
            PendingIntent.getActivity(
                this, 0, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        )

        val reset = Intent(this, WidgetClickProxy::class.java).apply { action = ACTION_RESET }
        views.setOnClickPendingIntent(
            R.id.btn_reset,
            PendingIntent.getActivity(
                this, 1, reset,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        )
        appWidgetManager.updateAppWidget(componentName, views)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 自定义通知布局：锁屏也能看到倒计时与阶段，并可直接操作
        val rv = RemoteViews(packageName, R.layout.notification_timer)
        rv.setTextViewText(R.id.tv_phase, phaseLabel())
        rv.setTextViewText(R.id.tv_time, if (paused) "${fmt()} ⏸" else fmt())
        rv.setImageViewResource(R.id.btn_toggle, if (paused) R.drawable.ic_play else R.drawable.ic_pause)

        val toggle = Intent(this, TimerService::class.java).apply { action = ACTION_TOGGLE }
        rv.setOnClickPendingIntent(
            R.id.btn_toggle,
            PendingIntent.getService(
                this, 10, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        val reset = Intent(this, TimerService::class.java).apply { action = ACTION_RESET }
        rv.setOnClickPendingIntent(
            R.id.btn_reset,
            PendingIntent.getService(
                this, 11, reset,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // 锁屏完整显示内容与操作按钮
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "番茄钟", NotificationManager.IMPORTANCE_LOW)
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(alertTimeout)
        stopFishAnim()
        soundPool?.release()  // 释放音效池，避免 AudioTrack 资源泄漏
        soundPool = null
        ringtone?.stop()      // 服务销毁时务必停掉仍在播放的铃声，避免进程残留继续响
        ringtone = null
        alerting = false
        removeFloatingWindow()   // 其内部已 unregisterWallpaperListener()，无需在 onDestroy 重复调用
        saveState()   // 销毁前落盘，确保 lastTick/剩余时间等均为最新
        super.onDestroy()
    }

    companion object {
        const val PREF = "pomodoro"
        const val PHASE_WORK = "work"
        const val PHASE_BREAK = "break"
        const val WORK_SECONDS = 30 * 60
        const val BREAK_SECONDS = 5 * 60
        const val ACTION_TOGGLE = "com.example.pomodoro.TOGGLE"
        const val ACTION_RESET = "com.example.pomodoro.RESET"
        const val ACTION_CONFIGURE = "com.example.pomodoro.CONFIGURE"
        const val CHANNEL_ID = "pomodoro_channel"
        const val NOTIF_ID = 1

        // 背景三态：普通 / 完全透明 / 毛玻璃
        const val BG_MODE_NORMAL = 0
        const val BG_MODE_TRANSPARENT = 1
        const val BG_MODE_GLASS = 2

        // 工作态前景色：浅背景用深灰，深背景用白
        val TEXT_ON_LIGHT = 0xFF3A3A3A.toInt()
        val TEXT_ON_DARK = 0xFFFFFFFF.toInt()
        // 休息态前景色：绿色。浅背景上纯亮绿对比度不足，改用深绿
        val TIME_BREAK_ON_LIGHT = 0xFF1B8A3A.toInt()
        val TIME_BREAK_ON_DARK = 0xFF4ADE80.toInt()

        // 背景模式的中文名，设置页按钮循环显示
        val BG_MODE_NAMES = arrayOf("普通", "透明", "玻璃")

        // 今日已完成番茄数（供设置页读取，跨日自动归零）
        fun todayTomatoes(p: SharedPreferences): Int {
            val t = todayStr()
            return if (p.getString("tomatoDate", "") == t) p.getInt("tomatoCount", 0) else 0
        }

        fun todayStr(): String {
            val c = java.util.Calendar.getInstance()
            return String.format(
                "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }
}
