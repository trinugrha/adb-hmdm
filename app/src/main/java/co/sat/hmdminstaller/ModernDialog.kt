package co.sat.hmdminstaller

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Popup bergaya modern: sudut 28dp, margin kiri-kanan, dan tombol penuh lebar yang
 * ditumpuk ke bawah (aksi utama di atas, "Batal" di bawah).
 *
 * API-nya meniru AlertDialog.Builder supaya mudah dipakai:
 *   ModernDialog(ctx).title("...").message("...").positive("OK") { }.negative("Batal").show()
 *
 * Aksi tombol menerima dialog-nya. Untuk tombol yang tidak boleh menutup dialog otomatis
 * (mis. validasi isian), pakai positive(..., dismiss = false) lalu panggil it.dismiss() sendiri.
 */
class ModernDialog(private val ctx: Context) {

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var content: View? = null
    private var items: Array<String>? = null
    private var onPick: ((Int) -> Unit)? = null
    private var posText: CharSequence? = null
    private var posDanger = false
    private var posDismiss = true
    private var posAction: ((AlertDialog) -> Unit)? = null
    private var negText: CharSequence? = null
    private var negAction: ((AlertDialog) -> Unit)? = null
    private var dismissListener: (() -> Unit)? = null

    fun title(t: CharSequence) = apply { title = t }
    fun message(m: CharSequence) = apply { message = m }
    fun view(v: View) = apply { content = v }
    fun items(names: Array<String>, pick: (Int) -> Unit) = apply { items = names; onPick = pick }
    fun positive(text: CharSequence, danger: Boolean = false, dismiss: Boolean = true, action: (AlertDialog) -> Unit = {}) = apply {
        posText = text; posDanger = danger; posDismiss = dismiss; posAction = action
    }
    fun negative(text: CharSequence, action: (AlertDialog) -> Unit = {}) = apply { negText = text; negAction = action }
    fun onDismiss(l: () -> Unit) = apply { dismissListener = l }

    fun create(): AlertDialog {
        var ref: AlertDialog? = null

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }

        title?.let {
            root.addView(TextView(ctx).apply {
                text = it
                setTextColor(color(R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTypeface(typeface, Typeface.BOLD)
            }, wrap())
        }

        message?.let {
            val tv = TextView(ctx).apply {
                text = it
                setTextColor(color(R.color.muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            val sv = ScrollView(ctx).apply { isFillViewport = false; addView(tv) }
            root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(10) })
        }

        content?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            root.addView(it, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = if (title != null || message != null) dp(8) else 0 })
        }

        items?.let { names ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            names.forEachIndexed { i, label ->
                col.addView(button(label, outlined = true).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setOnClickListener { ref?.dismiss(); onPick?.invoke(i) }
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = if (i == 0) 0 else dp(8) })
            }
            val sv = ScrollView(ctx).apply { addView(col) }
            root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(14) })
        }

        posText?.let {
            root.addView(button(it, outlined = false, danger = posDanger).apply {
                setOnClickListener {
                    val d = ref ?: return@setOnClickListener
                    if (posDismiss) d.dismiss()
                    posAction?.invoke(d)
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
        }
        negText?.let {
            root.addView(button(it, outlined = true).apply {
                setOnClickListener {
                    val d = ref ?: return@setOnClickListener
                    d.dismiss()
                    negAction?.invoke(d)
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(if (posText != null) 8 else 18) })
        }

        val dlg = AlertDialog.Builder(ctx).setView(root).create()
        dismissListener?.let { l -> dlg.setOnDismissListener { l() } }
        styleWindow(dlg)
        ref = dlg
        return dlg
    }

    fun show(): AlertDialog {
        val d = create()
        d.show()
        // Izinkan keyboard muncul untuk kolom isian di dalam popup.
        d.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        return d
    }

    private fun button(text: CharSequence, outlined: Boolean, danger: Boolean = false): MaterialButton {
        val attr = if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle
        return MaterialButton(ctx, null, attr).apply {
            this.text = text
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(48)
            minimumHeight = dp(48)
            cornerRadius = dp(14)
            if (!outlined) {
                backgroundTintList = ColorStateList.valueOf(color(if (danger) R.color.danger_fill else R.color.btn_main))
                setTextColor(color(if (danger) R.color.on_danger_fill else R.color.on_tab_button))
            } else {
                setTextColor(color(R.color.text))
                strokeColor = ColorStateList.valueOf(color(R.color.outline))
            }
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(ctx, id)
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun wrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Latar popup: sudut 28dp dengan jarak ke tepi layar. Dipakai juga oleh popup hasil. */
        fun styleWindow(dlg: AlertDialog) {
            val ctx = dlg.context
            val d = ctx.resources.displayMetrics.density
            val bg = GradientDrawable().apply {
                cornerRadius = 28 * d
                setColor(ContextCompat.getColor(ctx, R.color.surface))
            }
            dlg.window?.setBackgroundDrawable(InsetDrawable(bg, (12 * d).toInt()))
        }
    }
}
