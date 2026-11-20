package app.term.one.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import app.term.one.TermSettings
import app.term.one.core.Attr
import app.term.one.core.Colors
import app.term.one.core.TerminalEmulator
import app.term.one.core.TerminalRow
import app.term.one.core.TerminalSession
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TerminalView(context: Context, private val modifiers: KeyModifiers) : View(context) {

    interface Listener {
        fun onSelectionChanged(view: TerminalView, active: Boolean)
        fun onExitedEnter(session: TerminalSession)
        fun onPaneTouched(view: TerminalView)
        fun onZoom(sizeSp: Int)
    }

    var listener: Listener? = null

    private val dp = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#38BDF8") }
    private val dstRect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val scroller = OverScroller(context)

    private var session: TerminalSession? = null
    val attachedSession: TerminalSession? get() = session
    private var cellWidth = 10f
    private var cellHeight = 20f
    private var ascent = 16f
    private var textSizeSp = 14
    private var typeface: Typeface = Typeface.MONOSPACE
    private var cursorStyleName = "block"
    private var cursorBlink = true
    private var cursorPhase = true

    private var scrollOffset = 0
    private var lastLineBase = 0L
    private var lastAlt = false
    private var dragAccum = 0f
    private var flingAccum = 0f
    private var flingLastY = 0

    private var hasSel = false
    private var selStartLine = 0L
    private var selStartCol = 0
    private var selEndLine = 0L
    private var selEndCol = 0
    private var anchorStartLine = 0L
    private var anchorStartCol = 0
    private var anchorEndLine = 0L
    private var anchorEndCol = 0
    private var draggingHandle = 0
    private var selectingByLongPress = false
    private var tmpLine = 0L
    private var tmpCol = 0

    private var effFg = 0
    private var effBg = 0
    private var runBuffer = CharArray(256)
    private var composing = ""

    private val blinkRunnable = object : Runnable {
        override fun run() {
            val em = session?.emulator
            val steady = em != null && (em.cursorStyle == 2 || em.cursorStyle == 4 || em.cursorStyle == 6)
            if (cursorBlink && !steady && hasFocus()) {
                cursorPhase = !cursorPhase
                invalidate()
            } else if (!cursorPhase) {
                cursorPhase = true
                invalidate()
            }
            handler.postDelayed(this, 530)
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            handleScroll(e2, distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean =
            handleFling(velocityY)

        override fun onLongPress(e: MotionEvent) {
            handleLongPress(e)
        }
    })

    private var zooming = false
    private var zoomStart = 14f
    private var zoomFactor = 1f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zoomStart = textSizeSp.toFloat()
                zoomFactor = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomFactor *= detector.scaleFactor
                val target = (zoomStart * zoomFactor).roundToInt().coerceIn(8, 40)
                if (target != textSizeSp) {
                    listener?.onZoom(target)
                }
                return true
            }
        }
    ).also { it.isQuickScaleEnabled = false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        applyTextSize(textSizeSp)
    }

    // ------------------------------------------------------------------ configuration

    fun configure(settings: TermSettings, fontFile: File) {
        typeface = try {
            if (fontFile.isFile) Typeface.createFromFile(fontFile) else Typeface.MONOSPACE
        } catch (e: RuntimeException) {
            Typeface.MONOSPACE
        }
        cursorStyleName = settings.cursorStyle
        cursorBlink = settings.cursorBlink
        applyTextSize(settings.fontSizeSp)
    }

    val fontSize: Int get() = textSizeSp

    fun setFontSize(sp: Int) {
        applyTextSize(sp.coerceIn(8, 40))
    }

    private fun applyTextSize(sp: Int) {
        textSizeSp = sp
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics)
        textPaint.typeface = typeface
        textPaint.textSize = px
        cellWidth = max(1f, textPaint.measureText("M"))
        val fm = textPaint.fontMetrics
        cellHeight = ceil(fm.descent - fm.ascent)
        ascent = -fm.ascent
        updateTerminalSize()
        invalidate()
    }

    fun currentColumns(): Int = if (width > 0) max(4, (width / cellWidth).toInt()) else 80

    fun currentRows(): Int = if (height > 0) max(2, (height / cellHeight).toInt()) else 24

    private fun updateTerminalSize() {
        val s = session ?: return
        if (width <= 0 || height <= 0) {
            return
        }
        s.resize(currentColumns(), currentRows(), cellWidth.roundToInt(), cellHeight.roundToInt())
        scrollOffset = min(scrollOffset, s.emulator.scrollbackSize)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (hasSel) {
            clearSelection()
        }
        updateTerminalSize()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(blinkRunnable)
        handler.postDelayed(blinkRunnable, 530)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val em = session?.emulator
        if (em != null && em.focusReporting) {
            session?.writeText(if (gainFocus) "\u001b[I" else "\u001b[O")
        }
        cursorPhase = true
        invalidate()
    }

    // ------------------------------------------------------------------ session

    fun attachSession(newSession: TerminalSession?) {
        session = newSession
        scrollOffset = 0
        composing = ""
        dragAccum = 0f
        scroller.forceFinished(true)
        if (hasSel) {
            clearSelection()
        }
        if (newSession != null) {
            lastLineBase = newSession.emulator.lineBase
            lastAlt = newSession.emulator.isAltScreen
            updateTerminalSize()
        }
        invalidate()
    }

    fun onScreenUpdated() {
        val em = session?.emulator ?: return
        val alt = em.isAltScreen
        val base = em.lineBase
        if (alt != lastAlt && hasSel) {
            clearSelection()
        }
        if (alt || alt != lastAlt) {
            scrollOffset = 0
        } else if (scrollOffset > 0) {
            scrollOffset = (scrollOffset + (base - lastLineBase).toInt()).coerceIn(0, em.scrollbackSize)
        }
        lastAlt = alt
        lastLineBase = base
        invalidate()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val em = session?.emulator
        if (em == null) {
            canvas.drawColor(Color.BLACK)
            return
        }
        synchronized(em.lock) { drawTerminal(canvas, em) }
    }

    private fun drawTerminal(canvas: Canvas, em: TerminalEmulator) {
        val theme = em.theme
        val canvasBg = if (em.reverseVideo) theme.foreground else theme.background
        canvas.drawColor(canvasBg)

        val cols = em.cols
        val rows = em.rows
        if (runBuffer.size < cols) {
            runBuffer = CharArray(cols)
        }
        val cw = cellWidth
        val ch = cellHeight
        val lineBase = em.lineBase

        val cursorScreenRow = em.cursorRow + scrollOffset
        val cursorShown = em.cursorVisible && cursorPhase && cursorScreenRow < rows
        val cursorKind = resolveCursorKind(em)
        val focused = hasFocus()
        val cursorBlock = cursorShown && cursorKind == CURSOR_BLOCK && focused
        val cursorCol = em.cursorCol
        val cursorWide = cursorShown && (em.getLine(em.cursorRow).attrAt(cursorCol) and Attr.WIDE) != 0

        for (r in 0 until rows) {
            val idx = r - scrollOffset
            val row = em.getLine(idx)
            val rowAbs = lineBase + idx
            val top = r * ch
            val limit = min(cols, row.cols)
            val cursorRowHere = cursorBlock && r == cursorScreenRow

            var runStart = 0
            var runColor = canvasBg
            for (c in 0 until limit) {
                resolveColors(em, row, c, isSelected(rowAbs, c), cursorRowHere && isCursorCell(c, cursorCol, cursorWide))
                val bg = effBg
                if (bg != runColor) {
                    if (runColor != canvasBg) {
                        fillPaint.color = runColor
                        canvas.drawRect(runStart * cw, top, c * cw, top + ch, fillPaint)
                    }
                    runStart = c
                    runColor = bg
                }
            }
            if (runColor != canvasBg) {
                fillPaint.color = runColor
                canvas.drawRect(runStart * cw, top, limit * cw, top + ch, fillPaint)
            }

            var c = 0
            while (c < limit) {
                val attr = row.attr[c]
                if ((attr and Attr.WIDE_CONT) != 0 || (attr and Attr.INVISIBLE) != 0) {
                    c++
                    continue
                }
                val cp = row.cp[c]
                resolveColors(em, row, c, isSelected(rowAbs, c), cursorRowHere && isCursorCell(c, cursorCol, cursorWide))
                val fg = effFg
                val styleBits = attr and STYLE_MASK

                if (cp in 0x20..0x7E) {
                    var e = c
                    var n = 0
                    var hasInk = false
                    while (e < limit) {
                        val cpe = row.cp[e]
                        if (cpe !in 0x20..0x7E) break
                        if ((row.attr[e] and STYLE_MASK) != styleBits) break
                        if (e != c) {
                            resolveColors(em, row, e, isSelected(rowAbs, e), cursorRowHere && isCursorCell(e, cursorCol, cursorWide))
                            if (effFg != fg) break
                        }
                        runBuffer[n++] = cpe.toChar()
                        if (cpe != 0x20) hasInk = true
                        e++
                    }
                    val decorated = (styleBits and (Attr.UNDERLINE or Attr.STRIKE)) != 0
                    if (hasInk || decorated) {
                        applyTextStyle(styleBits, fg)
                        if (hasInk) {
                            canvas.drawText(runBuffer, 0, n, c * cw, top + ascent, textPaint)
                        }
                        drawDecorations(canvas, c, e, top, styleBits, fg)
                    }
                    c = e
                } else {
                    if (cp != TerminalRow.SPACE) {
                        val span = if ((attr and Attr.WIDE) != 0) 2 else 1
                        applyTextStyle(styleBits, fg)
                        canvas.drawText(String(Character.toChars(cp)), c * cw, top + ascent, textPaint)
                        drawDecorations(canvas, c, c + span, top, styleBits, fg)
                    }
                    c++
                }
            }
        }

        for (img in em.visibleImages()) {
            val relRow = (img.line - lineBase).toInt()
            val y = (relRow + scrollOffset) * ch
            if (y + img.destHeight <= 0f || y >= height) {
                continue
            }
            val x = img.col * cw
            dstRect.set(x, y, x + img.destWidth, y + img.destHeight)
            canvas.drawBitmap(img.bitmap, null, dstRect, imagePaint)
        }

        if (cursorShown && !cursorBlock) {
            val x = cursorCol * cw
            val y = cursorScreenRow * ch
            val w = if (cursorWide) 2 * cw else cw
            strokePaint.color = theme.cursor
            strokePaint.strokeWidth = 1.5f * dp
            fillPaint.color = theme.cursor
            when {
                !focused -> canvas.drawRect(x + 1f, y + 1f, x + w - 1f, y + ch - 1f, strokePaint)
                cursorKind == CURSOR_UNDERLINE -> canvas.drawRect(x, y + ch - 2f * dp, x + w, y + ch, fillPaint)
                else -> canvas.drawRect(x, y, x + 2f * dp, y + ch, fillPaint)
            }
        }

        if (hasSel) {
            canvas.drawCircle(selStartCol * cw, handleY(selStartLine, lineBase), 8 * dp, handlePaint)
            canvas.drawCircle((selEndCol + 1) * cw, handleY(selEndLine, lineBase), 8 * dp, handlePaint)
        }
    }

    private fun handleY(line: Long, lineBase: Long): Float =
        (line - lineBase + scrollOffset + 1) * cellHeight + 8 * dp

    private fun isCursorCell(c: Int, cursorCol: Int, wide: Boolean): Boolean =
        c == cursorCol || (wide && c == cursorCol + 1)

    private fun resolveCursorKind(em: TerminalEmulator): Int = when (em.cursorStyle) {
        3, 4 -> CURSOR_UNDERLINE
        5, 6 -> CURSOR_BAR
        2 -> CURSOR_BLOCK
        else -> when (cursorStyleName) {
            "underline" -> CURSOR_UNDERLINE
            "bar" -> CURSOR_BAR
            else -> CURSOR_BLOCK
        }
    }

    private fun resolveColors(em: TerminalEmulator, row: TerminalRow, c: Int, selected: Boolean, cursorBlock: Boolean) {
        val theme = em.theme
        val attr = row.attr[c]
        val fgSpec = row.fg[c]
        var fg = theme.resolve(fgSpec, true)
        if ((attr and Attr.BOLD) != 0 && Colors.isIndexed(fgSpec) && (fgSpec and 0xFF) < 8) {
            fg = theme.palette[(fgSpec and 0xFF) + 8]
        }
        var bg = theme.resolve(row.bg[c], false)
        val inverse = ((attr and Attr.INVERSE) != 0) != em.reverseVideo
        if (inverse) {
            val t = fg
            fg = bg
            bg = t
        }
        if ((attr and Attr.FAINT) != 0) {
            fg = blend(fg, bg)
        }
        if (selected) {
            fg = Color.WHITE
            bg = SELECTION_BG
        }
        if (cursorBlock) {
            fg = if (em.reverseVideo) theme.foreground else theme.background
            bg = theme.cursor
        }
        effFg = fg
        effBg = bg
    }

    private fun blend(a: Int, b: Int): Int {
        val r = (Color.red(a) * 6 + Color.red(b) * 4) / 10
        val g = (Color.green(a) * 6 + Color.green(b) * 4) / 10
        val bl = (Color.blue(a) * 6 + Color.blue(b) * 4) / 10
        return Color.rgb(r, g, bl)
    }

    private fun applyTextStyle(styleBits: Int, fg: Int) {
        textPaint.color = fg
        textPaint.isFakeBoldText = (styleBits and Attr.BOLD) != 0
        textPaint.textSkewX = if ((styleBits and Attr.ITALIC) != 0) -0.25f else 0f
    }

    private fun drawDecorations(canvas: Canvas, from: Int, to: Int, top: Float, styleBits: Int, fg: Int) {
        if ((styleBits and (Attr.UNDERLINE or Attr.STRIKE)) == 0) {
            return
        }
        fillPaint.color = fg
        val thickness = max(1f, dp)
        if ((styleBits and Attr.UNDERLINE) != 0) {
            val y = top + cellHeight - 2f * dp
            canvas.drawRect(from * cellWidth, y, to * cellWidth, y + thickness, fillPaint)
        }
        if ((styleBits and Attr.STRIKE) != 0) {
            val y = top + cellHeight / 2f
            canvas.drawRect(from * cellWidth, y, to * cellWidth, y + thickness, fillPaint)
        }
    }

    private fun isSelected(line: Long, col: Int): Boolean {
        if (!hasSel) return false
        if (line < selStartLine || line > selEndLine) return false
        if (line == selStartLine && col < selStartCol) return false
        if (line == selEndLine && col > selEndCol) return false
        return true
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || zooming) {
            if (!zooming) {
                zooming = true
                draggingHandle = 0
                selectingByLongPress = false
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                gestures.onTouchEvent(cancel)
                cancel.recycle()
            }
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                zooming = false
            }
            return true
        }
        return handleTouch(event)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onPaneTouched(this)
                scroller.forceFinished(true)
                dragAccum = 0f
                if (hasSel) {
                    val handle = hitHandle(event.x, event.y)
                    if (handle != 0) {
                        draggingHandle = handle
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle != 0) {
                    dragHandle(event.x, event.y)
                    return true
                }
                if (selectingByLongPress) {
                    extendLongPress(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle != 0 || selectingByLongPress) {
                    draggingHandle = 0
                    selectingByLongPress = false
                    invalidate()
                    return true
                }
            }
        }
        gestures.onTouchEvent(event)
        return true
    }

    private fun handleTap(e: MotionEvent) {
        if (hasSel) {
            clearSelection()
            return
        }
        requestFocus()
        val em = session?.emulator
        if (em != null && em.mouseTracking != 0) {
            val col = (e.x / cellWidth).toInt().coerceIn(0, em.cols - 1) + 1
            val row = (e.y / cellHeight).toInt().coerceIn(0, em.rows - 1) + 1
            sendMouse(em, 0, col, row, true)
            sendMouse(em, 0, col, row, false)
        }
        showKeyboard()
    }

    private fun handleScroll(e: MotionEvent, distanceY: Float) {
        dragAccum += distanceY
        val lines = (dragAccum / cellHeight).toInt()
        if (lines == 0) {
            return
        }
        dragAccum -= lines * cellHeight
        scrollLines(lines, e.x, e.y)
    }

    private fun scrollLines(lines: Int, x: Float, y: Float) {
        val s = session ?: return
        val em = s.emulator
        if (em.isAltScreen || em.mouseTracking != 0) {
            val count = abs(lines)
            if (em.mouseTracking != 0) {
                val col = (x / cellWidth).toInt().coerceIn(0, em.cols - 1) + 1
                val row = (y / cellHeight).toInt().coerceIn(0, em.rows - 1) + 1
                repeat(count) { sendMouse(em, if (lines > 0) 65 else 64, col, row, true) }
            } else {
                val code = if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                val seq = KeyEncoder.encodeSpecial(code, 0, em.appCursorKeys) ?: return
                repeat(count) { s.writeText(seq) }
            }
        } else {
            scrollOffset = (scrollOffset - lines).coerceIn(0, em.scrollbackSize)
            invalidate()
        }
    }

    private fun handleFling(velocityY: Float): Boolean {
        val em = session?.emulator ?: return false
        if (em.isAltScreen || em.mouseTracking != 0) {
            return false
        }
        scroller.forceFinished(true)
        flingLastY = 0
        flingAccum = 0f
        scroller.fling(0, 0, 0, velocityY.toInt(), 0, 0, -1_000_000, 1_000_000)
        postInvalidateOnAnimation()
        return true
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) {
            return
        }
        val em = session?.emulator
        if (em == null) {
            scroller.forceFinished(true)
            return
        }
        val y = scroller.currY
        flingAccum += (y - flingLastY).toFloat()
        flingLastY = y
        val lines = (flingAccum / cellHeight).toInt()
        if (lines != 0) {
            flingAccum -= lines * cellHeight
            scrollOffset = (scrollOffset + lines).coerceIn(0, em.scrollbackSize)
            if (scrollOffset == 0 || scrollOffset == em.scrollbackSize) {
                scroller.forceFinished(true)
            }
        }
        postInvalidateOnAnimation()
    }

    private fun sendMouse(em: TerminalEmulator, button: Int, col: Int, row: Int, pressed: Boolean) {
        val s = session ?: return
        if (em.mouseSgr) {
            val suffix = if (pressed) 'M' else 'm'
            s.writeText("\u001b[<$button;$col;${row}$suffix")
        } else if (col <= 223 && row <= 223) {
            val code = if (pressed) button else 3
            val seq = charArrayOf('\u001b', '[', 'M', (32 + code).toChar(), (32 + col).toChar(), (32 + row).toChar())
            s.writeText(String(seq))
        }
    }

    // ------------------------------------------------------------------ selection

    fun hasSelection(): Boolean = hasSel

    fun clearSelection() {
        if (!hasSel) {
            return
        }
        hasSel = false
        draggingHandle = 0
        selectingByLongPress = false
        listener?.onSelectionChanged(this, false)
        invalidate()
    }

    fun selectedText(): String {
        val em = session?.emulator ?: return ""
        if (!hasSel) {
            return ""
        }
        val base = em.lineBase
        return em.extractText((selStartLine - base).toInt(), selStartCol, (selEndLine - base).toInt(), selEndCol)
    }

    fun selectAll() {
        val em = session?.emulator ?: return
        synchronized(em.lock) {
            val base = em.lineBase
            selStartLine = base - em.scrollbackSize
            selStartCol = 0
            selEndLine = base + em.rows - 1
            selEndCol = em.cols - 1
        }
        setSelectionActive()
    }

    private fun setSelectionActive() {
        val wasActive = hasSel
        hasSel = true
        if (!wasActive) {
            listener?.onSelectionChanged(this, true)
        }
        invalidate()
    }

    private fun locate(x: Float, y: Float) {
        val em = session?.emulator ?: return
        val col = (x / cellWidth).toInt().coerceIn(0, em.cols - 1)
        val r = (y / cellHeight).toInt().coerceIn(0, em.rows - 1)
        tmpLine = em.lineBase + (r - scrollOffset)
        tmpCol = col
    }

    private fun handleLongPress(e: MotionEvent) {
        val em = session?.emulator ?: return
        locate(e.x, e.y)
        var start = tmpCol
        var end = tmpCol
        synchronized(em.lock) {
            val row = em.getLine((tmpLine - em.lineBase).toInt())
            if (row.cpAt(start) != TerminalRow.SPACE) {
                while (start > 0 && row.cpAt(start - 1) != TerminalRow.SPACE) start--
                while (end < row.cols - 1 && row.cpAt(end + 1) != TerminalRow.SPACE) end++
            }
        }
        selStartLine = tmpLine
        selStartCol = start
        selEndLine = tmpLine
        selEndCol = end
        anchorStartLine = selStartLine
        anchorStartCol = selStartCol
        anchorEndLine = selEndLine
        anchorEndCol = selEndCol
        selectingByLongPress = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        setSelectionActive()
    }

    private fun extendLongPress(x: Float, y: Float) {
        autoScrollForDrag(y)
        locate(x, y)
        val beforeAnchor = tmpLine < anchorStartLine || (tmpLine == anchorStartLine && tmpCol < anchorStartCol)
        if (beforeAnchor) {
            selStartLine = tmpLine
            selStartCol = tmpCol
            selEndLine = anchorEndLine
            selEndCol = anchorEndCol
        } else {
            selStartLine = anchorStartLine
            selStartCol = anchorStartCol
            selEndLine = tmpLine
            selEndCol = tmpCol
        }
        invalidate()
    }

    private fun hitHandle(x: Float, y: Float): Int {
        val em = session?.emulator ?: return 0
        val base = em.lineBase
        val startDistance = hypot(x - selStartCol * cellWidth, y - handleY(selStartLine, base))
        val endDistance = hypot(x - (selEndCol + 1) * cellWidth, y - handleY(selEndLine, base))
        val radius = 28 * dp
        return when {
            startDistance > radius && endDistance > radius -> 0
            startDistance <= endDistance -> 1
            else -> 2
        }
    }

    private fun dragHandle(x: Float, y: Float) {
        autoScrollForDrag(y)
        locate(x, y - cellHeight)
        if (draggingHandle == 1) {
            selStartLine = tmpLine
            selStartCol = tmpCol
        } else {
            selEndLine = tmpLine
            selEndCol = tmpCol
        }
        val reversed = selStartLine > selEndLine || (selStartLine == selEndLine && selStartCol > selEndCol)
        if (reversed) {
            val line = selStartLine
            val col = selStartCol
            selStartLine = selEndLine
            selStartCol = selEndCol
            selEndLine = line
            selEndCol = col
            draggingHandle = 3 - draggingHandle
        }
        invalidate()
    }

    private fun autoScrollForDrag(y: Float) {
        val em = session?.emulator ?: return
        if (em.isAltScreen) {
            return
        }
        if (y < cellHeight) {
            scrollOffset = min(em.scrollbackSize, scrollOffset + 1)
        } else if (y > height - cellHeight) {
            scrollOffset = max(0, scrollOffset - 1)
        }
    }

    // ------------------------------------------------------------------ input

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                updateComposing(text.toString())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                updateComposing(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { sendKeyCode(KeyEvent.KEYCODE_DEL) }
                composing = ""
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onKeyDown(event.keyCode, event)
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                sendKeyCode(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    private fun updateComposing(updated: String) {
        var common = 0
        val limit = min(composing.length, updated.length)
        while (common < limit && composing[common] == updated[common]) {
            common++
        }
        if (common > 0 && Character.isHighSurrogate(composing[common - 1])) {
            common--
        }
        val removed = composing.codePointCount(common, composing.length)
        repeat(removed) { emit("\u007f") }
        if (updated.length > common) {
            sendText(updated.substring(common))
        }
        composing = updated
    }

    fun sendText(text: String) {
        sendTextInternal(text, false, false)
    }

    private fun sendTextInternal(raw: String, hwCtrl: Boolean, hwAlt: Boolean) {
        val s = session ?: return
        if (s.exitCode != null) {
            if (raw.contains('\r') || raw.contains('\n')) {
                listener?.onExitedEnter(s)
            }
            return
        }
        val ctrl = hwCtrl || modifiers.isActive(KeyModifiers.Key.CTRL)
        val alt = hwAlt || modifiers.isActive(KeyModifiers.Key.ALT)
        var text = raw.replace('\n', '\r')
        if (text.length == 1) {
            if (modifiers.isActive(KeyModifiers.Key.FN)) {
                val number = KeyEncoder.fnDigit(text[0])
                if (number > 0) {
                    emit(KeyEncoder.functionKey(number, modifiers.mask()))
                    modifiers.consumeOneShot()
                    return
                }
            }
            if (modifiers.isActive(KeyModifiers.Key.SHIFT)) {
                text = text.uppercase()
            }
        }
        emit(KeyEncoder.applyTextModifiers(text, ctrl, alt))
        modifiers.consumeOneShot()
    }

    fun sendKeyCode(keyCode: Int) {
        val s = session ?: return
        if (s.exitCode != null) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                listener?.onExitedEnter(s)
            }
            return
        }
        var code = keyCode
        if (modifiers.isActive(KeyModifiers.Key.FN)) {
            code = KeyEncoder.fnRemap(code)
        }
        val seq = KeyEncoder.encodeSpecial(code, modifiers.mask(), s.emulator.appCursorKeys) ?: return
        emit(seq)
        modifiers.consumeOneShot()
    }

    fun paste(text: String) {
        val s = session ?: return
        if (s.exitCode != null || text.isEmpty()) {
            return
        }
        var out = text.replace("\u001b", "").replace("\r\n", "\r").replace('\n', '\r')
        if (s.emulator.bracketedPaste) {
            out = "\u001b[200~$out\u001b[201~"
        }
        emit(out)
    }

    private fun emit(sequence: String) {
        session?.writeText(sequence)
        cursorPhase = true
        if (scrollOffset != 0) {
            scrollOffset = 0
        }
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        if (event.isSystem) {
            return super.onKeyDown(keyCode, event)
        }
        if (KeyEvent.isModifierKey(keyCode)) {
            return true
        }
        if (s.exitCode != null) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                listener?.onExitedEnter(s)
            }
            return true
        }
        var mods = modifiers.mask()
        if (event.isShiftPressed) mods = mods or KeyEncoder.SHIFT
        if (event.isAltPressed) mods = mods or KeyEncoder.ALT
        if (event.isCtrlPressed) mods = mods or KeyEncoder.CTRL

        var code = keyCode
        if (modifiers.isActive(KeyModifiers.Key.FN)) {
            code = KeyEncoder.fnRemap(code)
        }
        val encoded = KeyEncoder.encodeSpecial(code, mods, s.emulator.appCursorKeys)
        if (encoded != null) {
            emit(encoded)
            modifiers.consumeOneShot()
            return true
        }

        val metaMask = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK
        val unicode = event.getUnicodeChar(event.metaState and metaMask.inv())
        if (unicode > 0) {
            sendTextInternal(String(Character.toChars(unicode)), event.isCtrlPressed, event.isAltPressed)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val CURSOR_BLOCK = 0
        const val CURSOR_UNDERLINE = 1
        const val CURSOR_BAR = 2

        const val STYLE_MASK = Attr.BOLD or Attr.FAINT or Attr.ITALIC or Attr.UNDERLINE or
            Attr.STRIKE or Attr.INVISIBLE or Attr.INVERSE

        val SELECTION_BG = 0xFF3B5B8C.toInt()
    }
}