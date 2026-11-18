package app.term.one.core

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

class TerminalEmulator(
    initialCols: Int,
    initialRows: Int,
    private val callbacks: TerminalCallbacks,
    val theme: TerminalTheme = TerminalTheme(),
    private val maxScrollback: Int = 2000
) {

    val lock = Any()

    var cols = max(1, initialCols)
        private set
    var rows = max(1, initialRows)
        private set
    var cellWidthPx = 10
        private set
    var cellHeightPx = 20
        private set
    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var title = ""
        private set

    var cursorVisible = true
    var cursorStyle = 1
    var appCursorKeys = false
    var appKeypad = false
    var bracketedPaste = false
    var mouseTracking = 0
    var mouseSgr = false
    var focusReporting = false
    var reverseVideo = false

    val isAltScreen: Boolean get() = usingAlt
    val scrollbackSize: Int get() = screen.scrollback.size
    val lineBase: Long get() = screen.lineBase

    private class ScreenState(var lines: Array<TerminalRow>) {
        val scrollback = ArrayDeque<TerminalRow>()
        var lineBase = 0L
        val images = ArrayList<PlacedImage>()
    }

    private class SavedCursor(
        val row: Int, val col: Int, val fg: Int, val bg: Int, val attr: Int,
        val g0Graphics: Boolean, val g1Graphics: Boolean, val shiftOut: Boolean,
        val origin: Boolean, val wrapPending: Boolean
    )

    private class ByteSink {
        var buf = ByteArray(256)
        var size = 0

        fun append(b: Int) {
            if (size == buf.size) {
                buf = buf.copyOf(buf.size * 2)
            }
            buf[size++] = b.toByte()
        }

        fun clear() {
            size = 0
            if (buf.size > 1_048_576) {
                buf = ByteArray(256)
            }
        }
    }

    private val mainScreen = ScreenState(Array(rows) { TerminalRow(cols) })
    private val altScreen = ScreenState(Array(rows) { TerminalRow(cols) })
    private var screen = mainScreen
    private var usingAlt = false

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var tabStops = BooleanArray(cols)

    private var curFg = Colors.DEFAULT
    private var curBg = Colors.DEFAULT
    private var curAttr = 0

    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var wrapPending = false
    private var sixelDisplayMode = false
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false
    private var lastCodePoint = 0
    private var saved: SavedCursor? = null
    private var saved1049: SavedCursor? = null

    private var state = STATE_GROUND
    private val params = IntArray(MAX_PARAMS)
    private val paramColon = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var curParam = -1
    private var curColon = false
    private var csiPrivate = 0
    private var csiInter = 0
    private var escInter = 0
    private var utf8Need = 0
    private var utf8Cp = 0
    private val stringBuf = ByteSink()
    private var stringEsc = false
    private var stringOverflow = false

    init {
        resetTabStops()
    }

    // ------------------------------------------------------------------ public API

    fun feed(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val end = offset + length
            var i = offset
            while (i < end) {
                processByte(data[i].toInt() and 0xFF)
                i++
            }
        }
    }

    fun setCellSize(widthPx: Int, heightPx: Int) {
        synchronized(lock) {
            cellWidthPx = max(1, widthPx)
            cellHeightPx = max(1, heightPx)
        }
    }

    fun getLine(index: Int): TerminalRow {
        val s = screen
        return if (index < 0) {
            val i = s.scrollback.size + index
            if (i in 0 until s.scrollback.size) s.scrollback[i] else s.lines[0]
        } else {
            s.lines[min(index, rows - 1)]
        }
    }

    fun visibleImages(): List<PlacedImage> = ArrayList(screen.images)

    fun extractText(startIndex: Int, startCol: Int, endIndex: Int, endCol: Int): String {
        synchronized(lock) {
            var r1 = startIndex
            var c1 = startCol
            var r2 = endIndex
            var c2 = endCol
            if (r1 > r2 || (r1 == r2 && c1 > c2)) {
                r1 = endIndex; c1 = endCol
                r2 = startIndex; c2 = startCol
            }
            r1 = max(r1, -screen.scrollback.size)
            r2 = min(r2, rows - 1)
            val sb = StringBuilder()
            for (r in r1..r2) {
                val row = getLine(r)
                val from = if (r == r1) c1 else 0
                val to = if (r == r2) c2 + 1 else row.cols
                sb.append(row.text(from, to).trimEnd())
                if (r != r2 && !row.wrapped) {
                    sb.append('\n')
                }
            }
            return sb.toString()
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        synchronized(lock) {
            val nc = max(1, newCols)
            val nr = max(1, newRows)
            if (nc == cols && nr == rows) {
                return
            }
            val mainShift = resizeScreen(mainScreen, nc, nr, true, !usingAlt)
            val altShift = resizeScreen(altScreen, nc, nr, false, usingAlt)
            cursorRow += if (usingAlt) altShift else mainShift
            cols = nc
            rows = nr
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
            scrollTop = 0
            scrollBottom = rows - 1
            wrapPending = false
            resetTabStops()
        }
    }

    private fun resizeScreen(
        s: ScreenState, nc: Int, nr: Int, isMain: Boolean, active: Boolean
    ): Int {
        val list = ArrayList<TerminalRow>(s.lines.asList())
        var shift = 0
        if (nr < rows) {
            val drop = rows - nr
            val cursor = if (active) cursorRow else 0
            val dropTop = max(0, min(drop, cursor - (nr - 1)))
            repeat(dropTop) {
                val removed = list.removeAt(0)
                if (isMain) {
                    s.scrollback.addLast(removed)
                    while (s.scrollback.size > maxScrollback) s.scrollback.removeFirst()
                }
                s.lineBase++
            }
            shift = -dropTop
            repeat(drop - dropTop) { list.removeAt(list.size - 1) }
        } else if (nr > rows) {
            var add = nr - rows
            if (isMain) {
                val pull = min(add, s.scrollback.size)
                repeat(pull) {
                    list.add(0, s.scrollback.removeLast())
                    s.lineBase--
                }
                shift = pull
                add -= pull
            }
            repeat(add) { list.add(TerminalRow(cols)) }
        }
        for (i in list.indices) {
            if (list[i].cols != nc) {
                list[i] = list[i].resized(nc)
            }
        }
        s.lines = list.toTypedArray()
        return shift
    }

    // ------------------------------------------------------------------ byte processing

    private fun processByte(b: Int) {
        if (state >= STATE_OSC) {
            processStringByte(b)
            return
        }
        if (b == 0x18 || b == 0x1A) {
            state = STATE_GROUND
            utf8Need = 0
            return
        }
        if (b == 0x1B) {
            state = STATE_ESC
            utf8Need = 0
            return
        }
        if (b < 0x20) {
            utf8Need = 0
            executeControl(b)
            return
        }
        when (state) {
            STATE_GROUND -> processGround(b)
            STATE_ESC -> processEsc(b)
            STATE_ESC_INTER -> processEscInter(b)
            STATE_CSI -> processCsi(b)
            STATE_DCS -> processDcsHeader(b)
        }
    }

    private fun processGround(b: Int) {
        if (b < 0x80) {
            utf8Need = 0
            if (b != 0x7F) {
                printCodePoint(b)
            }
            return
        }
        when {
            (b and 0xC0) == 0x80 -> {
                if (utf8Need > 0) {
                    utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                    utf8Need--
                    if (utf8Need == 0) {
                        val cp = utf8Cp
                        printCodePoint(if (cp > 0x10FFFF || cp in 0xD800..0xDFFF) 0xFFFD else cp)
                    }
                }
            }
            (b and 0xE0) == 0xC0 -> { utf8Cp = b and 0x1F; utf8Need = 1 }
            (b and 0xF0) == 0xE0 -> { utf8Cp = b and 0x0F; utf8Need = 2 }
            (b and 0xF8) == 0xF0 -> { utf8Cp = b and 0x07; utf8Need = 3 }
            else -> { utf8Need = 0; printCodePoint(0xFFFD) }
        }
    }

    private fun executeControl(b: Int) {
        when (b) {
            0x07 -> callbacks.onBell()
            0x08 -> {
                cursorCol = max(0, cursorCol - 1)
                wrapPending = false
            }
            0x09 -> forwardTab()
            0x0A, 0x0B, 0x0C -> {
                index()
                wrapPending = false
            }
            0x0D -> {
                cursorCol = 0
                wrapPending = false
            }
            0x0E -> shiftOut = true
            0x0F -> shiftOut = false
        }
    }

    private fun processEsc(b: Int) {
        when (b) {
            '['.code -> { resetParams(); state = STATE_CSI }
            ']'.code -> {
                stringBuf.clear()
                stringEsc = false
                stringOverflow = false
                state = STATE_OSC
            }
            'P'.code -> { resetParams(); state = STATE_DCS }
            'X'.code, '^'.code, '_'.code -> { stringEsc = false; state = STATE_STR_IGNORE }
            in 0x20..0x2F -> { escInter = b; state = STATE_ESC_INTER }
            else -> { state = STATE_GROUND; dispatchEsc(b) }
        }
    }

    private fun processEscInter(b: Int) {
        if (b in 0x20..0x2F) {
            escInter = b
            return
        }
        state = STATE_GROUND
        when (escInter) {
            '('.code -> g0Graphics = (b == '0'.code)
            ')'.code -> g1Graphics = (b == '0'.code)
            '#'.code -> if (b == '8'.code) {
                fillWithE()
            }
        }
    }

    private fun dispatchEsc(b: Int) {
        when (b) {
            '7'.code -> saved = saveCursor()
            '8'.code -> restoreCursor(saved)
            'D'.code -> index()
            'E'.code -> {
                cursorCol = 0
                index()
                wrapPending = false
            }
            'M'.code -> reverseIndex()
            'H'.code -> tabStops[cursorCol] = true
            'c'.code -> fullReset()
            '='.code -> appKeypad = true
            '>'.code -> appKeypad = false
            'Z'.code -> reply("\u001b[?62;4;22c")
        }
    }

    private fun resetParams() {
        paramCount = 0
        curParam = -1
        curColon = false
        csiPrivate = 0
        csiInter = 0
    }

    private fun acceptDigit(b: Int) {
        curParam = (if (curParam < 0) 0 else curParam) * 10 + (b - 0x30)
        if (curParam > 99999) curParam = 99999
    }

    private fun storeParam() {
        if (paramCount < MAX_PARAMS) {
            params[paramCount] = curParam
            paramColon[paramCount] = curColon
            paramCount++
        }
        curParam = -1
    }

    private fun praw(i: Int, def: Int): Int = if (i < paramCount && params[i] >= 0) params[i] else def

    private fun pn(i: Int): Int {
        val v = praw(i, 0)
        return if (v <= 0) 1 else v
    }

    private fun processCsi(b: Int) {
        when {
            b in 0x30..0x39 -> acceptDigit(b)
            b == ';'.code -> { storeParam(); curColon = false }
            b == ':'.code -> { storeParam(); curColon = true }
            b in 0x3C..0x3F -> {
                if (paramCount == 0 && curParam < 0) {
                    csiPrivate = b
                }
            }
            b in 0x20..0x2F -> csiInter = b
            b in 0x40..0x7E -> {
                storeParam()
                state = STATE_GROUND
                dispatchCsi(b)
            }
        }
    }

    private fun processDcsHeader(b: Int) {
        when {
            b in 0x30..0x39 -> acceptDigit(b)
            b == ';'.code -> { storeParam(); curColon = false }
            b in 0x20..0x2F -> csiInter = b
            b in 0x40..0x7E -> {
                storeParam()
                stringEsc = false
                if (b == 'q'.code && csiInter == 0) {
                    stringBuf.clear()
                    stringOverflow = false
                    state = STATE_DCS_PASS
                } else {
                    state = STATE_DCS_IGNORE
                }
            }
        }
    }

    private fun processStringByte(b: Int) {
        if (stringEsc) {
            stringEsc = false
            if (b == '\\'.code) {
                finishString()
                return
            }
            abortString()
            state = STATE_ESC
            processByte(b)
            return
        }
        when {
            b == 0x1B -> stringEsc = true
            b == 0x07 && state == STATE_OSC -> finishString()
            b == 0x18 || b == 0x1A -> abortString()
            state == STATE_OSC || state == STATE_DCS_PASS -> {
                if (stringBuf.size >= MAX_STRING) {
                    stringOverflow = true
                } else {
                    stringBuf.append(b)
                }
            }
        }
    }

    private fun abortString() {
        state = STATE_GROUND
        stringEsc = false
        stringBuf.clear()
    }

    private fun finishString() {
        val current = state
        state = STATE_GROUND
        stringEsc = false
        if (!stringOverflow) {
            when (current) {
                STATE_OSC -> dispatchOsc()
                STATE_DCS_PASS -> dispatchSixel()
            }
        }
        stringBuf.clear()
    }

    // ------------------------------------------------------------------ printing

    private fun printCodePoint(raw: Int) {
        var cp = raw
        val graphics = if (shiftOut) g1Graphics else g0Graphics
        if (graphics && cp in 0x60..0x7E) {
            cp = DEC_GRAPHICS[cp - 0x60]
        }
        lastCodePoint = cp
        putCodePoint(cp)
    }

    private fun putCodePoint(cp: Int) {
        val width = CharWidth.of(cp)
        if (width == 0) {
            combine(cp)
            return
        }
        if (wrapPending) {
            if (autoWrap) {
                screen.lines[cursorRow].wrapped = true
                cursorCol = 0
                index()
            }
            wrapPending = false
        }
        if (width == 2 && cursorCol == cols - 1) {
            if (!autoWrap) {
                return
            }
            screen.lines[cursorRow].clear(cursorCol, cols, curBg)
            screen.lines[cursorRow].wrapped = true
            cursorCol = 0
            index()
        }
        val row = screen.lines[cursorRow]
        if (insertMode) {
            shiftCellsRight(row, cursorCol, width)
        }
        breakWide(row, cursorCol)
        if (width == 2) {
            breakWide(row, cursorCol + 1)
        }
        row.cp[cursorCol] = cp
        row.fg[cursorCol] = curFg
        row.bg[cursorCol] = curBg
        row.attr[cursorCol] = curAttr or (if (width == 2) Attr.WIDE else 0)
        if (width == 2) {
            row.cp[cursorCol + 1] = TerminalRow.SPACE
            row.fg[cursorCol + 1] = curFg
            row.bg[cursorCol + 1] = curBg
            row.attr[cursorCol + 1] = curAttr or Attr.WIDE_CONT
        }
        if (screen.images.isNotEmpty()) {
            removeImagesInRect(cursorRow, cursorRow, cursorCol, cursorCol + width - 1)
        }
        cursorCol += width
        if (cursorCol >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        }
    }

    private fun combine(cp: Int) {
        if (cp < 0x300) {
            return
        }
        val row = screen.lines[cursorRow]
        var prev = if (wrapPending) cursorCol else cursorCol - 1
        if (prev >= 1 && (row.attr[prev] and Attr.WIDE_CONT) != 0) {
            prev--
        }
        if (prev < 0) {
            return
        }
        val text = String(Character.toChars(row.cp[prev])) + String(Character.toChars(cp))
        val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (composed.codePointCount(0, composed.length) == 1) {
            row.cp[prev] = composed.codePointAt(0)
        }
    }

    private fun breakWide(row: TerminalRow, c: Int) {
        if (c < 0 || c >= cols) {
            return
        }
        val a = row.attr[c]
        if ((a and Attr.WIDE) != 0 && c + 1 < cols) {
            row.cp[c + 1] = TerminalRow.SPACE
            row.attr[c + 1] = row.attr[c + 1] and Attr.WIDE_CONT.inv()
        }
        if ((a and Attr.WIDE_CONT) != 0 && c > 0) {
            row.cp[c - 1] = TerminalRow.SPACE
            row.attr[c - 1] = row.attr[c - 1] and Attr.WIDE.inv()
        }
    }

    private fun shiftCellsRight(row: TerminalRow, from: Int, n: Int) {
        val count = min(n, cols - from)
        for (i in cols - 1 downTo from + count) {
            row.copyCell(i - count, i)
        }
        row.clear(from, from + count, curBg)
    }

    // ------------------------------------------------------------------ cursor and scrolling

    private fun index() {
        if (cursorRow == scrollBottom) {
            scrollUp(1, true)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
        wrapPending = false
    }

    private fun newBlankRow(): TerminalRow {
        val row = TerminalRow(cols)
        if (curBg != Colors.DEFAULT) {
            row.clear(0, cols, curBg)
        }
        return row
    }

    private fun scrollUp(n: Int, toScrollback: Boolean) {
        val s = screen
        val count = min(n, scrollBottom - scrollTop + 1)
        repeat(count) {
            val removed = s.lines[scrollTop]
            System.arraycopy(s.lines, scrollTop + 1, s.lines, scrollTop, scrollBottom - scrollTop)
            s.lines[scrollBottom] = newBlankRow()
            if (scrollTop == 0 && toScrollback) {
                if (!usingAlt) {
                    s.scrollback.addLast(removed)
                    while (s.scrollback.size > maxScrollback) s.scrollback.removeFirst()
                }
                s.lineBase++
            }
        }
        if (scrollTop == 0 && toScrollback) {
            val oldest = s.lineBase - s.scrollback.size
            s.images.removeAll { it.line + it.rows <= oldest }
        }
    }

    private fun scrollDown(n: Int) {
        val s = screen
        val count = min(n, scrollBottom - scrollTop + 1)
        repeat(count) {
            System.arraycopy(s.lines, scrollTop, s.lines, scrollTop + 1, scrollBottom - scrollTop)
            s.lines[scrollTop] = newBlankRow()
        }
    }

    private fun setCursor(row: Int, col: Int) {
        val top = if (originMode) scrollTop else 0
        val bottom = if (originMode) scrollBottom else rows - 1
        cursorRow = (row + top).coerceIn(top, bottom)
        cursorCol = col.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun moveRow(delta: Int) {
        val target = cursorRow + delta
        cursorRow = if (delta < 0) {
            val top = if (cursorRow >= scrollTop) scrollTop else 0
            max(target, top)
        } else {
            val bottom = if (cursorRow <= scrollBottom) scrollBottom else rows - 1
            min(target, bottom)
        }
        wrapPending = false
    }

    private fun forwardTab() {
        var c = cursorCol + 1
        while (c < cols - 1 && !tabStops[c]) c++
        cursorCol = min(c, cols - 1)
        wrapPending = false
    }

    private fun backTab() {
        var c = cursorCol - 1
        while (c > 0 && !tabStops[c]) c--
        cursorCol = max(0, c)
        wrapPending = false
    }

    private fun resetTabStops() {
        tabStops = BooleanArray(cols)
        for (i in 0 until cols step 8) tabStops[i] = true
    }

    private fun saveCursor() = SavedCursor(
        cursorRow, cursorCol, curFg, curBg, curAttr,
        g0Graphics, g1Graphics, shiftOut, originMode, wrapPending
    )

    private fun restoreCursor(sc: SavedCursor?) {
        if (sc == null) {
            return
        }
        cursorRow = sc.row.coerceIn(0, rows - 1)
        cursorCol = sc.col.coerceIn(0, cols - 1)
        curFg = sc.fg
        curBg = sc.bg
        curAttr = sc.attr
        g0Graphics = sc.g0Graphics
        g1Graphics = sc.g1Graphics
        shiftOut = sc.shiftOut
        originMode = sc.origin
        wrapPending = sc.wrapPending
    }

    // ------------------------------------------------------------------ erase and edit

    private fun removeImagesInRect(r1: Int, r2: Int, c1: Int, c2: Int) {
        val s = screen
        if (s.images.isEmpty()) {
            return
        }
        val a1 = s.lineBase + r1
        val a2 = s.lineBase + r2
        s.images.removeAll {
            it.line <= a2 && it.line + it.rows - 1 >= a1 &&
                it.col <= c2 && it.col + it.cols - 1 >= c1
        }
    }

    private fun eraseLine(mode: Int) {
        val row = screen.lines[cursorRow]
        val from: Int
        val to: Int
        when (mode) {
            0 -> { from = cursorCol; to = cols }
            1 -> { from = 0; to = cursorCol + 1 }
            else -> { from = 0; to = cols }
        }
        row.clear(from, to, curBg)
        if (to >= cols) {
            row.wrapped = false
        }
        removeImagesInRect(cursorRow, cursorRow, from, to - 1)
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) {
                    s.lines[r].clear(0, cols, curBg)
                    s.lines[r].wrapped = false
                }
                removeImagesInRect(cursorRow + 1, rows - 1, 0, cols - 1)
            }
            1 -> {
                eraseLine(1)
                for (r in 0 until cursorRow) {
                    s.lines[r].clear(0, cols, curBg)
                    s.lines[r].wrapped = false
                }
                removeImagesInRect(0, cursorRow - 1, 0, cols - 1)
            }
            2, 3 -> {
                for (r in 0 until rows) {
                    s.lines[r].clear(0, cols, curBg)
                    s.lines[r].wrapped = false
                }
                s.images.clear()
                if (mode == 3 && !usingAlt) {
                    s.scrollback.clear()
                }
            }
        }
    }

    private fun insertChars(n: Int) {
        shiftCellsRight(screen.lines[cursorRow], cursorCol, n)
        wrapPending = false
    }

    private fun deleteChars(n: Int) {
        val row = screen.lines[cursorRow]
        val count = min(n, cols - cursorCol)
        for (i in cursorCol until cols - count) {
            row.copyCell(i + count, i)
        }
        row.clear(cols - count, cols, curBg)
        wrapPending = false
    }

    private fun eraseChars(n: Int) {
        val end = min(cols, cursorCol + n)
        screen.lines[cursorRow].clear(cursorCol, end, curBg)
        removeImagesInRect(cursorRow, cursorRow, cursorCol, end - 1)
        wrapPending = false
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) {
            return
        }
        val top = scrollTop
        scrollTop = cursorRow
        scrollDown(n)
        scrollTop = top
        cursorCol = 0
        wrapPending = false
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) {
            return
        }
        val top = scrollTop
        scrollTop = cursorRow
        scrollUp(n, false)
        scrollTop = top
        cursorCol = 0
        wrapPending = false
    }

    private fun fillWithE() {
        for (r in 0 until rows) {
            val row = screen.lines[r]
            row.clear(0, cols, Colors.DEFAULT)
            for (c in 0 until cols) row.cp[c] = 'E'.code
        }
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
    }

    // ------------------------------------------------------------------ CSI

    private fun dispatchCsi(fin: Int) {
        if (csiInter == ' '.code) {
            if (fin == 'q'.code) {
                cursorStyle = praw(0, 1).coerceIn(0, 6)
            }
            return
        }
        if (csiInter == '!'.code) {
            if (fin == 'p'.code) {
                softReset()
            }
            return
        }
        if (csiInter != 0) {
            return
        }
        when (csiPrivate) {
            0 -> dispatchStandard(fin)
            '?'.code -> dispatchPrivate(fin)
            '>'.code -> if (fin == 'c'.code) {
                reply("\u001b[>1;10;0c")
            }
        }
    }

    private fun dispatchStandard(fin: Int) {
        when (fin) {
            '@'.code -> insertChars(pn(0))
            'A'.code -> moveRow(-pn(0))
            'B'.code, 'e'.code -> moveRow(pn(0))
            'C'.code, 'a'.code -> {
                cursorCol = min(cols - 1, cursorCol + pn(0))
                wrapPending = false
            }
            'D'.code -> {
                cursorCol = max(0, cursorCol - pn(0))
                wrapPending = false
            }
            'E'.code -> { moveRow(pn(0)); cursorCol = 0 }
            'F'.code -> { moveRow(-pn(0)); cursorCol = 0 }
            'G'.code, '`'.code -> {
                cursorCol = (pn(0) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'H'.code, 'f'.code -> setCursor(pn(0) - 1, pn(1) - 1)
            'I'.code -> repeat(pn(0)) { forwardTab() }
            'J'.code -> eraseDisplay(praw(0, 0))
            'K'.code -> eraseLine(praw(0, 0))
            'L'.code -> insertLines(pn(0))
            'M'.code -> deleteLines(pn(0))
            'P'.code -> deleteChars(pn(0))
            'S'.code -> scrollUp(pn(0), true)
            'T'.code -> if (paramCount <= 1) {
                scrollDown(pn(0))
            }
            'X'.code -> eraseChars(pn(0))
            'Z'.code -> repeat(pn(0)) { backTab() }
            'b'.code -> if (lastCodePoint > 0) {
                repeat(min(pn(0), 65535)) { putCodePoint(lastCodePoint) }
            }
            'c'.code -> if (praw(0, 0) == 0) {
                reply("\u001b[?62;4;22c")
            }
            'd'.code -> setCursor(pn(0) - 1, cursorCol)
            'g'.code -> when (praw(0, 0)) {
                0 -> tabStops[cursorCol] = false
                3 -> tabStops.fill(false)
            }
            'h'.code, 'l'.code -> {
                for (i in 0 until paramCount) {
                    if (params[i] == 4) {
                        insertMode = (fin == 'h'.code)
                    }
                }
            }
            'm'.code -> applySgr()
            'n'.code -> deviceStatusReport()
            'r'.code -> setScrollRegion()
            's'.code -> if (paramCount <= 1 && params[0] < 0) {
                saved = saveCursor()
            }
            'u'.code -> restoreCursor(saved)
            't'.code -> windowOps()
        }
    }

    private fun dispatchPrivate(fin: Int) {
        when (fin) {
            'h'.code, 'l'.code -> {
                for (i in 0 until paramCount) {
                    setPrivateMode(params[i], fin == 'h'.code)
                }
            }
            'S'.code -> graphicsQuery()
        }
    }

    private fun setPrivateMode(mode: Int, enable: Boolean) {
        when (mode) {
            1 -> appCursorKeys = enable
            5 -> reverseVideo = enable
            6 -> {
                originMode = enable
                setCursor(0, 0)
            }
            7 -> autoWrap = enable
            25 -> cursorVisible = enable
            47, 1047 -> switchScreen(enable, false)
            1048 -> {
                if (enable) {
                    saved = saveCursor()
                } else {
                    restoreCursor(saved)
                }
            }
            1049 -> switchScreen(enable, true)
            1000 -> mouseTracking = if (enable) 1000 else 0
            1002 -> mouseTracking = if (enable) 1002 else 0
            1003 -> mouseTracking = if (enable) 1003 else 0
            1004 -> focusReporting = enable
            1006 -> mouseSgr = enable
            2004 -> bracketedPaste = enable
            80 -> sixelDisplayMode = enable
        }
    }

    private fun switchScreen(toAlt: Boolean, saveRestore: Boolean) {
        if (toAlt == usingAlt) {
            return
        }
        if (toAlt) {
            if (saveRestore) {
                saved1049 = saveCursor()
            }
            usingAlt = true
            screen = altScreen
            if (saveRestore) {
                altScreen.lines = Array(rows) { TerminalRow(cols) }
                altScreen.images.clear()
            }
        } else {
            usingAlt = false
            screen = mainScreen
            if (saveRestore) {
                restoreCursor(saved1049)
            }
        }
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun setScrollRegion() {
        val top = pn(0) - 1
        val p1 = praw(1, 0)
        val bottom = (if (p1 <= 0) rows else p1) - 1
        if (top < bottom && bottom < rows) {
            scrollTop = top
            scrollBottom = bottom
            setCursor(0, 0)
        }
    }

    private fun deviceStatusReport() {
        when (praw(0, 0)) {
            5 -> reply("\u001b[0n")
            6 -> {
                val row = cursorRow - (if (originMode) scrollTop else 0) + 1
                reply("\u001b[$row;${cursorCol + 1}R")
            }
        }
    }

    private fun windowOps() {
        when (praw(0, 0)) {
            14 -> reply("\u001b[4;${rows * cellHeightPx};${cols * cellWidthPx}t")
            16 -> reply("\u001b[6;$cellHeightPx;${cellWidthPx}t")
            18, 19 -> reply("\u001b[8;$rows;${cols}t")
        }
    }

    private fun graphicsQuery() {
        val item = praw(0, 0)
        when (item) {
            1 -> reply("\u001b[?1;0;1024S")
            2 -> reply("\u001b[?2;0;${min(4096, cols * cellWidthPx)};${min(4096, rows * cellHeightPx)}S")
            else -> reply("\u001b[?$item;2;0S")
        }
    }

    // ------------------------------------------------------------------ SGR

    private fun applySgr() {
        var i = 0
        while (i < paramCount) {
            val code = if (params[i] < 0) 0 else params[i]
            when (code) {
                0 -> { curFg = Colors.DEFAULT; curBg = Colors.DEFAULT; curAttr = 0 }
                1 -> curAttr = curAttr or Attr.BOLD
                2 -> curAttr = curAttr or Attr.FAINT
                3 -> curAttr = curAttr or Attr.ITALIC
                4, 21 -> curAttr = curAttr or Attr.UNDERLINE
                5, 6 -> curAttr = curAttr or Attr.BLINK
                7 -> curAttr = curAttr or Attr.INVERSE
                8 -> curAttr = curAttr or Attr.INVISIBLE
                9 -> curAttr = curAttr or Attr.STRIKE
                22 -> curAttr = curAttr and (Attr.BOLD or Attr.FAINT).inv()
                23 -> curAttr = curAttr and Attr.ITALIC.inv()
                24 -> curAttr = curAttr and Attr.UNDERLINE.inv()
                25 -> curAttr = curAttr and Attr.BLINK.inv()
                27 -> curAttr = curAttr and Attr.INVERSE.inv()
                28 -> curAttr = curAttr and Attr.INVISIBLE.inv()
                29 -> curAttr = curAttr and Attr.STRIKE.inv()
                in 30..37 -> curFg = Colors.indexed(code - 30)
                38 -> i = extendedColor(i, true)
                39 -> curFg = Colors.DEFAULT
                in 40..47 -> curBg = Colors.indexed(code - 40)
                48 -> i = extendedColor(i, false)
                49 -> curBg = Colors.DEFAULT
                in 90..97 -> curFg = Colors.indexed(code - 90 + 8)
                in 100..107 -> curBg = Colors.indexed(code - 100 + 8)
            }
            i++
        }
    }

    private fun extendedColor(i: Int, foreground: Boolean): Int {
        var color = -1
        var last = i
        if (i + 1 < paramCount && paramColon[i + 1]) {
            var j = i + 1
            val sub = IntArray(8)
            var n = 0
            while (j < paramCount && paramColon[j]) {
                if (n < sub.size) sub[n++] = params[j]
                j++
            }
            last = j - 1
            if (sub[0] == 5 && n >= 2) {
                color = Colors.indexed(channel(sub[1]))
            } else if (sub[0] == 2) {
                val off = if (n >= 5) 2 else 1
                if (n >= off + 3) {
                    color = Colors.rgb(channel(sub[off]), channel(sub[off + 1]), channel(sub[off + 2]))
                }
            }
        } else if (i + 1 < paramCount) {
            val mode = params[i + 1]
            if (mode == 5 && i + 2 < paramCount) {
                color = Colors.indexed(channel(params[i + 2]))
                last = i + 2
            } else if (mode == 2 && i + 4 < paramCount) {
                color = Colors.rgb(channel(params[i + 2]), channel(params[i + 3]), channel(params[i + 4]))
                last = i + 4
            } else {
                last = paramCount - 1
            }
        }
        if (color != -1) {
            if (foreground) curFg = color else curBg = color
        }
        return last
    }

    private fun channel(v: Int): Int = v.coerceIn(0, 255)

    // ------------------------------------------------------------------ OSC

    private fun dispatchOsc() {
        val data = stringBuf.buf
        val n = stringBuf.size
        var i = 0
        var code = 0
        var hasCode = false
        while (i < n && data[i].toInt() in 0x30..0x39) {
            code = code * 10 + (data[i].toInt() - 0x30)
            hasCode = true
            i++
        }
        if (!hasCode) {
            return
        }
        val payload = if (i < n && data[i].toInt() == ';'.code) i + 1 else n
        when (code) {
            0, 1, 2 -> {
                title = String(data, payload, min(n - payload, 512), Charsets.UTF_8)
                callbacks.onTitleChanged(title)
            }
            7 -> {
                val text = String(data, payload, n - payload, Charsets.UTF_8)
                val path = Uri.parse(text).path
                if (!path.isNullOrEmpty()) {
                    callbacks.onCwdChanged(path)
                }
            }
            10, 11 -> {
                if (n - payload == 1 && data[payload].toInt() == '?'.code) {
                    val color = if (code == 10) theme.foreground else theme.background
                    reply("\u001b]$code;${oscColor(color)}\u001b\\")
                }
            }
            52 -> clipboardFromOsc(data, payload, n)
            1337 -> {
                if (startsWith(data, payload, n, "File=")) {
                    val parsed = InlineImage.parse(data, payload, n)
                    if (parsed != null) {
                        placeInlineImage(parsed)
                    }
                } else if (startsWith(data, payload, n, "TermVnc=")) {
                    val raw = String(data, payload + 8, n - payload - 8, Charsets.UTF_8)
                    callbacks.onVncRequest(parseVncArgs(raw))
                }
            }
        }
    }

    private fun parseVncArgs(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        val parts = raw.split(';')
        out["action"] = parts[0].trim()
        for (i in 1 until parts.size) {
            val eq = parts[i].indexOf('=')
            if (eq > 0) {
                out[parts[i].substring(0, eq).trim()] = parts[i].substring(eq + 1).trim()
            }
        }
        return out
    }

    private fun oscColor(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return String.format("rgb:%02x%02x/%02x%02x/%02x%02x", r, r, g, g, b, b)
    }

    private fun startsWith(data: ByteArray, start: Int, end: Int, prefix: String): Boolean {
        if (end - start < prefix.length) {
            return false
        }
        for (k in prefix.indices) {
            if (data[start + k].toInt() != prefix[k].code) {
                return false
            }
        }
        return true
    }

    private fun clipboardFromOsc(data: ByteArray, start: Int, end: Int) {
        var sep = -1
        for (k in start until end) {
            if (data[k].toInt() == ';'.code) {
                sep = k
                break
            }
        }
        if (sep < 0 || end - sep - 1 <= 0) {
            return
        }
        if (end - sep - 1 == 1 && data[sep + 1].toInt() == '?'.code) {
            return
        }
        try {
            val bytes = Base64.decode(data, sep + 1, end - sep - 1, Base64.DEFAULT)
            callbacks.onClipboardSet(String(bytes, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            return
        }
    }

    // ------------------------------------------------------------------ images

    private fun dispatchSixel() {
        val decoded = SixelDecoder.decode(stringBuf.buf, stringBuf.size) ?: return
        val bitmap = Bitmap.createBitmap(
            decoded.pixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888
        )
        placeImage(bitmap, bitmap.width, bitmap.height, false)
    }

    private fun placeInlineImage(parsed: InlineImage.Parsed) {
        val bw = parsed.bitmap.width
        val bh = parsed.bitmap.height
        val totalW = cols * cellWidthPx
        val totalH = rows * cellHeightPx
        var w = sizeToPixels(parsed.width, cellWidthPx, totalW)
        var h = sizeToPixels(parsed.height, cellHeightPx, totalH)

        if (w == null && h == null) {
            w = bw
            h = bh
        } else if (parsed.preserveAspect) {
            if (w != null && h == null) {
                h = (w.toLong() * bh / bw).toInt()
            } else if (h != null && w == null) {
                w = (h.toLong() * bw / bh).toInt()
            } else if (w != null && h != null) {
                val scale = min(w.toDouble() / bw, h.toDouble() / bh)
                w = (bw * scale).toInt()
                h = (bh * scale).toInt()
            }
        } else {
            if (w == null) w = bw
            if (h == null) h = bh
        }

        var destW = max(1, w ?: bw)
        var destH = max(1, h ?: bh)
        if (destW > totalW) {
            destH = max(1, (destH.toLong() * totalW / destW).toInt())
            destW = totalW
        }
        placeImage(parsed.bitmap, destW, destH, true)
    }

    private fun sizeToPixels(size: InlineImage.Size, cell: Int, total: Int): Int? = when (size.unit) {
        InlineImage.SizeUnit.AUTO -> null
        InlineImage.SizeUnit.CELLS -> size.value * cell
        InlineImage.SizeUnit.PIXELS -> size.value
        InlineImage.SizeUnit.PERCENT -> (total.toLong() * size.value / 100).toInt()
    }

    private fun placeImage(bitmap: Bitmap, destW: Int, destH: Int, itermStyle: Boolean) {
        val spanCols = max(1, (destW + cellWidthPx - 1) / cellWidthPx)
        val spanRows = max(1, (destH + cellHeightPx - 1) / cellHeightPx)
        val s = screen
        val startRow = if (sixelDisplayMode) 0 else cursorRow
        val startCol = if (sixelDisplayMode) 0 else cursorCol

        removeImagesInRect(
            startRow, min(rows - 1, startRow + spanRows - 1),
            startCol, min(cols - 1, startCol + spanCols - 1)
        )
        s.images.add(
            PlacedImage(bitmap, s.lineBase + startRow, startCol, spanCols, spanRows, destW, destH)
        )
        while (s.images.size > MAX_IMAGES) {
            s.images.removeAt(0)
        }
        if (sixelDisplayMode) {
            return
        }
        wrapPending = false
        if (itermStyle) {
            repeat(spanRows - 1) { index() }
            cursorCol = min(cols - 1, startCol + spanCols)
        } else {
            repeat(spanRows) { index() }
            cursorCol = 0
        }
    }

    // ------------------------------------------------------------------ resets and replies

    private fun softReset() {
        curFg = Colors.DEFAULT
        curBg = Colors.DEFAULT
        curAttr = 0
        autoWrap = true
        originMode = false
        insertMode = false
        cursorVisible = true
        appCursorKeys = false
        appKeypad = false
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
    }

    private fun fullReset() {
        softReset()
        usingAlt = false
        screen = mainScreen
        for (s in listOf(mainScreen, altScreen)) {
            s.lines = Array(rows) { TerminalRow(cols) }
            s.images.clear()
        }
        mainScreen.scrollback.clear()
        bracketedPaste = false
        mouseTracking = 0
        mouseSgr = false
        focusReporting = false
        reverseVideo = false
        sixelDisplayMode = false
        cursorStyle = 1
        cursorRow = 0
        cursorCol = 0
        saved = null
        saved1049 = null
        resetTabStops()
    }

    private fun reply(text: String) {
        callbacks.onWriteToPty(text.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val MAX_PARAMS = 32
        const val MAX_STRING = 16 * 1024 * 1024
        const val MAX_IMAGES = 64

        const val STATE_GROUND = 0
        const val STATE_ESC = 1
        const val STATE_ESC_INTER = 2
        const val STATE_CSI = 3
        const val STATE_DCS = 4
        const val STATE_OSC = 5
        const val STATE_DCS_PASS = 6
        const val STATE_DCS_IGNORE = 7
        const val STATE_STR_IGNORE = 8

        val DEC_GRAPHICS = intArrayOf(
            0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1,
            0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C, 0x23BA,
            0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534, 0x252C,
            0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7
        )
    }
}