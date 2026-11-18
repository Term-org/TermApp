package app.term.one.ui

import android.os.SystemClock
import java.util.EnumMap

class KeyModifiers {

    enum class Key { CTRL, ALT, SHIFT, FN }

    enum class State { OFF, ONCE, LOCKED }

    private val states = EnumMap<Key, State>(Key::class.java)
    private val tapTimes = EnumMap<Key, Long>(Key::class.java)

    var onChanged: (() -> Unit)? = null

    fun state(key: Key): State = states[key] ?: State.OFF

    fun isActive(key: Key): Boolean = state(key) != State.OFF

    fun tap(key: Key) {
        val now = SystemClock.uptimeMillis()
        val next = when (state(key)) {
            State.OFF -> State.ONCE
            State.ONCE -> if (now - (tapTimes[key] ?: 0L) < DOUBLE_TAP_MS) State.LOCKED else State.OFF
            State.LOCKED -> State.OFF
        }
        tapTimes[key] = now
        states[key] = next
        onChanged?.invoke()
    }

    fun consumeOneShot() {
        var changed = false
        for (key in Key.values()) {
            if (state(key) == State.ONCE) {
                states[key] = State.OFF
                changed = true
            }
        }
        if (changed) {
            onChanged?.invoke()
        }
    }

    fun mask(): Int {
        var mask = 0
        if (isActive(Key.SHIFT)) mask = mask or KeyEncoder.SHIFT
        if (isActive(Key.ALT)) mask = mask or KeyEncoder.ALT
        if (isActive(Key.CTRL)) mask = mask or KeyEncoder.CTRL
        return mask
    }

    private companion object {
        const val DOUBLE_TAP_MS = 400L
    }
}