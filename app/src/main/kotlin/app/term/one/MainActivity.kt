package app.term.one

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import app.term.one.core.CombineResult
import app.term.one.core.SessionManager
import app.term.one.core.TerminalSession
import app.term.one.ui.ExtraKeysConfig
import app.term.one.ui.ExtraKeysView
import app.term.one.ui.KeyModifiers
import app.term.one.ui.SessionTabBar
import app.term.one.ui.SplitContainer
import app.term.one.ui.TerminalView
import app.term.one.ui.BootstrapView
import app.term.one.ui.VncView
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(),
    SessionManager.Listener,
    ServiceConnection,
    SessionTabBar.Listener,
    ExtraKeysView.Listener,
    TerminalView.Listener,
    VncView.Host {

    private class PasswordAnswer(val value: String?)

    private lateinit var paths: TermPaths
    private lateinit var settings: TermSettings
    private lateinit var root: LinearLayout
    private lateinit var tabBar: SessionTabBar
    private lateinit var split: SplitContainer
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var terminalFrame: FrameLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var tabArea: FrameLayout

    private val modifiers = KeyModifiers()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: SessionManager? = null
    private var bound = false
    private var selectionOwner: TerminalView? = null
    private var fontSizeSp = 14

    private var vncView: VncView? = null
    private var vncVisible = false
    private var vncTarget: String? = null

    private lateinit var bootstrapView: BootstrapView
    private var bootstrapRunning = false
    private var bootstrapSkipped = false

    private val dp: Float get() = resources.displayMetrics.density

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingStorageToken: String? = null

    private val intents by lazy { TermIntents(this, paths) { snack(it) } }
    private val pendingRequests = ArrayDeque<Pair<String, Map<String, String>>>()
    private var requestShowing = false

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val token = pendingStorageToken
            pendingStorageToken = null
            if (token != null && results.values.all { it }) {
                finishStorageSetup(token)
            } else {
                snack("Storage permission was denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = TermPaths.get(this)
        paths.ensureDirectories()
        TermConfig.writeDefaults(paths)
        TermScripts.install(this, paths)
        settings = TermSettings.load(paths.termProperties)
        fontSizeSp = settings.fontSizeSp

        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        applyConfig()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val owner = selectionOwner
                if (owner != null && owner.hasSelection()) {
                    owner.clearSelection()
                } else if (vncVisible) {
                    hideVnc()
                } else {
                    moveTaskToBack(true)
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val intent = Intent(this, TermService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bound = bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        modifiers.releaseHeld()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (settings.volumeKeys &&
            (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            val key = if (code == KeyEvent.KEYCODE_VOLUME_DOWN) KeyModifiers.Key.CTRL else KeyModifiers.Key.ALT
            when (event.action) {
                KeyEvent.ACTION_DOWN -> modifiers.hold(key, true)
                KeyEvent.ACTION_UP -> modifiers.hold(key, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun reloadColors() {
        manager?.sessions?.forEach { it.reloadTheme() }
        split.allViews().forEach { it.invalidate() }
    }

    override fun onResume() {
        super.onResume()
        if (vncVisible) {
            vncView?.requestFocus()
        } else {
            focusedView()?.requestFocus()
        }
    }

    override fun onDestroy() {
        vncView?.disconnect()
        vncView = null
        manager?.removeListener(this)
        if (bound) {
            unbindService(this)
            bound = false
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun createTerminalView(): TerminalView =
        TerminalView(this, modifiers).also {
            it.listener = this
            it.configure(settings, paths.termFont)
            it.setFontSize(fontSizeSp)
        }

    private fun focusedView(): TerminalView? = manager?.current?.let { split.viewFor(it) }

    private fun activeVnc(): VncView? = if (vncVisible) vncView else null

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        tabBar = SessionTabBar(this).also { it.listener = this }
        split = SplitContainer(this) { createTerminalView() }
        extraKeys = ExtraKeysView(this, modifiers).also { it.listener = this }
        selectionBar = buildSelectionBar()
        tabArea = FrameLayout(this).apply {
            addView(
                tabBar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                selectionBar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        terminalFrame = FrameLayout(this).apply {
            addView(
                split,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        bootstrapView = BootstrapView(this).apply {
            visibility = View.GONE
            onRetry = { runBootstrap() }
            onSkip = {
                bootstrapSkipped = true
                visibility = View.GONE
                createSession()
            }
        }
        terminalFrame.addView(
            bootstrapView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun arrange() {
        root.removeAllViews()
        val tabParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * dp).toInt())
        val termParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        val keyParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (settings.sessionTabsTop) {
            root.addView(tabArea, tabParams)
            root.addView(terminalFrame, termParams)
            root.addView(extraKeys, keyParams)
        } else {
            root.addView(terminalFrame, termParams)
            root.addView(tabArea, tabParams)
            root.addView(extraKeys, keyParams)
        }
    }

    private fun buildSelectionBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        fun action(label: String, onClick: () -> Unit) {
            val view = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            }
            bar.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        action("Copy") { copySelection() }
        action("Select all") { selectionOwner?.selectAll() }
        action("Cancel") { selectionOwner?.clearSelection() }
        return bar
    }

    private fun applyConfig() {
        settings = TermSettings.load(paths.termProperties)
        fontSizeSp = settings.fontSizeSp
        split.allViews().forEach {
            it.configure(settings, paths.termFont)
            it.setFontSize(fontSizeSp)
        }
        extraKeys.setLayout(ExtraKeysConfig.load(paths.extraKeysProperties))
        reloadColors()
        arrange()
    }

    private fun snack(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT)
            .setAnchorView(extraKeys)
            .show()
    }

    // ------------------------------------------------------------------ service

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val service = (binder as TermService.LocalBinder).getService()
        val m = service.manager
        manager = m
        m.addListener(this)
        if (m.sessions.isEmpty()) {
            createSession()
        } else {
            refreshTabs()
            showCurrent()
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        manager?.removeListener(this)
        manager = null
    }

    private fun createSession() {
        val m = manager ?: return
        if (!paths.isBootstrapped() && !bootstrapSkipped) {
            runBootstrap()
            return
        }
        hideVnc()
        val view = focusedView()
        val cols = view?.currentColumns() ?: 80
        val rows = view?.currentRows() ?: 24
        try {
            m.createSession(cols, rows, settings.scrollback)
        } catch (e: RuntimeException) {
            snack("Unable to start session: ${e.message}")
        }
    }

    private fun runBootstrap() {
        if (bootstrapRunning) {
            return
        }
        bootstrapRunning = true
        bootstrapView.visibility = View.VISIBLE
        bootstrapView.showProgress("Preparing", -1)
        Thread({
            var failure: String? = null
            try {
                Bootstrapper(applicationContext, paths).install(object : Bootstrapper.Progress {
                    override fun onProgress(stage: String, percent: Int) {
                        runOnUiThread { bootstrapView.showProgress(stage, percent) }
                    }
                })
            } catch (e: BootstrapException) {
                failure = e.message ?: "Bootstrap failed"
            }
            val message = failure
            runOnUiThread {
                bootstrapRunning = false
                if (message == null) {
                    TermScripts.install(this, paths)
                    TermShellFiles.install(this, paths)
                    bootstrapView.visibility = View.GONE
                    createSession()
                } else {
                    bootstrapView.showError(message)
                }
            }
        }, "term-bootstrap").apply {
            isDaemon = true
            start()
        }
    }

    private fun refreshTabs() {
        val m = manager ?: return
        tabBar.update(m.sessions, m.current, { m.displayName(it) }, { m.groupOf(it)?.colorIndex })
    }

    private fun showCurrent() {
        val m = manager ?: return
        split.prune(m.sessions.map { it.id }.toSet())
        val current = m.current
        if (current == null) {
            split.show(emptyList(), null)
            return
        }
        split.show(m.displayedFor(current), current)
        if (!vncVisible) {
            split.viewFor(current)?.requestFocus()
        }
    }

    // ------------------------------------------------------------------ SessionManager.Listener

    override fun onSessionsChanged() {
        refreshTabs()
    }

    override fun onCurrentChanged(session: TerminalSession?) {
        showCurrent()
    }

    override fun onLayoutChanged() {
        showCurrent()
    }

    override fun onSessionScreen(session: TerminalSession) {
        split.viewFor(session)?.onScreenUpdated()
    }

    override fun onSessionBell(session: TerminalSession) {
        ringBell()
    }

    override fun onSessionClipboard(session: TerminalSession, text: String) {
        TermClipboard.set(paths, text)
        snack("Term clipboard updated by application")
    }

    override fun onAllSessionsClosed() {
        stopService(Intent(this, TermService::class.java))
        finishAndRemoveTask()
    }

    override fun onTermCommand(session: TerminalSession, name: String, args: Map<String, String>) {
        when (name) {
            "storage" -> setupStorage(args["token"].orEmpty())
            "open", "share" -> enqueueRequest(name, args)
            "theme" -> reloadColors()
        }
    }

    override fun onStart() {
        super.onStart()
        processRequests()
    }

    private fun enqueueRequest(name: String, args: Map<String, String>) {
        if (pendingRequests.size >= 5) {
            return
        }
        pendingRequests.addLast(Pair(name, args))
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            processRequests()
        }
    }

    private fun processRequests() {
        if (requestShowing) {
            return
        }
        val next = pendingRequests.removeFirstOrNull() ?: return
        requestShowing = true
        val finished = {
            requestShowing = false
            processRequests()
        }
        when (next.first) {
            "open" -> intents.open(next.second, finished)
            "share" -> intents.share(next.second, finished)
            else -> finished()
        }
    }

    private fun setupStorage(token: String) {
        if (token.isEmpty()) {
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            finishStorageSetup(token)
        } else {
            pendingStorageToken = token
            storagePermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun finishStorageSetup(token: String) {
        try {
            val count = TermStorage.setup(this, paths, token)
            snack("Storage ready: $count folders linked in ~/storage")
        } catch (e: android.system.ErrnoException) {
            snack("Unable to set up storage: ${e.message}")
        } catch (e: java.io.IOException) {
            snack("Unable to set up storage: ${e.message}")
        }
    }

    override fun onVncRequest(session: TerminalSession, args: Map<String, String>) {
        when (args["action"]) {
            "open" -> {
                val hostName = args["host"]?.takeIf { it.isNotEmpty() } ?: "127.0.0.1"
                val port = args["port"]?.toIntOrNull() ?: 5901
                if (port !in 1..65535) {
                    snack("Invalid VNC port: $port")
                    return
                }
                val target = "$hostName:$port"
                if (vncView != null && vncTarget == target) {
                    showVnc()
                    return
                }
                if (isLoopback(hostName)) {
                    openVnc(hostName, port)
                } else {
                    confirmRemoteVnc(hostName, port)
                }
            }
            "close" -> {
                if (vncView != null) {
                    closeVnc("VNC disconnected")
                }
            }
        }
    }

    // ------------------------------------------------------------------ VNC

    private fun isLoopback(hostName: String): Boolean =
        hostName == "127.0.0.1" || hostName == "localhost" || hostName == "::1"

    private fun confirmRemoteVnc(hostName: String, port: Int) {
        AlertDialog.Builder(this)
            .setTitle("Connect to remote VNC server?")
            .setMessage("A program in the terminal asked to open $hostName:$port. Only continue if you started this yourself.")
            .setPositiveButton("Connect") { _, _ -> openVnc(hostName, port) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openVnc(hostName: String, port: Int) {
        if (vncView != null) {
            closeVnc(null)
        }
        val view = VncView(this, modifiers)
        view.host = this
        vncView = view
        vncTarget = "$hostName:$port"
        terminalFrame.addView(
            view,
            1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        view.connect(hostName, port)
        showVnc()
    }

    private fun showVnc() {
        val view = vncView ?: return
        selectionOwner?.clearSelection()
        view.visibility = View.VISIBLE
        split.visibility = View.INVISIBLE
        vncVisible = true
        extraKeys.showPage(1)
        view.requestFocus()
    }

    private fun hideVnc() {
        val view = vncView ?: return
        if (!vncVisible) {
            return
        }
        view.visibility = View.GONE
        split.visibility = View.VISIBLE
        vncVisible = false
        extraKeys.showPage(0)
        focusedView()?.requestFocus()
    }

    private fun closeVnc(message: String?) {
        val view = vncView ?: return
        view.disconnect()
        terminalFrame.removeView(view)
        vncView = null
        vncTarget = null
        vncVisible = false
        split.visibility = View.VISIBLE
        extraKeys.showPage(0)
        focusedView()?.requestFocus()
        if (message != null) {
            snack(message)
        }
    }

    override fun requestPassword(): String? {
        val answer = ArrayBlockingQueue<PasswordAnswer>(1)
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                answer.offer(PasswordAnswer(null))
                return@runOnUiThread
            }
            val input = EditText(this).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "VNC password"
            }
            val container = FrameLayout(this).apply {
                val pad = (20 * dp).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            AlertDialog.Builder(this)
                .setTitle("VNC password")
                .setView(container)
                .setPositiveButton("Connect") { _, _ -> answer.offer(PasswordAnswer(input.text.toString())) }
                .setNegativeButton("Cancel") { _, _ -> answer.offer(PasswordAnswer(null)) }
                .setOnCancelListener { answer.offer(PasswordAnswer(null)) }
                .show()
        }
        return try {
            answer.poll(120, TimeUnit.SECONDS)?.value
        } catch (e: InterruptedException) {
            null
        }
    }

    override fun onVncConnected(name: String) {
        snack("Connected to $name")
    }

    override fun onVncClosed(reason: String?) {
        closeVnc(if (reason.isNullOrEmpty()) "VNC disconnected" else "VNC disconnected: $reason")
    }

    override fun onVncServerText(text: String) {
        TermClipboard.set(paths, text)
        snack("Remote clipboard copied to Term clipboard")
    }

    override fun onVncBell() {
        ringBell()
    }

    // ------------------------------------------------------------------ tab bar

    override fun onTabSelected(session: TerminalSession) {
        hideVnc()
        manager?.select(session)
    }

    override fun onTabLongPressed(session: TerminalSession, anchor: View) {
        val m = manager ?: return
        val group = m.groupOf(session)
        PopupMenu(this, anchor).apply {
            if (group == null && m.combineTargets(session).isNotEmpty()) {
                menu.add(0, MENU_COMBINE, 0, "Combine with...")
            }
            if (group != null && group.owner === session) {
                menu.add(0, MENU_EJECT_ALL, 1, "Eject all sessions")
            }
            if (group != null && group.owner !== session) {
                menu.add(0, MENU_EJECT, 1, "Eject from group")
            }
            menu.add(0, MENU_RENAME, 2, "Rename")
            menu.add(0, MENU_CLOSE, 3, "Close")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_COMBINE -> mainHandler.post { promptCombine(session) }
                    MENU_EJECT_ALL -> {
                        if (m.ejectAll(session)) {
                            snack("All sessions ejected from session ${indexOf(session)}")
                        }
                    }
                    MENU_EJECT -> {
                        if (m.eject(session)) {
                            snack("Session ${indexOf(session)} ejected")
                        }
                    }
                    MENU_RENAME -> promptRename(session)
                    MENU_CLOSE -> m.close(session)
                }
                true
            }
            show()
        }
    }

    private fun indexOf(session: TerminalSession): Int = (manager?.sessions?.indexOf(session) ?: -1) + 1

    private fun promptCombine(session: TerminalSession) {
        val m = manager ?: return
        val targets = m.combineTargets(session)
        if (targets.isEmpty()) {
            snack("No session available to combine with")
            return
        }
        PopupMenu(this, tabBar).apply {
            targets.forEachIndexed { i, target ->
                val suffix = if (m.groupOf(target) != null) " (group of ${m.groupSize(target)})" else ""
                menu.add(0, i, i, "${indexOf(target)}: ${m.displayName(target)}$suffix")
            }
            setOnMenuItemClickListener { item ->
                doCombine(session, targets[item.itemId])
                true
            }
            show()
        }
    }

    private fun doCombine(session: TerminalSession, target: TerminalSession) {
        val m = manager ?: return
        when (m.combine(session, target)) {
            CombineResult.OK ->
                snack("Session ${indexOf(session)} combined into session ${indexOf(target)}")
            CombineResult.GROUP_FULL ->
                snack("A group can hold at most ${SessionManager.MAX_GROUP} sessions")
            CombineResult.INVALID ->
                snack("Unable to combine these sessions")
        }
    }

    override fun onNewSession() {
        createSession()
    }

    override fun onMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_NEW, 0, "New session")
            menu.add(0, MENU_PASTE, 1, "Paste (system clipboard)")
            menu.add(0, MENU_PASTE_TERM, 2, "Paste (Term clipboard)")
            menu.add(0, MENU_FONT_UP, 3, "Increase font size")
            menu.add(0, MENU_FONT_DOWN, 4, "Decrease font size")
            menu.add(0, MENU_RELOAD, 5, "Reload config")
            if (vncView != null && !vncVisible) {
                menu.add(0, MENU_VNC_SHOW, 6, "Show VNC viewer")
            }
            if (vncView != null) {
                menu.add(0, MENU_VNC_CLOSE, 7, "Disconnect VNC")
            }
            menu.add(0, MENU_IME_ENABLE, 8, "Enable Term clipboard keyboard")
            menu.add(0, MENU_IME_PICK, 9, "Switch keyboard")
            menu.add(0, MENU_REBOOTSTRAP, 10, "Reinstall bootstrap")
            menu.add(0, MENU_EXIT, 11, "Exit and close all sessions")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_NEW -> createSession()
                    MENU_PASTE -> pasteSystem()
                    MENU_PASTE_TERM -> pasteTerm()
                    MENU_FONT_UP -> changeFont(1)
                    MENU_FONT_DOWN -> changeFont(-1)
                    MENU_RELOAD -> {
                        applyConfig()
                        refreshTabs()
                        snack("Configuration reloaded")
                    }
                    MENU_VNC_SHOW -> showVnc()
                    MENU_VNC_CLOSE -> closeVnc("VNC disconnected")
                    MENU_IME_ENABLE -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    MENU_IME_PICK -> {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    }
                    MENU_REBOOTSTRAP -> confirmRebootstrap()
                    MENU_EXIT -> manager?.closeAll()
                }
                true
            }
            show()
        }
    }

    private fun confirmRebootstrap() {
        AlertDialog.Builder(this)
            .setTitle("Reinstall bootstrap?")
            .setMessage("This deletes everything under the prefix, including packages you installed, and downloads a fresh bootstrap. Your home folder is kept.")
            .setPositiveButton("Reinstall") { _, _ -> runBootstrap() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun changeFont(delta: Int) {
        fontSizeSp = (fontSizeSp + delta).coerceIn(8, 40)
        split.allViews().forEach { it.setFontSize(fontSizeSp) }
    }

    private fun promptRename(session: TerminalSession) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(manager?.displayName(session) ?: "")
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * dp).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename session")
            .setView(container)
            .setPositiveButton("OK") { _, _ -> manager?.rename(session, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ extra keys

    override fun onKeyCode(keyCode: Int) {
        val vnc = activeVnc()
        if (vnc != null) {
            vnc.sendKeyCode(keyCode)
        } else {
            focusedView()?.sendKeyCode(keyCode)
        }
    }

    override fun onText(text: String) {
        val vnc = activeVnc()
        if (vnc != null) {
            vnc.sendText(text)
        } else {
            focusedView()?.sendText(text)
        }
    }

    override fun onVnc(token: String) {
        val vnc = vncView
        if (vnc == null) {
            snack("VNC viewer is not running. Start it with terminal-vnc.")
            return
        }
        if (!vncVisible) {
            showVnc()
            return
        }
        when (token) {
            "VNC_LEFT_CLICK" -> vnc.click(1)
            "VNC_RIGHT_CLICK" -> vnc.click(4)
            "VNC_MIDDLE_CLICK" -> vnc.click(2)
            "VNC_SCROLL_UP" -> vnc.scroll(true)
            "VNC_SCROLL_DOWN" -> vnc.scroll(false)
            "VNC_KEYBOARD" -> toggleKeyboard()
            "VNC_FIT" -> snack(if (vnc.toggleFit()) "Fit to screen" else "Actual size (1:1)")
            "VNC_CLOSE" -> closeVnc("VNC disconnected")
            else -> snack("Unknown VNC key: $token")
        }
    }

    override fun onToggleKeyboard() {
        toggleKeyboard()
    }

    private fun toggleKeyboard() {
        val visible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val vnc = activeVnc()
        if (vnc != null) {
            if (visible) vnc.hideKeyboard() else vnc.showKeyboard()
            return
        }
        val view = focusedView() ?: return
        if (visible) view.hideKeyboard() else view.showKeyboard()
    }

    override fun onPaste() {
        pasteSystem()
    }

    // ------------------------------------------------------------------ TerminalView.Listener

    override fun onSelectionChanged(view: TerminalView, active: Boolean) {
        if (active) {
            selectionOwner = view
            selectionBar.visibility = View.VISIBLE
        } else if (selectionOwner === view) {
            selectionOwner = null
            selectionBar.visibility = View.GONE
        }
    }

    override fun onZoom(sizeSp: Int) {
        fontSizeSp = sizeSp.coerceIn(8, 40)
        split.allViews().forEach { it.setFontSize(fontSizeSp) }
    }

    override fun onExitedEnter(session: TerminalSession) {
        manager?.close(session)
    }

    override fun onPaneTouched(view: TerminalView) {
        split.allViews().forEach {
            if (it !== view && it.hasSelection()) {
                it.clearSelection()
            }
        }
        val session = view.attachedSession ?: return
        manager?.select(session)
    }

    // ------------------------------------------------------------------ clipboard

    private fun copySelection() {
        val owner = selectionOwner ?: return
        val text = owner.selectedText()
        if (text.isNotEmpty()) {
            TermClipboard.set(paths, text)
            snack("Copied ${text.length} characters to Term clipboard")
        }
        owner.clearSelection()
    }

    private fun deliverPaste(text: String) {
        val vnc = activeVnc()
        if (vnc != null) {
            vnc.sendText(text)
        } else {
            focusedView()?.paste(text)
        }
    }

    private fun pasteSystem() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrEmpty()) {
            snack("System clipboard is empty")
        } else {
            deliverPaste(text)
        }
    }

    private fun pasteTerm() {
        val text = TermClipboard.get(paths)
        if (text.isEmpty()) {
            snack("Term clipboard is empty")
        } else {
            deliverPaste(text)
        }
    }

    // ------------------------------------------------------------------ bell

    private fun ringBell() {
        when (settings.bell) {
            "vibrate" -> vibrate()
            "beep" -> beep()
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        mainHandler.postDelayed({ tone.release() }, 300)
    }

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_CLOSE = 2
        const val MENU_COMBINE = 3
        const val MENU_EJECT = 4
        const val MENU_EJECT_ALL = 5
        const val MENU_NEW = 10
        const val MENU_PASTE = 11
        const val MENU_PASTE_TERM = 12
        const val MENU_FONT_UP = 13
        const val MENU_FONT_DOWN = 14
        const val MENU_RELOAD = 15
        const val MENU_EXIT = 16
        const val MENU_IME_ENABLE = 17
        const val MENU_IME_PICK = 18
        const val MENU_VNC_SHOW = 19
        const val MENU_VNC_CLOSE = 20
        const val MENU_REBOOTSTRAP = 21
    }
}