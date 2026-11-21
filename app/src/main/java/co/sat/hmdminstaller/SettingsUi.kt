package co.sat.hmdminstaller

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** Ukuran dp -> piksel. Dipakai bersama oleh semua tampilan yang dibuat lewat kode. */
internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Ukuran dari dimens.xml dalam piksel. */
internal fun Context.px(@DimenRes id: Int): Int = resources.getDimensionPixelSize(id)

/** Warna dari colors.xml. */
internal fun Context.colorOf(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

/**
 * Pembuat komponen untuk dialog pengaturan (Model Perangkat, Aplikasi & Versi) dan daftar yang dibuat lewat kode.
 * Warna dan ukuran diambil dari resource supaya sama dengan layar utama.
 */
internal class SettingsUi(private val ctx: Context) {

    val cText = ctx.colorOf(R.color.accent_text)
    val cMuted = ctx.colorOf(R.color.muted)
    val cSurface = ctx.colorOf(R.color.surface)
    val cOutline = ctx.colorOf(R.color.outline)
    val cChip = ctx.colorOf(R.color.chip_bg)
    val cStroke = ctx.colorOf(R.color.stroke)
    val cSoft = ctx.colorOf(R.color.accent_text_soft)
    val cMain = ctx.colorOf(R.color.btn_main)
    val cOnMain = ctx.colorOf(R.color.on_tab_button)
    val cDanger = ctx.colorOf(R.color.danger)
    val cEdit = ctx.colorOf(R.color.icon_edit)

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = ctx.dp(v)

    fun rounded(fill: Int, stroke: Int? = null, @DimenRes radius: Int = R.dimen.radius_sm): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.resources.getDimension(radius)
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    /** Lebar penuh dengan jarak atas [top] dp; tinggi [h] (default menyesuaikan isi). */
    fun lpTop(top: Int, h: Int = wrap) = LinearLayout.LayoutParams(match, h).apply { topMargin = dp(top) }

    /** Untuk kolom isian di dalam popup. */
    fun lpField(top: Int = 6) = lpTop(top, ctx.px(R.dimen.control_height_dialog))

    fun label(s: String, bold: Boolean = false, size: Float = 12f, color: Int = cMuted) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.25f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** Judul kecil huruf besar di atas daftar/kelompok. */
    fun sectionLabel(s: String) = label(s, bold = true, size = 11f, color = cText).apply { letterSpacing = 0.08f }

    fun field(placeholder: String, value: String, capitalize: Boolean = false) = EditText(ctx).apply {
        setText(value)
        hint = placeholder
        textSize = 14f
        setTextColor(cText)
        setHintTextColor(cMuted)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (capitalize) InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else 0)
        background = rounded(cSurface, cOutline)
        setPadding(dp(12), 0, dp(12), 0)
    }

    /** Tombol utama (terisi navy) dengan ikon tambah. Warna mengikuti tema (Widget.Hmdm.Button). */
    fun addButton(text: String) = MaterialButton(ctx).apply {
        this.text = text
        setIconResource(R.drawable.ic_add)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        iconPadding = dp(8)
    }

    /** Tombol bingkai (Widget.Hmdm.Button.Outlined) dengan ikon di kiri teks. */
    fun outlinedButton(text: String, icon: Int) =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(6)
        }

    /** Tombol ikon 40dp: latar membulat berwarna lembut dari [tint], efek ketuk (ripple). */
    fun iconButton(res: Int, tint: Int, desc: String, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, Color.red(tint), Color.green(tint), Color.blue(tint))),
            rounded(Color.argb(32, Color.red(tint), Color.green(tint), Color.blue(tint))),
            null
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * Satu baris daftar: judul + lencana di kiri, tombol aksi di kanan.
     * [badgeMuted] true = lencana abu-abu (mis. "tanpa cek versi"), false = lencana aksen.
     */
    fun itemCard(title: String, badge: String, badgeMuted: Boolean, vararg actions: View): LinearLayout {
        val name = label(title, bold = true, size = 13f, color = cText).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val badgeView = TextView(ctx).apply {
            text = badge
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (badgeMuted) cMuted else cText)
            background = rounded(if (badgeMuted) cChip else cSoft, null, R.dimen.radius_xs)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(badgeView, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(8) })
        }
        val size = ctx.px(R.dimen.icon_button_size)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cSurface, cStroke, R.dimen.radius_lg)
            setPadding(dp(16), dp(12), dp(10), dp(12))
            addView(textCol, LinearLayout.LayoutParams(0, wrap, 1f))
            for (a in actions) addView(a, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(8) })
        }
    }

    class Segment(val view: LinearLayout, val refresh: () -> Unit)

    /**
     * Pilihan jenis perangkat berbentuk tombol segmen (POS / TABLET / PDA).
     * [onSelect] dipanggil hanya bila jenis yang diketuk berbeda dari [selected]; tampilan diperbarui setelahnya.
     */
    fun typeSegment(types: List<String>, selected: () -> String, onSelect: (String) -> Unit): Segment {
        val chips = LinkedHashMap<String, TextView>()
        val refresh: () -> Unit = {
            for ((t, tv) in chips) {
                val sel = t == selected()
                tv.background = if (sel) rounded(cMain) else null
                tv.setTextColor(if (sel) cOnMain else cMuted)
            }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cChip, null, R.dimen.radius_md)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            for (t in types) {
                val tv = TextView(ctx).apply {
                    text = t
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (t == selected()) return@setOnClickListener
                        onSelect(t)
                        refresh()
                    }
                }
                chips[t] = tv
                addView(tv, LinearLayout.LayoutParams(0, ctx.px(R.dimen.segment_height), 1f))
            }
        }
        refresh()
        return Segment(row, refresh)
    }

    /** Badan dialog pengaturan: jenis perangkat, tombol tambah, tombol Export/Import, lalu [list]. */
    fun body(typeRow: View, btnAdd: View, btnExport: View, btnImport: View, list: View): LinearLayout {
        val h = ctx.px(R.dimen.control_height)
        val io = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnExport, LinearLayout.LayoutParams(0, h, 1f))
            addView(btnImport, LinearLayout.LayoutParams(0, h, 1f).apply { marginStart = dp(8) })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(sectionLabel("JENIS PERANGKAT"))
            addView(typeRow, lpTop(10))
            addView(btnAdd, lpTop(12, h))
            addView(io, lpTop(8, h))
            addView(list)
        }
    }
}
