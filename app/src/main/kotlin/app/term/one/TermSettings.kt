package app.term.one

import java.io.File
import java.io.IOException
import java.util.Properties

data class TermSettings(
    val fontSizeSp: Int,
    val cursorStyle: String,
    val cursorBlink: Boolean,
    val bell: String,
    val scrollback: Int,
    val sessionTabsTop: Boolean
) {
    companion object {
        private val CURSOR_STYLES = setOf("block", "underline", "bar")
        private val BELLS = setOf("vibrate", "beep", "ignore")

        fun load(file: File): TermSettings {
            val props = Properties()
            if (file.isFile) {
                try {
                    file.inputStream().use { props.load(it) }
                } catch (e: IOException) {
                    props.clear()
                }
            }
            val style = props.getProperty("cursor-style", "block").trim().lowercase()
            val bell = props.getProperty("bell", "vibrate").trim().lowercase()
            return TermSettings(
                fontSizeSp = (props.getProperty("font-size")?.trim()?.toIntOrNull() ?: 14).coerceIn(8, 40),
                cursorStyle = if (style in CURSOR_STYLES) style else "block",
                cursorBlink = props.getProperty("cursor-blink", "true").trim().toBoolean(),
                bell = if (bell in BELLS) bell else "vibrate",
                scrollback = (props.getProperty("scrollback")?.trim()?.toIntOrNull() ?: 2000).coerceIn(100, 50000),
                sessionTabsTop = props.getProperty("session-tabs-top", "true").trim().toBoolean()
            )
        }
    }
}