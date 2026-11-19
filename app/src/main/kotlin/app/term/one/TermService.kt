package app.term.one

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.term.one.core.SessionManager
import app.term.one.core.TerminalSession

class TermService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): TermService = this@TermService
    }

    private val binder = LocalBinder()
    private lateinit var notificationManager: NotificationManager
    private lateinit var paths: TermPaths
    private lateinit var commands: TermCommands

    lateinit var manager: SessionManager
        private set

    private val actionExit: String get() = "$packageName.action.EXIT"

    private val managerListener = object : SessionManager.Listener {
        override fun onSessionsChanged() {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }

        override fun onAllSessionsClosed() {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        override fun onTermCommand(session: TerminalSession, name: String, args: Map<String, String>) {
            commands.handle(name, args)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        paths = TermPaths.get(this)
        commands = TermCommands(this, paths)
        manager = SessionManager(paths)
        manager.addListener(managerListener)
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == actionExit) {
            manager.closeAll()
            return START_NOT_STICKY
        }
        enterForeground()
        if (intent?.action == ACTION_RUN) {
            runSession(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        manager.removeListener(managerListener)
        manager.closeAll()
        super.onDestroy()
    }

    // Starts a visible session for a command that another app asked for (RUN_COMMAND).
    private fun runSession(intent: Intent) {
        val path = intent.getStringExtra(RunCommand.EXTRA_PATH) ?: return
        val args = intent.getStringArrayExtra(RunCommand.EXTRA_ARGUMENTS)?.toList() ?: emptyList()
        val dir = intent.getStringExtra(RunCommand.EXTRA_WORKDIR)
        val settings = TermSettings.load(paths.termProperties)
        try {
            manager.createSession(80, 24, settings.scrollback, path, args, dir)
        } catch (e: RuntimeException) {
            return
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Terminal sessions",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while terminal sessions are running"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun enterForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val count = manager.sessions.size
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exitIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TermService::class.java).setAction(actionExit),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (count == 1) "1 session running" else "$count sessions running"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_term)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stat_term, "Exit", exitIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_RUN = "app.term.one.action.RUN_SESSION"
        private const val CHANNEL_ID = "term_sessions"
        private const val NOTIFICATION_ID = 1
    }
}