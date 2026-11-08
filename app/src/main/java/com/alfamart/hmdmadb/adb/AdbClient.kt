package com.alfamart.hmdmadb.adb

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Klien ADB Wireless murni (tanpa root/USB).
 * Mendukung koneksi TCP polos (port 5555 klasik) dan TLS (Wireless Debugging Android 11+).
 * Operasi diserialisasi (satu socket logis aktif dalam satu waktu) — cukup untuk
 * shell, install streaming, dan sync push.
 */
class AdbClient(private val appCtx: Context) {

    private var socket: Socket? = null
    private var inp: DataInputStream? = null
    private var out: DataOutputStream? = null
    private var maxData = 4096
    private var nextLocalId = 1
    private val lock = Any()

    val connected: Boolean get() = socket?.isConnected == true

    // ---- konstanta protokol ----
    private val A_CNXN = cmd("CNXN"); private val A_AUTH = cmd("AUTH")
    private val A_OPEN = cmd("OPEN"); private val A_OKAY = cmd("OKAY")
    private val A_CLSE = cmd("CLSE"); private val A_WRTE = cmd("WRTE")
    private val AUTH_TOKEN = 1; private val AUTH_SIGNATURE = 2; private val AUTH_RSAPUBLICKEY = 3
    private val VERSION = 0x01000000

    private fun cmd(s: String): Int {
        val b = s.toByteArray(Charsets.US_ASCII)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    data class Msg(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun checksum(data: ByteArray): Int {
        var s = 0
        for (b in data) s += b.toInt() and 0xFF
        return s
    }

    private fun writeMsg(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
        val o = out!!
        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(command); bb.putInt(arg0); bb.putInt(arg1)
        bb.putInt(payload.size); bb.putInt(checksum(payload)); bb.putInt(command xor -1)
        synchronized(o) {
            o.write(bb.array())
            if (payload.isNotEmpty()) o.write(payload)
            o.flush()
        }
    }

    private fun readMsg(): Msg {
        val i = inp!!
        val hdr = ByteArray(24)
        var off = 0
        while (off < 24) {
            val n = i.read(hdr, off, 24 - off)
            if (n < 0) throw EOFException("Koneksi ADB terputus")
            off += n
        }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int; val arg0 = bb.int; val arg1 = bb.int
        val len = bb.int; val cks = bb.int; val magic = bb.int
        if (command xor -1 != magic) throw IllegalStateException("Header ADB rusak")
        if (len < 0 || len > 1024 * 1024 * 4) throw IllegalStateException("Panjang payload ADB tidak wajar: $len")
        val payload = ByteArray(len)
        var p = 0
        while (p < len) {
            val n = i.read(payload, p, len - p)
            if (n < 0) throw EOFException("Koneksi ADB terputus (payload)")
            p += n
        }
        if (checksum(payload) != cks) throw IllegalStateException("Checksum ADB tidak cocok")
        return Msg(command, arg0, arg1, payload)
    }

    /** Hubungkan ke HP target. useTls=true untuk Wireless Debugging (Android 11+). */
    fun connect(host: String, port: Int, useTls: Boolean, timeoutMs: Int = 12_000) {
        close()
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = 30_000
        val s: Socket = if (useTls) {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, tm, java.security.SecureRandom())
            val ssl = sc.socketFactory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
            ssl.soTimeout = 30_000
            ssl.startHandshake()
            ssl
        } else raw
        socket = s
        inp = DataInputStream(s.getInputStream())
        out = DataOutputStream(s.getOutputStream())

        val keys = AdbCrypto.getOrCreate(appCtx)
        writeMsg(A_CNXN, VERSION, maxData, "host::android-hmdm\x00".toByteArray())
        while (true) {
            val m = readMsg()
            when {
                m.command == A_CNXN -> {
                    maxData = if (m.arg1 > 0) minOf(m.arg1, 256 * 1024) else 4096
                    return
                }
                m.command == A_AUTH && m.arg0 == AUTH_TOKEN -> {
                    return try {
                        val sig = AdbCrypto.sign(keys.private, m.payload)
                        writeMsg(A_AUTH, AUTH_SIGNATURE, 0, sig)
                    } catch (e: Exception) {
                        writeMsg(A_AUTH, AUTH_RSAPUBLICKEY, 0, AdbCrypto.publicKeyPayload(appCtx))
                    }
                }
                m.command == A_CLSE && m.arg1 == 0 ->
                    throw IllegalStateException("Perangkat menolak koneksi (belum di-otorisasi / butuh persetujuan di HP target).")
                else -> { /* abaikan */ }
            }
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; inp = null; out = null
    }

    private fun open(destination: String): Pair<Int, Int> {
        val localId = nextLocalId++
        writeMsg(A_OPEN, localId, 0, (destination + "\x00").toByteArray())
        while (true) {
            val m = readMsg()
            if (m.command == A_OKAY && m.arg0 == localId) return Pair(localId, m.arg1)
            if (m.command == A_CLSE && (m.arg0 == localId || m.arg1 == localId))
                throw IllegalStateException("Perangkat menolak layanan '$destination': ${m.payload.toString(Charsets.UTF_8)}")
            if (m.command == A_WRTE && m.arg1 == localId) writeMsg(A_OKAY, localId, m.arg0)
        }
    }

    private fun closeStream(localId: Int, remoteId: Int) {
        try { writeMsg(A_CLSE, localId, remoteId) } catch (_: Exception) {}
    }

    /** Jalankan perintah shell, kembalikan stdout+stderr sebagai teks. */
    fun shell(command: String): String {
        synchronized(lock) {
            val (localId, remoteId) = open("shell:$command")
            val sb = StringBuilder()
            return try {
                while (true) {
                    val m = readMsg()
                    when {
                        m.command == A_WRTE && m.arg1 == localId -> {
                            sb.append(m.payload.toString(Charsets.UTF_8))
                            writeMsg(A_OKAY, localId, m.arg0)
                        }
                        m.command == A_OKAY && m.arg0 == localId -> {}
                        m.command == A_CLSE && (m.arg0 == localId || m.arg1 == localId) -> {
                            try { writeMsg(A_CLSE, localId, remoteId) } catch (_: Exception) {}
                            return sb.toString().trim()
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE") sb.toString()
            } finally {
                try { closeStream(localId, remoteId) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Install APK modern: kirim bytes lewat `exec:cmd package install -r -t -S <size>`.
     * onProgress(sentBytes, totalBytes).
     */
    fun installStreaming(apkSize: Long, openStream: () -> InputStream, onProgress: (Long) -> Unit): String {
        synchronized(lock) {
            val (localId, remoteId) = open("exec:cmd package install -r -t -S $apkSize")
            // tunggu OKAY pembuka (sudah ditangani di open), lalu kirim WRTE
            var sent = 0L
            openStream().use { ins ->
                val buf = ByteArray(minOf(maxData, 64 * 1024))
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    writeMsg(A_WRTE, localId, remoteId, buf.copyOf(n))
                    sent += n
                    onProgress(sent)
                    // tunggu OKAY tiap chunk agar tidak overflow
                    while (true) {
                        val m = readMsg()
                        if (m.command == A_OKAY && m.arg0 == localId) break
                        if (m.command == A_CLSE) throw IllegalStateException("Stream install ditutup perangkat di tengah pengiriman.")
                        if (m.command == A_WRTE && m.arg1 == localId) writeMsg(A_OKAY, localId, m.arg0)
                    }
                }
            }
            // baca output "Success" sampai CLSE
            val sb = StringBuilder()
            // beri tahu EOF: tutup sisi tulis tidak ada di protokol ADB → langsung baca hasil
            val deadline = System.currentTimeMillis() + 120_000
            while (true) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("Timeout menunggu hasil install.")
                val m = readMsg()
                when {
                    m.command == A_WRTE && m.arg1 == localId -> {
                        sb.append(m.payload.toString(Charsets.UTF_8))
                        writeMsg(A_OKAY, localId, m.arg0)
                    }
                    m.command == A_CLSE -> {
                        try { writeMsg(A_CLSE, localId, remoteId) } catch (_: Exception) {}
                        return sb.toString().trim()
                    }
                }
            }
        }
    }

    /** Mode lama (SDK < 24): push file lalu `pm install`. */
    fun pushThenInstall(apkSize: Long, openStream: () -> InputStream, onProgress: (Long) -> Unit): String {
        val remote = "/data/local/tmp/hmdm_${System.currentTimeMillis()}.apk"
        syncPush(remote, apkSize, openStream, onProgress)
        return try {
            shell("pm install -r -t \"$remote\"")
        } finally {
            try { shell("rm -f \"$remote\"") } catch (_: Exception) {}
        }
    }

    private fun syncPush(remotePath: String, size: Long, openStream: () -> InputStream, onProgress: (Long) -> Unit) {
        synchronized(lock) {
            val (localId, remoteId) = open("sync:")
            try {
                fun sendSync(id: String, data: ByteArray) {
                    val bb = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                    bb.put(id.toByteArray(Charsets.US_ASCII), 0, 4)
                    bb.putInt(data.size)
                    bb.put(data)
                    writeMsg(A_WRTE, localId, remoteId, bb.array())
                    // tunggu OKAY
                    while (true) {
                        val m = readMsg()
                        if (m.command == A_OKAY && m.arg0 == localId) return
                        if (m.command == A_CLSE) throw IllegalStateException("Sync ditutup perangkat.")
                        if (m.command == A_WRTE && m.arg1 == localId) {
                            handleSyncReply(m.payload)
                            writeMsg(A_OKAY, localId, m.arg0)
                        }
                    }
                }
                sendSync("SEND", ("$remotePath,644".toByteArray(Charsets.UTF_8)))
                var sent = 0L
                openStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        sendSync("DATA", buf.copyOf(n))
                        sent += n
                        onProgress(sent)
                    }
                }
                // DONE dengan mtime
                val mtime = (System.currentTimeMillis() / 1000).toInt()
                val doneBb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mtime)
                sendSync("DONE", doneBb.array())
                // QUIT
                val quitBb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                quitBb.put("QUIT".toByteArray(Charsets.US_ASCII)); quitBb.putInt(0)
                writeMsg(A_WRTE, localId, remoteId, quitBb.array())
                // baca OKAY/FAIL akhir
                val deadline = System.currentTimeMillis() + 60_000
                while (true) {
                    if (System.currentTimeMillis() > deadline) throw IllegalStateException("Timeout sync push.")
                    val m = readMsg()
                    if (m.command == A_OKAY && m.arg0 == localId) {
                        // cek apakah ada FAIL di payload WRTE sebelumnya sudah dicek
                        break
                    }
                    if (m.command == A_WRTE && m.arg1 == localId) {
                        handleSyncReply(m.payload)
                        writeMsg(A_OKAY, localId, m.arg0)
                    }
                    if (m.command == A_CLSE) break
                }
            } finally {
                try { closeStream(localId, remoteId) } catch (_: Exception) {}
            }
        }
    }

    private fun handleSyncReply(payload: ByteArray) {
        if (payload.size < 8) return
        val id = payload.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        if (id == "FAIL") {
            val len = ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val msg = if (payload.size >= 8 + len) payload.copyOfRange(8, 8 + len).toString(Charsets.UTF_8) else "sync failed"
            throw IllegalStateException("Push gagal: $msg")
        }
    }

    /** Kirim perintah tcpip:5555 (untuk menyalakan ADB klasik). */
    fun tcpip(port: Int = 5555): String {
        synchronized(lock) {
            val (localId, remoteId) = open("tcpip:$port")
            val sb = StringBuilder()
            return try {
                val deadline = System.currentTimeMillis() + 10_000
                while (true) {
                    if (System.currentTimeMillis() > deadline) return sb.toString()
                    val m = readMsg()
                    if (m.command == A_WRTE && m.arg1 == localId) {
                        sb.append(m.payload.toString(Charsets.UTF_8))
                        writeMsg(A_OKAY, localId, m.arg0)
                    }
                    if (m.command == A_CLSE || m.command == A_OKAY && sb.isNotEmpty()) {
                        // tcpip biasanya menutup koneksi; anggap sukses
                        if (m.command == A_CLSE) {
                            try { writeMsg(A_CLSE, localId, remoteId) } catch (_: Exception) {}
                            return sb.toString().trim()
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE") sb.toString()
            } finally {
                try { closeStream(localId, remoteId) } catch (_: Exception) {}
            }
        }
    }
}
