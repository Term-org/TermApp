package app.term.one.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.term.one.core.TerminalSession
import kotlin.math.max

class SessionTabBar(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onTabSelected(session: TerminalSession)
        fun onTabLongPressed(session: TerminalSession, anchor: View)
        fun onNewSession()
        fun onMenu(anchor: View)
    }

    var listener: Listener? = null

    private val dp = resources.displayMetrics.density

    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    private val tabs = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val newButton = makeButton("+")
    private val menuButton = makeButton("\u22EE")

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.parseColor("#0B1220"))
        scroll.addView(tabs, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(newButton, LayoutParams((44 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(menuButton, LayoutParams((44 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        newButton.setOnClickListener { listener?.onNewSession() }
        menuButton.setOnClickListener { listener?.onMenu(it) }
    }

    fun update(
        sessions: List<TerminalSession>,
        current: TerminalSession?,
        nameOf: (TerminalSession) -> String,
        groupColorIndexOf: (TerminalSession) -> Int?
    ) {
        tabs.removeAllViews()
        var currentView: View? = null
        sessions.forEachIndexed { index, session ->
            val selected = session === current
            val colorIndex = groupColorIndexOf(session)
            val groupColor = colorIndex?.let { GROUP_COLORS[it % GROUP_COLORS.size] }
            val tab = TextView(context).apply {
                text = "${index + 1}: ${nameOf(session)}"
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (150 * dp).toInt()
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#94A3B8"))
                background = tabBackground(selected, groupColor)
                setOnClickListener { listener?.onTabSelected(session) }
                setOnLongClickListener {
                    listener?.onTabLongPressed(session, it)
                    true
                }
            }
            tabs.addView(tab, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (selected) {
                currentView = tab
            }
        }
        currentView?.let { view ->
            scroll.post {
                scroll.smoothScrollTo(max(0, view.left - scroll.width / 2 + view.width / 2), 0)
            }
        }
    }

    private fun tabBackground(selected: Boolean, groupColor: Int?): Drawable {
        val base = GradientDrawable().apply {
            setColor(if (selected) Color.parseColor("#1E293B") else Color.TRANSPARENT)
        }
        val lineColor = groupColor ?: if (selected) Color.parseColor("#38BDF8") else null
        if (lineColor == null) {
            return base
        }
        val line = GradientDrawable().apply { setColor(lineColor) }
        return LayerDrawable(arrayOf<Drawable>(base, line)).apply {
            setLayerInset(1, 0, (37 * dp).toInt(), 0, 0)
        }
    }

    private fun makeButton(label: String): TextView {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.parseColor("#CBD5E1"))
            setBackgroundResource(out.resourceId)
        }
    }

    private companion object {
        val GROUP_COLORS = intArrayOf(
            Color.parseColor("#F59E0B"),
            Color.parseColor("#22C55E"),
            Color.parseColor("#EC4899"),
            Color.parseColor("#A78BFA"),
            Color.parseColor("#06B6D4"),
            Color.parseColor("#EF4444")
        )
    }
}