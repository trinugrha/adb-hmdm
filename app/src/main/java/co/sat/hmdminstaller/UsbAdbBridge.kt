package co.sat.hmdminstaller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Jembatan ADB lewat kabel USB (USB debugging).
 *
 * HP teknisi bertindak sebagai USB host (kabel OTG). Paket ADB dari library (otentikasi RSA, shell, install)
 * dikirim lewat soket TCP loopback 127.0.0.1:<port>, lalu jembatan ini meneruskannya apa adanya ke
 * endpoint bulk USB milik HP target (dan sebaliknya). Dengan begitu seluruh logika ADB yang sudah ada
 * (pair/connect/install/set-device-owner) dipakai ulang tanpa perubahan.
 */
class UsbAdbBridge(context: Context) {

    /** HP target yang terdeteksi di port USB dan menyediakan interface ADB. */
    data class Target(val device: UsbDevice, val label: String)

    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var active: Socket? = null
    private var pumpThreads: List<Thread> = emptyList()

    /** Penyebab terakhir pompa USB berhenti (sebelumnya ditelan diam-diam), untuk diagnosis di log. */
    @Volatile var lastPumpError: String = ""

    /**
     * Kirim paket nol-panjang (ZLP) setelah payload yang panjangnya kelipatan ukuran paket endpoint,
     * seperti yang dilakukan adb di PC. Sebagian adbd menunggu paket pendek untuk menandai akhir payload;
     * tanpa ZLP transfer macet di 0%.
     */
    @Volatile var sendZlp = false

    private val upCount = java.util.concurrent.atomic.AtomicInteger()
    private val downCount = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastUp = "-"
    @Volatile private var lastDown = "-"

    /** Nol-kan penghitung paket (dipanggil sebelum tiap install). */
    fun resetStats() { upCount.set(0); downCount.set(0); lastUp = "-"; lastDown = "-" }

    /** Ringkasan lalu lintas jembatan sejak resetStats(), untuk diagnosis saat macet. */
    fun stats(): String =
        "ke target ${upCount.get()} paket (terakhir $lastUp), dari target ${downCount.get()} paket (terakhir $lastDown)"

    private fun cmdName(h: ByteArray): String = String(h, 0, 4, Charsets.US_ASCII)

    companion object {
        private const val ACTION_PERMISSION = "co.sat.hmdminstaller.USB_PERMISSION"
        private const val HEADER = 24
        private const val CHUNK = 16384
        private const val MAX_PAYLOAD = 1024 * 1024
    }

    /** Daftar perangkat USB yang punya interface ADB (class 0xFF, subclass 0x42, protocol 0x01). */
    fun findTargets(): List<Target> =
        usb.deviceList.values.mapNotNull { d ->
            if (findAdbInterface(d) == null) null
            else Target(d, listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank {
                "USB %04x:%04x".format(d.vendorId, d.productId)
            })
        }

    private fun findAdbInterface(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (itf.interfaceClass == 0xFF && itf.interfaceSubclass == 0x42 && itf.interfaceProtocol == 0x01) return itf
        }
        return null
    }

    fun hasPermission(d: UsbDevice) = usb.hasPermission(d)

    /** Minta izin USB ke user (dialog sistem) dan tunggu jawabannya. Panggil dari thread latar. */
    fun requestPermission(d: UsbDevice, timeoutSec: Long = 60): Boolean {
        if (usb.hasPermission(d)) return true
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != ACTION_PERMISSION) return
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                appContext, 0, Intent(ACTION_PERMISSION).setPackage(appContext.packageName), flags
            )
            usb.requestPermission(d, pi)
            latch.await(timeoutSec, TimeUnit.SECONDS)
        } finally {
            try { appContext.unregisterReceiver(receiver) } catch (e: Throwable) { }
        }
        return granted || usb.hasPermission(d)
    }

    /**
     * Buka perangkat USB dan nyalakan jembatan.
     * @return port TCP loopback yang bisa dipakai library ADB (connect 127.0.0.1:port).
     */
    @Synchronized
    fun start(d: UsbDevice): Int {
        stop()
        lastPumpError = ""
        val itf = findAdbInterface(d) ?: throw IllegalStateException("Interface ADB tidak ditemukan di perangkat USB.")
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        if (inEp == null || outEp == null) throw IllegalStateException("Endpoint bulk USB tidak lengkap.")
        val c = usb.openDevice(d) ?: throw IllegalStateException("Gagal membuka perangkat USB (izin belum diberikan?).")
        if (!c.claimInterface(itf, true)) {
            c.close()
            throw IllegalStateException("Gagal mengambil interface ADB (dipakai aplikasi lain? Matikan adb server di PC).")
        }
        conn = c; iface = itf; epIn = inEp; epOut = outEp; device = d
        val ss = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server = ss
        running = true
        Thread({ acceptLoop(ss) }, "usb-adb-accept").apply { isDaemon = true; start() }
        return ss.localPort
    }

    /** Tutup jembatan dan lepaskan perangkat USB. */
    @Synchronized
    fun stop() {
        running = false
        try { active?.close() } catch (e: Throwable) { }
        active = null
        try { server?.close() } catch (e: Throwable) { }
        server = null
        val c = conn
        if (c != null) {
            try { iface?.let { c.releaseInterface(it) } } catch (e: Throwable) { }
            try { c.close() } catch (e: Throwable) { }
        }
        conn = null; iface = null; epIn = null; epOut = null; device = null
    }

    /** Kunci jenis HP target (vendor:product ID USB), atau null bila tidak ada perangkat aktif. */

    val isRunning: Boolean get() = running && deviceAttached()

    private fun deviceAttached(): Boolean {
        val d = device ?: return false
        return usb.deviceList.containsKey(d.deviceName)
    }

    // Satu koneksi TCP dilayani sekali waktu; koneksi baru (mis. sambung ulang) menggantikan yang lama.
    // Pompa lama harus benar-benar berhenti dulu: bila tidak, ia ikut membaca endpoint USB dan "mencuri"
    // paket jawaban (CNXN/AUTH) yang seharusnya untuk sesi baru, sehingga handshake macet.
    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try { ss.accept() } catch (e: Throwable) { break }
            try { active?.close() } catch (e: Throwable) { }
            active = s
            pumpThreads.forEach { try { it.join(3000) } catch (e: InterruptedException) { } }
            if (active !== s) { try { s.close() } catch (e: Throwable) { }; continue }
            drainStale()
            s.tcpNoDelay = true
            val up = Thread({ pumpSocketToUsb(s) }, "usb-adb-up").apply { isDaemon = true }
            val down = Thread({ pumpUsbToSocket(s) }, "usb-adb-down").apply { isDaemon = true }
            pumpThreads = listOf(up, down)
            up.start(); down.start()
        }
    }

    /** Buang paket sisa sesi sebelumnya di endpoint IN (sesi baru belum mengirim apa pun, jadi semuanya basi). */
    private fun drainStale() {
        val c = conn ?: return
        val ep = epIn ?: return
        val buf = ByteArray(CHUNK)
        var n = 0
        while (n < 64 && c.bulkTransfer(ep, buf, buf.size, 50) > 0) n++
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun usbWrite(buf: ByteArray, len: Int) {
        val c = conn ?: throw java.io.IOException("USB tertutup")
        val ep = epOut ?: throw java.io.IOException("USB tertutup")
        var off = 0
        while (off < len) {
            val n = minOf(CHUNK, len - off)
            val r = c.bulkTransfer(ep, buf, off, n, 5000)
            if (r < 0) throw java.io.IOException("Tulis USB gagal")
            off += r
        }
    }

    /** Kirim paket nol-panjang bila `len` kelipatan ukuran paket endpoint OUT (hanya jika sendZlp aktif). */
    private fun usbZlpIfNeeded(len: Int) {
        if (!sendZlp || len <= 0) return
        val c = conn ?: return
        val ep = epOut ?: return
        val mps = ep.maxPacketSize
        if (mps > 0 && len % mps == 0) {
            c.bulkTransfer(ep, ByteArray(1), 0, 5000)
        }
    }

    /** Baca tepat `len` byte; timeout dianggap "belum ada data" selama `idleOk` dan perangkat masih terpasang. */
    private fun usbRead(buf: ByteArray, len: Int, idleOk: Boolean, mine: () -> Boolean) {
        val c = conn ?: throw java.io.IOException("USB tertutup")
        val ep = epIn ?: throw java.io.IOException("USB tertutup")
        var off = 0
        var idleSince = System.currentTimeMillis()
        while (off < len) {
            if (!running || !mine()) throw java.io.IOException("USB ditutup")
            val n = minOf(CHUNK, len - off)
            val r = c.bulkTransfer(ep, buf, off, n, 250)
            if (r > 0) { off += r; idleSince = System.currentTimeMillis(); continue }
            // r < 0: timeout atau error. Perangkat dicabut = berhenti.
            if (!deviceAttached()) throw java.io.IOException("Kabel USB dicabut")
            val waitingForHeaderStart = off == 0 && idleOk
            if (!waitingForHeaderStart && System.currentTimeMillis() - idleSince > 15_000) {
                throw java.io.IOException("Timeout baca USB")
            }
        }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun pumpSocketToUsb(s: Socket) {
        try {
            val input: InputStream = s.getInputStream()
            val header = ByteArray(HEADER)
            val payload = ByteArray(MAX_PAYLOAD)
            while (running && active === s) {
                readFully(input, header, HEADER)
                val len = le32(header, 12)
                if (len < 0 || len > MAX_PAYLOAD) throw java.io.IOException("Ukuran paket ADB tidak valid: $len")
                if (len > 0) readFully(input, payload, len)
                val name = cmdName(header)
                upCount.incrementAndGet()
                lastUp = "$name($len)"
                usbWrite(header, HEADER)
                if (len > 0) {
                    usbWrite(payload, len)
                    usbZlpIfNeeded(len)
                }
            }
        } catch (e: Throwable) {
            if (active === s && running) lastPumpError = "PC->USB: ${e.javaClass.simpleName}: ${e.message ?: "-"}"
        } finally {
            try { s.close() } catch (e: Throwable) { }
        }
    }

    private fun pumpUsbToSocket(s: Socket) {
        try {
            val out: OutputStream = s.getOutputStream()
            val header = ByteArray(HEADER)
            val payload = ByteArray(MAX_PAYLOAD)
            while (running && active === s) {
                usbRead(header, HEADER, idleOk = true) { active === s }
                val len = le32(header, 12)
                if (len < 0 || len > MAX_PAYLOAD) throw java.io.IOException("Ukuran paket ADB tidak valid: $len")
                if (len > 0) usbRead(payload, len, idleOk = false) { active === s }
                val name = cmdName(header)
                downCount.incrementAndGet()
                lastDown = "$name($len)"
                out.write(header, 0, HEADER)
                if (len > 0) out.write(payload, 0, len)
                out.flush()
            }
        } catch (e: Throwable) {
            if (active === s && running) lastPumpError = "USB->soket: ${e.javaClass.simpleName}: ${e.message ?: "-"}"
        } finally {
            try { s.close() } catch (e: Throwable) { }
        }
    }
}
