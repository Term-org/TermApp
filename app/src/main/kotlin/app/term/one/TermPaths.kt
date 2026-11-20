package app.term.one

import android.content.Context
import java.io.File

class TermPaths private constructor(context: Context) {

    val appId: String = context.packageName

    // context.filesDir creates and returns the private internal storage of this app.
    // Android reports it as /data/user/0/<pkg>/files, but compiled binaries refer to
    // /data/data/<pkg>/files, so the path is normalised to keep both identical.
    val filesDir: File = canonicalFilesDir(context)

    val rootDir: File = File(filesDir, "rootdir")
    val prefix: File = File(rootDir, "usr")
    val stagingPrefix: File = File(rootDir, "usr-staging")
    val home: File = File(rootDir, "home")
    val tmp: File = File(filesDir, "temp")
    val docs: File = File(filesDir, "docs")
    val configDir: File = File(home, ".term")
    val systemDir: File = File(rootDir, "system")
    val systemBashrc: File = File(systemDir, "bashrc")
    val systemMkshrc: File = File(systemDir, "mkshrc")

    val bin: File = File(prefix, "bin")
    val lib: File = File(prefix, "lib")
    val etc: File = File(prefix, "etc")
    val bash: File = File(bin, "bash")

    val termProperties: File = File(configDir, "term.properties")
    val termFont: File = File(configDir, "term.ttf")
    val extraKeysProperties: File = File(configDir, "extrakeys.properties")
    val colorsProperties: File = File(configDir, "colors.properties")

    val bootstrapMarker: File = File(rootDir, ".bootstrap-complete")

    fun isBootstrapped(): Boolean = bootstrapMarker.isFile && bash.canExecute()

    fun ensureDirectories() {
        listOf(rootDir, home, tmp, docs, configDir, systemDir).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: TermPaths? = null

        fun get(context: Context): TermPaths {
            return instance ?: synchronized(this) {
                instance ?: TermPaths(context.applicationContext).also { instance = it }
            }
        }

        private fun canonicalFilesDir(context: Context): File {
            val raw = context.filesDir.absolutePath
            val userPrefix = "/data/user/0/"
            return if (raw.startsWith(userPrefix)) {
                File("/data/data/" + raw.removePrefix(userPrefix))
            } else {
                File(raw)
            }
        }
    }
}