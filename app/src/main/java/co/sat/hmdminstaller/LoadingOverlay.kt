package co.sat.hmdminstaller

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * Kartu "sedang memproses" yang melayang di bawah layar selama [MainActivity] sibuk
 * (connect, pair, install, set owner, menyalin APK, dll).
 *
 * Isi kartu: spinner dua busur, teks status, stopwatch, dan garis geser di tepi bawah.
 * Tidak memblokir sentuhan, jadi log tetap bisa digulir; tombol sudah dimatikan oleh setBusy().
 */
class LoadingOverlay(private val activity: Activity) {

    private val card = Card(activity)
    private val spinner = SpinnerView(activity)
    private val txtTitle = TextView(activity)
    private val txtTime = TextView(activity)
    private var startedAt = 0L
    private var lastSec = -1L
    private var shown = false

    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            spinner.phase = t
            card.phase = t
            val sec = (SystemClock.elapsedRealtime() - startedAt) / 1000
            if (sec != lastSec) {
                lastSec = sec
                txtTime.text = "%02d:%02d".format(sec / 60, sec % 60)
            }
        }
    }

    init {
        val ctx: Context = activity
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(16), dp(12), dp(18), dp(14))
        card.elevation = dp(8).toFloat()
        card.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(ContextCompat.getColor(ctx, R.color.surface))
            setStroke(dp(1), ContextCompat.getColor(ctx, R.color.stroke))
        }

        card.addView(spinner, LinearLayout.LayoutParams(dp(30), dp(30)))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        txtTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text))
        txtTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        txtTitle.setTypeface(txtTitle.typeface, android.graphics.Typeface.BOLD)
        txtTitle.maxLines = 2
        txtTime.setTextColor(ContextCompat.getColor(ctx, R.color.muted))
        txtTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        txtTime.typeface = android.graphics.Typeface.MONOSPACE
        col.addView(txtTitle)
        col.addView(txtTime)
        card.addView(col, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(14) })

        card.visibility = View.GONE
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(88)  // di atas tab melayang
            marginStart = dp(20)
            marginEnd = dp(20)
        }
        activity.findViewById<ViewGroup>(android.R.id.content).addView(card, lp)
    }

    fun show(text: String) {
        txtTitle.text = text
        if (shown) return
        shown = true
        startedAt = SystemClock.elapsedRealtime()
        lastSec = -1
        clock.start()
        card.animate().cancel()
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.translationY = dp(24).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun update(text: String) { if (shown) txtTitle.text = text }

    fun hide() {
        if (!shown) return
        shown = false
        card.animate().cancel()
        card.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(180)
            .withEndAction {
                if (!shown) { card.visibility = View.GONE; clock.cancel() }
            }.start()
    }

    fun release() {
        shown = false
        clock.cancel()
        card.animate().cancel()
        (card.parent as? ViewGroup)?.removeView(card)
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    /** Kartu dengan garis aksen yang menyapu di tepi bawah. */
    private class Card(ctx: Context) : LinearLayout(ctx) {
        var phase = 0f
            set(v) { field = v; invalidate() }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(ctx, R.color.accent)
        }
        private val clip = Path()
        private val rect = RectF()
        private val d = ctx.resources.displayMetrics.density

        init { setWillNotDraw(false) }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val h = 3 * d
            val seg = width * 0.35f
            val x = -seg + (width + seg) * phase
            canvas.save()
            clip.reset()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            clip.addRoundRect(rect, 16 * d, 16 * d, Path.Direction.CW)
            canvas.clipPath(clip)
            barPaint.alpha = 60
            canvas.drawRect(0f, height - h, width.toFloat(), height.toFloat(), barPaint)
            barPaint.alpha = 255
            canvas.drawRect(x, height - h, x + seg, height.toFloat(), barPaint)
            canvas.restore()
        }
    }

    /** Spinner: busur luar berputar dan memanjang-memendek, busur dalam berputar berlawanan. */
    private class SpinnerView(ctx: Context) : View(ctx) {
        var phase = 0f
            set(v) { field = v; invalidate() }
        private val accent = ContextCompat.getColor(ctx, R.color.accent)
        private val d = ctx.resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3 * d; color = accent; alpha = 40
        }
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND; color = accent
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND
            color = accent; alpha = 140
        }
        private val oval = RectF()

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val pad = 2 * d
            val r = c - pad
            oval.set(pad, pad, width - pad, height - pad)
            canvas.drawCircle(c, c, r, track)
            val sweep = 60f + 200f * (0.5f + 0.5f * sin(phase * 2 * Math.PI - Math.PI / 2).toFloat())
            canvas.drawArc(oval, phase * 720f - 90f, sweep, false, outer)
            val ip = pad + 6 * d
            oval.set(ip, ip, width - ip, height - ip)
            canvas.drawArc(oval, -phase * 540f, 100f, false, inner)
        }
    }
}
