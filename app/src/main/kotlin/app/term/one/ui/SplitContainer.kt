package app.term.one.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewGroup
import app.term.one.core.TerminalSession
import kotlin.math.abs
import kotlin.math.max

class SplitContainer(
    context: Context,
    private val factory: () -> TerminalView
) : ViewGroup(context) {

    private val dp = resources.displayMetrics.density
    private val gap = max(1, (2 * dp).toInt())
    private val hit = (16 * dp).toInt()
    private val views = HashMap<Int, TerminalView>()
    private val rects = ArrayList<Rect>()
    private var shownIds: List<Int> = emptyList()
    private var focusedId = -1

    var isMaximized = false
        private set

    val paneCount: Int get() = childCount

    private var ratioX = 0.5f
    private var ratioY = 0.5f
    private var dragX = false
    private var dragY = false
    private var previewX = 0.5f
    private var previewY = 0.5f

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#38BDF8")
        strokeWidth = 2 * dp
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
    }
    private val previewPaint = Paint().apply {
        color = Color.parseColor("#38BDF8")
    }
    private val pill = RectF()

    init {
        setBackgroundColor(Color.parseColor("#1E293B"))
    }

    fun viewFor(session: TerminalSession): TerminalView? = views[session.id]

    fun allViews(): List<TerminalView> = ArrayList(views.values)

    fun show(sessions: List<TerminalSession>, focused: TerminalSession?) {
        val list = sessions.take(MAX_PANES)
        val ids = list.map { it.id }
        focusedId = focused?.id ?: -1
        if (ids != shownIds) {
            removeAllViews()
            shownIds = ids
            ratioX = 0.5f
            ratioY = 0.5f
            isMaximized = false
            for (session in list) {
                val view = views.getOrPut(session.id) {
                    factory().also { it.attachSession(session) }
                }
                addView(view)
                view.onScreenUpdated()
            }
        }
        applyVisibility()
        requestLayout()
        invalidate()
    }

    fun toggleMaximize() {
        isMaximized = if (isMaximized) false else childCount >= 2 && shownIds.contains(focusedId)
        applyVisibility()
        requestLayout()
        invalidate()
    }

    fun prune(liveIds: Set<Int>) {
        val dead = views.keys.filter { it !in liveIds }
        if (dead.isEmpty()) {
            return
        }
        for (id in dead) {
            val view = views.remove(id) ?: continue
            if (view.parent === this) {
                removeView(view)
            }
            view.attachSession(null)
        }
        shownIds = shownIds.filter { it in liveIds }
    }

    private fun applyVisibility() {
        val maximize = isMaximized && shownIds.contains(focusedId)
        for (i in 0 until childCount) {
            val hidden = maximize && shownIds.getOrNull(i) != focusedId
            getChildAt(i).visibility = if (hidden) GONE else VISIBLE
        }
    }

    // ------------------------------------------------------------------ geometry

    private fun dividerX(): Int = ((width - gap) * ratioX).toInt() + gap / 2

    private fun dividerY(): Int = ((height - gap) * ratioY).toInt() + gap / 2

    private fun computeRects(w: Int, h: Int) {
        rects.clear()
        val count = childCount
        if (count == 0) {
            return
        }
        if (isMaximized) {
            val focusedIndex = shownIds.indexOf(focusedId)
            for (i in 0 until count) {
                rects.add(if (i == focusedIndex) Rect(0, 0, w, h) else Rect(0, 0, 0, 0))
            }
            return
        }
        val lw = ((w - gap) * ratioX).toInt()
        val th = ((h - gap) * ratioY).toInt()
        when (count) {
            1 -> rects.add(Rect(0, 0, w, h))
            2 -> {
                rects.add(Rect(0, 0, lw, h))
                rects.add(Rect(lw + gap, 0, w, h))
            }
            3 -> {
                rects.add(Rect(0, 0, lw, h))
                rects.add(Rect(lw + gap, 0, w, th))
                rects.add(Rect(lw + gap, th + gap, w, h))
            }
            else -> {
                rects.add(Rect(0, 0, lw, th))
                rects.add(Rect(lw + gap, 0, w, th))
                rects.add(Rect(0, th + gap, lw, h))
                rects.add(Rect(lw + gap, th + gap, w, h))
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        computeRects(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) {
                continue
            }
            val rect = rects.getOrNull(i) ?: Rect(0, 0, 0, 0)
            child.measure(
                MeasureSpec.makeMeasureSpec(max(0, rect.width()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(max(0, rect.height()), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        computeRects(r - l, b - t)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) {
                continue
            }
            val rect = rects.getOrNull(i) ?: Rect(0, 0, 0, 0)
            child.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    // ------------------------------------------------------------------ dragging the dividers

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN || isMaximized || childCount < 2) {
            return false
        }
        val vx = dividerX()
        val hy = dividerY()
        val nearVertical = abs(ev.x - vx) <= hit
        val nearHorizontal = when (childCount) {
            3 -> ev.x > vx - hit && abs(ev.y - hy) <= hit
            4 -> abs(ev.y - hy) <= hit
            else -> false
        }
        if (!nearVertical && !nearHorizontal) {
            return false
        }
        dragX = nearVertical
        dragY = nearHorizontal
        previewX = ratioX
        previewY = ratioY
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragX && !dragY) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragX && width > 0) {
                    previewX = (event.x / width).coerceIn(MIN_RATIO, MAX_RATIO)
                }
                if (dragY && height > 0) {
                    previewY = (event.y / height).coerceIn(MIN_RATIO, MAX_RATIO)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (dragX) {
                    ratioX = previewX
                }
                if (dragY) {
                    ratioY = previewY
                }
                dragX = false
                dragY = false
                requestLayout()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragX = false
                dragY = false
                invalidate()
            }
        }
        return true
    }

    // ------------------------------------------------------------------ drawing

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (childCount < 2 || isMaximized) {
            return
        }

        val focused = views[focusedId]
        if (focused != null && focused.parent === this) {
            val half = borderPaint.strokeWidth / 2f
            canvas.drawRect(
                focused.left + half,
                focused.top + half,
                focused.right - half,
                focused.bottom - half,
                borderPaint
            )
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val vx = dividerX().toFloat()
        val hy = dividerY().toFloat()
        val grip = 40 * dp
        val thick = 4 * dp

        pill.set(vx - thick / 2, h / 2 - grip / 2, vx + thick / 2, h / 2 + grip / 2)
        canvas.drawRoundRect(pill, thick, thick, handlePaint)

        if (childCount == 3) {
            val cx = vx + (w - vx) / 2
            pill.set(cx - grip / 2, hy - thick / 2, cx + grip / 2, hy + thick / 2)
            canvas.drawRoundRect(pill, thick, thick, handlePaint)
        } else if (childCount == 4) {
            pill.set(w / 2 - grip / 2, hy - thick / 2, w / 2 + grip / 2, hy + thick / 2)
            canvas.drawRoundRect(pill, thick, thick, handlePaint)
        }

        val line = 3 * dp
        if (dragX) {
            val px = (width - gap) * previewX + gap / 2f
            canvas.drawRect(px - line / 2, 0f, px + line / 2, h, previewPaint)
        }
        if (dragY) {
            val py = (height - gap) * previewY + gap / 2f
            val from = if (childCount == 3) (width - gap) * (if (dragX) previewX else ratioX) + gap else 0f
            canvas.drawRect(from, py - line / 2, w, py + line / 2, previewPaint)
        }
    }

    private companion object {
        const val MAX_PANES = 4
        const val MIN_RATIO = 0.2f
        const val MAX_RATIO = 0.8f
    }
}