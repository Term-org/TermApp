package app.term.one

import android.content.Context
import java.io.File
import java.io.IOException

object TermScripts {

    private const val ASSET_DIR = "bin"

    fun install(context: Context, paths: TermPaths) {
        if (!paths.isBootstrapped()) {
            return
        }
        val names = try {
            context.assets.list(ASSET_DIR)
        } catch (e: IOException) {
            null
        } ?: return

        paths.bin.mkdirs()
        val header = "#!${paths.bash.absolutePath}\n".toByteArray(Charsets.UTF_8)
        for (name in names) {
            try {
                val body = context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                val data = header + body
                val target = File(paths.bin, name)
                if (!target.isFile || !target.readBytes().contentEquals(data)) {
                    target.writeBytes(data)
                }
                target.setReadable(true, false)
                target.setExecutable(true, false)
            } catch (e: IOException) {
                continue
            }
        }
    }
}