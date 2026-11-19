package co.sat.hmdminstaller

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * Animasi loading: dua riak (ripple) yang memancar dari titik tengah yang berdenyut,
 * dengan busur berputar mengelilinginya. Berjalan hanya saat view terlihat & menempel di window.
 */
class BusyIndicatorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val arcRect = RectF()
    private var t = 0f // 0..1, berulang
    private var animator: ValueAnimator? = null

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() { animator?.cancel(); animator = null }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (visibility == VISIBLE) start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)

        // Riak: membesar sambil memudar, dua fase berbeda.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.06f
        for (phase in 0..1) {
            val p = (t + phase * 0.5f) % 1f
            paint.alpha = ((1f - p) * 110).toInt()
            c.drawCircle(cx, cy, r * (0.30f + 0.70f * p), paint)
        }

        // Busur berputar.
        paint.alpha = 255
        paint.strokeWidth = r * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        val ar = r * 0.62f
        arcRect.set(cx - ar, cy - ar, cx + ar, cy + ar)
        val sweep = 100f + 60f * kotlin.math.sin(t * 2 * Math.PI).toFloat()
        c.drawArc(arcRect, t * 360f * 2f, sweep, false, paint)

        // Titik tengah berdenyut.
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        val pulse = 0.14f + 0.04f * kotlin.math.sin(t * 2 * Math.PI * 2).toFloat()
        c.drawCircle(cx, cy, r * pulse, paint)
    }
}
