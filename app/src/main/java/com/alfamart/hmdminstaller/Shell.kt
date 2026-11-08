package com.alfamart.hmdminstaller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Eksekusi shell: coba root (su) lalu shell biasa. Dipakai untuk dpm/pm bila diizinkan. */
object Shell {
    data class Result(val exit: Int, val output: String)

    suspend fun exec(cmd: String, asRoot: Boolean = true): Result = withContext(Dispatchers.IO) {
        // 1) coba root
        if (asRoot) {
            val r = run("su", "-c", cmd)
            // su tidak ada / ditolak -> coba tanpa root
            if (!r.output.contains("not found", true) && r.exit != 127) {
                // jika perintahnya memang butuh root dan gagal, tetap kembalikan hasilnya
                // pemanggil yang memutuskan fallback (kecuali su tidak tersedia sama sekali)
                if (r.exit != 127) return@withContext r
            }
        }
        run("sh", "-c", cmd)
    }

    private fun run(vararg argv: String): Result {
        return try {
            val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            Result(code, out)
        } catch (e: Exception) {
            Result(127, (e.message ?: e.toString()))
        }
    }

    /** Penjelasan error dpm — port explainDpmError() dari Web v2.5 */
    fun explainDpmError(output: String): Pair<String, String> {
        val o = output
        fun has(vararg re: String) = re.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(o) }
        return when {
            has("already some accounts", "accounts on the device") ->
                "Masih ada akun di perangkat" to "Hapus semua akun Google/Email di Pengaturan > Akun, lalu jalankan ulang."
            has("several users", "secondary user", "already.*users on the device") ->
                "Ada lebih dari satu pengguna" to "Hapus pengguna tambahan / profil kerja / Second Space di Pengaturan, lalu coba lagi."
            has("already (been )?(provisioned|set up)", "already provisioned", "user setup", "setup.*complete") ->
                "Perangkat sudah melewati setup awal" to "Android menolak set-device-owner setelah setup selesai + ada akun. Hapus akun dulu; jika tetap gagal, factory reset & jalankan sebelum login akun apa pun."
            has("device owner is already", "has a device owner", "already set") ->
                "Sudah ada Device Owner" to "Sudah ada aplikasi lain sebagai Device Owner. Hapus lewat MDM tsb atau factory reset."
            has("profile owner") ->
                "Ada Profile Owner" to "Hapus profil kerja terlebih dahulu."
            has("SecurityException", "not allowed") ->
                "Ditolak sistem Android" to "Di Xiaomi/Oppo/Vivo/Realme aktifkan \"USB Debugging (Security settings)\" di Opsi Developer, lalu coba lagi / gunakan perintah ADB dari PC."
            has("unknown admin", "no such component", "ClassNotFound", "IllegalArgument") ->
                "Admin Receiver tidak ditemukan" to "APK Headwind MDM belum terpasang / versi salah. Pasang APK launcher dulu (Langkah 1)."
            else -> "Gagal (alasan tidak dikenali)" to "Lihat output perangkat di log."
        }
    }

    fun explainInstallError(o: String): String {
        val map = listOf(
            "VERSION_DOWNGRADE" to "APK lebih lama dari yg terpasang. Copot dulu / pakai versi lebih baru.",
            "UPDATE_INCOMPATIBLE" to "Tanda tangan beda. Copot aplikasi lama dulu.",
            "SIGNATURE" to "Tanda tangan beda. Copot aplikasi lama dulu.",
            "INSUFFICIENT_STORAGE" to "Penyimpanan penuh. Kosongkan ruang.",
            "OLDER_SDK" to "Android HP terlalu lama untuk APK ini.",
            "NO_MATCHING_ABIS" to "Arsitektur CPU tidak cocok.",
            "USER_RESTRICTED" to "Diblokir. Aktifkan \"Install via USB\" di Opsi Developer.",
            "PARSE_FAILED" to "APK rusak / bukan APK valid.",
            "ALREADY_EXISTS" to "Aplikasi sudah terpasang.",
            "DEPRECATED_SDK_VERSION" to "APK menargetkan SDK terlalu lama (ditolak Android 14+)."
        )
        for ((k, v) in map) if (o.contains(k, true)) return v
        return ""
    }
}
