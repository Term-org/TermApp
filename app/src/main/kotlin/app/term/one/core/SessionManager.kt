package app.term.one.core

import android.os.SystemClock
import app.term.one.TermPaths
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

class SessionGroup(val owner: TerminalSession, val colorIndex: Int) {

    val members = ArrayList<TerminalSession>()

    val all: List<TerminalSession> get() = listOf(owner) + members

    val size: Int get() = 1 + members.size

    fun contains(session: TerminalSession): Boolean = session === owner || members.contains(session)
}

enum class CombineResult { OK, GROUP_FULL, INVALID }

class SessionManager(private val paths: TermPaths) : TerminalSession.Listener {

    interface Listener {
        fun onSessionsChanged() {}
        fun onCurrentChanged(session: TerminalSession?) {}
        fun onLayoutChanged() {}
        fun onSessionScreen(session: TerminalSession) {}
        fun onSessionBell(session: TerminalSession) {}
        fun onSessionClipboard(session: TerminalSession, text: String) {}
        fun onAllSessionsClosed() {}
        fun onVncRequest(session: TerminalSession, args: Map<String, String>) {}
        fun onTermCommand(session: TerminalSession, name: String, args: Map<String, String>) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val items = ArrayList<TerminalSession>()
    private val groups = ArrayList<SessionGroup>()
    private val names = HashMap<Int, String>()
    private var nextId = 1

    val sessions: List<TerminalSession> get() = items

    var current: TerminalSession? = null
        private set

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun displayName(session: TerminalSession): String = names[session.id] ?: session.title

    fun rename(session: TerminalSession, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            names.remove(session.id)
        } else {
            names[session.id] = trimmed
        }
        listeners.forEach { it.onSessionsChanged() }
    }

    // ------------------------------------------------------------------ groups

    fun groupOf(session: TerminalSession): SessionGroup? = groups.firstOrNull { it.contains(session) }

    fun groupSize(session: TerminalSession): Int = groupOf(session)?.size ?: 1

    fun displayedFor(session: TerminalSession): List<TerminalSession> =
        groupOf(session)?.all ?: listOf(session)

    fun combineTargets(session: TerminalSession): List<TerminalSession> {
        if (groupOf(session) != null) {
            return emptyList()
        }
        return items.filter { other ->
            if (other === session) {
                false
            } else {
                val group = groupOf(other)
                group == null || (group.owner === other && group.size < MAX_GROUP)
            }
        }
    }

    fun combine(session: TerminalSession, target: TerminalSession): CombineResult {
        if (session === target || !items.contains(session) || !items.contains(target)) {
            return CombineResult.INVALID
        }
        if (groupOf(session) != null) {
            return CombineResult.INVALID
        }
        val existing = groupOf(target)
        val group = existing ?: SessionGroup(target, freeColorIndex())
        if (group.size >= MAX_GROUP) {
            return CombineResult.GROUP_FULL
        }
        if (existing == null) {
            groups.add(group)
        }
        group.members.add(session)
        current = session
        notifyStructure(true)
        return CombineResult.OK
    }

    fun eject(member: TerminalSession): Boolean {
        val group = groups.firstOrNull { it.members.contains(member) } ?: return false
        group.members.remove(member)
        if (group.members.isEmpty()) {
            groups.remove(group)
        }
        notifyStructure(false)
        return true
    }

    fun ejectAll(owner: TerminalSession): Boolean {
        val group = groups.firstOrNull { it.owner === owner } ?: return false
        groups.remove(group)
        notifyStructure(false)
        return true
    }

    private fun freeColorIndex(): Int =
        (0 until COLOR_COUNT).firstOrNull { color -> groups.none { it.colorIndex == color } } ?: 0

    private fun notifyStructure(currentChanged: Boolean) {
        listeners.forEach {
            it.onSessionsChanged()
            if (currentChanged) {
                it.onCurrentChanged(current)
            }
            it.onLayoutChanged()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun createSession(
        cols: Int,
        rows: Int,
        scrollback: Int,
        command: String? = null,
        args: List<String> = emptyList(),
        workDir: String? = null
    ): TerminalSession {
        val session = TerminalSession(nextId++, paths, cols, rows, this, scrollback)
        items.add(session)
        try {
            session.start(command, args, workDir)
        } catch (e: RuntimeException) {
            items.remove(session)
            throw e
        }
        current = session
        listeners.forEach {
            it.onSessionsChanged()
            it.onCurrentChanged(session)
        }
        return session
    }

    fun select(session: TerminalSession) {
        if (!items.contains(session) || current === session) {
            return
        }
        current = session
        listeners.forEach {
            it.onSessionsChanged()
            it.onCurrentChanged(session)
        }
    }

    fun close(session: TerminalSession) {
        val index = items.indexOf(session)
        if (index < 0) {
            return
        }
        items.removeAt(index)
        names.remove(session.id)

        val group = groupOf(session)
        if (group != null) {
            if (group.owner === session) {
                groups.remove(group)
            } else {
                group.members.remove(session)
                if (group.members.isEmpty()) {
                    groups.remove(group)
                }
            }
        }

        if (session.isRunning) {
            session.kill()
        }
        val wasCurrent = current === session
        if (wasCurrent) {
            current = if (items.isEmpty()) null else items[min(index, items.size - 1)]
        }
        notifyStructure(wasCurrent)
        if (items.isEmpty()) {
            listeners.forEach { it.onAllSessionsClosed() }
        }
    }

    fun closeAll() {
        val copy = ArrayList(items)
        items.clear()
        groups.clear()
        names.clear()
        current = null
        copy.forEach {
            if (it.isRunning) {
                it.kill()
            }
        }
        listeners.forEach {
            it.onSessionsChanged()
            it.onCurrentChanged(null)
            it.onLayoutChanged()
            it.onAllSessionsClosed()
        }
    }

    override fun onScreenChanged(session: TerminalSession) {
        listeners.forEach { it.onSessionScreen(session) }
    }

    override fun onTitleChanged(session: TerminalSession, title: String) {
        listeners.forEach { it.onSessionsChanged() }
    }

    override fun onBell(session: TerminalSession) {
        listeners.forEach { it.onSessionBell(session) }
    }

    override fun onClipboardSet(session: TerminalSession, text: String) {
        listeners.forEach { it.onSessionClipboard(session, text) }
    }

    override fun onTermCommand(session: TerminalSession, name: String, args: Map<String, String>) {
        listeners.forEach { it.onTermCommand(session, name, args) }
    }

    override fun onVncRequest(session: TerminalSession, args: Map<String, String>) {
        listeners.forEach { it.onVncRequest(session, args) }
    }

    override fun onCwdChanged(session: TerminalSession, cwd: String) {
    }

    override fun onExited(session: TerminalSession, exitCode: Int) {
        if (!items.contains(session)) {
            return
        }
        val failedToStart = (exitCode == 126 || exitCode == 127) &&
            SystemClock.elapsedRealtime() - session.startedAt < 3000
        if (!failedToStart) {
            close(session)
            return
        }
        val message = "\r\n[Unable to start the shell (code $exitCode) - press Enter to close]\r\n"
        val bytes = message.toByteArray(Charsets.UTF_8)
        session.emulator.feed(bytes, 0, bytes.size)
        listeners.forEach {
            it.onSessionsChanged()
            it.onSessionScreen(session)
        }
    }

    companion object {
        const val MAX_GROUP = 4
        const val COLOR_COUNT = 6
    }
}