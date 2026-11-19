package app.term.one

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException

// Handles the terminal commands that need no screen: notifications and the Term clipboard.
class TermCommands(private val context: Context, private val paths: TermPaths) {

    private val notifier = TermNotifier(context)

    fun handle(name: String, args: Map<String, String>) {
        when (name) {
            "notify" -> notifier.handle(args)
            "clip" -> clip(args)
        }
    }

    private fun clip(args: Map<String, String>) {
        when (args["action"]) {
            "set" -> {
                val path = args["path"].orEmpty()
                Thread({ consume(path) }, "term-clip").apply {
                    isDaemon = true
                    start()
                }
            }
            "clear" -> TermClipboard.clear(paths)
        }
    }

    // Reads the text from a temporary file written by term-clip and deletes the file.
    // The command waits until the file is gone, which is how it learns that the copy happened.
    private fun consume(path: String) {
        val file = try {
            File(path).canonicalFile
        } catch (e: IOException) {
            return
        }
        val tmp = try {
            paths.tmp.canonicalPath
        } catch (e: IOException) {
            return
        }
        if (!file.path.startsWith("$tmp/") || !file.isFile) {
            return
        }
        try {
            if (file.length() <= MAX_CLIP_BYTES) {
                TermClipboard.set(paths, file.readText())
            }
        } catch (e: IOException) {
            return
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val MAX_CLIP_BYTES = 20_000_000L
    }
}

class TermNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val recent = ArrayDeque<Long>()
    private var channelReady = false

    fun handle(args: Map<String, String>) {
        val key = args["id"].orEmpty().ifEmpty { "default" }.take(100)
        val id = ID_BASE + (key.hashCode() and 0xFFFFF)

        if (args["remove"] == "1") {
            manager.cancel(id)
            return
        }

        // A program that loops must not be able to flood the notification shade.
        val now = SystemClock.elapsedRealtime()
        while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) {
            recent.removeFirst()
        }
        if (recent.size >= MAX_PER_WINDOW) {
            return
        }
        recent.addLast(now)

        ensureChannel()
        val title = args["title"].orEmpty().ifEmpty { context.getString(R.string.app_name) }.take(200)
        val content = args["content"].orEmpty().take(4000)
        val ongoing = args["ongoing"] == "1"

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_term)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(id, notification)
    }

    private fun ensureChannel() {
        if (channelReady) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages from the terminal",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications created with term-notify"
        }
        manager.createNotificationChannel(channel)
        channelReady = true
    }

    private companion object {
        const val CHANNEL_ID = "term_notifications"
        const val ID_BASE = 1000
        const val WINDOW_MS = 10_000L
        const val MAX_PER_WINDOW = 10
    }
}