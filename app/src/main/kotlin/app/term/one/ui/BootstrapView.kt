package app.term.one.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.term.one.R

class BootstrapView(context: Context) : LinearLayout(context) {

    var onRetry: (() -> Unit)? = null
    var onSkip: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val statusView = TextView(context)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val buttons = LinearLayout(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        val pad = (28 * dp).toInt()
        setPadding(pad, pad, pad, pad)

        val title = TextView(context).apply {
            text = "Setting up ${context.getString(R.string.app_name)}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        addView(title, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val subtitle = TextView(context).apply {
            text = "Downloading the base system (bash, coreutils, apt). This only happens once."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        addView(
            subtitle,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * dp).toInt()
            }
        )

        bar.max = 100
        bar.progressTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
        addView(
            bar,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (24 * dp).toInt()
            }
        )

        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        statusView.gravity = Gravity.CENTER
        addView(
            statusView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * dp).toInt()
            }
        )

        buttons.orientation = HORIZONTAL
        buttons.gravity = Gravity.CENTER
        buttons.visibility = GONE
        buttons.addView(makeButton("Retry", "#2563EB") { onRetry?.invoke() }, buttonParams())
        buttons.addView(makeButton("Use system shell", "#334155") { onSkip?.invoke() }, buttonParams())
        addView(
            buttons,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (20 * dp).toInt()
            }
        )
    }

    fun showProgress(stage: String, percent: Int) {
        buttons.visibility = GONE
        bar.visibility = VISIBLE
        statusView.setTextColor(Color.parseColor("#E2E8F0"))
        if (percent < 0) {
            bar.isIndeterminate = true
            statusView.text = "$stage..."
        } else {
            bar.isIndeterminate = false
            bar.progress = percent
            statusView.text = "$stage... $percent%"
        }
    }

    fun showError(message: String) {
        bar.visibility = GONE
        buttons.visibility = VISIBLE
        statusView.setTextColor(Color.parseColor("#F87171"))
        statusView.text = "Setup failed: $message"
    }

    private fun buttonParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()).apply {
            val m = (4 * dp).toInt()
            setMargins(m, 0, m, 0)
        }

    private fun makeButton(label: String, background: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            val pad = (16 * dp).toInt()
            setPadding(pad, 0, pad, 0)
            this.background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(Color.parseColor(background))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
}