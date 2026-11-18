package app.term.one.core

import kotlin.math.max
import kotlin.math.min

object SixelDecoder {

    class Result(val pixels: IntArray, val width: Int, val height: Int)

    private const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 8_000_000
    private const val REGISTERS = 1024

    private val defaultPercent = intArrayOf(
        0, 0, 0, 20, 20, 80, 80, 13, 13, 20, 80, 20,
        80, 20, 80, 20, 80, 80, 80, 80, 20, 53, 53, 53,
        26, 26, 26, 33, 33, 60, 60, 26, 26, 33, 60, 33,
        60, 33, 60, 33, 60, 60, 60, 60, 33, 80, 80, 80
    )

    fun decode(data: ByteArray, size: Int): Result? = Decoder(data, size).run()

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun percent(v: Int): Int = min(100, max(0, v)) * 255 / 100

    private fun hlsToRgb(hue: Int, light: Int, sat: Int): Int {
        val h = (((hue % 360) + 240) % 360) / 360.0
        val l = min(100, max(0, light)) / 100.0
        val s = min(100, max(0, sat)) / 100.0
        if (s == 0.0) {
            val v = Math.round(l * 255).toInt()
            return argb(v, v, v)
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val r = Math.round(hueToChannel(p, q, h + 1.0 / 3.0) * 255).toInt()
        val g = Math.round(hueToChannel(p, q, h) * 255).toInt()
        val b = Math.round(hueToChannel(p, q, h - 1.0 / 3.0) * 255).toInt()
        return argb(r, g, b)
    }

    private fun hueToChannel(p: Double, q: Double, t0: Double): Double {
        var t = t0
        if (t < 0) t += 1.0
        if (t > 1) t -= 1.0
        return when {
            t < 1.0 / 6.0 -> p + (q - p) * 6 * t
            t < 0.5 -> q
            t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6
            else -> p
        }
    }

    private class Canvas {
        var pixels = IntArray(0)
        var stride = 0
        var rowCapacity = 0

        fun ensure(width: Int, height: Int): Boolean {
            if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                return false
            }
            if (width <= stride && height <= rowCapacity) {
                return true
            }
            var newStride = stride
            var newRows = rowCapacity
            if (width > newStride) {
                newStride = min(MAX_DIMENSION, max(width, max(newStride * 2, 64)))
            }
            if (height > newRows) {
                newRows = min(MAX_DIMENSION, max(height, max(newRows * 2, 64)))
            }
            if (newStride.toLong() * newRows > MAX_PIXELS) {
                return false
            }
            val grown = IntArray(newStride * newRows)
            for (row in 0 until rowCapacity) {
                System.arraycopy(pixels, row * stride, grown, row * newStride, stride)
            }
            pixels = grown
            stride = newStride
            rowCapacity = newRows
            return true
        }
    }

    private class Decoder(private val data: ByteArray, private val size: Int) {
        private var pos = 0
        private val params = IntArray(8)
        private var paramCount = 0
        private val canvas = Canvas()
        private val palette = IntArray(REGISTERS)

        init {
            for (i in 0 until 16) {
                palette[i] = argb(
                    percent(defaultPercent[i * 3]),
                    percent(defaultPercent[i * 3 + 1]),
                    percent(defaultPercent[i * 3 + 2])
                )
            }
            for (i in 16 until REGISTERS) {
                palette[i] = argb(0, 0, 0)
            }
        }

        private fun readParams() {
            paramCount = 0
            var value = -1
            while (pos < size) {
                val c = data[pos].toInt()
                if (c in 0x30..0x39) {
                    value = (if (value < 0) 0 else value) * 10 + (c - 0x30)
                    if (value > 100000) value = 100000
                    pos++
                } else if (c == ';'.code) {
                    if (paramCount < params.size) params[paramCount++] = value
                    value = -1
                    pos++
                } else {
                    break
                }
            }
            if ((value >= 0 || paramCount > 0) && paramCount < params.size) {
                params[paramCount++] = value
            }
        }

        private fun param(i: Int): Int = if (i < paramCount && params[i] >= 0) params[i] else 0

        fun run(): Result? {
            var x = 0
            var y = 0
            var maxX = 0
            var maxY = 0
            var color = palette[0]
            var repeat = 1

            while (pos < size) {
                val c = data[pos].toInt() and 0xFF
                pos++
                when {
                    c == '"'.code -> {
                        readParams()
                        val w = param(2)
                        val h = param(3)
                        if (w > 0 && h > 0 && canvas.ensure(w, h)) {
                            maxX = max(maxX, w)
                            maxY = max(maxY, h)
                        }
                    }
                    c == '#'.code -> {
                        readParams()
                        val index = min(REGISTERS - 1, param(0))
                        if (paramCount >= 5) {
                            palette[index] = if (param(1) == 1) {
                                hlsToRgb(param(2), param(3), param(4))
                            } else {
                                argb(percent(param(2)), percent(param(3)), percent(param(4)))
                            }
                        }
                        color = palette[index]
                    }
                    c == '!'.code -> {
                        readParams()
                        repeat = max(1, param(0))
                    }
                    c == '$'.code -> x = 0
                    c == '-'.code -> {
                        x = 0
                        y += 6
                    }
                    c in 63..126 -> {
                        val bits = c - 63
                        val count = repeat
                        repeat = 1
                        if (bits != 0 && canvas.ensure(x + count, y + 6)) {
                            for (k in 0 until count) {
                                for (b in 0 until 6) {
                                    if (((bits shr b) and 1) == 1) {
                                        canvas.pixels[(y + b) * canvas.stride + x + k] = color
                                    }
                                }
                            }
                        }
                        x += count
                        maxX = max(maxX, x)
                        maxY = max(maxY, y + 6)
                    }
                }
            }

            val width = min(maxX, MAX_DIMENSION)
            val height = min(maxY, MAX_DIMENSION)
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) {
                return null
            }
            val out = IntArray(width * height)
            for (row in 0 until min(height, canvas.rowCapacity)) {
                System.arraycopy(
                    canvas.pixels, row * canvas.stride,
                    out, row * width,
                    min(width, canvas.stride)
                )
            }
            return Result(out, width, height)
        }
    }
}