package app.term.one

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import app.term.one.api.TermApiClient
import com.google.android.material.snackbar.Snackbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_REINSTALL = "reinstall-bootstrap"
    }
}

class SettingsFragment : PreferenceFragmentCompat(), TermApiClient.Listener {

    private lateinit var paths: TermPaths
    private var askedPermission = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            TermApiClient.connect()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        paths = TermPaths.get(context)
        TermApiClient.init(context)
        preferenceManager.preferenceDataStore = PropertiesDataStore(paths.termProperties)
        setPreferencesFromResource(R.xml.settings, rootKey)

        bindAbout()
        bindIcon()
        bindTermApi()
        bindTerminal()
        bindSystem()
    }

    override fun onStart() {
        super.onStart()
        TermApiClient.addListener(this)
        renderTermApi()
        renderShizuku()
    }

    override fun onStop() {
        TermApiClient.removeListener(this)
        super.onStop()
    }

    // ------------------------------------------------------------------ about and icon

    private fun bindAbout() {
        findPreference<Preference>("about_version")?.summary =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        findPreference<Preference>("about_id")?.summary = requireContext().packageName
        findPreference<Preference>("about_build")?.summary = if (BuildConfig.DEBUG) "debug" else "release"
        findPreference<Preference>("about_releases")?.setOnPreferenceClickListener {
            openUrl("https://github.com/Term-org/TermApp/releases/latest")
            true
        }
    }

    private fun bindIcon() {
        val list = findPreference<ListPreference>("app_icon") ?: return
        list.entries = TermIcons.VARIANTS.map { it.label }.toTypedArray()
        list.entryValues = TermIcons.VARIANTS.map { it.id }.toTypedArray()
        list.value = TermIcons.current(requireContext())
        list.setOnPreferenceChangeListener { _, newValue ->
            TermIcons.apply(requireContext(), newValue as String)
            notice("The launcher may take a few seconds to show the new icon")
            true
        }
    }

    // ------------------------------------------------------------------ TermAPI

    private fun bindTermApi() {
        findPreference<SwitchPreferenceCompat>("termapi")?.setOnPreferenceChangeListener { _, value ->
            if (value == true) {
                askedPermission = false
                TermApiClient.connect()
            } else {
                TermApiClient.disconnect()
            }
            true
        }
        findPreference<Preference>("termapi_open")?.setOnPreferenceClickListener {
            val context = requireContext()
            val launch = context.packageManager.getLaunchIntentForPackage(TermApiClient.PACKAGE)
            if (launch != null) {
                startActivity(launch)
            } else {
                openUrl(TermApiClient.RELEASES_URL)
            }
            true
        }
    }

    override fun onTermApiChanged(state: TermApiClient.State, info: TermApiClient.Info?) {
        // A debug build gets a runtime permission; a release build can only be granted by the
        // signature, and then the request simply fails and the summary explains why.
        if (state == TermApiClient.State.DENIED && !askedPermission) {
            askedPermission = true
            permissionLauncher.launch(TermApiClient.PERMISSION)
        }
        renderTermApi()
    }

    private fun renderTermApi() {
        val status = findPreference<Preference>("termapi_status") ?: return
        status.summary = when (TermApiClient.state) {
            TermApiClient.State.NOT_INSTALLED -> "TermAPI is not installed"
            TermApiClient.State.DISCONNECTED -> "Not connected"
            TermApiClient.State.CONNECTING -> "Connecting..."
            TermApiClient.State.CONNECTED -> TermApiClient.info?.let {
                "Connected: TermAPI ${it.versionName} (API ${it.apiLevel}), Shizuku ${it.shizuku}"
            } ?: "Connected"
            TermApiClient.State.DENIED ->
                "Not allowed: TermAPI and this app must be signed with the same key"
        }
    }

    // ------------------------------------------------------------------ terminal and system

    private fun bindTerminal() {
        findPreference<EditTextPreference>("scrollback")?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
        }
    }

    private fun bindSystem() {
        findPreference<Preference>("system_keyboard_enable")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            true
        }
        findPreference<Preference>("system_keyboard_pick")?.setOnPreferenceClickListener {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            true
        }
        findPreference<Preference>("system_shizuku")?.setOnPreferenceClickListener {
            when (TermShizuku.state()) {
                TermShizuku.State.NEEDS_PERMISSION -> TermShizuku.request()
                TermShizuku.State.READY -> notice("rish is already authorized")
                TermShizuku.State.DENIED ->
                    notice("Allow this app in the Shizuku app, under the authorized apps")
                TermShizuku.State.NOT_RUNNING ->
                    notice("Shizuku is not running. Start it in the Shizuku app")
                TermShizuku.State.UNSUPPORTED -> notice("Update the Shizuku app")
            }
            true
        }
        findPreference<Preference>("system_bootstrap")?.setOnPreferenceClickListener {
            val activity = requireActivity()
            activity.setResult(
                AppCompatActivity.RESULT_OK,
                Intent().putExtra(SettingsActivity.EXTRA_ACTION, SettingsActivity.ACTION_REINSTALL)
            )
            activity.finish()
            true
        }
    }

    private fun renderShizuku() {
        findPreference<Preference>("system_shizuku")?.summary = when (TermShizuku.state()) {
            TermShizuku.State.READY -> "rish is authorized"
            TermShizuku.State.NEEDS_PERMISSION -> "Shizuku is running. Tap to authorize rish"
            TermShizuku.State.DENIED -> "Authorization was denied"
            TermShizuku.State.NOT_RUNNING -> "Shizuku is not running"
            TermShizuku.State.UNSUPPORTED -> "Shizuku is too old"
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            notice("No app can open this link")
        }
    }

    private fun notice(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
    }
}