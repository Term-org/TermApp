package app.term.one.ui

import android.view.KeyEvent

object KeyEncoder {

    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4

    private const val ESC = "\u001b"

    fun encodeSpecial(keyCode: Int, mods: Int, appCursor: Boolean): String? {
        val cursorFinal: Char? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 'A'
            KeyEvent.KEYCODE_DPAD_DOWN -> 'B'
            KeyEvent.KEYCODE_DPAD_RIGHT -> 'C'
            KeyEvent.KEYCODE_DPAD_LEFT -> 'D'
            KeyEvent.KEYCODE_MOVE_HOME -> 'H'
            KeyEvent.KEYCODE_MOVE_END -> 'F'
            else -> null
        }
        if (cursorFinal != null) {
            return when {
                mods != 0 -> "$ESC[1;${1 + mods}$cursorFinal"
                appCursor -> "${ESC}O$cursorFinal"
                else -> "$ESC[$cursorFinal"
            }
        }

        val tilde = when (keyCode) {
            KeyEvent.KEYCODE_INSERT -> 2
            KeyEvent.KEYCODE_FORWARD_DEL -> 3
            KeyEvent.KEYCODE_PAGE_UP -> 5
            KeyEvent.KEYCODE_PAGE_DOWN -> 6
            KeyEvent.KEYCODE_F5 -> 15
            KeyEvent.KEYCODE_F6 -> 17
            KeyEvent.KEYCODE_F7 -> 18
            KeyEvent.KEYCODE_F8 -> 19
            KeyEvent.KEYCODE_F9 -> 20
            KeyEvent.KEYCODE_F10 -> 21
            KeyEvent.KEYCODE_F11 -> 23
            KeyEvent.KEYCODE_F12 -> 24
            else -> 0
        }
        if (tilde != 0) {
            return if (mods != 0) "$ESC[$tilde;${1 + mods}~" else "$ESC[$tilde~"
        }

        val ss3: Char? = when (keyCode) {
            KeyEvent.KEYCODE_F1 -> 'P'
            KeyEvent.KEYCODE_F2 -> 'Q'
            KeyEvent.KEYCODE_F3 -> 'R'
            KeyEvent.KEYCODE_F4 -> 'S'
            else -> null
        }
        if (ss3 != null) {
            return if (mods != 0) "$ESC[1;${1 + mods}$ss3" else "${ESC}O$ss3"
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (mods and ALT != 0) "$ESC\r" else "\r"
            KeyEvent.KEYCODE_TAB -> when {
                mods and SHIFT != 0 -> "$ESC[Z"
                mods and ALT != 0 -> "$ESC\t"
                else -> "\t"
            }
            KeyEvent.KEYCODE_ESCAPE -> ESC
            KeyEvent.KEYCODE_DEL -> when {
                mods and CTRL != 0 -> "\b"
                mods and ALT != 0 -> "$ESC\u007f"
                else -> "\u007f"
            }
            else -> null
        }
    }

    fun functionKey(number: Int, mods: Int): String =
        encodeSpecial(KeyEvent.KEYCODE_F1 + number.coerceIn(1, 12) - 1, mods, false) ?: ""

    fun fnDigit(c: Char): Int = when (c) {
        in '1'..'9' -> c - '0'
        '0' -> 10
        else -> 0
    }

    fun fnRemap(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_PAGE_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_MOVE_HOME
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_MOVE_END
        else -> keyCode
    }

    fun applyTextModifiers(text: String, ctrl: Boolean, alt: Boolean): String {
        var out = text
        if (ctrl && text.length == 1) {
            out = ctrlChar(text[0])?.toString() ?: text
        }
        return if (alt) ESC + out else out
    }

    private fun ctrlChar(c: Char): Char? = when {
        c in 'a'..'z' -> (c.code - 96).toChar()
        c in '@'..'_' -> (c.code - 64).toChar()
        c == ' ' -> '\u0000'
        c == '?' -> '\u007f'
        else -> null
    }
}