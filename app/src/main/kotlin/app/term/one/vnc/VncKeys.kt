package app.term.one.vnc

import android.view.KeyEvent

object VncKeys {

    const val SHIFT_L = 0xFFE1
    const val CONTROL_L = 0xFFE3
    const val ALT_L = 0xFFE9
    const val RETURN = 0xFF0D
    const val TAB = 0xFF09
    const val BACKSPACE = 0xFF08

    fun special(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
        KeyEvent.KEYCODE_INSERT -> 0xFF63
        KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
        KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
        KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
        KeyEvent.KEYCODE_MOVE_END -> 0xFF57
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xFFBE + (keyCode - KeyEvent.KEYCODE_F1)
        else -> 0
    }

    fun fromCodePoint(cp: Int): Int = when {
        cp == '\n'.code || cp == '\r'.code -> RETURN
        cp == '\t'.code -> TAB
        cp in 0x20..0x7E || cp in 0xA0..0xFF -> cp
        else -> 0x01000000 + cp
    }
}