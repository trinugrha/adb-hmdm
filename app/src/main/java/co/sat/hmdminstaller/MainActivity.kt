package co.sat.hmdminstaller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var edtPairIp: TextInputEditText
    private lateinit var edtPairPort: TextInputEditText
    private lateinit var edtPairCode: TextInputEditText
    private lateinit var edtConnIp: TextInputEditText
    private lateinit var edtConnPort: TextInputEditText
    private lateinit var txtLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var txtConn: TextView
    private lateinit var txtConnState: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtApkTitle: TextView
    private lateinit var txtApkEmpty: TextView
    private lateinit var statusDot: View
    private lateinit var progress: ProgressBar
    private lateinit var apkList: LinearLayout
    private lateinit var buttons: List<MaterialButton>
    private lateinit var btnAll: MaterialButton
    private lateinit var stepBadges: List<TextView>
    private lateinit var stepLines: List<View>

    private val worker = Executors.newSingleThreadExecutor()
    private val apks = mutableListOf<ApkItem>()
    private val checked = mutableSetOf<ApkItem>()
    private lateinit var adb: AdbController
    @Volatile private var busy = false
    @Volatile private var connectedAddr: String? = null
    @Volatile private var paired = false
    private var connectedSince = 0L
    private var lostAddr: String? = null

    // Pemantau status koneksi (live): jalan hanya saat layar terlihat.
    private val poller = Executors.newSingleThreadScheduledExecutor()
    private var pollTask: ScheduledFuture<*>? = null
    private var lastProbe = 0L

    private val pickApks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var added = 0
        var skipped = 0
        uris.forEach { uri ->
            val item = copyToCache(uri)
            if (item == null) skipped++ else { apks.add(item); added++ }
        }
        if (added > 0) log("Ditambahkan $added APK. Total: ${apks.size}")
        if (skipped > 0) log("$skipped file dilewati (bukan .apk / gagal dibaca).")
        refreshApkList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        edtPairIp = findViewById(R.id.edtPairIp)
        edtPairPort = findViewById(R.id.edtPairPort)
        edtPairCode = findViewById(R.id.edtPairCode)
        edtConnIp = findViewById(R.id.edtConnIp)
        edtConnPort = findViewById(R.id.edtConnPort)
        txtLog = findViewById(R.id.txtLog)
        logScroll = findViewById(R.id.logScroll)
        txtConn = findViewById(R.id.txtConn)
        txtConnState = findViewById(R.id.txtConnState)
        stepBadges = listOf(R.id.stepBadge1, R.id.stepBadge2, R.id.stepBadge3, R.id.stepBadge4)
            .map { findViewById<TextView>(it) }
        stepLines = listOf(R.id.stepLine1, R.id.stepLine2, R.id.stepLine3)
            .map { findViewById<View>(it) }
        txtStatus = findViewById(R.id.txtStatus)
        txtApkTitle = findViewById(R.id.txtApkTitle)
        txtApkEmpty = findViewById(R.id.txtApkEmpty)
        statusDot = findViewById(R.id.statusDot)
        progress = findViewById(R.id.progress)
        apkList = findViewById(R.id.apkList)

        val btnPair: MaterialButton = findViewById(R.id.btnPair)
        val btnConnect: MaterialButton = findViewById(R.id.btnConnect)
        val btnDisconnect: MaterialButton = findViewById(R.id.btnDisconnect)
        val btnAddApk: MaterialButton = findViewById(R.id.btnAddApk)
        val btnRemoveApk: MaterialButton = findViewById(R.id.btnRemoveApk)
        val btnClearApk: MaterialButton = findViewById(R.id.btnClearApk)
        val btnInstallOnly: MaterialButton = findViewById(R.id.btnInstallOnly)
        val btnOwner: MaterialButton = findViewById(R.id.btnOwner)
        btnAll = findViewById(R.id.btnAll)
        val btnCopyLog: MaterialButton = findViewById(R.id.btnCopyLog)
        val btnClearLog: MaterialButton = findViewById(R.id.btnClearLog)
        buttons = listOf(btnPair, btnConnect, btnDisconnect, btnAddApk, btnRemoveApk,
            btnClearApk, btnInstallOnly, btnOwner, btnAll)

        // Log (UI thread) → controller memakai callback ini dari thread kerja.
        adb = AdbController(this) { line -> runOnUiThread { log(line) } }

        btnPair.setOnClickListener {
            val addr = addr(edtPairIp.text.toString(), edtPairPort.text.toString())
            val code = clean(edtPairCode.text.toString())
            run("Pairing") { doPair(addr, code) }
        }
        btnConnect.setOnClickListener {
            val addr = addr(edtConnIp.text.toString(), edtConnPort.text.toString())
            run("Connect") { doConnect(addr) }
        }
        btnDisconnect.setOnClickListener {
            run("Disconnect") { doDisconnect() }
        }
        btnAddApk.setOnClickListener {
            pickApks.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
        }
        btnRemoveApk.setOnClickListener {
            val sel = apks.filter { it in checked }
            if (sel.isEmpty()) { log("Tidak ada APK yang dipilih untuk dihapus."); return@setOnClickListener }
            sel.forEach { log("Dihapus dari daftar: ${it.name}"); if (!it.builtIn) it.file.delete() }
            apks.removeAll(sel.toSet()); checked.removeAll(sel.toSet())
            log("Dihapus ${sel.size} APK. Sisa: ${apks.size}")
            refreshApkList()
        }
        btnClearApk.setOnClickListener {
            val n = apks.size
            apks.filter { !it.builtIn }.forEach { it.file.delete() }
            apks.clear(); checked.clear()
            log("Daftar APK dikosongkan ($n APK dihapus dari daftar).")
            refreshApkList()
        }
        btnInstallOnly.setOnClickListener {
            val list = apks.toList()
            run("Install APK") { adb.install(list) }
        }
        btnOwner.setOnClickListener {
            run("Set Device Owner") { doSetOwner() }
        }
        btnAll.setOnClickListener {
            val pAddr = addr(edtPairIp.text.toString(), edtPairPort.text.toString())
            val pCode = clean(edtPairCode.text.toString())
            val cAddr = addr(edtConnIp.text.toString(), edtConnPort.text.toString())
            val list = apks.toList()
            run("Jalankan semua") {
                if (adb.isConnected && connectedAddr == cAddr) {
                    runOnUiThread { log("[Pair & Connect] dilewati (sudah terhubung ke $cAddr).") }
                } else {
                    if (pAddr.isNotEmpty() && pCode.isNotEmpty()) {
                        if (doPair(pAddr, pCode)) {
                            // Beri waktu HP target menutup dialog pairing & membuka port connect.
                            Thread.sleep(1500)
                        } else {
                            // Kode pairing sekali pakai: bila HP sudah pernah di-pair, pair ulang pasti gagal.
                            // Jangan berhenti; coba connect (kunci ADB tersimpan).
                            runOnUiThread { log("Peringatan: pairing gagal, mencoba lanjut Connect (mungkin sudah pernah di-pair).") }
                        }
                    } else {
                        runOnUiThread { log("[Pair] dilewati (IP / Port / kode pairing kosong).") }
                    }
                    if (!doConnect(cAddr)) {
                        runOnUiThread { log("Connect gagal. Jika belum pernah pair: buka dialog pairing baru di HP target (kode baru), isi ulang, lalu coba lagi.") }
                        return@run false
                    }
                }
                adb.installAndOwner(list)
            }
        }
        btnCopyLog.setOnClickListener {
            if (txtLog.length() > 0) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", txtLog.text))
                Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
            }
        }
        btnClearLog.setOnClickListener {
            txtLog.text = ""
            log("Log dibersihkan.")
        }

        refreshApkList()
        refreshHeader()
        log("Isi IP dan Port lalu klik tombol.")
    }

    override fun onStart() {
        super.onStart()
        pollTask?.cancel(false)
        pollTask = poller.scheduleWithFixedDelay({ pollConnection() }, 0, 1, TimeUnit.SECONDS)
    }

    override fun onStop() {
        super.onStop()
        pollTask?.cancel(false)
        pollTask = null
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        poller.shutdownNow()
    }

    // ---------- Status koneksi live ----------

    /** Jalan di thread pemantau tiap 1 detik. */
    private fun pollConnection() {
        try {
            val wasBusy = busy
            var alive = adb.isConnected
            val now = System.currentTimeMillis()
            // Probe sungguhan tiap ±8 detik (hanya saat tidak ada operasi lain) untuk mendeteksi koneksi mati diam-diam.
            if (alive && !wasBusy && now - lastProbe >= 8_000) {
                lastProbe = now
                alive = adb.probe()
            }
            runOnUiThread { applyLiveState(alive, wasBusy) }
        } catch (_: Throwable) { }
    }

    private fun applyLiveState(alive: Boolean, polledWhileBusy: Boolean) {
        if (isFinishing) return
        val addr = connectedAddr
        // Saat operasi berjalan (mis. connect/install) jangan simpulkan putus: hasilnya bisa basi.
        if (addr != null && !alive && !polledWhileBusy && !busy) {
            log("Koneksi ke $addr terputus.")
            lostAddr = addr
            connectedAddr = null
            connectedSince = 0L
        }
        refreshHeader()
    }

    // ---------- Langkah (jalan di thread kerja) ----------

    private fun doPair(addr: String, code: String): Boolean {
        val ok = adb.pair(addr, code)
        if (ok) { paired = true; runOnUiThread { refreshHeader() } }
        return ok
    }

    private fun doConnect(addr: String): Boolean {
        val ok = adb.connect(addr)
        if (ok) {
            connectedAddr = addr
            connectedSince = System.currentTimeMillis()
            lostAddr = null
            lastProbe = connectedSince
        } else {
            // Connect gagal: koneksi lama sudah diputus, jangan tampilkan alamat lama sebagai terhubung.
            connectedAddr = null
            connectedSince = 0L
        }
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doDisconnect(): Boolean {
        val ok = adb.disconnect()
        if (ok) { connectedAddr = null; connectedSince = 0L; lostAddr = null }
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun doSetOwner(): Boolean {
        val ok = adb.setDeviceOwner()
        runOnUiThread { refreshHeader() }
        return ok
    }

    private fun run(title: String, work: () -> Boolean) {
        if (busy) return
        busy = true
        setBusy(true, "Sedang berjalan: $title...")
        worker.execute {
            var ok = false
            try { ok = work() } catch (e: Throwable) {
                runOnUiThread { log("ERROR: ${e.message ?: e.javaClass.simpleName}") }
            }
            runOnUiThread {
                log(if (ok) "=== SELESAI: BERHASIL ===" else "=== GAGAL / BERHENTI ===")
                log("")
                busy = false
                setBusy(false, if (ok) "Selesai: $title" else "Gagal / berhenti: $title (lihat log)")
                refreshHeader()
            }
        }
    }

    private fun setBusy(on: Boolean, status: String) {
        buttons.forEach { it.isEnabled = !on }
        progress.visibility = if (on) View.VISIBLE else View.GONE
        txtStatus.text = status
        btnAll.text = if (on) "Sedang berjalan…" else "Jalankan Semua"
    }

    // ---------- UI helper ----------

    private fun refreshHeader() {
        val addr = connectedAddr
        val lost = lostAddr
        val on = addr != null && adb.isConnected
        when {
            on -> {
                val s = ((System.currentTimeMillis() - connectedSince) / 1000).coerceAtLeast(0)
                val dur = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
                          else "%02d:%02d".format(s / 60, s % 60)
                txtConnState.text = "Terhubung"
                txtConn.text = "$addr · $dur"
                statusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(74, 222, 128))
            }
            lost != null -> {
                txtConnState.text = "Terputus"
                txtConn.text = "$lost · Connect ulang"
                statusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(248, 113, 113))
            }
            else -> {
                txtConnState.text = "Belum terhubung"
                txtConn.text = "Pair lalu Connect untuk mulai"
                statusDot.backgroundTintList = ColorStateList.valueOf(Color.rgb(148, 163, 184))
            }
        }
        updateSteps(on)
    }

    /** Rel langkah: selesai = terisi + centang, langkah berikutnya = bercincin aksen, sisanya netral. */
    private fun updateSteps(connected: Boolean) {
        val done = booleanArrayOf(paired || connected, connected, apks.isNotEmpty(), false)
        val current = done.indexOfFirst { !it }.let { if (it < 0) done.size - 1 else it }
        stepBadges.forEachIndexed { i, badge ->
            when {
                done[i] -> {
                    badge.setBackgroundResource(R.drawable.bg_step_done)
                    badge.setTextColor(getColor(R.color.on_accent))
                    badge.text = "✓"
                }
                i == current -> {
                    badge.setBackgroundResource(R.drawable.bg_step_active)
                    badge.setTextColor(getColor(R.color.accent))
                    badge.text = (i + 1).toString()
                }
                else -> {
                    badge.setBackgroundResource(R.drawable.bg_step_pending)
                    badge.setTextColor(getColor(R.color.muted))
                    badge.text = (i + 1).toString()
                }
            }
        }
        stepLines.forEachIndexed { i, line ->
            line.setBackgroundColor(getColor(if (done[i]) R.color.accent else R.color.stroke))
        }
    }

    private fun refreshApkList() {
        apkList.removeAllViews()
        apks.forEachIndexed { i, item ->
            val row = layoutInflater.inflate(R.layout.item_apk, apkList, false)
            val cb = row.findViewById<CheckBox>(R.id.cbApk)
            row.findViewById<TextView>(R.id.txtApkIdx).text = "%02d".format(i + 1)
            row.findViewById<TextView>(R.id.txtApkName).text = item.name
            row.findViewById<TextView>(R.id.txtApkSize).text = item.sizeText
            cb.isChecked = item in checked
            cb.setOnCheckedChangeListener { _, isChecked -> if (isChecked) checked.add(item) else checked.remove(item) }
            row.setOnClickListener { cb.toggle() }
            apkList.addView(row)
        }
        txtApkEmpty.visibility = if (apks.isEmpty()) View.VISIBLE else View.GONE
        txtApkTitle.text = if (apks.isEmpty()) "Daftar APK" else "Daftar APK (${apks.size})"
        refreshHeader()
    }

    private fun log(s: String) {
        val sp = SpannableString(s + "\n")
        sp.setSpan(ForegroundColorSpan(lineColor(s)), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        txtLog.append(sp)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun lineColor(s: String): Int {
        val l = s.lowercase()
        return when {
            s.startsWith("===") ->
                if (l.contains("berhasil")) Color.rgb(74, 222, 128) else Color.rgb(248, 113, 113)
            s.startsWith("> ") -> Color.rgb(56, 189, 248)
            l.startsWith("ringkasan") -> Color.WHITE
            l.startsWith("peringatan") -> Color.rgb(251, 191, 36)
            l.contains("sudah ada") || l.contains("dilewati") -> Color.rgb(251, 191, 36)
            l.contains("fail") || l.contains("gagal") || l.contains("error") || l.contains("cannot") ||
                l.contains("tidak ditemukan") || l.contains("timeout") -> Color.rgb(248, 113, 113)
            l.contains("success") || l.contains("berhasil") || l.contains("connected to") ||
                l.contains("paired") -> Color.rgb(74, 222, 128)
            s.startsWith("[") || s.startsWith("Isi ") || s.startsWith("Daftar APK kosong") -> Color.rgb(252, 211, 77)
            else -> Color.rgb(203, 213, 225)
        }
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

    private fun copyToCache(uri: Uri): ApkItem? {
        var name = "file-${System.currentTimeMillis()}.apk"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        if (!name.endsWith(".apk", ignoreCase = true)) return null
        if (apks.any { it.name.equals(name, ignoreCase = true) }) {
            log("APK sudah ada di daftar, tidak ditambahkan lagi: $name")
            return null
        }
        return try {
            val dir = File(cacheDir, "picked").apply { mkdirs() }
            val f = File(dir, name)
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ApkItem(name, f)
        } catch (e: Exception) {
            log("Gagal membaca $name: ${e.message}")
            null
        }
    }
}
