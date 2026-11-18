package app.term.one.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import app.term.one.core.TerminalSession
import kotlin.math.max
import kotlin.math.min

class SplitContainer(
    context: Context,
    private val factory: () -> TerminalView
) : ViewGroup(context) {

    private val dp = resources.displayMetrics.density
    private val gap = max(1, (2 * dp).toInt())
    private val views = HashMap<Int, TerminalView>()
    private val rects = ArrayList<Rect>()
    private var shownIds: List<Int> = emptyList()
    private var focusedId = -1

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#38BDF8")
        strokeWidth = 2 * dp
    }

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
            for (session in list) {
                val view = views.getOrPut(session.id) {
                    factory().also { it.attachSession(session) }
                }
                addView(view)
                view.onScreenUpdated()
            }
            requestLayout()
        }
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

    private fun computeRects(count: Int, w: Int, h: Int) {
        rects.clear()
        val half = (w - gap) / 2
        val halfH = (h - gap) / 2
        when {
            count <= 0 -> Unit
            count == 1 -> rects.add(Rect(0, 0, w, h))
            count == 2 -> {
                rects.add(Rect(0, 0, half, h))
                rects.add(Rect(half + gap, 0, w, h))
            }
            count == 3 -> {
                rects.add(Rect(0, 0, half, h))
                rects.add(Rect(half + gap, 0, w, halfH))
                rects.add(Rect(half + gap, halfH + gap, w, h))
            }
            else -> {
                rects.add(Rect(0, 0, half, halfH))
                rects.add(Rect(half + gap, 0, w, halfH))
                rects.add(Rect(0, halfH + gap, half, h))
                rects.add(Rect(half + gap, halfH + gap, w, h))
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        computeRects(min(childCount, MAX_PANES), w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (i < rects.size) {
                val r = rects[i]
                child.measure(
                    MeasureSpec.makeMeasureSpec(max(0, r.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(max(0, r.height()), MeasureSpec.EXACTLY)
                )
            } else {
                child.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
                )
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        computeRects(min(childCount, MAX_PANES), r - l, b - t)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (i < rects.size) {
                val rect = rects[i]
                child.layout(rect.left, rect.top, rect.right, rect.bottom)
            } else {
                child.layout(0, 0, 0, 0)
                child.visibility = View.GONE
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (childCount < 2) {
            return
        }
        val view = views[focusedId] ?: return
        if (view.parent !== this) {
            return
        }
        val half = borderPaint.strokeWidth / 2f
        canvas.drawRect(
            view.left + half,
            view.top + half,
            view.right - half,
            view.bottom - half,
            borderPaint
        )
    }

    private companion object {
        const val MAX_PANES = 4
    }
}