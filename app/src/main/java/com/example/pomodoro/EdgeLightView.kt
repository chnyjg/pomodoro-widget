package com.example.pomodoro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Outline
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 悬浮窗边缘跑马灯：一个发光小点沿窗口（16dp 圆角矩形）外框匀速转圈。
 *
 * - 进度完全由系统时间驱动：progress = (now % 60000) / 60000，每 60 秒一圈，连续循环，
 *   无需保存任何状态，悬浮窗被系统重建后也能无缝衔接。
 * - 该 View 不接收触摸（clickable/focusable=false 且无 onTouchListener），
 *   触摸事件会穿透到下层控件，不影响拖动 / 点时间暂停 / 点图标切背景。
 * - 颜色由外部 setGlowColor() 注入，跟随工作白/休息绿（含明暗自适应）。
 */
class EdgeLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val path = Path()
    private val measure = PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var glowColor = Color.WHITE
    private var cornerPx = 0f
    private var glowR = 0f
    private var coreR = 0f
    private var active = true   // 是否处于活跃动画（计时运行中）；暂停时由 Service 置 false 停止自转
    private val particles = mutableListOf<Particle>()   // 散落光点粒子池
    private var headT = -1f     // 头部进度(0~1)；暂停时冻结，不随系统时间推进
    private var pauseStart = 0L // 暂停开始的系统时刻，用于累计暂停时长
    private var pausedAccum = 0L   // 累计暂停毫秒数：恢复时从冻结位置平滑接续，避免光点跳变
    private val MAX_PARTICLES = 150
    private var particleR = 0f  // 粒子半径(dp)
    private var lastSpawn = 0L          // 上次撒雪时间（节流，实现一粒一粒缓慢发射）
    private val SPAWN_INTERVAL_MS = 750L   // 约每 0.75s 才落一片，极稀疏、极静谧
    private val MAX_AGE = 2400          // 雪花最长寿命（帧≈40s），留存极久，缓缓飘落铺满整窗

    init {
        val d = resources.displayMetrics.density
        cornerPx = 16f * d          // 与 widget_bg / widget_bg_glass 的圆角一致
        glowR = 9f * d              // 光晕半径
        coreR = 3f * d              // 核心亮点半径
        particleR = 2f * d          // 散落粒子半径
        // 圆角裁剪：让四角处超出 16dp 圆角的光晕也被裁掉，与直边（被窗口边界裁成半圆）保持一致
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                }
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = cornerPx.coerceAtMost(if (w < h) w / 2f else h / 2f)
        // 不内缩：光点中心对齐窗口外边缘，沿整个图标的轮廓转动
        path.reset()
        path.addRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            r, r, Path.Direction.CW
        )
        measure.setPath(path, true)
        invalidateOutline()   // 尺寸变化后刷新圆角裁剪区域
    }

    fun setGlowColor(color: Int) {
        if (glowColor != color) {
            glowColor = color
            invalidate()
        }
    }

    /** 计时暂停/恢复时由 Service 调用：
     *  - true：恢复自转并继续散落粒子
     *  - false：头部冻结、停止生成新粒子，但已存在的粒子会继续飘向中心，飘完才真正停帧 */
    fun setActive(a: Boolean) {
        if (active == a) return
        val now = System.currentTimeMillis()
        if (a) {
            // 恢复：把本次暂停时长累加到 pausedAccum，使光点从冻结位置继续，而非跳到当前系统时刻
            pausedAccum += now - pauseStart
        } else {
            pauseStart = now
        }
        active = a
        if (a) invalidate()   // 重新激活时立即触发下一帧
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val len = measure.length
        if (len <= 0f) return

        // 头部位置：运行中按「系统时间 − 累计暂停时长」推进，暂停期间不前进，
        // 恢复后从冻结位置平滑接续（不会跳到暂停期间错过的那段）
        val now = System.currentTimeMillis()
        if (active) {
            val eff = now - pausedAccum
            headT = ((if (eff < 0L) eff + 60000L else eff) % 60000L) / 60000f
        } else if (headT < 0f) {
            headT = (now % 60000L) / 60000f
        }
        if (!measure.getPosTan(headT * len, pos, tan)) return
        val hx = pos[0]
        val hy = pos[1]

        val cx0 = width / 2f
        val cy0 = height / 2f
        val maxDist = hypot(cx0, cy0).coerceAtLeast(1f)

        // 运行中：沿边缘缓慢、一粒一粒地撒出雪花（暂停时不生成，仅让已存在的飘落完）
        if (active && now - lastSpawn >= SPAWN_INTERVAL_MS) {
            spawnParticle(hx, hy, cx0, cy0)
            lastSpawn = now
        }
        updateParticles(cx0, cy0)
        drawParticles(canvas, cx0, cy0, maxDist)

        // 头部主光晕 + 核心亮点（"边缘光点"本体）
        paint.shader = RadialGradient(
            hx, hy, glowR,
            glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        paint.alpha = 230
        canvas.drawCircle(hx, hy, glowR, paint)

        // 核心亮点（更实一点）：显式重置为不透明，避免继承光晕的 230 导致偏暗
        paint.shader = null
        paint.color = glowColor
        paint.alpha = 255
        canvas.drawCircle(hx, hy, coreR, paint)

        // 续帧：运行中一直转；暂停时只要还有雪花在飞就继续，飘完自动停
        if (active || particles.isNotEmpty()) postInvalidateOnAnimation()
    }

    /** 在头部位置生成一片雪花：朝窗口中心缓慢飘落（上缘→下、下缘→上、左缘→右、右缘→左），并带轻柔飘摆 */
    private fun spawnParticle(hx: Float, hy: Float, cx0: Float, cy0: Float) {
        val d = resources.displayMetrics.density
        var dx = cx0 - hx
        var dy = cy0 - hy
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        dx /= dist; dy /= dist                       // 朝中心单位向量（决定飘落大方向）
        val px = -dy; val py = dx                   // 垂直向量（用于左右飘摆）
        if (particles.size >= MAX_PARTICLES) particles.removeAt(0)
        particles.add(
            Particle(
                hx + (Math.random().toFloat() - 0.5f) * 6f * d,
                hy + (Math.random().toFloat() - 0.5f) * 6f * d,
                dx, dy, px, py, 0,
                0.18f * d + Math.random().toFloat() * 0.18f * d,   // 飘摆幅度（雪片横向轻颤）
                0.025f + Math.random().toFloat() * 0.045f,        // 飘摆角速度（更慢更柔）
                Math.random().toFloat() * 6.2832f,                // 飘摆初相
                0.035f * d                                      // 朝中心飘落速度(px/帧)，极缓，雪片几乎悬停般静静飘落
            )
        )
    }

    /** 推进所有雪花：朝中心匀速飘 + 垂直方向正弦飘摆；到中心或寿命到则回收 */
    private fun updateParticles(cx0: Float, cy0: Float) {
        val it = particles.iterator()
        val stopR = coreR * 2f
        while (it.hasNext()) {
            val p = it.next()
            p.age++
            val sway = p.swayAmp * sin(p.swayPhase + p.age * p.swaySpeed)
            p.x += p.inX * p.inward + p.perpX * sway
            p.y += p.inY * p.inward + p.perpY * sway
            val ddx = p.x - cx0
            val ddy = p.y - cy0
            if (p.age > MAX_AGE || (ddx * ddx + ddy * ddy) <= stopR * stopR) it.remove()
        }
    }

    /** 绘制雪花：亮度随到中心距离衰减（边缘亮、中心透明）并随年龄淡出 */
    private fun drawParticles(canvas: Canvas, cx0: Float, cy0: Float, maxDist: Float) {
        if (particles.isEmpty()) return
        paint.shader = null
        paint.color = glowColor
        for (p in particles) {
            val dist = hypot(p.x - cx0, p.y - cy0)
            // 寿命衰减：前 70% 时间保持明亮，后 30% 才缓缓淡出，使雪片留存更久、余韵更长
            val life = if (p.age < MAX_AGE * 0.7f) 1f
                       else (1f - (p.age - MAX_AGE * 0.7f) / (MAX_AGE * 0.3f)).coerceIn(0f, 1f)
            // 出生淡入：前 12% 寿命由 0 缓缓浮现，避免"弹出"的突兀感
            val fadeIn = if (p.age < MAX_AGE * 0.12f) p.age / (MAX_AGE * 0.12f) else 1f
            val a = (225 * (dist / maxDist).coerceIn(0f, 1f) * life * fadeIn).toInt()
            if (a <= 0) continue
            paint.alpha = a
            canvas.drawCircle(p.x, p.y, particleR, paint)
        }
        paint.alpha = 255
    }

    /** 关闭边缘光点时清空所有雪花，避免重新打开时残留突现 */
    fun clearSnow() {
        particles.clear()
        lastSpawn = 0L
    }

    private class Particle(
        var x: Float, var y: Float,
        val inX: Float, val inY: Float,        // 朝中心的单位向量
        val perpX: Float, val perpY: Float,    // 垂直单位向量（飘摆方向）
        var age: Int,
        val swayAmp: Float,
        val swaySpeed: Float,
        val swayPhase: Float,
        val inward: Float
    )
}
