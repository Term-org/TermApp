package app.term.one.ui

import android.os.SystemClock
import java.util.EnumMap
import java.util.EnumSet

class KeyModifiers {

    enum class Key { CTRL, ALT, SHIFT, FN }

    enum class State { OFF, ONCE, LOCKED }

    private val states = EnumMap<Key, State>(Key::class.java)
    private val tapTimes = EnumMap<Key, Long>(Key::class.java)
    private val held: MutableSet<Key> = EnumSet.noneOf(Key::class.java)

    var onChanged: (() -> Unit)? = null

    private fun base(key: Key): State = states[key] ?: State.OFF

    // A key that is physically held (a volume key) counts as active until it is released.
    fun state(key: Key): State {
        val current = base(key)
        return if (current == State.OFF && held.contains(key)) State.ONCE else current
    }

    fun isActive(key: Key): Boolean = state(key) != State.OFF

    fun hold(key: Key, down: Boolean) {
        val changed = if (down) held.add(key) else held.remove(key)
        if (changed) {
            onChanged?.invoke()
        }
    }

    fun releaseHeld() {
        if (held.isNotEmpty()) {
            held.clear()
            onChanged?.invoke()
        }
    }

    fun tap(key: Key) {
        val now = SystemClock.uptimeMillis()
        val next = when (base(key)) {
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
            if (base(key) == State.ONCE) {
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