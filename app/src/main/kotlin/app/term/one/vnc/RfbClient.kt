package app.term.one.vnc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.min

class RfbClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onPasswordRequired(): String?
        fun onConnected(width: Int, height: Int, name: String)
        fun onResized(width: Int, height: Int)
        fun onUpdated(pixels: IntArray, width: Int, x: Int, y: Int, w: Int, h: Int)
        fun onBell()
        fun onServerText(text: String)
        fun onClosed(reason: String?)
    }

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var closed = false

    @Volatile
    private var output: DataOutputStream? = null

    private val outLock = Any()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vnc-writer").apply { isDaemon = true }
    }

    private val pixel = ByteArray(4)
    private val scratch = ByteArray(8192)
    private var width = 0
    private var height = 0
    private var fb = IntArray(0)

    fun start() {
        Thread({ runSession() }, "vnc-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        shutdown()
    }

    fun sendPointer(mask: Int, x: Int, y: Int) {
        val px = x.coerceIn(0, 65535)
        val py = y.coerceIn(0, 65535)
        submit { out ->
            out.writeByte(5)
            out.writeByte(mask and 0xFF)
            out.writeShort(px)
            out.writeShort(py)
        }
    }

    fun sendKey(down: Boolean, keysym: Int) {
        submit { out ->
            out.writeByte(4)
            out.writeByte(if (down) 1 else 0)
            out.writeShort(0)
            out.writeInt(keysym)
        }
    }

    // ------------------------------------------------------------------ session

    private fun runSession() {
        var reason: String? = null
        try {
            connectAndLoop()
        } catch (e: IOException) {
            reason = e.message ?: "Connection lost"
        } catch (e: RuntimeException) {
            reason = "Protocol error: ${e.message}"
        } catch (e: java.security.GeneralSecurityException) {
            reason = "Authentication error: ${e.message}"
        }
        val userClosed = closed
        shutdown()
        if (!userClosed) {
            listener.onClosed(reason)
        }
    }

    private fun shutdown() {
        closed = true
        try {
            socket?.close()
        } catch (e: IOException) {
            socket = null
        }
        writer.shutdownNow()
    }

    private fun connectAndLoop() {
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 8000)
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 14))

        handshake(inp, out)

        out.let {
            synchronized(outLock) {
                it.writeByte(1)
                it.flush()
            }
        }

        val w = inp.readUnsignedShort()
        val h = inp.readUnsignedShort()
        inp.readFully(ByteArray(16))
        val nameLength = inp.readInt()
        if (nameLength < 0 || nameLength > 4096) {
            throw IOException("Invalid server name length")
        }
        val nameBytes = ByteArray(nameLength)
        inp.readFully(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)

        if (w <= 0 || h <= 0 || w > 8192 || h > 8192 || w.toLong() * h > 16_000_000L) {
            throw IOException("Unsupported desktop size ${w}x$h")
        }
        allocate(w, h)

        synchronized(outLock) {
            out.writeByte(0)
            out.writeByte(0)
            out.writeShort(0)
            out.writeByte(32)
            out.writeByte(24)
            out.writeByte(0)
            out.writeByte(1)
            out.writeShort(255)
            out.writeShort(255)
            out.writeShort(255)
            out.writeByte(16)
            out.writeByte(8)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)

            out.writeByte(2)
            out.writeByte(0)
            out.writeShort(4)
            out.writeInt(ENC_HEXTILE)
            out.writeInt(ENC_COPYRECT)
            out.writeInt(ENC_RAW)
            out.writeInt(ENC_DESKTOP_SIZE)
            out.flush()
        }
        output = out

        requestUpdate(out, false)
        listener.onConnected(w, h, name)

        while (!closed) {
            when (val type = inp.readUnsignedByte()) {
                0 -> readUpdate(inp, out)
                1 -> {
                    inp.readUnsignedByte()
                    inp.readUnsignedShort()
                    val count = inp.readUnsignedShort()
                    skip(inp, count * 6L)
                }
                2 -> listener.onBell()
                3 -> readCutText(inp)
                else -> throw IOException("Unknown server message $type")
            }
        }
    }

    private fun handshake(inp: DataInputStream, out: DataOutputStream) {
        val versionBytes = ByteArray(12)
        inp.readFully(versionBytes)
        val version = String(versionBytes, Charsets.US_ASCII)
        if (!version.startsWith("RFB ") || version.length < 12) {
            throw IOException("The server is not a VNC server")
        }
        val major = version.substring(4, 7).toIntOrNull() ?: throw IOException("Invalid protocol version")
        val minor = version.substring(8, 11).toIntOrNull() ?: throw IOException("Invalid protocol version")
        val use = when {
            major > 3 || (major == 3 && minor >= 8) -> 8
            major == 3 && minor == 7 -> 7
            else -> 3
        }
        synchronized(outLock) {
            out.write("RFB 003.00$use\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        val securityType: Int
        if (use == 3) {
            securityType = inp.readInt()
            if (securityType == 0) {
                throw IOException(readReason(inp))
            }
        } else {
            val count = inp.readUnsignedByte()
            if (count == 0) {
                throw IOException(readReason(inp))
            }
            val types = ByteArray(count)
            inp.readFully(types)
            securityType = when {
                types.contains(1.toByte()) -> 1
                types.contains(2.toByte()) -> 2
                else -> throw IOException("The server requires an unsupported security type")
            }
            synchronized(outLock) {
                out.writeByte(securityType)
                out.flush()
            }
        }

        when (securityType) {
            1 -> Unit
            2 -> authenticate(inp, out)
            else -> throw IOException("Unsupported security type $securityType")
        }

        if (use >= 8 || securityType != 1) {
            val result = inp.readInt()
            if (result != 0) {
                val reason = if (use >= 8) readReason(inp) else ""
                throw IOException(if (reason.isEmpty()) "Authentication failed" else "Authentication failed: $reason")
            }
        }
    }

    private fun authenticate(inp: DataInputStream, out: DataOutputStream) {
        val challenge = ByteArray(16)
        inp.readFully(challenge)
        val password = listener.onPasswordRequired() ?: throw IOException("Password entry was cancelled")
        val response = VncDes.response(password, challenge)
        synchronized(outLock) {
            out.write(response)
            out.flush()
        }
    }

    private fun readReason(inp: DataInputStream): String {
        val length = inp.readInt()
        if (length < 0 || length > 4096) {
            return ""
        }
        val bytes = ByteArray(length)
        inp.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readCutText(inp: DataInputStream) {
        skip(inp, 3)
        val length = inp.readInt()
        if (length < 0) {
            throw IOException("Invalid clipboard length")
        }
        val keep = min(length, MAX_CUT_TEXT)
        val bytes = ByteArray(keep)
        inp.readFully(bytes)
        skip(inp, (length - keep).toLong())
        listener.onServerText(String(bytes, Charsets.ISO_8859_1))
    }

    private fun skip(inp: DataInputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val n = min(remaining, scratch.size.toLong()).toInt()
            inp.readFully(scratch, 0, n)
            remaining -= n
        }
    }

    private fun allocate(w: Int, h: Int) {
        width = w
        height = h
        fb = IntArray(w * h)
        Arrays.fill(fb, OPAQUE_BLACK)
    }

    private fun requestUpdate(out: DataOutputStream, incremental: Boolean) {
        synchronized(outLock) {
            out.writeByte(3)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(width)
            out.writeShort(height)
            out.flush()
        }
    }

    // ------------------------------------------------------------------ updates

    private fun readUpdate(inp: DataInputStream, out: DataOutputStream) {
        inp.readUnsignedByte()
        val count = inp.readUnsignedShort()
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        var resized = false

        for (i in 0 until count) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            val encoding = inp.readInt()

            if (encoding == ENC_DESKTOP_SIZE) {
                if (w <= 0 || h <= 0 || w > 8192 || h > 8192 || w.toLong() * h > 16_000_000L) {
                    throw IOException("Unsupported desktop size ${w}x$h")
                }
                allocate(w, h)
                resized = true
                minX = 0
                minY = 0
                maxX = w - 1
                maxY = h - 1
                continue
            }

            if (w == 0 || h == 0) {
                continue
            }
            if (x + w > width || y + h > height) {
                throw IOException("Rectangle is outside the framebuffer")
            }

            when (encoding) {
                ENC_RAW -> readRaw(inp, x, y, w, h)
                ENC_COPYRECT -> readCopyRect(inp, x, y, w, h)
                ENC_HEXTILE -> readHextile(inp, x, y, w, h)
                else -> throw IOException("Unsupported encoding $encoding")
            }

            minX = min(minX, x)
            minY = min(minY, y)
            maxX = maxOf(maxX, x + w - 1)
            maxY = maxOf(maxY, y + h - 1)
        }

        if (resized) {
            listener.onResized(width, height)
        }
        if (maxX >= 0) {
            listener.onUpdated(fb, width, minX, minY, maxX - minX + 1, maxY - minY + 1)
        }
        requestUpdate(out, !resized)
    }

    private fun pixelAt(buffer: ByteArray, p: Int): Int =
        OPAQUE_BLACK or
            ((buffer[p + 2].toInt() and 0xFF) shl 16) or
            ((buffer[p + 1].toInt() and 0xFF) shl 8) or
            (buffer[p].toInt() and 0xFF)

    private fun readPixel(inp: DataInputStream): Int {
        inp.readFully(pixel)
        return pixelAt(pixel, 0)
    }

    private fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (j in 0 until h) {
            val start = (y + j) * width + x
            Arrays.fill(fb, start, start + w, color)
        }
    }

    private fun readRaw(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val row = ByteArray(w * 4)
        for (j in 0 until h) {
            inp.readFully(row, 0, w * 4)
            var o = (y + j) * width + x
            var p = 0
            for (i in 0 until w) {
                fb[o++] = pixelAt(row, p)
                p += 4
            }
        }
    }

    private fun readCopyRect(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val sx = inp.readUnsignedShort()
        val sy = inp.readUnsignedShort()
        if (sx + w > width || sy + h > height) {
            throw IOException("CopyRect source is outside the framebuffer")
        }
        val temp = IntArray(w * h)
        for (j in 0 until h) {
            System.arraycopy(fb, (sy + j) * width + sx, temp, j * w, w)
        }
        for (j in 0 until h) {
            System.arraycopy(temp, j * w, fb, (y + j) * width + x, w)
        }
    }

    private fun readHextile(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        var background = OPAQUE_BLACK
        var foreground = OPAQUE_BLACK
        val tile = ByteArray(16 * 16 * 4)

        var ty = 0
        while (ty < h) {
            val th = min(16, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = min(16, w - tx)
                val sub = inp.readUnsignedByte()
                val px = x + tx
                val py = y + ty

                if ((sub and 1) != 0) {
                    for (j in 0 until th) {
                        inp.readFully(tile, 0, tw * 4)
                        var o = (py + j) * width + px
                        var p = 0
                        for (i in 0 until tw) {
                            fb[o++] = pixelAt(tile, p)
                            p += 4
                        }
                    }
                } else {
                    if ((sub and 2) != 0) {
                        background = readPixel(inp)
                    }
                    if ((sub and 4) != 0) {
                        foreground = readPixel(inp)
                    }
                    fillRect(px, py, tw, th, background)
                    if ((sub and 8) != 0) {
                        val subrects = inp.readUnsignedByte()
                        val coloured = (sub and 16) != 0
                        for (k in 0 until subrects) {
                            val color = if (coloured) readPixel(inp) else foreground
                            val xy = inp.readUnsignedByte()
                            val wh = inp.readUnsignedByte()
                            val sx = xy shr 4
                            val sy = xy and 15
                            val sw = (wh shr 4) + 1
                            val sh = (wh and 15) + 1
                            if (sx + sw > tw || sy + sh > th) {
                                throw IOException("Hextile subrectangle is outside its tile")
                            }
                            fillRect(px + sx, py + sy, sw, sh, color)
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
    }

    // ------------------------------------------------------------------ writing

    private fun submit(block: (DataOutputStream) -> Unit) {
        val out = output ?: return
        try {
            writer.execute {
                try {
                    synchronized(outLock) {
                        block(out)
                        out.flush()
                    }
                } catch (e: IOException) {
                    try {
                        socket?.close()
                    } catch (ignored: IOException) {
                        socket = null
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            return
        }
    }

    private companion object {
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_HEXTILE = 5
        const val ENC_DESKTOP_SIZE = -223
        const val MAX_CUT_TEXT = 1_000_000
        val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}