package app.term.one.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

object InlineImage {

    enum class SizeUnit { AUTO, CELLS, PIXELS, PERCENT }

    class Size(val unit: SizeUnit, val value: Int)

    class Parsed(
        val bitmap: Bitmap,
        val width: Size,
        val height: Size,
        val preserveAspect: Boolean
    )

    private const val MAX_PIXELS = 8_000_000L

    fun parse(data: ByteArray, start: Int, end: Int): Parsed? {
        var colon = -1
        var i = start
        while (i < end) {
            if (data[i] == ':'.code.toByte()) {
                colon = i
                break
            }
            i++
        }
        if (colon < 0) {
            return null
        }

        val header = String(data, start, colon - start, Charsets.US_ASCII).removePrefix("File=")
        var inline = false
        var width = Size(SizeUnit.AUTO, 0)
        var height = Size(SizeUnit.AUTO, 0)
        var preserve = true

        for (entry in header.split(';')) {
            val eq = entry.indexOf('=')
            if (eq < 0) continue
            val key = entry.substring(0, eq)
            val value = entry.substring(eq + 1)
            when (key) {
                "inline" -> inline = value == "1"
                "width" -> width = parseSize(value)
                "height" -> height = parseSize(value)
                "preserveAspectRatio" -> preserve = value != "0"
            }
        }
        if (!inline) {
            return null
        }

        val bytes = try {
            Base64.decode(data, colon + 1, end - colon - 1, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return Parsed(bitmap, width, height, preserve)
    }

    private fun parseSize(raw: String): Size {
        val text = raw.trim().lowercase()
        if (text.isEmpty() || text == "auto") {
            return Size(SizeUnit.AUTO, 0)
        }
        if (text.endsWith("px")) {
            val v = text.removeSuffix("px").toIntOrNull() ?: return Size(SizeUnit.AUTO, 0)
            return Size(SizeUnit.PIXELS, v)
        }
        if (text.endsWith("%")) {
            val v = text.removeSuffix("%").toIntOrNull() ?: return Size(SizeUnit.AUTO, 0)
            return Size(SizeUnit.PERCENT, v)
        }
        val v = text.toIntOrNull() ?: return Size(SizeUnit.AUTO, 0)
        return Size(SizeUnit.CELLS, v)
    }
}