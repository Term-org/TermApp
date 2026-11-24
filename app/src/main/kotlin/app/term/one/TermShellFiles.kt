package app.term.one

import android.content.Context
import java.io.File
import java.io.IOException

object TermShellFiles {

    private val SYSTEM_ASSETS = mapOf(
        "system/bashrc" to "bashrc",
        "system/mkshrc" to "mkshrc",
        "system/completion.bash" to "completion.bash",
        "system/bootrc" to "bootrc"
    )

    // Headers written into the user files by older versions. Files that still start with
    // one of them are cleared so that the user files stay empty.
    private const val OLD_BASHRC_HEADER = "# ~/.bashrc - default interactive shell configuration"
    private const val OLD_PROFILE_HEADER = "# ~/.bash_profile - read by login shells"

    fun install(context: Context, paths: TermPaths) {
        paths.systemDir.mkdirs()
        for ((asset, name) in SYSTEM_ASSETS) {
            writeSystemFile(context, asset, File(paths.systemDir, name))
        }
        paths.home.mkdirs()
        prepareUserFile(File(paths.home, ".bashrc"), OLD_BASHRC_HEADER)
        prepareUserFile(File(paths.home, ".bash_profile"), OLD_PROFILE_HEADER)
    }

    private fun writeSystemFile(context: Context, asset: String, target: File) {
        try {
            val data = context.assets.open(asset).use { it.readBytes() }
            if (!target.isFile || !target.readBytes().contentEquals(data)) {
                target.writeBytes(data)
            }
            target.setReadable(true, false)
        } catch (e: IOException) {
            return
        }
    }

    private fun prepareUserFile(file: File, oldHeader: String) {
        try {
            if (!file.exists()) {
                file.createNewFile()
                return
            }
            val firstLine = file.bufferedReader().use { it.readLine() }
            if (firstLine != null && firstLine.startsWith(oldHeader)) {
                file.writeText("")
            }
        } catch (e: IOException) {
            return
        }
    }
}