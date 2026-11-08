package com.alfamart.hmdminstaller

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var isBusy = false
    private var apkUris = mutableListOf<Uri>()
    private var apkNames = mutableListOf<String>()

    private lateinit var loginModal: View
    private lateinit var mainApp: View
    private lateinit var logOutput: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var deviceInfo: TextView
    private lateinit var ownerBadge: TextView
    private lateinit var fileNameDisplay: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnInstall: Button
    private lateinit var btnAutoAll: Button
    private lateinit var sessionTimer: TextView

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            contentResolver.takePersistableUriPermissionSafe(uris)
            apkUris = uris.toMutableList()
            apkNames = uris.map { uri -> queryName(uri) }.toMutableList()
            updateFileDisplay()
            appendLog("${uris.size} file APK dipilih.", "ok")
        }
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ikuti tema sistem saat pertama dibuka
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginModal = findViewById(R.id.loginModal)
        mainApp = findViewById(R.id.mainApp)
        logOutput = findViewById(R.id.logOutput)
        logScroll = findViewById(R.id.logScroll)
        deviceInfo = findViewById(R.id.deviceInfo)
        ownerBadge = findViewById(R.id.ownerBadge)
        fileNameDisplay = findViewById(R.id.fileNameDisplay)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        btnInstall = findViewById(R.id.btnInstall)
        btnAutoAll = findViewById(R.id.btnAutoAll)
        sessionTimer = findViewById(R.id.sessionTimer)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_theme -> {
                    val night = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
                    AppCompatDelegate.setDefaultNightMode(
                        if (night) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                    )
                    true
                }
                R.id.menu_logout -> {
                    if (isBusy) {
                        AlertDialog.Builder(this)
                            .setMessage("Proses masih berjalan. Tetap keluar?")
                            .setPositiveButton("Keluar") { _, _ -> doLogout() }
                            .setNegativeButton("Batal", null).show()
                    } else doLogout()
                    true
                }
                else -> false
            }
        }

        setupPin()
        findViewById<Button>(R.id.btnPickApk).setOnClickListener {
            pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
        }
        btnInstall.setOnClickListener { runTask { installAll(keepSelection = false) } }
        btnAutoAll.setOnClickListener { runTask { autoAll() } }
        findViewById<Button>(R.id.btnCheckOwner).setOnClickListener { runTask { checkOwner() } }
        findViewById<Button>(R.id.btnSetOwner).setOnClickListener { runTask { setOwner() } }
        findViewById<Button>(R.id.btnClearKaspersky).setOnClickListener {
            confirm("Hapus seluruh data Kaspersky (com.kaspersky.kes)?") { runTask { clearKaspersky() } }
        }
        findViewById<Button>(R.id.btnUninstallKaspersky).setOnClickListener {
            confirm("Uninstal Kaspersky (com.kaspersky.kes)?") { runTask { uninstallKaspersky() } }
        }
        findViewById<Button>(R.id.btnWireless).setOnClickListener { showWireless() }
        findViewById<Button>(R.id.btnCopyOwnerCmd).setOnClickListener { copyOwnerCmd() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logOutput.text = ""
            appendLog("Log dibersihkan.", "info")
        }

        if (AuthManager.isLoggedIn(this)) showMain() else showLogin()
    }

    // ---------- LOGIN ----------
    private fun pinBoxes() = listOf<EditText>(
        findViewById(R.id.pin1), findViewById(R.id.pin2),
        findViewById(R.id.pin3), findViewById(R.id.pin4)
    )

    private fun setupPin() {
        val boxes = pinBoxes()
        boxes.forEachIndexed { i, box ->
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    var v = s.toString().filter { it.isDigit() }
                    if (v.length > 1) v = v.last().toString()
                    if (v != s.toString()) { box.setText(v); box.setSelection(v.length) }
                    if (v.isNotEmpty() && i < 3) boxes[i + 1].requestFocus()
                    if (boxes.all { it.text.length == 1 }) tryLogin()
                }
            })
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    box.text.isEmpty() && i > 0
                ) {
                    boxes[i - 1].requestFocus(); boxes[i - 1].setText("")
                    true
                } else false
            }
        }
        findViewById<Button>(R.id.btnLogin).setOnClickListener { tryLogin() }
    }

    private fun pinValue() = pinBoxes().joinToString("") { it.text.toString() }

    private fun tryLogin() {
        val err = findViewById<TextView>(R.id.loginError)
        val wait = AuthManager.lockUntil(this) - System.currentTimeMillis()
        if (wait > 0) {
            err.text = "Terlalu banyak percobaan. Coba lagi dalam ${wait / 1000 + 1} detik."
            err.visibility = View.VISIBLE
            return
        }
        val pin = pinValue()
        if (pin.length < 4) {
            err.text = "Lengkapi 4 digit PIN."
            err.visibility = View.VISIBLE
            return
        }
        if (AuthManager.checkPin(pin)) {
            AuthManager.resetFail(this)
            AuthManager.saveLogin(this)
            err.visibility = View.GONE
            pinBoxes().forEach { it.setText("") }
            showMain()
        } else {
            val until = AuthManager.recordFail(this)
            err.text = if (until > System.currentTimeMillis()) "PIN salah! Terkunci sementara."
            else "PIN salah!"
            err.visibility = View.VISIBLE
            pinBoxes().forEach { it.setText("") }
            pinBoxes()[0].requestFocus()
        }
    }

    private fun showLogin() {
        loginModal.visibility = View.VISIBLE
        mainApp.visibility = View.GONE
        handler.removeCallbacks(timerTick)
    }

    private fun showMain() {
        loginModal.visibility = View.GONE
        mainApp.visibility = View.VISIBLE
        resetLog("Sistem siap (v2.5-android). Berjalan langsung di perangkat.")
        loadDeviceInfo()
        runTask { checkOwner(silentHead = false) }
        handler.post(timerTick)
        refreshUi()
    }

    private fun doLogout() {
        AuthManager.logout(this)
        handler.removeCallbacks(timerTick)
        showLogin()
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (!AuthManager.isLoggedIn(this@MainActivity)) {
                if (isBusy) {
                    sessionTimer.visibility = View.VISIBLE
                    sessionTimer.text = "0m 00s (diperpanjang selama proses)"
                    handler.postDelayed(this, 1000)
                } else {
                    Toast.makeText(this@MainActivity, "Sesi 20 menit habis. Silakan login ulang.", Toast.LENGTH_LONG).show()
                    doLogout()
                }
                return
            }
            val rem = AuthManager.remainingMs(this@MainActivity)
            val m = rem / 60000
            val s = (rem % 60000) / 1000
            sessionTimer.visibility = View.VISIBLE
            sessionTimer.text = "Sesi: ${m}m ${if (s < 10) "0" else ""}${s}s"
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- LOG ----------
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun appendLog(msg: String, type: String = "info") {
        val tag = when (type) {
            "ok" -> "✔ SUKSES"; "err" -> "✖ GAGAL"; "warn" -> "⚠ INFO";
            "cmd" -> "$ CMD"; "head" -> "━━"; "hint" -> "➜ SARAN"; else -> "ℹ INFO"
        }
        val t = timeFmt.format(Date())
        logOutput.append("[$t] $tag: $msg\n")
        // batasi ~500 baris
        val lines = logOutput.text.lines()
        if (lines.size > 600) logOutput.text = lines.takeLast(500).joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resetLog(msg: String) {
        logOutput.text = ""
        appendLog(msg, "info")
    }

    // ---------- UI STATE ----------
    private fun refreshUi() {
        val ready = !isBusy && AuthManager.isLoggedIn(this)
        val has = apkUris.isNotEmpty()
        btnInstall.isEnabled = ready && has
        btnAutoAll.isEnabled = ready && has
        findViewById<Button>(R.id.btnPickApk).isEnabled = ready
        findViewById<Button>(R.id.btnCheckOwner).isEnabled = ready
        findViewById<Button>(R.id.btnSetOwner).isEnabled = ready
        findViewById<Button>(R.id.btnClearKaspersky).isEnabled = ready
        findViewById<Button>(R.id.btnUninstallKaspersky).isEnabled = ready
    }

    private fun runTask(block: suspend () -> Unit) {
        if (isBusy) {
            Toast.makeText(this, "Tunggu proses berjalan selesai…", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isBusy = true
            refreshUi()
            try {
                block()
            } catch (e: Exception) {
                appendLog(e.message ?: e.toString(), "err")
            } finally {
                isBusy = false
                refreshUi()
            }
        }
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Ya") { _, _ -> ok() }
            .setNegativeButton("Batal", null).show()
    }

    // ---------- DEVICE INFO ----------
    private fun loadDeviceInfo() {
        val model = Build.MODEL ?: "?"
        val brand = Build.MANUFACTURER ?: ""
        val rel = Build.VERSION.RELEASE ?: "?"
        val sdk = Build.VERSION.SDK_INT
        deviceInfo.text = "Terhubung (perangkat ini): $brand $model · Android $rel (SDK $sdk)"
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "app.apk"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) name = c.getString(idx) ?: name
        }
        return name
    }

    private fun updateFileDisplay() {
        fileNameDisplay.text = when {
            apkNames.isEmpty() -> "Belum ada APK dipilih"
            apkNames.size == 1 -> apkNames[0]
            else -> "${apkNames.size} APK: " + apkNames.joinToString(", ")
        }
    }

    // ---------- INSTALL APK (PackageInstaller, seperti pm install versi Web) ----------
    private suspend fun installAll(keepSelection: Boolean) {
        if (apkUris.isEmpty()) throw IllegalStateException("Pilih minimal satu file APK.")
        appendLog("Pemasangan APK (${apkUris.size} berkas)", "head")
        var okCount = 0
        apkUris.forEachIndexed { i, uri ->
            val name = apkNames.getOrElse(i) { "app-$i.apk" }
            appendLog("[${i + 1}/${apkUris.size}] Memasang $name…", "cmd")
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.text = "[${i + 1}/${apkUris.size}] $name…"
            }
            val res = installOneApk(uri)
            withContext(Dispatchers.Main) { progressBar.isIndeterminate = false; progressBar.progress = 100 }
            if (res.startsWith("OK")) {
                appendLog("$name berhasil dipasang.", "ok"); okCount++
            } else {
                val hint = Shell.explainInstallError(res)
                appendLog("Gagal memasang $name. Detail: $res" + if (hint.isNotEmpty()) "\n=> $hint" else "", "err")
            }
        }
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            progressText.text = ""
        }
        appendLog("Selesai. $okCount/${apkUris.size} APK berhasil dipasang.", if (okCount == apkUris.size) "ok" else "warn")
        if (!keepSelection) {
            apkUris.clear(); apkNames.clear()
            withContext(Dispatchers.Main) { updateFileDisplay() }
        }
    }

    private suspend fun installOneApk(uri: Uri): String = withContext(Dispatchers.IO) {
        InstallReceiver.lastResult = null
        val pi = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            contentResolver.openInputStream(uri)?.use { input ->
                session.openWrite("apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            } ?: return@withContext "FAIL: tidak bisa membaca file APK"
            val intent = Intent(this@MainActivity, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi2 = PendingIntent.getBroadcast(this@MainActivity, sessionId, intent, flags)
            session.commit(pi2.intentSender)
        }
        // Tunggu hasil (sistem menampilkan dialog konfirmasi ke user)
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000
        while (InstallReceiver.lastResult == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(300) } catch (_: InterruptedException) { break }
        }
        InstallReceiver.lastResult ?: "OK (tidak ada status — periksa daftar aplikasi)"
    }

    // ---------- DEVICE OWNER ----------
    private fun dpm(): DevicePolicyManager =
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun isPkgInstalled(pkg: String): Boolean =
        runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private suspend fun checkOwner(silentHead: Boolean = true) {
        val pkg = getString(R.string.owner_pkg)
        val owner = try { dpm().deviceOwner } catch (_: Exception) { null }
        val msg = when {
            owner == null -> {
                ownerBadge.text = "Status: belum ada Device Owner"
                "Belum ada Device Owner di perangkat."
            }
            owner == pkg -> {
                ownerBadge.text = "Status: ✔ $pkg adalah Device Owner"
                "$pkg aktif sebagai Device Owner."
            }
            else -> {
                ownerBadge.text = "Status: ⚠ Owner lain: $owner"
                "Perangkat dikelola $owner, bukan $pkg."
            }
        }
        appendLog("Status Device Owner: $msg", if (owner == pkg) "ok" else "info")
        // info tambahan root (untuk dpm via shell)
        val root = withContext(Dispatchers.IO) {
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                p.inputStream.bufferedReader().readText() to p.waitFor()
            }.getOrNull()
        }
        if (root != null && root.second == 0) appendLog("Akses root tersedia: ${root.first.trim()}", "info")
    }

    private suspend fun setOwner() {
        val receiver = getString(R.string.owner_receiver)
        val pkg = getString(R.string.owner_pkg)
        appendLog("Set Device Owner", "head")
        appendLog("$pkg akan menjadi pengelola penuh (MDM, kiosk). Syarat: tanpa akun/pengguna lain.", "warn")
        if (!isPkgInstalled(pkg)) {
            appendLog("Aplikasi $pkg BELUM terpasang. Pasang APK Headwind MDM dulu (Langkah 1).", "err")
            ownerBadge.text = "Status: ✖ $pkg belum terpasang"
            return
        }
        appendLog("Menjalankan: dpm set-device-owner $receiver …", "cmd")
        // 1) Coba langsung via shell/root (bisa berhasil di perangkat rooted / build engineering)
        val r = Shell.exec("dpm set-device-owner $receiver")
        val out = "exit=${r.exit} ${r.output}".trim()
        appendLog(out.ifBlank { "(tanpa output)" }, "info")
        if (r.output.contains("Success", true) && !r.output.contains("Exception", true)) {
            ownerBadge.text = "Status: ✔ $pkg adalah Device Owner"
            appendLog("Headwind MDM kini menjadi Device Owner. Buka aplikasinya untuk enroll ke server MDM.", "ok")
            return
        }
        val (title, detail) = Shell.explainDpmError(r.output)
        // 2) Perintah ADB dari PC sebagai jalan umum (dpm tidak bisa dari aplikasi biasa)
        val adbCmd = "adb shell dpm set-device-owner $receiver"
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Gagal langsung: $title")
                .setMessage(
                    "$detail\n\nAplikasi biasa TIDAK diizinkan Android menjalankan dpm.\n" +
                        "Jalankan sekali dari PC via ADB ( Aktifkan USB Debugging ):\n\n$adbCmd\n\n" +
                        "Output: ${r.output.ifBlank { "(exit ${r.exit})" }}\n\nSalin perintah lalu tempel di PC?"
                )
                .setPositiveButton("Salin perintah") { _, _ -> copyOwnerCmd() }
                .setNegativeButton("Tutup", null)
                .show()
        }
        appendLog("Gagal: $title — $detail", "err")
        appendLog("Jalankan dari PC: $adbCmd", "hint")
    }

    private fun copyOwnerCmd() {
        val cmd = "adb shell dpm set-device-owner ${getString(R.string.owner_receiver)}"
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("dpm", cmd))
        Toast.makeText(this, "Disalin: $cmd", Toast.LENGTH_LONG).show()
        appendLog("Perintah disalin: $cmd", "info")
    }

    private suspend fun autoAll() {
        appendLog("Proses Otomatis: Install APK + Set Owner", "head")
        installAll(keepSelection = true)
        setOwner()
        apkUris.clear(); apkNames.clear()
        withContext(Dispatchers.Main) { updateFileDisplay() }
        appendLog("Proses otomatis selesai.", "ok")
    }

    // ---------- KASPERSKY ----------
    private suspend fun clearKaspersky() {
        val pkg = getString(R.string.kaspersky_pkg)
        appendLog("Hapus Data Kaspersky", "head")
        if (!isPkgInstalled(pkg)) {
            appendLog("$pkg tidak ditemukan di perangkat.", "warn"); return
        }
        // PackageInstaller tidak bisa clear-data; coba via shell (root/Shizuku/ADB).
        val r = Shell.exec("pm clear $pkg")
        appendLog("pm clear $pkg → exit=${r.exit} ${r.output}", "info")
        if (r.output.contains("Success", true)) appendLog("Data Kaspersky berhasil dihapus.", "ok")
        else {
            appendLog(
                "Gagal via aplikasi. Alternatif: Pengaturan > Aplikasi > Kaspersky > Hapus data, " +
                    "atau dari PC: adb shell pm clear $pkg", "hint"
            )
        }
    }

    private suspend fun uninstallKaspersky() {
        val pkg = getString(R.string.kaspersky_pkg)
        appendLog("Uninstal Kaspersky", "head")
        if (!isPkgInstalled(pkg)) {
            appendLog("$pkg tidak ditemukan di perangkat.", "warn"); return
        }
        // Coba shell dulu (root), lalu fallback dialog sistem.
        val r = Shell.exec("pm uninstall $pkg")
        if (r.output.contains("Success", true)) {
            appendLog("Kaspersky berhasil di-uninstal.", "ok"); return
        }
        if (r.output.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER", true)) {
            appendLog("Ditolak: masih menjadi Device Admin. Matikan di Pengaturan > Keamanan > Admin perangkat.", "err")
        } else if (r.output.contains("USER_RESTRICTED", true) || r.output.contains("OWNER_BLOCKED", true)) {
            appendLog("Diblokir kebijakan Device/ Profile Owner. Detail: ${r.output}", "err")
        }
        // Fallback resmi: dialog uninstall sistem
        withContext(Dispatchers.Main) {
            try {
                startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkg".toUri()))
                appendLog("Membuka dialog uninstal sistem — konfirmasi di layar.", "info")
            } catch (e: Exception) {
                appendLog("Tidak bisa membuka dialog uninstal: ${e.message}", "err")
            }
        }
    }

    // ---------- ADB WIRELESS ----------
    private fun showWireless() {
        val ip = wifiIp() ?: "(tidak terdeteksi — hubungkan Wi-Fi dulu)"
        AlertDialog.Builder(this)
            .setTitle("ADB Wireless")
            .setMessage(
                "IP Wi-Fi perangkat: $ip\n\n" +
                    "• Tanpa root: buka Pengaturan > Opsi Developer > Debugging nirkabel, " +
                    "lalu dari PC: adb pair / adb connect $ip:5555.\n" +
                    "• Dengan root: aplikasi mencoba mengaktifkan port 5555 langsung."
            )
            .setPositiveButton("Buka Opsi Developer") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
            .setNeutralButton("Aktifkan 5555 (root)") { _, _ ->
                scope.launch {
                    isBusy = true; refreshUi()
                    try {
                        val r = Shell.exec("setprop service.adb.tcp.port 5555; stop adbd; start adbd")
                        appendLog("Aktifkan tcpip 5555 → exit=${r.exit} ${r.output}", "info")
                        appendLog("Dari PC: adb connect $ip:5555", "hint")
                    } finally { isBusy = false; refreshUi() }
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun wifiIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION") val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null else "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (_: Exception) { null }
    }

    override fun onResume() {
        super.onResume()
        if (AuthManager.isLoggedIn(this) && mainApp.visibility != View.VISIBLE) showMain()
        if (mainApp.visibility == View.VISIBLE && !AuthManager.isLoggedIn(this) && !isBusy) doLogout()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerTick)
        super.onDestroy()
    }
}

/** takePersistableUriPermission untuk banyak URI sekaligus (API 19+). */
private fun android.content.ContentResolver.takePersistableUriPermissionSafe(uris: List<Uri>) {
    for (u in uris) {
        runCatching { takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
