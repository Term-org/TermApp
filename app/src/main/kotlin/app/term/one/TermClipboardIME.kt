package app.term.one

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

class TermClipboardIME : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var paths: TermPaths

    private var entries: List<TermClipboard.Entry> = emptyList()
    private var selected: TermClipboard.Entry? = null
    private var insertTask: Runnable? = null

    private var statusView: TextView? = null
    private var previewView: TextView? = null
    private var chipsView: LinearLayout? = null
    private var insertButton: TextView? = null

    private val dp: Float get() = resources.displayMetrics.density

    override fun onCreate() {
        super.onCreate()
        paths = TermPaths.get(this)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = getString(R.string.ime_name)
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(button("Switch", "#334155") { switchKeyboard() }, smallParams())
        header.addView(button("Hide", "#334155") { requestHideSelf(0) }, smallParams())
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (36 * dp).toInt()))

        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        statusView = status
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val preview = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        previewView = preview
        val previewScroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 6 * dp
                setColor(Color.BLACK)
            }
            addView(preview, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(
            previewScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (96 * dp).toInt()).apply {
                topMargin = (4 * dp).toInt()
            }
        )

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipsView = chips
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(
            chipScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * dp).toInt()).apply {
                topMargin = (4 * dp).toInt()
            }
        )

        val insert = button("Insert", "#2563EB") { insertSelected() }
        insert.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        insertButton = insert
        root.addView(
            insert,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * dp).toInt()).apply {
                topMargin = (4 * dp).toInt()
            }
        )

        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(button("Space", "#334155") { sendText(" ") }, keyParams(1.6f))
        keys.addView(button("\u232B", "#334155") { sendDownUp(KeyEvent.KEYCODE_DEL) }, keyParams(1f))
        keys.addView(button("\u21B5", "#334155") { sendDownUp(KeyEvent.KEYCODE_ENTER) }, keyParams(1f))
        keys.addView(button("Delete", "#7F1D1D") { deleteSelected() }, keyParams(1.3f))
        keys.addView(button("Clear", "#7F1D1D") { clearAll() }, keyParams(1.2f))
        root.addView(
            keys,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (42 * dp).toInt()).apply {
                topMargin = (4 * dp).toInt()
            }
        )

        reload()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        reload()
    }

    override fun onFinishInput() {
        cancelInsert()
        super.onFinishInput()
    }

    override fun onDestroy() {
        cancelInsert()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        entries = TermClipboard.history(paths)
        val current = selected
        selected = entries.firstOrNull { it.id == current?.id } ?: entries.firstOrNull()
        status("")
        render()
    }

    private fun render() {
        val chips = chipsView ?: return
        chips.removeAllViews()
        entries.forEachIndexed { index, entry ->
            val active = entry.id == selected?.id
            val label = entry.preview(24).replace('\n', ' ').trim().ifEmpty { "(blank)" }
            val chip = TextView(this).apply {
                text = "${index + 1}: $label"
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (150 * dp).toInt()
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#CBD5E1"))
                val pad = (10 * dp).toInt()
                setPadding(pad, 0, pad, 0)
                background = GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(Color.parseColor(if (active) "#2563EB" else "#1E293B"))
                }
                setOnClickListener {
                    selected = entry
                    status("")
                    render()
                }
            }
            chips.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = (4 * dp).toInt()
                    topMargin = (4 * dp).toInt()
                    bottomMargin = (4 * dp).toInt()
                }
            )
        }

        val entry = selected
        if (entry == null) {
            previewView?.text = "Term clipboard is empty. Select text in the terminal and press Copy."
            insertButton?.text = "Insert"
            insertButton?.alpha = 0.4f
        } else {
            val shown = entry.preview(PREVIEW_CHARS)
            val more = entry.sizeBytes > PREVIEW_CHARS * 3L
            previewView?.text = if (more) "$shown\n..." else shown
            insertButton?.text = "Insert all (${formatSize(entry.sizeBytes)})"
            insertButton?.alpha = 1f
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun status(message: String) {
        statusView?.text = message
    }

    // ------------------------------------------------------------------ actions

    private fun insertSelected() {
        val entry = selected
        if (entry == null) {
            status("Nothing to insert")
            return
        }
        val text = entry.text()
        if (text.isEmpty()) {
            status("Nothing to insert")
            return
        }
        cancelInsert()
        val total = text.length
        var position = 0
        val task = object : Runnable {
            override fun run() {
                val connection = currentInputConnection
                if (connection == null) {
                    status("No input field is active")
                    insertTask = null
                    return
                }
                var end = min(total, position + CHUNK)
                if (end < total && Character.isHighSurrogate(text[end - 1])) {
                    end--
                }
                connection.commitText(text.substring(position, end), 1)
                position = end
                if (position < total) {
                    status("Inserting ${position * 100L / total}%")
                    handler.postDelayed(this, 15)
                } else {
                    status("Inserted $total characters")
                    insertTask = null
                }
            }
        }
        insertTask = task
        handler.post(task)
    }

    private fun cancelInsert() {
        insertTask?.let { handler.removeCallbacks(it) }
        insertTask = null
    }

    private fun deleteSelected() {
        val entry = selected ?: return
        TermClipboard.remove(paths, entry)
        selected = null
        reload()
        status("Entry deleted")
    }

    private fun clearAll() {
        TermClipboard.clear(paths)
        selected = null
        reload()
        status("Clipboard cleared")
    }

    private fun sendText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun sendDownUp(keyCode: Int) {
        sendDownUpKeyEvents(keyCode)
    }

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= 28 && switchToNextInputMethod(false)) {
            return
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    // ------------------------------------------------------------------ view helpers

    private fun smallParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams((64 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = (4 * dp).toInt()
            topMargin = (2 * dp).toInt()
            bottomMargin = (2 * dp).toInt()
        }

    private fun keyParams(weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
            val m = (2 * dp).toInt()
            setMargins(m, 0, m, 0)
        }

    private fun button(label: String, background: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            this.background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(Color.parseColor(background))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private companion object {
        const val CHUNK = 8000
        const val PREVIEW_CHARS = 2000
    }
}