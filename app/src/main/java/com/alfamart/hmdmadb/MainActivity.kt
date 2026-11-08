package com.alfamart.hmdmadb

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alfamart.hmdmadb.adb.AdbClient
import kotlinx.coroutines.*
import java.security.MessageDigest

private const val VALID_PASS_HASH = "db2e7f1bd5ab9968ae76199b7cc74795ca7404d5a08d78567715ce532f9d2669"
private const val OWNER_RECEIVER = "com.hmdm.launcher/.AdminReceiver"
private const val OWNER_PKG = "com.hmdm.launcher"
private const val KASPERSKY_PKG = "com.kaspersky.kes"
private const val SESSION_MS = 20 * 60 * 1000L

fun sha256Hex(s: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    return d.joinToString("") { "%02x".format(it) }
}

fun queryNameSize(ctx: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "app.apk"
    var size = -1L
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val si = c.getColumnIndex(OpenableColumns.SIZE)
        if (c.moveToFirst()) {
            if (ni >= 0) name = c.getString(ni) ?: name
            if (si >= 0) size = c.getLong(si)
        }
    }
    if (size < 0) ctx.contentResolver.openInputStream(uri)?.use { size = it.available().toLong() }
    return name to size
}

fun explainInstall(output: String): String {
    val o = output
    fun has(vararg r: Regex) = r.any { it.containsMatchIn(o) }
    return when {
        has(Regex("INSTALL_FAILED_VERSION_DOWNGRADE")) -> "Versi APK lebih lama dari yang terpasang."
        has(Regex("ALREADY_EXISTS")) -> "Aplikasi sudah terpasang."
        has(Regex("INSUFFICIENT_STORAGE")) -> "Penyimpanan HP penuh."
        has(Regex("OLDER_SDK")) -> "Android HP terlalu lama untuk APK ini."
        has(Regex("NO_MATCHING_ABIS")) -> "Arsitektur CPU tidak cocok."
        has(Regex("USER_RESTRICTED|VERIFICATION_FAILURE")) -> "Aktifkan \"Install via USB\" di Opsi Developer HP target."
        has(Regex("PARSE_FAILED")) -> "File APK rusak / bukan APK valid."
        else -> ""
    }
}

fun explainDpm(output: String): String {
    val o = output
    fun has(vararg r: Regex) = r.any { it.containsMatchIn(o) }
    return when {
        has(Regex("accounts", RegexOption.IGNORE_CASE)) -> "Hapus semua akun Google/Email di HP target."
        has(Regex("several users|secondary user", RegexOption.IGNORE_CASE)) -> "Hapus pengguna/profil kerja tambahan di HP target."
        has(Regex("already.*provisioned|setup.*complete|already.*set up", RegexOption.IGNORE_CASE)) -> "HP sudah selesai setup. Factory reset lalu jalankan sebelum login akun apa pun."
        has(Regex("already.*device owner|has a device owner", RegexOption.IGNORE_CASE)) -> "Sudah ada Device Owner lain."
        has(Regex("profile owner", RegexOption.IGNORE_CASE)) -> "Hapus profil kerja terlebih dahulu."
        has(Regex("SecurityException|not allowed", RegexOption.IGNORE_CASE)) -> "Aktifkan \"USB Debugging (Security settings)\" di HP target (Xiaomi/Oppo/Vivo)."
        has(Regex("unknown admin|no such component|ClassNotFound", RegexOption.IGNORE_CASE)) -> "APK Headwind MDM belum terpasang. Pasang APK dulu."
        else -> "Lihat pesan asli perangkat di log."
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var pin by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(false) }
    var loginErr by remember { mutableStateOf("") }
    var fails by remember { mutableStateOf(0) }
    var lockUntil by remember { mutableStateOf(0L) }
    var loginAt by remember { mutableStateOf(0L) }

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }
    var useTls by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var deviceInfo by remember { mutableStateOf("Belum ada perangkat terhubung") }
    var ownerBadge by remember { mutableStateOf("Belum diperiksa") }
    var sdk by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf("Sistem siap (v3.0 Wireless). Masukkan PIN.")) }
    var apks by remember { mutableStateOf(listOf<Uri>()) }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }

    val clientRef = remember { arrayOfNulls<AdbClient>(1) }
    fun client(): AdbClient = clientRef[0] ?: AdbClient(ctx.applicationContext).also { clientRef[0] = it }

    fun log(s: String) { logs = (logs + s).takeLast(500) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val apk = uris.filter { it.toString().endsWith(".apk", true) || true }
        if (apk.isNotEmpty()) {
            apks = apk
            log("Dipilih ${apk.size} file APK.")
        } else log("Tidak ada file dipilih.")
    }

    fun runJob(name: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            try { block() } catch (e: Exception) {
                log("GAGAL [$name]: ${e.message}")
            } finally { busy = false }
        }
    }

    suspend fun checkOwner() {
        withContext(Dispatchers.IO) {
            try {
                var out = ""
                try { out = client().shell("dpm list-owners") } catch (_: Exception) {}
                var owner: String? = Regex("admin=(?:ComponentInfo\\{)?([\\w.]+)/").find(out)?.groupValues?.get(1)
                if (owner == null) {
                    val dump = try { client().shell("dumpsys device_policy") } catch (_: Exception) { "" }
                    owner = Regex("Device Owner:[\\s\\S]{0,400}?admin=ComponentInfo\\{([a-zA-Z0-9_.]+)/", RegexOption.IGNORE_CASE).find(dump)?.groupValues?.get(1)
                }
                withContext(Dispatchers.Main) {
                    ownerBadge = when {
                        owner == null -> "Belum ada Device Owner"
                        owner == OWNER_PKG -> "Headwind MDM adalah Device Owner ✔"
                        else -> "Device Owner lain: $owner"
                    }
                    log("Status Device Owner: $ownerBadge")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { log("Gagal cek owner: ${e.message}") }
            }
        }
    }

    suspend fun installAll(keepAfter: Boolean = false) {
        if (apks.isEmpty()) { withContext(Dispatchers.Main) { log("Pilih minimal satu APK.") }; return }
        withContext(Dispatchers.Main) { log("━━ Pemasangan ${apks.size} APK ━━") }
        apks.forEachIndexed { i, uri ->
            val (name, size) = queryNameSize(ctx, uri)
            withContext(Dispatchers.Main) {
                progress = ("[${i + 1}/${apks.size}] $name" to 0f)
                log("[${i + 1}/${apks.size}] Mengirim $name (${"%.2f".format(size / 1048576.0)} MB)...")
            }
            val out = if (sdk >= 24 || sdk == 0) {
                client().installStreaming(size, { ctx.contentResolver.openInputStream(uri)!! }) { sent ->
                    scope.launch(Dispatchers.Main) { progress = progress?.copy(second = sent.toFloat() / size.toFloat()) }
                }
            } else {
                withContext(Dispatchers.Main) { log("SDK $sdk: memakai mode push + pm install.") }
                client().pushThenInstall(size, { ctx.contentResolver.openInputStream(uri)!! }) { sent ->
                    scope.launch(Dispatchers.Main) { progress = progress?.copy(second = sent.toFloat() / size.toFloat()) }
                }
            }
            withContext(Dispatchers.Main) {
                if (out.contains("Success", true)) log("$name berhasil dipasang ✔")
                else {
                    val hint = explainInstall(out)
                    log("GAGAL $name: $out${if (hint.isNotEmpty()) " → $hint" else ""}")
                    throw IllegalStateException("Install $name gagal.")
                }
            }
        }
        withContext(Dispatchers.Main) {
            progress = null
            log("Selesai. ${apks.size} APK dipasang ✔")
            if (!keepAfter) apks = emptyList()
        }
    }

    suspend fun setOwner() {
        withContext(Dispatchers.Main) { log("━━ Set Device Owner ━━") }
        val listed = try { client().shell("pm list packages $OWNER_PKG") } catch (_: Exception) { "" }
        if (!listed.lines().any { it.trim() == "package:$OWNER_PKG" }) {
            withContext(Dispatchers.Main) { log("GAGAL: $OWNER_PKG belum terpasang. Pasang APK dulu.") }
            throw IllegalStateException("MDM belum terpasang")
        }
        val out = client().shell("dpm set-device-owner $OWNER_RECEIVER")
        withContext(Dispatchers.Main) {
            if (out.contains("Success", true) && !out.contains("Exception", true)) {
                log("Headwind MDM kini menjadi Device Owner ✔")
                log("Buka aplikasi Headwind MDM di HP target untuk enroll ke server.")
            } else {
                log("GAGAL set-device-owner: $out")
                log("Saran: ${explainDpm(out)}")
                throw IllegalStateException("set-device-owner gagal")
            }
        }
        checkOwner()
    }

    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("HMDM Wireless ADB  v3.0", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(if (!loggedIn) "Login diperlukan (PIN sama seperti Web v2.5)." else deviceInfo, fontSize = 12.sp)

            if (!loggedIn) {
                OutlinedTextField(value = pin, onValueChange = { if (it.length <= 4) pin = it.filter(Char::isDigit) },
                    label = { Text("PIN 4 digit") }, singleLine = true)
                if (loginErr.isNotEmpty()) Text(loginErr, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    val wait = lockUntil - System.currentTimeMillis()
                    if (wait > 0) { loginErr = "Terkunci. Coba lagi ${wait / 1000}s."; return@Button }
                    if (sha256Hex(pin) == VALID_PASS_HASH) {
                        loggedIn = true; loginErr = ""; fails = 0; loginAt = System.currentTimeMillis()
                        log("Login berhasil ✔ (sesi 20 mnt)")
                    } else {
                        fails++
                        if (fails >= 3) lockUntil = System.currentTimeMillis() + minOf(60, 1 shl (fails - 2)) * 1000L
                        loginErr = "PIN salah!"
                        pin = ""
                    }
                }) { Text("Masuk") }
            } else {
                // Sesi 20 menit
                val remainMs = SESSION_MS - (System.currentTimeMillis() - loginAt)
                if (remainMs <= 0 && !busy) {
                    loggedIn = false; clientRef[0]?.close(); clientRef[0] = null
                    connected = false; deviceInfo = "Sesi habis. Login ulang."
                } else Text("Sesi: ${remainMs / 60000}m ${"%02d".format((remainMs % 60000) / 1000)}s")

                // --- Koneksi ---
                Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Koneksi ADB Wireless", fontWeight = FontWeight.Bold)
                    Text("HP target & HP ini harus satu Wi-Fi. Aktifkan Debugging nirkabel di HP target.", fontSize = 12.sp)
                    OutlinedTextField(host, { host = it }, label = { Text("IP HP target (mis. 192.168.1.10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(useTls, { useTls = it; if (it && port == "5555") port = "" })
                            Text("TLS\n(Wi-Fi debug)", fontSize = 11.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && !connected, onClick = {
                            runJob("connect") {
                                val p = port.toIntOrNull() ?: throw IllegalStateException("Port tidak valid")
                                withContext(Dispatchers.Main) { log("Menghubungkan ke $host:$p ${if (useTls) "(TLS)" else ""}... Setujui dialog di HP target.") }
                                client().connect(host.trim(), p, useTls)
                                val model = try { client().shell("getprop ro.product.model") } catch (_: Exception) { "?" }
                                val ver = try { client().shell("getprop ro.build.version.release") } catch (_: Exception) { "?" }
                                sdk = try { client().shell("getprop ro.build.version.sdk").trim().toInt() } catch (_: Exception) { 0 }
                                withContext(Dispatchers.Main) {
                                    connected = true
                                    deviceInfo = "Terhubung: $model (Android $ver, SDK $sdk)"
                                    log("Terhubung ke $model · Android $ver ✔")
                                }
                                checkOwner()
                            }
                        }) { Text("Hubungkan") }
                        OutlinedButton(enabled = !busy && connected, onClick = {
                            runJob("dc") {
                                clientRef[0]?.close(); clientRef[0] = null
                                withContext(Dispatchers.Main) { connected = false; deviceInfo = "Terputus."; log("Koneksi diputus.") }
                            }
                        }) { Text("Putuskan") }
                    }
                    Text("Status owner: $ownerBadge", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = connected && !busy, onClick = { runJob("cek") { checkOwner() } }) { Text("Cek Owner") }
                        OutlinedButton(enabled = connected && !busy, onClick = {
                            runJob("tcpip") {
                                withContext(Dispatchers.Main) { log("Mengirim tcpip:5555...") }
                                client().tcpip(5555)
                                var ip = ""
                                try {
                                    val o = client().shell("ip -o -4 addr show scope global")
                                    ip = Regex("inet\\s+([0-9.]+)").find(o)?.groupValues?.get(1) ?: ""
                                } catch (_: Exception) {}
                                withContext(Dispatchers.Main) {
                                    log(if (ip.isNotEmpty()) "Mode 5555 aktif. Hubungkan: adb connect $ip:5555" else "Perintah 5555 terkirim ✔")
                                    clientRef[0]?.close(); clientRef[0] = null; connected = false
                                    deviceInfo = "Mode wireless 5555 aktif. Hubungkan ulang ke IP:5555."
                                }
                            }
                        }) { Text("Aktifkan 5555") }
                    }
                }}

                // --- APK ---
                Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Instalasi APK (bisa banyak)", fontWeight = FontWeight.Bold)
                    Button(enabled = connected && !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text(if (apks.isEmpty()) "Pilih file APK" else "${apks.size} APK dipilih — ganti?")
                    }
                    apks.forEach { u -> Text("• ${queryNameSize(ctx, u).first}", fontSize = 12.sp) }
                    progress?.let { (t, f) -> Text(t, fontSize = 12.sp); LinearProgressIndicator(progress = f, modifier = Modifier.fillMaxWidth()) }
                    Button(enabled = connected && !busy && apks.isNotEmpty(), onClick = { runJob("install") { installAll() } }) { Text("Pasang Semua APK") }
                }}

                // --- Owner ---
                Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Set Device Owner", fontWeight = FontWeight.Bold)
                    Text(OWNER_RECEIVER, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("Hapus semua akun Google & user lain di HP target sebelum menjalankan.", fontSize = 12.sp)
                    Button(enabled = connected && !busy, onClick = { runJob("owner") { setOwner() } }) { Text("Jalankan set-device-owner") }
                    Button(enabled = connected && !busy && apks.isNotEmpty(), onClick = { runJob("auto") { installAll(true); setOwner(); withContext(Dispatchers.Main){ apks = emptyList() } } }) { Text("⚡ Otomatis: Install + Set Owner") }
                }}

                // --- Kaspersky ---
                Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Uninstall Kaspersky ($KASPERSKY_PKG)", fontWeight = FontWeight.Bold)
                    Text("1) Hapus data → 2) Matikan Device Admin di HP target → 3) Uninstal.", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = connected && !busy, onClick = { runJob("kclear") {
                            val l = client().shell("pm list packages $KASPERSKY_PKG")
                            if (!l.contains(KASPERSKY_PKG)) withContext(Dispatchers.Main){ log("Kaspersky tidak ditemukan.") }
                            else { val o = client().shell("pm clear $KASPERSKY_PKG"); withContext(Dispatchers.Main){ log(if (o.contains("Success", true)) "Data Kaspersky dihapus ✔" else "Gagal hapus data: $o") } }
                        } }) { Text("Hapus Data") }
                        Button(enabled = connected && !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = { runJob("kuninst") {
                            val o = client().shell("pm uninstall $KASPERSKY_PKG")
                            withContext(Dispatchers.Main) {
                                when {
                                    o.contains("Success", true) -> log("Kaspersky di-uninstal ✔")
                                    o.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER", true) -> log("Ditolak: masih Device Admin. Nonaktifkan dulu di Pengaturan > Keamanan > Admin perangkat.")
                                    else -> log("Gagal uninstal: $o")
                                }
                            }
                        } }) { Text("Uninstal") }
                    }
                }}

                // --- Log ---
                Card { Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Terminal Log", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { logs = emptyList() }) { Text("Bersihkan") }
                    }
                    Text(logs.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }}
                OutlinedButton(onClick = { loggedIn = false; scope.launch(Dispatchers.IO){ try{clientRef[0]?.close()}catch(_:Exception){} }; clientRef[0]=null; connected=false }) { Text("Keluar") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
