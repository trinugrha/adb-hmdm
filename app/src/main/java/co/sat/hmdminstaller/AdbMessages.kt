package co.sat.hmdminstaller

/**
 * Penyaring output mentah dan teks alasan gagal untuk ADB. Murni (tanpa state), dipakai [AdbController]
 * supaya logika teks terpisah dari logika koneksi.
 */
object AdbMessages {
    private val stackFrameRe = Regex("^at [\\w.$<>/]+\\(.*\\)$")
    private val moreRe = Regex("^\\.\\.\\. \\d+ more$")

    fun isRawNoise(l: String) =
        l.startsWith("Performing ") || stackFrameRe.matches(l) || moreRe.matches(l)

    fun restates(line: String, shown: String): Boolean {
        if (shown.isEmpty()) return false
        val body = line.removePrefix("Error:").trim()
        if (body.isNotEmpty() && shown.contains(body, ignoreCase = true)) return true
        val code = Regex("^Failure \\[([A-Z_]+)").find(line)?.groupValues?.get(1)
        return code != null && shown.contains(code)
    }

    private val installReasons = mapOf(
        "INSTALL_FAILED_ALREADY_EXISTS" to "sudah terpasang",
        "INSTALL_FAILED_VERSION_DOWNGRADE" to "versi lebih rendah dari yang terpasang",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "tanda tangan berbeda dengan versi terpasang",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "penyimpanan HP target penuh",
        "INSTALL_FAILED_INVALID_APK" to "file APK rusak atau tidak valid",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to "arsitektur CPU tidak cocok",
        "INSTALL_FAILED_OLDER_SDK" to "versi Android HP target terlalu lama",
        "INSTALL_FAILED_USER_RESTRICTED" to "pemasangan dibatasi kebijakan HP target",
        "INSTALL_FAILED_TEST_ONLY" to "APK bertanda test-only"
    )

    /** Saran perbaikan per kode install; hanya dipakai di baris ✗ akhir, ditulis sebagai kalimat sendiri. */
    private val installAdvice = mapOf(
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "Uninstall versi yang terpasang dulu, lalu pasang ulang",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "Kosongkan penyimpanan HP target, lalu coba lagi"
    )

    /** Alasan singkat dari keluaran `cmd package install` yang gagal. [advice] = sertakan saran perbaikan (untuk baris ✗ akhir). */
    fun installFailureReason(out: String, advice: Boolean, stalled: Boolean, viaUsb: Boolean): String {
        val code = Regex("INSTALL_[A-Z_]+").find(out)?.value
        val friendly = code?.let { installReasons[it] }
        return when {
            stalled ->
                if (!advice) "pengiriman macet"
                else if (viaUsb) "pengiriman macet. Cabut-colok kabel USB, lalu ketuk \"Pilih Perangkat\" lagi"
                else "pengiriman macet. Pastikan HP target menyala dan satu jaringan Wi-Fi, lalu ketuk \"Connect\" lagi"
            friendly != null -> "$friendly ($code)" + (if (advice) code?.let { installAdvice[it] }?.let { ". $it" }.orEmpty() else "")
            code != null -> code
            // Kegagalan kirim tanpa kode install (stream putus, exception): teks mentahnya (sering berbahasa Inggris,
            // mis. "stream closed") hanya tampil di baris DETAIL, bukan di baris ✗.
            out.startsWith("Error:") ->
                if (advice) "pengiriman APK gagal. Cek koneksi ke HP target, lalu coba lagi" else "pengiriman APK gagal"
            out.isBlank() -> "tidak ada balasan dari HP target"
            // Baris pertama yang bukan noise ("Performing Streamed Install", stack trace) dan tidak kosong.
            else -> out.lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !isRawNoise(it) }
                ?.removePrefix("Error:")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Fmt.clip(it, 120) }
                ?: "tidak diketahui"
        }
    }

    /** Alasan singkat dari keluaran `dpm set-device-owner` yang gagal. */
    fun ownerFailureReason(out: String): String {
        val l = out.lowercase()
        return when {
            "already has a device owner" in l || "device owner is already set" in l -> "HP target sudah memiliki device owner. Reset HP target ke pengaturan pabrik bila ingin menggantinya"
            "accounts" in l -> "masih ada akun (Google dan lainnya) di HP target. Hapus akun atau reset HP target"
            "provisioned" in l || "setup" in l -> "HP target sudah melewati setup awal. Reset HP target ke pengaturan pabrik"
            "unknown admin" in l || "does not exist" in l || "not found" in l -> "HMDM belum terpasang di HP target. Jalankan \"Pasang Aplikasi\" terlebih dahulu"
            else -> "perintah ditolak HP target. Pastikan HP target baru di-reset dan belum ada akun (Google dan lainnya)"
        }
    }
}
