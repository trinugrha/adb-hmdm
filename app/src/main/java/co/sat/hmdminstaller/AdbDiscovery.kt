package co.sat.hmdminstaller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Penemuan layanan ADB lewat mDNS (NSD) + pembuat QR pairing.
 *
 * Alur "Pair dengan QR" (sama seperti Android Studio):
 *  1. Aplikasi membuat nama layanan acak + kode acak, lalu menampilkan QR `WIFI:T:ADB;S:<nama>;P:<kode>;;`.
 *  2. HP target: Wireless debugging › Pair device with QR code › pindai QR tersebut.
 *  3. HP target lalu menyiarkan layanan `_adb-tls-pairing._tcp` bernama <nama>; aplikasi menemukannya
 *     (IP + port pairing) dan memakai <kode> sebagai kode pairing.
 */
object AdbDiscovery {
    const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
    const val TYPE_CONNECT = "_adb-tls-connect._tcp"

    class Found(val name: String, val host: String, val port: Int) {
        val addr: String get() = "$host:$port"
    }

    /** Data QR: nama layanan + kode pairing sekali pakai. */
    class QrSession(val serviceName: String, val password: String) {
        val payload: String get() = "WIFI:T:ADB;S:$serviceName;P:$password;;"
    }

    private const val ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun newQrSession(): QrSession {
        val r = SecureRandom()
        fun rnd(n: Int) = (1..n).map { ALNUM[r.nextInt(ALNUM.length)] }.joinToString("")
        return QrSession("studio-" + rnd(10), rnd(12))
    }

    /** Gambar QR hitam di atas putih (selalu terang agar mudah dipindai, juga di mode gelap). */
    fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Cari satu layanan [type] yang lolos [accept] (nama layanan, host). Memblokir sampai ketemu,
     * [timeoutMs] habis, atau [cancel] bernilai true. Panggil dari thread latar. null bila tidak ketemu.
     */
    @Suppress("DEPRECATION")
    fun find(
        context: Context, type: String, timeoutMs: Long, cancel: AtomicBoolean,
        accept: (name: String, host: String) -> Boolean
    ): Found? {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val latch = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Found?>(null)
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)

        fun pump() {
            if (latch.count == 0L || !resolving.compareAndSet(false, true)) return
            val si = queue.poll() ?: run { resolving.set(false); return }
            try {
                nsd.resolveService(si, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) queue.add(s)
                        resolving.set(false); pump()
                    }
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val host = s.host?.hostAddress?.substringBefore('%')
                        if (host != null && s.port > 0 && accept(s.serviceName, host)) {
                            result.set(Found(s.serviceName, host, s.port))
                            latch.countDown()
                        }
                        resolving.set(false); pump()
                    }
                })
            } catch (e: Throwable) { resolving.set(false) }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) { queue.add(s); pump() }
        }
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) { return null }
        try {
            val end = System.currentTimeMillis() + timeoutMs
            while (result.get() == null && !cancel.get() && System.currentTimeMillis() < end) {
                if (latch.await(250, TimeUnit.MILLISECONDS)) break
            }
        } finally {
            latch.countDown()
            try { nsd.stopServiceDiscovery(listener) } catch (e: Throwable) { }
        }
        return result.get()
    }
}
