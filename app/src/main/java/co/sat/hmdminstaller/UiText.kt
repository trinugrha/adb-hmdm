package co.sat.hmdminstaller

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan

/* Pemformat teks murni untuk popup, alasan gagal, dan isian alamat; dipisah dari MainActivity. */

/**
 * Merapikan teks isi popup: label ("Penyebab:", "Periksa:", "Langkah berikutnya:", "Catatan:", "Perhatian:", "Perintah:")
 * ditebalkan, perintah adb memakai huruf monospace, dan baris "• " diberi indent gantung supaya baris lanjutan sejajar dengan isi.
 */
internal fun Context.formatPopupMessage(text: String): CharSequence {
    val labelRe = Regex("^(Penyebab|Periksa|Langkah berikutnya|Catatan|Perhatian|Perintah):")
    val strong = colorOf(R.color.text)
    val sb = SpannableStringBuilder()
    val lines = text.split("\n")
    for ((i, line) in lines.withIndex()) {
        val start = sb.length
        sb.append(line)
        if (i < lines.lastIndex) sb.append('\n')
        val m = labelRe.find(line)
        if (m != null) {
            val labelEnd = start + m.value.length
            sb.setSpan(StyleSpan(Typeface.BOLD), start, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(strong), start, labelEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (m.groupValues[1] == "Perintah") {
                sb.setSpan(android.text.style.TypefaceSpan("monospace"), labelEnd, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (line.startsWith("• ")) {
            sb.setSpan(LeadingMarginSpan.Standard(0, dp(12)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return sb
}

/**
 * Isi satu butir "Penyebab" di popup: baris ✗ tanpa awalan "<Aksi> gagal: " / "Gagal: ", karena judul popup
 * sudah memuat aksinya ("Connect USB gagal"). Huruf pertama dibuat kapital.
 */
internal fun errReason(s: String): String {
    val stripped = s.replace(Regex("^(?:[^:]{1,60}? )?[Gg]agal: "), "")
    // Kapitalkan hanya bila awalan benar-benar dibuang; baris seperti "agent.apk: ..." dibiarkan.
    return plainReason(if (stripped.length != s.length) stripped.replaceFirstChar { it.uppercase() } else s)
}

/** Popup berbahasa sehari-hari: kode teknis install (mis. "(INSTALL_FAILED_...)") hanya tampil di log. */
internal fun plainReason(s: String): String = s.replace(Regex(" \\(INSTALL_[A-Z_]+\\)"), "")

/** Buang spasi dan tanda kutip dari isian IP / port. */
internal fun clean(s: String) = s.trim().replace("\"", "").replace(" ", "")

/** Gabung IP + port jadi "ip:port"; toleran bila user menempel "ip:port" di kolom IP. */
internal fun addr(ipText: String, portText: String): String {
    var ip = clean(ipText)
    var port = clean(portText)
    val i = ip.lastIndexOf(':')
    if (i > 0 && port.isEmpty()) {
        port = ip.substring(i + 1)
        ip = ip.substring(0, i)
    }
    return if (ip.isEmpty() || port.isEmpty()) "" else "$ip:$port"
}
