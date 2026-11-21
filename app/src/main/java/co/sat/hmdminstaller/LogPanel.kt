package co.sat.hmdminstaller

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.TextView

/**
 * Panel log di layar: menulis entri berwarna ke [txtLog], menggulir ke bawah, membatasi panjang log, dan mencatat
 * baris ✗ / ! tiap proses (dipakai MainActivity untuk isi popup gagal). Harus dipakai dari UI thread.
 */
internal class LogPanel(private val ctx: Context, private val txtLog: TextView, private val logScroll: ScrollView) {

    /** Pembuka satu proses: "── Judul · 14:02:11". */
    fun logHead(title: String, at: Long = System.currentTimeMillis()) {
        // Pesan lepas (mis. APK dilewati, koneksi terputus) tidak diakhiri baris kosong; pisahkan di sini.
        val cur = txtLog.text
        if (cur.isNotEmpty() && !cur.endsWith("\n\n")) logBlank()
        log(LogLevel.HEAD, "$title · ${Fmt.clock(at)}")
    }

    /** Penutup satu proses: "✓ Selesai: Judul · 4.2 dtk" atau "✗ Gagal: Judul · 4.2 dtk", lalu baris kosong pemisah. */
    fun logEnd(title: String, ok: Boolean, startedAt: Long) {
        val took = Fmt.duration(System.currentTimeMillis() - startedAt)
        if (ok) log(LogLevel.END_OK, "Selesai: $title · $took")
        else log(LogLevel.END_ERR, "Gagal: $title · $took")
        logBlank()
    }

    /** Gulir isi log saja ke bawah, tanpa memindahkan fokus/halaman utama. */
    private fun scrollLogToEnd() {
        logScroll.post { logScroll.scrollTo(0, (logScroll.getChildAt(0).height - logScroll.height).coerceAtLeast(0)) }
    }

    /** Satu baris kosong sebagai pemisah antar proses. */
    private fun logBlank() {
        txtLog.append("\n")
        scrollLogToEnd()
    }

    /** Baris ERR selama satu proses, dipakai sebagai isi popup bila proses gagal. */
    val runErrors = mutableListOf<String>()

    /**
     * Baris WARN selama satu proses. Hanya dipakai sebagai butir "Penyebab" bila proses gagal tanpa baris ✗
     * (mis. "Belum terhubung ke HP target" atau "Isi IP:Port connect terlebih dahulu"), supaya popup tidak
     * berisi "tidak ada penyebab yang tercatat" padahal log sudah menyebut alasannya.
     */
    val runWarns = mutableListOf<String>()

    /**
     * Satu entri log berwarna. Warna dan penanda ditentukan oleh [level];
     * pesan multi-baris dirapikan (baris lanjutan diberi indent selebar penanda), dan titik di
     * akhir pesan dibuang supaya semua baris seragam (elipsis "..." dibiarkan).
     */
    fun log(level: LogLevel, s: String) {
        if (s.isBlank()) return
        if (level == LogLevel.ERR) runErrors.add(s.trim())
        if (level == LogLevel.WARN) runWarns.add(s.trim())
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
        scrollLogToEnd()
    }

    /** Batasi panjang log agar tidak membengkak: buang baris paling lama (dipotong di batas baris). */
    private fun trimLog() {
        val cur = txtLog.text
        if (cur.length <= MAX_CHARS) return
        val sb = SpannableStringBuilder(cur)
        val cut = sb.indexOf("\n", sb.length - KEEP_CHARS)
        sb.delete(0, if (cut < 0) sb.length - KEEP_CHARS else cut + 1)
        txtLog.text = sb
    }

    private fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.HEAD, LogLevel.SUMMARY -> Color.WHITE
        LogLevel.STEP -> Color.rgb(252, 211, 77)
        LogLevel.CMD -> Color.rgb(56, 189, 248)
        LogLevel.OK, LogLevel.END_OK -> ctx.colorOf(R.color.status_ok)
        LogLevel.WARN -> ctx.colorOf(R.color.status_warn)
        LogLevel.ERR, LogLevel.END_ERR -> ctx.colorOf(R.color.status_bad)
        LogLevel.DETAIL -> ctx.colorOf(R.color.status_idle)
    }

    private companion object {
        const val MAX_CHARS = 60_000
        const val KEEP_CHARS = 40_000
    }
}
