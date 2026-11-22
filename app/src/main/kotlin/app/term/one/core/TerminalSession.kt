package app.term.one.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.term.one.TermEnvironment
import app.term.one.TermPaths
import app.term.one.TermTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TerminalSession(
    val id: Int,
    private val paths: TermPaths,
    cols: Int,
    rows: Int,
    private val listener: Listener,
    scrollback: Int = 2000
) : TerminalCallbacks {

    interface Listener {
        fun onScreenChanged(session: TerminalSession)
        fun onTitleChanged(session: TerminalSession, title: String)
        fun onBell(session: TerminalSession)
        fun onClipboardSet(session: TerminalSession, text: String)
        fun onCwdChanged(session: TerminalSession, cwd: String)
        fun onExited(session: TerminalSession, exitCode: Int)
        fun onVncRequest(session: TerminalSession, args: Map<String, String>)
        fun onTermCommand(session: TerminalSession, name: String, args: Map<String, String>)
    }

    val emulator = TerminalEmulator(
        cols, rows, this,
        theme = TermTheme.create(paths),
        maxScrollback = scrollback
    )

    val startedAt: Long = SystemClock.elapsedRealtime()

    @Volatile
    var title: String = "Session $id"
        private set

    @Volatile
    var cwd: String = paths.home.absolutePath
        private set

    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    private var ptyFd = -1

    @Volatile
    private var pid = -1

    // Every process started from this session inherits this value, which lets
    // killRemnants() find daemons that detached from the shell.
    private val token: String = UUID.randomUUID().toString()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val updatePosted = AtomicBoolean(false)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "term-writer-$id").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = ptyFd >= 0 && exitCode == null

    fun start(command: String? = null, args: List<String> = emptyList(), workDir: String? = null) {
        check(ptyFd < 0) { "Session already started" }

        val bashReady = paths.bash.canExecute()
        val executable = command ?: if (bashReady) paths.bash.absolutePath else "/system/bin/sh"
        val name = File(executable).name
        val argv: Array<String> = when {
            command != null -> arrayOf(name) + args
            bashReady -> arrayOf(name, "--rcfile", paths.systemBashrc.absolutePath)
            else -> arrayOf(name)
        }
        val dir = workDir ?: paths.home.absolutePath
        File(dir).mkdirs()

        val environment = TermEnvironment.build(paths) + "TERM_SESSION_ID=$token"

        val outPid = IntArray(1)
        val fd = NativePty.open(
            executable, argv, environment, dir,
            emulator.rows, emulator.cols, outPid
        )
        pid = outPid[0]
        ptyFd = fd
        NativePty.setSize(
            fd, emulator.rows, emulator.cols,
            emulator.cols * emulator.cellWidthPx, emulator.rows * emulator.cellHeightPx
        )

        Thread({ readLoop(fd) }, "term-reader-$id").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(fd: Int) {
        val buffer = ByteArray(16384)
        while (true) {
            val n = NativePty.read(fd, buffer, 0, buffer.size)
            if (n <= 0) {
                break
            }
            emulator.feed(buffer, 0, n)
            if (updatePosted.compareAndSet(false, true)) {
                mainHandler.post {
                    updatePosted.set(false)
                    listener.onScreenChanged(this)
                }
            }
        }
        val code = NativePty.waitFor(pid)
        ptyFd = -1
        NativePty.close(fd)
        exitCode = code
        killRemnants()
        writer.shutdown()
        mainHandler.post { listener.onExited(this, code) }
    }

    fun reloadTheme() {
        synchronized(emulator.lock) {
            TermTheme.apply(paths, emulator.theme)
        }
    }

    fun write(data: ByteArray) {
        val fd = ptyFd
        if (fd < 0 || data.isEmpty()) {
            return
        }
        writer.execute { NativePty.write(fd, data, 0, data.size) }
    }

    fun writeText(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    fun resize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        emulator.setCellSize(cellWidthPx, cellHeightPx)
        emulator.resize(cols, rows)
        val fd = ptyFd
        if (fd >= 0) {
            NativePty.setSize(fd, rows, cols, cols * cellWidthPx, rows * cellHeightPx)
        }
    }

    fun terminate() {
        val p = pid
        if (p > 0 && exitCode == null) {
            NativePty.kill(p, 1)
        }
    }

    fun kill() {
        val p = pid
        if (p > 0 && exitCode == null) {
            NativePty.kill(p, 9)
        }
        killRemnants()
    }

    // Kills every process that was started from this session, including daemons.
    fun killRemnants() {
        NativePty.killSession(token)
    }

    override fun onWriteToPty(data: ByteArray) {
        write(data)
    }

    override fun onTitleChanged(title: String) {
        this.title = title
        mainHandler.post { listener.onTitleChanged(this, title) }
    }

    override fun onBell() {
        mainHandler.post { listener.onBell(this) }
    }

    override fun onClipboardSet(text: String) {
        mainHandler.post { listener.onClipboardSet(this, text) }
    }

    override fun onVncRequest(args: Map<String, String>) {
        mainHandler.post { listener.onVncRequest(this, args) }
    }

    override fun onTermCommand(name: String, args: Map<String, String>) {
        mainHandler.post { listener.onTermCommand(this, name, args) }
    }

    override fun onCwdChanged(cwd: String) {
        this.cwd = cwd
        mainHandler.post { listener.onCwdChanged(this, cwd) }
    }
}