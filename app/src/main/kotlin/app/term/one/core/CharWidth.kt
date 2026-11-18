package app.term.one.core

object CharWidth {

    fun of(cp: Int): Int {
        if (cp < 0x20 || cp in 0x7F..0x9F) {
            return 0
        }
        if (cp < 0x300) {
            return 1
        }
        val type = Character.getType(cp)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()
        ) {
            return 0
        }
        return if (isWide(cp)) 2 else 1
    }

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F ||
            cp in 0x2E80..0x303E ||
            cp in 0x3041..0xA4CF ||
            cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE6F ||
            cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x1F300..0x1F64F ||
            cp in 0x1F680..0x1F6FF ||
            cp in 0x1F900..0x1F9FF ||
            cp in 0x1FA70..0x1FAFF ||
            cp in 0x20000..0x3FFFD
}