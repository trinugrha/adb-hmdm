package co.sat.hmdminstaller

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.OnBackPressedCallback
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    /** Swipe horizontal di mana saja pindah ke tab sebelah (Wireless <-> USB), selama tab tujuan tidak terkunci. */
    private val swipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || !::tabMode.isInitialized) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val d = resources.displayMetrics.density
                if (Math.abs(dx) < 80 * d || Math.abs(dx) < 2 * Math.abs(dy) || Math.abs(vx) < 400 * d) return false
                val target = tabMode.selectedTabPosition + if (dx < 0) 1 else -1
                val tab = tabMode.getTabAt(target) ?: return false
                if (!tab.view.isEnabled) return false
                tab.select()
                return true
            }
        })
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var kbHandledForGesture = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipeDetector.onTouchEvent(ev)
        // Geser layar ke atas/bawah => keyboard menutup.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                kbHandledForGesture = false
            }
            MotionEvent.ACTION_MOVE -> if (!kbHandledForGesture) {
                val dx = Math.abs(ev.x - touchDownX)
                val dy = Math.abs(ev.y - touchDownY)
                if (dy > ViewConfiguration.get(this).scaledTouchSlop && dy > dx) {
                    kbHandledForGesture = true
                    hideKeyboard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hideKeyboard() {
        val visible = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        val focus = currentFocus ?: return
        if (!visible) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(focus.windowToken, 0)
        focus.clearFocus()
    }

    private lateinit var edtPairIp: TextInputEditText
    private lateinit var edtPairPort: TextInputEditText
    private lateinit var edtPairCode: TextInputEditText
    private lateinit var edtConnIp: TextInputEditText
    private lateinit var edtConnPort: TextInputEditText
    private lateinit var txtLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var txtConn: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtApkTitle: TextView
    private lateinit var txtApkEmpty: TextView
    private lateinit var txtDropSub: TextView
    private lateinit var dropZone: View
    private lateinit var ownerPill: View
    private lateinit var apkListBox: android.widget.LinearLayout
    private lateinit var tabMode: TabLayout
    private lateinit var statusDot: View
    private lateinit var ownerDot: View
    private lateinit var txtOwner: TextView
    private lateinit var txtOwnerDetail: TextView
    private lateinit var tcpipDot: View
    private lateinit var txtTcpip: TextView
    private lateinit var appsPill: View
    private lateinit var appsDot: View
    private lateinit var txtApps: TextView
    private lateinit var txtAppsDetail: TextView
    private lateinit var homeDot: View
    private lateinit var txtHome: TextView
    private lateinit var txtHomeDetail: TextView
    private lateinit var permAdminDot: View
    private lateinit var txtPermAdmin: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: List<MaterialButton>
    // Tombol yang butuh koneksi ADB aktif: nonaktif (tidak bisa ditekan) selama belum terhubung.
    private lateinit var needsConnection: Set<MaterialButton>
    /** Tombol yang membuka sambungan baru: mati selama masih ada sambungan aktif (harus Disconnect dulu). */
    private lateinit var blockedWhenConnected: Set<MaterialButton>

    private val worker = Executors.newSingleThreadExecutor()
    // Penyalinan APK ke cache dipisah dari worker ADB agar tidak antre di belakang install, dan tidak membebani UI thread.
    private val io = Executors.newSingleThreadExecutor()
    private val apks = mutableListOf<ApkItem>()
    private lateinit var adb: AdbController
    @Volatile private var busy = false
    @Volatile private var connectedAddr: String? = null
    private var connectedSince = 0L
    private var lostAddr: String? = null
    @Volatile private var ownerState: OwnerState = OwnerState.Unknown
    @Volatile private var homeState: HomeState = HomeState.Unknown
    /** Apakah com.kaspersky.kes terpasang di HP target: null = belum diketahui (tombol Kaspersky mati). */
    @Volatile private var kasperskyInstalled: Boolean? = null
    /** Apakah com.hmdm.launcher terpasang di HP target: null = belum diketahui (tombol Owner/Launcher mati). */
    @Volatile private var hmdmInstalled: Boolean? = null
    /** Apakah adbd HP target sudah berjalan dalam mode TCP/IP (port aktif): true = tombol "Ubah Mode ke TCP/IP" dimatikan. */
    @Volatile private var tcpipActive: Boolean? = null
    /** Status tiap aplikasi wajib (AdbController.REQUIRED_PACKAGES) di HP target: null = belum diketahui. */
    @Volatile private var appsInstalled: Map<String, Boolean>? = null
    /** Status gabungan tiga permission com.hmdm.launcher (admin perangkat, overlay, riwayat penggunaan). */
    private fun refreshPerms() {
        if (!::txtPermAdmin.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val p = if (connected) hmdmPerms else null
        val grey = Color.rgb(148, 163, 184)
        val green = Color.rgb(74, 222, 128)
        val red = Color.rgb(248, 113, 113)
        val amber = Color.rgb(251, 191, 36)
        // Device owner otomatis juga admin perangkat aktif.
        val admin = if (p?.admin == true || (connected && ownerState is OwnerState.Hmdm)) true else p?.admin
        val values = listOf(admin, p?.overlay, p?.usage)
        val (text, color) = when {
            !connected -> "Belum terhubung" to grey
            p == null || values.any { it == null } -> "Belum diperiksa" to grey
            values.all { it == true } -> "Aktif ✓" to green
            values.none { it == true } -> "Belum aktif" to red
            else -> "Belum lengkap (${values.count { it == true }}/3)" to amber
        }
        txtPermAdmin.text = text
        txtPermAdmin.setTextColor(color)
        permAdminDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    /** Status default launcher: aktif bila launcher home default HP target adalah com.hmdm.launcher. */
    private fun refreshHome() {
        if (!::txtHome.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) homeState else HomeState.Unknown
        val (text, color) = when (st) {
            is HomeState.Hmdm -> "Aktif ✓" to Color.rgb(74, 222, 128)
            is HomeState.Other -> "Belum aktif · launcher lain" to Color.rgb(248, 113, 113)
            HomeState.None -> "Belum ada default" to Color.rgb(251, 191, 36)
            HomeState.Unknown ->
                (if (connected) "Belum diperiksa" else "Belum terhubung") to Color.rgb(148, 163, 184)
        }
        txtHome.text = text
        homeDot.backgroundTintList = ColorStateList.valueOf(color)
        val detail = when (st) {
            is HomeState.Hmdm -> st.component
            is HomeState.Other -> st.component
            else -> null
        }
        txtHomeDetail.text = detail ?: ""
        txtHomeDetail.visibility = if (detail != null) View.VISIBLE else View.GONE
    }

    private fun refreshApps() {
        if (!::txtApps.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) appsInstalled else null
        val total = AdbController.REQUIRED_PACKAGES.size
        val missing = st?.filterValues { !it }?.keys?.toList() ?: emptyList()
        val (text, color) = when {
            st == null -> (if (connected) "Belum diperiksa" else "Belum terhubung") to Color.rgb(148, 163, 184)
            missing.isEmpty() -> "Semua terinstal ✓ ($total/$total)" to Color.rgb(74, 222, 128)
            else -> "Belum lengkap (${total - missing.size}/$total)" to Color.rgb(248, 113, 113)
        }
        txtApps.text = text
        appsDot.backgroundTintList = ColorStateList.valueOf(color)
        if (missing.isNotEmpty()) {
            txtAppsDetail.text = "Belum terinstal:\n" + missing.joinToString("\n")
            txtAppsDetail.visibility = View.VISIBLE
        } else {
            txtAppsDetail.text = ""
            txtAppsDetail.visibility = View.GONE
        }
    }

    private fun refreshTcpip() {
        if (!::txtTcpip.isInitialized) return
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) tcpipActive else null
        val (text, color) = when (st) {
            true -> "Aktif ✓" to Color.rgb(74, 222, 128)
            false -> "Belum aktif" to Color.rgb(251, 191, 36)
            null -> (if (connected) "Belum diperiksa" else "Belum terhubung") to Color.rgb(148, 163, 184)
        }
        txtTcpip.text = text
        tcpipDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun refreshOwner() {
        val connected = connectedAddr != null && adb.isConnected
        val st = if (connected) ownerState else OwnerState.Unknown
        val (text, color) = when (st) {
            is OwnerState.Hmdm -> "HMDM aktif ✓" to Color.rgb(74, 222, 128)
            is OwnerState.Other -> "Aplikasi lain" to Color.rgb(248, 113, 113)
            OwnerState.None -> "Belum diset" to Color.rgb(251, 191, 36)
            OwnerState.Unknown ->
                (if (connected) "Belum diperiksa" else "Belum terhubung") to Color.rgb(148, 163, 184)
        }
        txtOwner.text = text
        ownerDot.backgroundTintList = ColorStateList.valueOf(color)
        val detail = when (st) {
            is OwnerState.Hmdm -> st.pkg
            is OwnerState.Other -> st.pkg
            else -> null
        }
        txtOwnerDetail.text = detail ?: ""
        txtOwnerDetail.visibility = if (detail != null) View.VISIBLE else View.GONE
    }

    private fun refreshApkList() {
        if (apks.isEmpty()) {
            txtApkEmpty.text = "Pilih File APK Di Sini"
            txtDropSub.text = "Dapat memilih lebih dari satu file APK"
            txtApkTitle.text = "Unggah File APK (Bisa Banyak)"
        } else {
            txtApkEmpty.text = "${apks.size} APK dipilih"
            txtDropSub.text = "Ketuk untuk memilih ulang (daftar sebelumnya diganti)"
            txtApkTitle.text = "Unggah File APK (${apks.size})"
        }
        renderApkList()
        refreshHeader()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Cache nama paket per file APK (kunci: path + ukuran + waktu ubah) agar file tidak di-parse berulang. */
    private val apkPkgCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Nama paket (mis. com.hmdm.launcher) dari file APK; "" bila tidak terbaca. */
    private fun apkPackage(item: ApkItem): String {
        val f = item.file
        val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        return apkPkgCache.getOrPut(key) {
            try {
                packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.packageName ?: ""
            } catch (e: Throwable) { "" }
        }
    }

    /** Apakah daftar APK yang dipilih berisi com.hmdm.launcher. */
    private fun hasHmdmApk(): Boolean = apks.any { apkPackage(it) == AdbController.HMDM_PACKAGE }

    /** Tampilkan daftar APK yang akan dipasang (urut sesuai nama) lengkap dengan ukuran & tombol hapus. */
    private fun renderApkList() {
        apkListBox.removeAllViews()
        if (apks.isEmpty()) { apkListBox.visibility = View.GONE; return }
        apkListBox.visibility = View.VISIBLE

        apkListBox.addView(TextView(this).apply {
            text = "DAFTAR APLIKASI YANG AKAN DIPASANG (${apks.size})"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            setPadding(dp(2), 0, 0, dp(6))
        })

        apks.forEachIndexed { i, item ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(8), dp(4), dp(8))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) topMargin = dp(6) }
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                minWidth = dp(22)
            })
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = item.name
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            col.addView(TextView(this).apply {
                text = item.sizeText
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            })
            row.addView(col)
            row.addView(android.widget.ImageButton(this).apply {
                id = R.id.apkRemoveTag
                setImageResource(R.drawable.ic_delete)
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.danger))
                background = null
                contentDescription = "Hapus ${item.name} dari daftar"
                val canRemove = !busy && connectedAddr != null && adb.isConnected
                isEnabled = canRemove
                isClickable = canRemove
                alpha = if (canRemove) 1f else 0.3f
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener {
                    if (busy || !(connectedAddr != null && adb.isConnected)) return@setOnClickListener
                    apks.remove(item)
                    if (!item.builtIn) io.execute { item.file.delete() }
                    refreshApkList()
                }
            })
            apkListBox.addView(row)
        }
    }

    /** Pembuka satu proses: "── Judul · 14:02:11". */
    private fun logHead(title: String, at: Long = System.currentTimeMillis()) {
        // Pesan lepas (mis. APK dilewati, koneksi terputus) tidak diakhiri baris kosong; pisahkan di sini.
        val cur = txtLog.text
        if (cur.isNotEmpty() && !cur.endsWith("\n\n")) logBlank()
        log(LogLevel.HEAD, "$title · ${Fmt.clock(at)}")
    }

    /** Penutup satu proses: "✓ Selesai: Judul · 4.2 dtk" atau "✗ Gagal: Judul · 4.2 dtk", lalu baris kosong pemisah. */
    private fun logEnd(title: String, ok: Boolean, startedAt: Long) {
        val took = Fmt.duration(System.currentTimeMillis() - startedAt)
        if (ok) log(LogLevel.END_OK, "Selesai: $title · $took")
        else log(LogLevel.END_ERR, "Gagal: $title · $took")
        logBlank()
    }

    /** Satu baris kosong sebagai pemisah antar proses. */
    private fun logBlank() {
        txtLog.append("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Satu entri log berwarna. Warna dan penanda ditentukan oleh [level];
     * pesan multi-baris dirapikan (baris lanjutan diberi indent selebar penanda), dan titik di
     * akhir pesan dibuang supaya semua baris seragam (elipsis "..." dibiarkan).
     */
    private fun log(level: LogLevel, s: String) {
        if (s.isBlank()) return
        val indent = " ".repeat(level.marker.length)
        val body = s.trimEnd().let {
            // Output mentah device (DETAIL) tidak diubah sama sekali.
            if (level != LogLevel.DETAIL && it.endsWith(".") && !it.endsWith("..")) it.dropLast(1) else it
        }
        val lines = body.lines().map { it.trimEnd() }.map { if (level == LogLevel.DETAIL) Fmt.clip(it) else it }
        val text = lines.mapIndexed { i, l -> (if (i == 0) level.marker else indent) + l }.joinToString("\n") + "\n"
        val sp = SpannableString(text)
        sp.setSpan(ForegroundColorSpan(levelColor(level)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (level.bold) sp.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Hanging indent: baris lanjutan akibat teks panjang sejajar dengan isi pesan, bukan dengan penanda.
        val hang = (txtLog.paint.measureText("M") * level.marker.length).toInt()
        if (hang > 0) sp.setSpan(LeadingMarginSpan.Standard(0, hang), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        txtLog.append(sp)
        trimLog()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Batasi panjang log agar tidak membengkak: buang baris paling lama (dipotong di batas baris). */
    private fun trimLog() {
        val cur = txtLog.text
        if (cur.length <= LOG_MAX_CHARS) return
        val sb = android.text.SpannableStringBuilder(cur)
        val cut = sb.indexOf("\n", sb.length - LOG_KEEP_CHARS)
        sb.delete(0, if (cut < 0) sb.length - LOG_KEEP_CHARS else cut + 1)
        txtLog.text = sb
    }

    private fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.HEAD, LogLevel.SUMMARY -> Color.WHITE
        LogLevel.STEP -> Color.rgb(252, 211, 77)
        LogLevel.CMD -> Color.rgb(56, 189, 248)
        LogLevel.OK, LogLevel.END_OK -> Color.rgb(74, 222, 128)
        LogLevel.WARN -> Color.rgb(251, 191, 36)
        LogLevel.ERR, LogLevel.END_ERR -> Color.rgb(248, 113, 113)
        LogLevel.DETAIL -> Color.rgb(148, 163, 184)
    }

    private fun clean(s: String) = s.trim().replace("\"", "").replace(" ", "")

    /** Gabung IP + port jadi "ip:port"; toleran bila user menempel "ip:port" di kolom IP. */
    private fun addr(ipText: String, portText: String): String {
        var ip = clean(ipText)
        var port = clean(portText)
        val i = ip.lastIndexOf(':')
        if (i > 0 && port.isEmpty()) {
            port = ip.substring(i + 1)
            ip = ip.substring(0, i)
        }
        return if (ip.isEmpty() || port.isEmpty()) "" else "$ip:$port"
    }

    /** Muat ulang APK yang sudah disalin ke cache (mis. setelah proses aplikasi dimatikan sistem). */
    private fun restorePickedApks() {
        val dir = File(cacheDir, "picked")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) } ?: return
        files.sortedBy { it.name.lowercase() }.forEach { apks.add(ApkItem(it.name, it)) }
    }

    private fun displayName(uri: Uri): String {
        var name = "file-${System.currentTimeMillis()}.apk"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (e: Exception) { }
        // Cegah path traversal dari nama file yang aneh.
        return name.substringAfterLast('/')
    }

    /** Salin ke cache. Dipanggil dari thread latar (bukan UI thread). */
    private fun copyToCache(uri: Uri, name: String): ApkItem? {
        val dir = File(cacheDir, "picked").apply { mkdirs() }
        val f = File(dir, name)
        return try {
            val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("tidak bisa membuka file")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ApkItem(name, f)
        } catch (e: Exception) {
            f.delete() // jangan tinggalkan file setengah jadi
            runOnUiThread {
                log(LogLevel.ERR, "Salin $name gagal: file tidak bisa dibaca atau disimpan. Pilih ulang file APK")
                log(LogLevel.DETAIL, Fmt.cause(e))
            }
            null
        }
    }

    companion object {
        /** Aplikasi dikunci lagi bila ditinggalkan lebih dari ini (ms). */
        private const val LOCK_TIMEOUT_MS = 30 * 60_000L
        private const val LOG_MAX_CHARS = 60_000
        private const val LOG_KEEP_CHARS = 40_000
    }
}
