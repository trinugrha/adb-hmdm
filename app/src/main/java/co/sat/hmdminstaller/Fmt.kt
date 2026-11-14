package co.sat.hmdminstaller

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Format ukuran, durasi, dan jam untuk log dan progress.
 *
 * Selalu memakai Locale.US supaya titik desimal sama di semua tempat, juga di HP berbahasa
 * Indonesia (tanpa ini ukuran tampil "12,3 MB" sementara durasi "4.2 dtk").
 */
object Fmt {
    /** 12.3 MB / 840 KB / 512 B */
    fun size(bytes: Long): String = when {
        bytes >= 1_048_576 -> mb(bytes)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** Selalu dalam MB, satu desimal: 12.3 MB */
    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

    /** 850 ms / 4.2 dtk */
    fun duration(ms: Long): String =
        if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f dtk", ms / 1000.0)

    /** Panjang maksimum satu baris DETAIL di log; selebihnya dipotong dan diakhiri "…". */
    const val DETAIL_MAX = 240

    /** Potong satu baris teks panjang dan akhiri dengan "…" supaya jelas bahwa teksnya tidak utuh. */
    fun clip(s: String, max: Int = DETAIL_MAX): String =
        if (s.length > max) s.take(max).trimEnd() + "…" else s

    /** 14:02:11 */
    fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMs))
}
