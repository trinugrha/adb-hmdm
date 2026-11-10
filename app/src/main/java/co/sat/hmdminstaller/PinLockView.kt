package co.sat.hmdminstaller

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Layar login PIN (overlay penuh) di atas MainActivity: kartu putih di atas latar redup,
 * 4 kotak PIN, "Tampilkan PIN", dan tombol "Masuk". Dibuat lewat kode agar koneksi ADB
 * yang berjalan di MainActivity tidak ikut terputus.
 */
class PinLockView(
    context: Context,
    private val store: PinStore,
    private val onUnlocked: () -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val input = EditText(context)
    private val boxes = mutableListOf<TextView>()
    private val boxRow = LinearLayout(context)
    private val message = TextView(context)
    private val eyeIcon = ImageView(context)
    private val eyeText = TextView(context)
    private val btnLogin = MaterialButton(context)
    private var showPin = false

    // ---- Terkunci sementara karena terlalu banyak salah ----
    // Harus dideklarasikan sebelum blok init, karena init memanggil startLockTicker().
    private val ticker = object : Runnable {
        override fun run() {
            val left = store.lockRemainingMs()
            if (left > 0) {
                setInputEnabled(false)
                val s = (left + 999) / 1000
                showMessage("Terlalu banyak percobaan salah. Coba lagi dalam %d:%02d".format(s / 60, s % 60))
                handler.postDelayed(this, 500)
            } else if (!input.isEnabled) {
                setInputEnabled(true)
                hideMessage()
                focusInput()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    init {
        isClickable = true            // telan semua sentuhan agar layar di bawahnya tidak bisa disentuh
        isFocusable = false
        setBackgroundColor(Color.argb(0xF2, 0x3E, 0x45, 0x56))   // latar redup (isi aplikasi tidak terbaca)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(color(R.color.surface))
            }
            elevation = dp(12).toFloat()
        }
        addView(card, LayoutParams(-1, -2, Gravity.CENTER).apply {
            marginStart = dp(20); marginEnd = dp(20)
        })

        // Ikon gembok dalam kotak hijau muda
        card.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList = ColorStateList.valueOf(color(R.color.accent))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(color(R.color.accent_soft))
            }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))

        card.addView(TextView(context).apply {
            text = "Otentikasi Pengguna"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })

        card.addView(TextView(context).apply {
            text = "Silakan login untuk mengakses HMDM ADB Installer"
            textSize = 14f
            setTextColor(color(R.color.muted))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        card.addView(TextView(context).apply {
            text = "Masukkan PIN ${PinStore.PIN_LENGTH} digit"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })

        // Kotak-kotak PIN
        boxRow.orientation = LinearLayout.HORIZONTAL
        boxRow.gravity = Gravity.CENTER
        repeat(PinStore.PIN_LENGTH) {
            val b = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text))
            }
            boxes.add(b)
            boxRow.addView(b, LinearLayout.LayoutParams(dp(56), dp(62)).apply {
                marginStart = dp(6); marginEnd = dp(6)
            })
        }
        boxRow.setOnClickListener { focusInput() }
        card.addView(boxRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        // EditText asli (tak terlihat) yang menampung ketikan; kotak di atas hanya tampilan.
        input.apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(PinStore.PIN_LENGTH))
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            isCursorVisible = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    paintBoxes()
                    if (message.tag == "err") hideMessage()
                    // Digit terakhir terisi → langsung diperiksa; jika benar langsung masuk.
                    if ((s?.length ?: 0) == PinStore.PIN_LENGTH) handler.postDelayed({ submit() }, 100)
                }
            })
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        }
        card.addView(input, LinearLayout.LayoutParams(dp(1), dp(1)))

        message.apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.danger))
            visibility = View.GONE
        }
        card.addView(message, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        // Tampilkan PIN
        val eyeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showPin = !showPin; updateEye(); paintBoxes(); focusInput() }
        }
        eyeIcon.imageTintList = ColorStateList.valueOf(color(R.color.muted))
        eyeText.apply { textSize = 14f; setTextColor(color(R.color.muted)) }
        eyeRow.addView(eyeIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        eyeRow.addView(eyeText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(eyeRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        updateEye()

        // Tombol Masuk
        btnLogin.apply {
            text = "Masuk"
            textSize = 16f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setIconResource(R.drawable.ic_login)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(8)
            cornerRadius = dp(14)
            setOnClickListener { submit() }
        }
        card.addView(btnLogin, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })

        paintBoxes()
        startLockTicker()
    }

    private fun updateEye() {
        eyeIcon.setImageResource(if (showPin) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        eyeText.text = if (showPin) "Sembunyikan PIN" else "Tampilkan PIN"
    }

    private fun paintBoxes() {
        val s = input.text?.toString().orEmpty()
        val locked = store.lockRemainingMs() > 0
        boxes.forEachIndexed { i, b ->
            b.text = when {
                i >= s.length -> ""
                showPin -> s[i].toString()
                else -> "•"
            }
            val active = !locked && i == s.length.coerceAtMost(PinStore.PIN_LENGTH - 1) && input.hasFocus()
            b.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color(R.color.box_bg))
                setStroke(dp(2), color(if (active) R.color.accent else R.color.stroke))
            }
        }
    }

    private fun showMessage(text: String) { message.text = text; message.tag = "err"; message.visibility = View.VISIBLE }
    private fun hideMessage() { message.tag = null; message.visibility = View.GONE }

    private fun submit() {
        if (store.lockRemainingMs() > 0) return
        val pin = input.text?.toString().orEmpty()
        if (pin.length < PinStore.PIN_LENGTH) {
            showMessage("PIN harus ${PinStore.PIN_LENGTH} digit")
            shake()
            return
        }
        if (store.verify(pin)) {
            hideKeyboard()
            onUnlocked()
            return
        }
        input.setText("")
        shake()
        if (store.lockRemainingMs() > 0) startLockTicker()
        else showMessage("PIN salah. Sisa percobaan: ${store.triesLeft()}")
    }

    private fun shake() {
        val d = dp(10).toFloat()
        boxRow.animate().translationX(d).setDuration(50).withEndAction {
            boxRow.animate().translationX(-d).setDuration(80).withEndAction {
                boxRow.animate().translationX(0f).setDuration(50).start()
            }.start()
        }.start()
    }

    private fun startLockTicker() { handler.removeCallbacks(ticker); handler.post(ticker) }

    private fun setInputEnabled(on: Boolean) {
        input.isEnabled = on
        btnLogin.isEnabled = on
        boxRow.alpha = if (on) 1f else 0.5f
        if (!on) { input.setText(""); hideKeyboard() }
    }

    // ---- Keyboard ----
    private fun focusInput() {
        if (!input.isEnabled) return
        input.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        paintBoxes()
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        input.setOnFocusChangeListener { _, _ -> paintBoxes() }
        handler.postDelayed({ focusInput() }, 250)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
