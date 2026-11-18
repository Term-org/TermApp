package app.term.one

import android.content.Context
import java.io.File
import java.io.IOException

object TermShellFiles {

    private val FILES = mapOf(
        "skel/bashrc" to ".bashrc",
        "skel/bash_profile" to ".bash_profile"
    )

    fun install(context: Context, paths: TermPaths) {
        paths.home.mkdirs()
        for ((asset, name) in FILES) {
            val target = File(paths.home, name)
            if (target.exists()) {
                continue
            }
            try {
                context.assets.open(asset).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                continue
            }
        }
    }
}