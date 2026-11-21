package app.term.one.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.concurrent.CopyOnWriteArrayList

// The connection to the TermAPI app. Everything here runs on the main thread.
object TermApiClient {

    const val PACKAGE = "app.term.api"
    const val SERVICE = "app.term.api.TermApiService"
    const val PERMISSION = "app.term.api.permission.CONNECT"
    const val RELEASES_URL = "https://github.com/Term-org/TermAPI/releases/latest"

    enum class State { NOT_INSTALLED, DISCONNECTED, CONNECTING, CONNECTED, DENIED }

    class Info(
        val versionName: String,
        val versionCode: Int,
        val apiLevel: Int,
        val shizuku: String
    )

    interface Listener {
        fun onTermApiChanged(state: State, info: Info?)
    }

    var state: State = State.DISCONNECTED
        private set

    var info: Info? = null
        private set

    private var appContext: Context? = null
    private var service: Messenger? = null
    private var bound = false
    private var nextId = 1
    private val pending = HashMap<Int, (Bundle?) -> Unit>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        onReply(message)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            request(ApiProtocol.MSG_PING, null, 8_000) { data ->
                when {
                    data == null -> {
                        unbindQuietly()
                        setState(State.DISCONNECTED, null)
                    }
                    data.containsKey(ApiProtocol.KEY_ERROR) -> {
                        unbindQuietly()
                        setState(State.DENIED, null)
                    }
                    else -> setState(
                        State.CONNECTED,
                        Info(
                            data.getString(ApiProtocol.KEY_VERSION_NAME).orEmpty(),
                            data.getInt(ApiProtocol.KEY_VERSION_CODE),
                            data.getInt(ApiProtocol.KEY_API_LEVEL),
                            data.getString(ApiProtocol.KEY_SHIZUKU).orEmpty()
                        )
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            lost()
        }

        override fun onBindingDied(name: ComponentName) {
            unbindQuietly()
            lost()
        }

        override fun onNullBinding(name: ComponentName) {
            unbindQuietly()
            lost()
        }
    }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun connect() {
        val context = appContext ?: return
        if (state == State.CONNECTED || state == State.CONNECTING) {
            return
        }
        if (!isInstalled(context)) {
            setState(State.NOT_INSTALLED, null)
            return
        }
        setState(State.CONNECTING, null)
        val intent = Intent().setComponent(ComponentName(PACKAGE, SERVICE))
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                unbindQuietly()
                setState(State.DISCONNECTED, null)
            }
        } catch (e: SecurityException) {
            unbindQuietly()
            setState(State.DENIED, null)
        }
    }

    fun disconnect() {
        unbindQuietly()
        service = null
        failPending()
        setState(State.DISCONNECTED, null)
    }

    // Sends a request. The callback gets the reply, or null when nothing came back in time.
    fun request(what: Int, data: Bundle?, timeoutMs: Long, callback: (Bundle?) -> Unit) {
        val target = service
        if (target == null) {
            main.post { callback(null) }
            return
        }
        val id = nextId++
        pending[id] = callback
        val message = Message.obtain(null, what, id, 0)
        message.replyTo = replies
        if (data != null) {
            message.data = data
        }
        try {
            target.send(message)
        } catch (e: RemoteException) {
            pending.remove(id)
            lost()
            main.post { callback(null) }
            return
        }
        main.postDelayed({ pending.remove(id)?.invoke(null) }, timeoutMs)
    }

    private fun onReply(message: Message) {
        val callback = pending.remove(message.arg1) ?: return
        callback(message.data)
    }

    private fun lost() {
        service = null
        failPending()
        if (state != State.DENIED && state != State.NOT_INSTALLED) {
            setState(State.DISCONNECTED, null)
        }
    }

    private fun failPending() {
        val callbacks = ArrayList(pending.values)
        pending.clear()
        callbacks.forEach { it(null) }
    }

    private fun unbindQuietly() {
        try {
            appContext?.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            bound = false
        }
        bound = false
    }

    private fun setState(newState: State, newInfo: Info?) {
        state = newState
        info = newInfo
        listeners.forEach { it.onTermApiChanged(newState, newInfo) }
    }
}