package app.term.one.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.Properties

object ExtraKeysConfig {

    private const val DEFAULT_ROW1 = "ESC,TAB,CTRL,ALT,SHIFT,UP,DOWN,HOME,END"
    private const val DEFAULT_ROW2 = "LEFT,RIGHT,PGUP,PGDN,-,/,|,~,FN"
    private const val DEFAULT_VNC =
        "VNC_LEFT_CLICK,VNC_RIGHT_CLICK,VNC_MIDDLE_CLICK,VNC_SCROLL_UP,VNC_SCROLL_DOWN,VNC_KEYBOARD,VNC_FIT,VNC_CLOSE"

    fun load(file: File): List<List<List<String>>> {
        val props = Properties()
        if (file.isFile) {
            try {
                file.inputStream().use { props.load(it) }
            } catch (e: IOException) {
                props.clear()
            }
        }
        fun row(key: String, default: String): List<String> =
            (props.getProperty(key) ?: default).split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val normal = listOf(row("row1", DEFAULT_ROW1), row("row2", DEFAULT_ROW2)).filter { it.isNotEmpty() }
        val vnc = row("vnc-row", DEFAULT_VNC)
        val half = (vnc.size + 1) / 2
        val vncPage = if (vnc.isEmpty()) {
            emptyList()
        } else {
            listOf(vnc.subList(0, half), vnc.subList(half, vnc.size)).filter { it.isNotEmpty() }
        }
        return listOf(normal, vncPage).filter { it.isNotEmpty() }
    }
}

class ExtraKeysView(context: Context, private val modifiers: KeyModifiers) : LinearLayout(context) {

    interface Listener {
        fun onKeyCode(keyCode: Int)
        fun onText(text: String)
        fun onVnc(token: String)
        fun onToggleKeyboard()
        fun onPaste()
    }

    var listener: Listener? = null

    private val dp = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val pager = Pager(context)
    private val dots = Dots(context)
    private val modifierViews = ArrayList<Pair<TextView, KeyModifiers.Key>>()
    private var pageWidth = 0
    private var pageCount = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0B1220"))
        pager.isHorizontalScrollBarEnabled = false
        pager.overScrollMode = OVER_SCROLL_NEVER
        pager.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(pager, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(dots, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (8 * dp).toInt()))
        pager.setOnScrollChangeListener { _, _, _, _, _ -> dots.setPage(currentPage()) }
        modifiers.onChanged = { refreshModifiers() }
    }

    fun setLayout(pages: List<List<List<String>>>) {
        content.removeAllViews()
        modifierViews.clear()
        pageCount = pages.size
        for (rows in pages) {
            val page = LinearLayout(context).apply { orientation = VERTICAL }
            for (keys in rows) {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                for (token in keys) {
                    val params = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    val margin = (2 * dp).toInt()
                    params.setMargins(margin, margin, margin, margin)
                    row.addView(makeKey(token), params)
                }
                page.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            content.addView(page, LayoutParams(pageWidth, (88 * dp).toInt()))
        }
        dots.count = pageCount
        dots.visibility = if (pageCount > 1) View.VISIBLE else View.GONE
        applyPageWidth()
        refreshModifiers()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pageWidth = w
        applyPageWidth()
        val page = currentPage()
        pager.post { pager.scrollTo(page * pageWidth, 0) }
    }

    fun showPage(page: Int) {
        if (pageCount == 0) {
            return
        }
        val target = page.coerceIn(0, pageCount - 1)
        pager.post { pager.smoothScrollTo(target * pageWidth, 0) }
    }

    private fun applyPageWidth() {
        for (i in 0 until content.childCount) {
            val page = content.getChildAt(i)
            val params = page.layoutParams
            params.width = pageWidth
            page.layoutParams = params
        }
    }

    private fun currentPage(): Int =
        if (pageWidth <= 0) 0 else ((pager.scrollX + pageWidth / 2) / pageWidth).coerceIn(0, maxOf(0, pageCount - 1))

    private fun snapTo(startPage: Int) {
        if (pageWidth <= 0 || pageCount == 0) {
            return
        }
        val delta = pager.scrollX - startPage * pageWidth
        val target = when {
            delta > pageWidth / 6 -> startPage + 1
            delta < -pageWidth / 6 -> startPage - 1
            else -> startPage
        }.coerceIn(0, pageCount - 1)
        pager.smoothScrollTo(target * pageWidth, 0)
    }

    private fun modifierOf(token: String): KeyModifiers.Key? = when (token.uppercase()) {
        "CTRL" -> KeyModifiers.Key.CTRL
        "ALT" -> KeyModifiers.Key.ALT
        "SHIFT" -> KeyModifiers.Key.SHIFT
        "FN" -> KeyModifiers.Key.FN
        else -> null
    }

    private fun makeKey(token: String): TextView {
        val modifier = modifierOf(token)
        val repeatable = (if (token.length > 1) token.uppercase() else token) in REPEATABLE
        val key = TextView(context).apply {
            text = labelOf(token)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isClickable = true
        }
        if (modifier != null) {
            modifierViews.add(Pair(key, modifier))
        }
        styleKey(key, modifier, false)

        var repeated = false
        val repeatRunnable = object : Runnable {
            override fun run() {
                repeated = true
                dispatch(token)
                key.postDelayed(this, 70)
            }
        }

        key.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeated = false
                    styleKey(key, modifier, true)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (repeatable) {
                        v.postDelayed(repeatRunnable, 350)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(repeatRunnable)
                    styleKey(key, modifier, false)
                    val inside = event.x >= 0f && event.x <= v.width && event.y >= 0f && event.y <= v.height
                    if (!repeated && inside) {
                        dispatch(token)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(repeatRunnable)
                    styleKey(key, modifier, false)
                }
            }
            true
        }
        return key
    }

    private fun styleKey(view: TextView, modifier: KeyModifiers.Key?, pressed: Boolean) {
        var background = if (pressed) "#475569" else "#334155"
        var foreground = "#E2E8F0"
        if (modifier != null) {
            when (modifiers.state(modifier)) {
                KeyModifiers.State.ONCE -> {
                    background = "#2563EB"
                    foreground = "#FFFFFF"
                }
                KeyModifiers.State.LOCKED -> {
                    background = "#F59E0B"
                    foreground = "#0F172A"
                }
                KeyModifiers.State.OFF -> Unit
            }
        }
        view.background = GradientDrawable().apply {
            cornerRadius = 6 * dp
            setColor(Color.parseColor(background))
        }
        view.setTextColor(Color.parseColor(foreground))
    }

    private fun refreshModifiers() {
        for ((view, modifier) in modifierViews) {
            styleKey(view, modifier, false)
        }
    }

    private fun labelOf(token: String): String {
        val key = if (token.length > 1) token.uppercase() else token
        return LABELS[key] ?: token
    }

    private fun dispatch(token: String) {
        val l = listener ?: return
        val key = if (token.length > 1) token.uppercase() else token

        val modifier = modifierOf(key)
        if (modifier != null) {
            modifiers.tap(modifier)
            return
        }
        val code = KEY_CODES[key]
        when {
            code != null -> l.onKeyCode(code)
            key == "KEYBOARD" -> l.onToggleKeyboard()
            key == "PASTE" -> l.onPaste()
            key == "COMMA" -> l.onText(",")
            key.startsWith("VNC_") -> l.onVnc(key)
            else -> l.onText(token)
        }
    }

    private inner class Pager(context: Context) : HorizontalScrollView(context) {
        private var startPage = 0

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                startPage = currentPage()
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val handled = super.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                snapTo(startPage)
            }
            return handled
        }
    }

    private inner class Dots(context: Context) : View(context) {
        var count = 0
        private var index = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setPage(page: Int) {
            if (page != index) {
                index = page
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (count < 2) {
                return
            }
            val gap = 12 * dp
            val startX = width / 2f - gap * (count - 1) / 2f
            for (i in 0 until count) {
                paint.color = Color.parseColor(if (i == index) "#38BDF8" else "#475569")
                canvas.drawCircle(startX + i * gap, height / 2f, 2.5f * dp, paint)
            }
        }
    }

    private companion object {
        val REPEATABLE = setOf("UP", "DOWN", "LEFT", "RIGHT", "PGUP", "PGDN", "BKSP", "DEL")

        val KEY_CODES: Map<String, Int> = buildMap {
            put("ESC", KeyEvent.KEYCODE_ESCAPE)
            put("TAB", KeyEvent.KEYCODE_TAB)
            put("UP", KeyEvent.KEYCODE_DPAD_UP)
            put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
            put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
            put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)
            put("HOME", KeyEvent.KEYCODE_MOVE_HOME)
            put("END", KeyEvent.KEYCODE_MOVE_END)
            put("PGUP", KeyEvent.KEYCODE_PAGE_UP)
            put("PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
            put("INS", KeyEvent.KEYCODE_INSERT)
            put("DEL", KeyEvent.KEYCODE_FORWARD_DEL)
            put("BKSP", KeyEvent.KEYCODE_DEL)
            put("ENTER", KeyEvent.KEYCODE_ENTER)
            for (n in 1..12) {
                put("F$n", KeyEvent.KEYCODE_F1 + n - 1)
            }
        }

        val LABELS = mapOf(
            "UP" to "\u2191",
            "DOWN" to "\u2193",
            "LEFT" to "\u2190",
            "RIGHT" to "\u2192",
            "BKSP" to "\u232B",
            "ENTER" to "\u21B5",
            "KEYBOARD" to "\u2328",
            "COMMA" to ",",
            "VNC_LEFT_CLICK" to "LMB",
            "VNC_RIGHT_CLICK" to "RMB",
            "VNC_MIDDLE_CLICK" to "MMB",
            "VNC_SCROLL_UP" to "Scr\u2191",
            "VNC_SCROLL_DOWN" to "Scr\u2193",
            "VNC_KEYBOARD" to "Kbd",
            "VNC_FIT" to "Fit",
            "VNC_CLOSE" to "Close"
        )
    }
}