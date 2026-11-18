package app.term.one.core

object Attr {
    const val BOLD = 1
    const val FAINT = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val INVISIBLE = 64
    const val STRIKE = 128
    const val WIDE = 256
    const val WIDE_CONT = 512
}

object Colors {
    const val DEFAULT = 0x01000000
    private const val INDEXED = 0x02000000

    fun indexed(index: Int): Int = INDEXED or (index and 0xFF)

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun isIndexed(color: Int): Boolean = (color ushr 24) == 2

    fun defaultPalette(): IntArray {
        val palette = IntArray(256)
        val base = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x3B6FE0, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x6C8CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
        )
        for (i in 0 until 16) {
            palette[i] = (0xFF shl 24) or base[i]
        }
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 16 until 232) {
            val n = i - 16
            palette[i] = rgb(levels[n / 36], levels[(n / 6) % 6], levels[n % 6])
        }
        for (i in 232 until 256) {
            val v = 8 + (i - 232) * 10
            palette[i] = rgb(v, v, v)
        }
        return palette
    }
}

class TerminalTheme {
    var foreground: Int = 0xFFE5E5E5.toInt()
    var background: Int = 0xFF000000.toInt()
    var cursor: Int = 0xFFE5E5E5.toInt()
    val palette: IntArray = Colors.defaultPalette()

    fun resolve(color: Int, foregroundSlot: Boolean): Int = when {
        color == Colors.DEFAULT -> if (foregroundSlot) foreground else background
        Colors.isIndexed(color) -> palette[color and 0xFF]
        else -> color
    }
}