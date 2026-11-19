package app.term.one

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.term.one.core.NativePty
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

object RunCommand {
    const val EXTRA_PATH = "RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "RUN_COMMAND_BACKGROUND"
    const val EXTRA_TIMEOUT = "RUN_COMMAND_TIMEOUT_SECONDS"
    const val EXTRA_RESULT_PENDING_INTENT = "RUN_COMMAND_RESULT_PENDING_INTENT"

    const val RESULT_BUNDLE = "RESULT"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERROR = "errmsg"
}

class RunCommandService : Service() {

    private lateinit var paths: TermPaths

    override fun onCreate() {
        super.onCreate()
        paths = TermPaths.get(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Commands from other apps", NotificationManager.IMPORTANCE_LOW)
        )
        // Callers use startForegroundService(), so the notification must appear right away.
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread({
            try {
                execute(intent)
            } finally {
                stopSelf(startId)
            }
        }, "term-run-command").apply {
            isDaemon = true
            start()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_term)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Running a command for another app")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    // ------------------------------------------------------------------ request

    private fun execute(intent: Intent) {
        val result = intent.getParcelableExtra<PendingIntent>(RunCommand.EXTRA_RESULT_PENDING_INTENT)
        try {
            dispatch(intent, result)
        } catch (e: IOException) {
            reply(result, "", "", -1, e.message ?: "I/O error")
        } catch (e: RuntimeException) {
            reply(result, "", "", -1, e.message ?: "Unexpected error")
        }
    }

    private fun dispatch(intent: Intent, result: PendingIntent?) {
        val settings = TermSettings.load(paths.termProperties)
        if (!settings.allowExternalApps) {
            reply(
                result, "", "", -1,
                "External apps are not allowed. Set allow-external-apps=true in ~/.term/term.properties"
            )
            return
        }

        val path = intent.getStringExtra(RunCommand.EXTRA_PATH).orEmpty()
        val executable = File(path)
        if (!path.startsWith("/") || !executable.isFile || !executable.canExecute()) {
            reply(result, "", "", -1, "The program does not exist or is not executable: $path")
            return
        }
        if (!isInside(executable, paths.prefix) && !isInside(executable, paths.home)) {
            reply(result, "", "", -1, "The program must be inside the prefix or the home folder")
            return
        }

        val args = intent.getStringArrayExtra(RunCommand.EXTRA_ARGUMENTS) ?: emptyArray()
        if (args.size > MAX_ARGS || args.any { it.length > MAX_ARG_LENGTH }) {
            reply(result, "", "", -1, "Too many or too long arguments")
            return
        }

        val dir = File(intent.getStringExtra(RunCommand.EXTRA_WORKDIR) ?: paths.home.path)
        val dirAllowed = isInside(dir, paths.filesDir) || isInside(dir, Environment.getExternalStorageDirectory())
        if (!dir.isDirectory || !dirAllowed) {
            reply(result, "", "", -1, "Invalid working directory: ${dir.path}")
            return
        }

        if (intent.getBooleanExtra(RunCommand.EXTRA_BACKGROUND, true)) {
            val timeout = intent.getIntExtra(RunCommand.EXTRA_TIMEOUT, 300).coerceIn(1, 3600).toLong()
            runInBackground(executable, args, dir, timeout, result)
        } else {
            runInSession(executable, args, dir, result)
        }
    }

    // ------------------------------------------------------------------ foreground: a visible session

    private fun runInSession(executable: File, args: Array<String>, dir: File, result: PendingIntent?) {
        val forward = Intent(this, TermService::class.java)
            .setAction(TermService.ACTION_RUN)
            .putExtra(RunCommand.EXTRA_PATH, executable.absolutePath)
            .putExtra(RunCommand.EXTRA_ARGUMENTS, args)
            .putExtra(RunCommand.EXTRA_WORKDIR, dir.path)
        ContextCompat.startForegroundService(this, forward)
        reply(result, "", "", 0, null)
    }

    // ------------------------------------------------------------------ background: capture the output

    private fun runInBackground(
        executable: File,
        args: Array<String>,
        dir: File,
        timeoutSeconds: Long,
        result: PendingIntent?
    ) {
        val builder = ProcessBuilder(listOf(executable.absolutePath) + args.toList()).directory(dir)
        val environment = builder.environment()
        environment.clear()
        for (entry in TermEnvironment.build(paths)) {
            val index = entry.indexOf('=')
            if (index > 0) {
                environment[entry.substring(0, index)] = entry.substring(index + 1)
            }
        }
        val token = "run-" + UUID.randomUUID()
        environment["TERM_SESSION_ID"] = token

        val process = builder.start()
        process.outputStream.close()
        val out = Collector(process.inputStream)
        val err = Collector(process.errorStream)
        out.start()
        err.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            NativePty.killSession(token)
        }
        out.join(2000)
        err.join(2000)

        reply(
            result,
            out.text(),
            err.text(),
            if (finished) process.exitValue() else -1,
            if (finished) null else "Timed out after $timeoutSeconds seconds"
        )
    }

    private fun reply(result: PendingIntent?, stdout: String, stderr: String, exitCode: Int, error: String?) {
        if (result == null) {
            return
        }
        val bundle = Bundle().apply {
            putString(RunCommand.RESULT_STDOUT, stdout)
            putString(RunCommand.RESULT_STDERR, stderr)
            putInt(RunCommand.RESULT_EXIT_CODE, exitCode)
            putString(RunCommand.RESULT_ERROR, error ?: "")
        }
        try {
            result.send(this, 0, Intent().putExtra(RunCommand.RESULT_BUNDLE, bundle))
        } catch (e: PendingIntent.CanceledException) {
            return
        }
    }

    private fun isInside(file: File, root: File): Boolean = try {
        val base = root.canonicalPath
        val actual = file.canonicalPath
        actual == base || actual.startsWith("$base/")
    } catch (e: IOException) {
        false
    }

    private class Collector(private val input: InputStream) : Thread("term-run-output") {

        private val buffer = ByteArrayOutputStream()

        init {
            isDaemon = true
        }

        override fun run() {
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) {
                        break
                    }
                    val room = OUTPUT_LIMIT - buffer.size()
                    if (room > 0) {
                        buffer.write(chunk, 0, minOf(n, room))
                    }
                }
            } catch (e: IOException) {
                return
            }
        }

        fun text(): String = String(buffer.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val CHANNEL_ID = "term_commands"
        const val NOTIFICATION_ID = 2
        const val MAX_ARGS = 256
        const val MAX_ARG_LENGTH = 8192
        const val OUTPUT_LIMIT = 100_000
    }
}