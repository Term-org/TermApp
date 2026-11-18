package app.term.one.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import app.term.one.vnc.RfbClient
import app.term.one.vnc.VncKeys
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class VncView(context: Context, private val modifiers: KeyModifiers) : View(context) {

    interface Host {
        fun requestPassword(): String?
        fun onVncConnected(name: String)
        fun onVncClosed(reason: String?)
        fun onVncServerText(text: String)
        fun onVncBell()
    }

    var host: Host? = null

    private enum class State { IDLE, CONNECTING, CONNECTED, CLOSED }

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
        textSize = 14 * dp
    }
    private val dst = RectF()
    private val cursorPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 16f)
        lineTo(4f, 12.5f)
        lineTo(7f, 19f)
        lineTo(9.5f, 18f)
        lineTo(6.5f, 11.5f)
        lineTo(11.5f, 11.5f)
        close()
    }

    private var client: RfbClient? = null
    private var bitmap: Bitmap? = null

    @Volatile
    private var fbW = 0

    @Volatile
    private var fbH = 0

    private var state = State.IDLE
    private var status = ""
    private var fit = true
    private var panX = 0f
    private var panY = 0f
    private var pointerX = 0f
    private var pointerY = 0f
    private var buttonMask = 0
    private var sentX = -1
    private var sentY = -1

    private var touchMode = MODE_NONE
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var moved = false
    private var dragging = false
    private var twoStart = 0L
    private var twoMoved = false
    private var scrollAccum = 0f
    private var lastMidY = 0f
    private var startMidY = 0f
    private var composing = ""

    private val longPress = Runnable {
        if (touchMode == MODE_SINGLE && !moved && state == State.CONNECTED) {
            dragging = true
            buttonMask = buttonMask or BUTTON_LEFT
            pushPointer(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ------------------------------------------------------------------ connection

    fun connect(hostName: String, port: Int) {
        state = State.CONNECTING
        status = "Connecting to $hostName:$port..."
        val c = RfbClient(hostName, port, rfbListener)
        client = c
        c.start()
        invalidate()
    }

    fun disconnect() {
        mainHandler.removeCallbacksAndMessages(null)
        client?.disconnect()
        client = null
        synchronized(lock) {
            bitmap?.recycle()
            bitmap = null
        }
    }

    val isConnected: Boolean get() = state == State.CONNECTED

    private fun allocate(w: Int, h: Int) {
        synchronized(lock) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fbW = w
            fbH = h
        }
    }

    private val rfbListener = object : RfbClient.Listener {
        override fun onPasswordRequired(): String? = host?.requestPassword()

        override fun onConnected(width: Int, height: Int, name: String) {
            allocate(width, height)
            mainHandler.post {
                state = State.CONNECTED
                pointerX = width / 2f
                pointerY = height / 2f
                panX = 0f
                panY = 0f
                ensurePointerVisible()
                host?.onVncConnected(name)
                invalidate()
            }
        }

        override fun onResized(width: Int, height: Int) {
            allocate(width, height)
            mainHandler.post {
                pointerX = min(pointerX, width - 1f)
                pointerY = min(pointerY, height - 1f)
                ensurePointerVisible()
                invalidate()
            }
        }

        override fun onUpdated(pixels: IntArray, width: Int, x: Int, y: Int, w: Int, h: Int) {
            synchronized(lock) {
                val bm = bitmap
                if (bm != null && !bm.isRecycled && bm.width == width) {
                    bm.setPixels(pixels, y * width + x, width, x, y, w, h)
                }
            }
            postInvalidateOnAnimation()
        }

        override fun onBell() {
            mainHandler.post { host?.onVncBell() }
        }

        override fun onServerText(text: String) {
            mainHandler.post { host?.onVncServerText(text) }
        }

        override fun onClosed(reason: String?) {
            mainHandler.post {
                state = State.CLOSED
                status = "Disconnected"
                host?.onVncClosed(reason)
                invalidate()
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private fun scale(): Float {
        if (!fit || fbW <= 0 || fbH <= 0 || width <= 0 || height <= 0) {
            return 1f
        }
        return min(width.toFloat() / fbW, height.toFloat() / fbH)
    }

    private fun offsetX(s: Float): Float =
        if (fbW * s <= width) (width - fbW * s) / 2f else -panX * s

    private fun offsetY(s: Float): Float =
        if (fbH * s <= height) (height - fbH * s) / 2f else -panY * s

    private fun ensurePointerVisible() {
        if (fbW <= 0 || width <= 0 || height <= 0) {
            return
        }
        val s = scale()
        val viewW = width / s
        val viewH = height / s
        val margin = 24f / s
        if (fbW > viewW) {
            if (pointerX < panX + margin) panX = pointerX - margin
            if (pointerX > panX + viewW - margin) panX = pointerX - viewW + margin
            panX = panX.coerceIn(0f, fbW - viewW)
        } else {
            panX = 0f
        }
        if (fbH > viewH) {
            if (pointerY < panY + margin) panY = pointerY - margin
            if (pointerY > panY + viewH - margin) panY = pointerY - viewH + margin
            panY = panY.coerceIn(0f, fbH - viewH)
        } else {
            panY = 0f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensurePointerVisible()
    }

    fun toggleFit(): Boolean {
        fit = !fit
        ensurePointerVisible()
        invalidate()
        return fit
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val s = scale()
        val ox = offsetX(s)
        val oy = offsetY(s)
        synchronized(lock) {
            val bm = bitmap
            if (bm != null && !bm.isRecycled) {
                dst.set(ox, oy, ox + fbW * s, oy + fbH * s)
                canvas.drawBitmap(bm, null, dst, bitmapPaint)
            }
        }
        if (state == State.CONNECTED) {
            canvas.save()
            canvas.translate(ox + pointerX * s, oy + pointerY * s)
            canvas.scale(dp, dp)
            canvas.drawPath(cursorPath, fillPaint)
            canvas.drawPath(cursorPath, strokePaint)
            canvas.restore()
        } else {
            canvas.drawText(status, width / 2f, height / 2f, textPaint)
        }
    }

    // ------------------------------------------------------------------ pointer

    private fun movePointer(dx: Float, dy: Float) {
        if (fbW <= 0) {
            return
        }
        pointerX = (pointerX + dx).coerceIn(0f, (fbW - 1).toFloat())
        pointerY = (pointerY + dy).coerceIn(0f, (fbH - 1).toFloat())
        ensurePointerVisible()
        pushPointer(false)
        invalidate()
    }

    private fun pushPointer(force: Boolean) {
        val x = pointerX.toInt()
        val y = pointerY.toInt()
        if (!force && x == sentX && y == sentY) {
            return
        }
        sentX = x
        sentY = y
        client?.sendPointer(buttonMask, x, y)
    }

    fun click(mask: Int) {
        if (state != State.CONNECTED) {
            return
        }
        val x = pointerX.toInt()
        val y = pointerY.toInt()
        val base = buttonMask
        client?.sendPointer(base or mask, x, y)
        client?.sendPointer(base, x, y)
        sentX = x
        sentY = y
    }

    fun scroll(up: Boolean) {
        click(if (up) WHEEL_UP else WHEEL_DOWN)
    }

    private fun midY(e: MotionEvent): Float = (e.getY(0) + e.getY(1)) / 2f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (state != State.CONNECTED) {
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
                touchMode = MODE_SINGLE
                lastX = e.x
                lastY = e.y
                startX = e.x
                startY = e.y
                downTime = e.eventTime
                moved = false
                dragging = false
                mainHandler.removeCallbacks(longPress)
                mainHandler.postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mainHandler.removeCallbacks(longPress)
                if (e.pointerCount == 2 && touchMode != MODE_IGNORE) {
                    endDrag()
                    touchMode = MODE_TWO
                    twoStart = e.eventTime
                    twoMoved = false
                    scrollAccum = 0f
                    lastMidY = midY(e)
                    startMidY = lastMidY
                } else {
                    touchMode = MODE_IGNORE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchMode == MODE_SINGLE) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    if (!moved && hypot(e.x - startX, e.y - startY) > slop) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (moved || dragging) {
                        val s = scale()
                        movePointer(dx / s * SPEED, dy / s * SPEED)
                    }
                    lastX = e.x
                    lastY = e.y
                } else if (touchMode == MODE_TWO && e.pointerCount >= 2) {
                    val mid = midY(e)
                    val dy = mid - lastMidY
                    lastMidY = mid
                    if (abs(mid - startMidY) > slop) {
                        twoMoved = true
                    }
                    if (twoMoved) {
                        scrollAccum += dy
                        val step = 36 * dp
                        while (scrollAccum <= -step) {
                            scroll(false)
                            scrollAccum += step
                        }
                        while (scrollAccum >= step) {
                            scroll(true)
                            scrollAccum -= step
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (touchMode == MODE_TWO) {
                    if (!twoMoved && e.eventTime - twoStart < TAP_MS) {
                        click(BUTTON_RIGHT)
                    }
                    touchMode = MODE_IGNORE
                }
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPress)
                if (touchMode == MODE_SINGLE) {
                    if (dragging) {
                        endDrag()
                    } else if (!moved && e.eventTime - downTime < TAP_MS) {
                        click(BUTTON_LEFT)
                    }
                }
                touchMode = MODE_NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPress)
                endDrag()
                touchMode = MODE_NONE
            }
        }
        return true
    }

    private fun endDrag() {
        if (dragging) {
            dragging = false
            buttonMask = buttonMask and BUTTON_LEFT.inv()
            pushPointer(true)
        }
    }

    // ------------------------------------------------------------------ keyboard

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
                applyComposing(text.toString())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                applyComposing(text.toString())
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

    private fun applyComposing(updated: String) {
        var common = 0
        val limit = min(composing.length, updated.length)
        while (common < limit && composing[common] == updated[common]) {
            common++
        }
        if (common > 0 && Character.isHighSurrogate(composing[common - 1])) {
            common--
        }
        val removed = composing.codePointCount(common, composing.length)
        repeat(removed) { chord(VncKeys.BACKSPACE, false, false, false) }
        if (updated.length > common) {
            sendText(updated.substring(common))
        }
        composing = updated
    }

    fun sendText(text: String) {
        if (state != State.CONNECTED) {
            return
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            chord(
                VncKeys.fromCodePoint(cp),
                modifiers.isActive(KeyModifiers.Key.CTRL),
                modifiers.isActive(KeyModifiers.Key.ALT),
                modifiers.isActive(KeyModifiers.Key.SHIFT)
            )
            modifiers.consumeOneShot()
        }
    }

    fun sendKeyCode(keyCode: Int) {
        if (state != State.CONNECTED) {
            return
        }
        var code = keyCode
        if (modifiers.isActive(KeyModifiers.Key.FN)) {
            code = KeyEncoder.fnRemap(code)
        }
        val keysym = VncKeys.special(code)
        if (keysym == 0) {
            return
        }
        chord(
            keysym,
            modifiers.isActive(KeyModifiers.Key.CTRL),
            modifiers.isActive(KeyModifiers.Key.ALT),
            modifiers.isActive(KeyModifiers.Key.SHIFT)
        )
        modifiers.consumeOneShot()
    }

    private fun chord(keysym: Int, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        val c = client ?: return
        if (shift) c.sendKey(true, VncKeys.SHIFT_L)
        if (ctrl) c.sendKey(true, VncKeys.CONTROL_L)
        if (alt) c.sendKey(true, VncKeys.ALT_L)
        c.sendKey(true, keysym)
        c.sendKey(false, keysym)
        if (alt) c.sendKey(false, VncKeys.ALT_L)
        if (ctrl) c.sendKey(false, VncKeys.CONTROL_L)
        if (shift) c.sendKey(false, VncKeys.SHIFT_L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (state != State.CONNECTED || event.isSystem) {
            return super.onKeyDown(keyCode, event)
        }
        if (KeyEvent.isModifierKey(keyCode)) {
            return true
        }
        val ctrl = event.isCtrlPressed || modifiers.isActive(KeyModifiers.Key.CTRL)
        val alt = event.isAltPressed || modifiers.isActive(KeyModifiers.Key.ALT)
        val shift = event.isShiftPressed || modifiers.isActive(KeyModifiers.Key.SHIFT)

        var code = keyCode
        if (modifiers.isActive(KeyModifiers.Key.FN)) {
            code = KeyEncoder.fnRemap(code)
        }
        val special = VncKeys.special(code)
        if (special != 0) {
            chord(special, ctrl, alt, shift)
            modifiers.consumeOneShot()
            return true
        }
        val metaMask = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK
        val unicode = event.getUnicodeChar(event.metaState and metaMask.inv())
        if (unicode > 0) {
            chord(VncKeys.fromCodePoint(unicode), ctrl, alt, event.isShiftPressed || modifiers.isActive(KeyModifiers.Key.SHIFT))
            modifiers.consumeOneShot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_SINGLE = 1
        const val MODE_TWO = 2
        const val MODE_IGNORE = 3

        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 4
        const val WHEEL_UP = 8
        const val WHEEL_DOWN = 16

        const val LONG_PRESS_MS = 450L
        const val TAP_MS = 260L
        const val SPEED = 1.3f
    }
}