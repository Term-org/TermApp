package app.term.one.core

import kotlin.math.max
import kotlin.math.min

class TerminalRow(val cols: Int) {

    val cp = IntArray(cols) { SPACE }
    val fg = IntArray(cols) { Colors.DEFAULT }
    val bg = IntArray(cols) { Colors.DEFAULT }
    val attr = IntArray(cols)
    val link = IntArray(cols)
    var wrapped = false

    fun clear(from: Int, to: Int, background: Int) {
        val start = max(0, from)
        val end = min(cols, to)
        for (i in start until end) {
            cp[i] = SPACE
            fg[i] = Colors.DEFAULT
            bg[i] = background
            attr[i] = 0
            link[i] = 0
        }
    }

    fun copyCell(from: Int, to: Int) {
        cp[to] = cp[from]
        fg[to] = fg[from]
        bg[to] = bg[from]
        attr[to] = attr[from]
        link[to] = link[from]
    }

    fun resized(newCols: Int): TerminalRow {
        val row = TerminalRow(newCols)
        val n = min(cols, newCols)
        System.arraycopy(cp, 0, row.cp, 0, n)
        System.arraycopy(fg, 0, row.fg, 0, n)
        System.arraycopy(bg, 0, row.bg, 0, n)
        System.arraycopy(attr, 0, row.attr, 0, n)
        System.arraycopy(link, 0, row.link, 0, n)
        row.wrapped = wrapped
        return row
    }

    fun cpAt(i: Int): Int = if (i in 0 until cols) cp[i] else SPACE
    fun fgAt(i: Int): Int = if (i in 0 until cols) fg[i] else Colors.DEFAULT
    fun bgAt(i: Int): Int = if (i in 0 until cols) bg[i] else Colors.DEFAULT
    fun attrAt(i: Int): Int = if (i in 0 until cols) attr[i] else 0

    fun text(from: Int, to: Int): String {
        val sb = StringBuilder()
        val start = max(0, from)
        val end = min(cols, to)
        for (i in start until end) {
            if ((attr[i] and Attr.WIDE_CONT) != 0) {
                continue
            }
            sb.appendCodePoint(cp[i])
        }
        return sb.toString()
    }

    companion object {
        const val SPACE = 0x20
    }
}