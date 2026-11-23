package app.term.one

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException

object TermScripts {

    private const val ASSET_DIR = "bin"
    private const val MULTICALL = "termcli"

    // The commands of the termcli binary. Keep this list identical to COMMANDS in termcli/src/main.rs.
    val CLI_NAMES = listOf(
        "tn",
        "terminal-vnc",
        "term-storage",
        "term-open",
        "term-share",
        "term-notify",
        "term-clip",
        "term-wakelock",
        "term-shizuku",
        "term-api",
        "term-theme"
    )

    private val EXEC_MODE = "755".toInt(8)
    private val READ_ONLY_MODE = "400".toInt(8)

    fun install(context: Context, paths: TermPaths) {
        if (!paths.isBootstrapped()) {
            return
        }
        paths.bin.mkdirs()
        installAssets(context, paths)
        installCli(context, paths)
    }

    // Plain files from assets/bin: rish (a script) and rish_shizuku.dex.
    private fun installAssets(context: Context, paths: TermPaths) {
        val names = try {
            context.assets.list(ASSET_DIR)
        } catch (e: IOException) {
            null
        } ?: return

        val header = "#!${paths.bash.absolutePath}\n".toByteArray(Charsets.UTF_8)
        for (name in names) {
            try {
                val body = context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                val isDex = name.endsWith(".dex")
                val data = if (isDex) body else header + body
                val target = File(paths.bin, name)
                if (!target.isFile || !target.readBytes().contentEquals(data)) {
                    // A read-only file cannot be overwritten, so it is replaced.
                    target.delete()
                    target.writeBytes(data)
                }
                if (isDex) {
                    // Android 14 and later refuse to load a dex file that is writable.
                    Os.chmod(target.path, READ_ONLY_MODE)
                } else {
                    Os.chmod(target.path, EXEC_MODE)
                }
            } catch (e: IOException) {
                continue
            } catch (e: ErrnoException) {
                continue
            }
        }
    }

    // One multi-call binary per ABI; every command is a symbolic link to it.
    private fun installCli(context: Context, paths: TermPaths) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: return
        try {
            val data = context.assets.open("bin-$abi/$MULTICALL").use { it.readBytes() }
            val target = File(paths.bin, MULTICALL)
            if (!target.isFile || target.length() != data.size.toLong() || !target.readBytes().contentEquals(data)) {
                // Written next to the target and renamed, so a running command is never overwritten.
                val fresh = File(paths.bin, "$MULTICALL.new")
                fresh.writeBytes(data)
                Os.chmod(fresh.path, EXEC_MODE)
                if (!fresh.renameTo(target)) {
                    fresh.delete()
                    return
                }
            }
            Os.chmod(target.path, EXEC_MODE)
            for (name in CLI_NAMES) {
                link(File(paths.bin, name))
            }
        } catch (e: IOException) {
            return
        } catch (e: ErrnoException) {
            return
        }
    }

    // Replaces an old script (or a wrong link) with a link to the multi-call binary.
    private fun link(path: File) {
        val current = try {
            Os.readlink(path.path)
        } catch (e: ErrnoException) {
            null
        }
        if (current == MULTICALL) {
            return
        }
        path.delete()
        Os.symlink(MULTICALL, path.path)
    }
}