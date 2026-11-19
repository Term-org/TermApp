package app.term.one

import android.content.Context
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

object TermStorage {

    // Links the shared folders into ~/storage and writes the token the term-storage
    // command is waiting for. Returns the number of links that were created.
    @Throws(ErrnoException::class)
    fun setup(context: Context, paths: TermPaths, token: String): Int {
        val dir = File(paths.home, "storage")
        dir.mkdirs()

        val links = linkedMapOf(
            "shared" to Environment.getExternalStorageDirectory(),
            "downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "dcim" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        )

        var count = 0
        for ((name, target) in links) {
            target.mkdirs()
            if (link(File(dir, name), target)) {
                count++
            }
        }

        val removable = context.getExternalFilesDirs(null)
        for (i in 1 until removable.size) {
            val target = removable[i] ?: continue
            if (link(File(dir, "external-$i"), target)) {
                count++
            }
        }

        File(dir, ".token").writeText(token)
        return count
    }

    // Replaces an existing symbolic link; anything else at that path is left alone.
    @Throws(ErrnoException::class)
    private fun link(link: File, target: File): Boolean {
        val mode = try {
            Os.lstat(link.path).st_mode
        } catch (e: ErrnoException) {
            -1
        }
        if (mode != -1) {
            if (!OsConstants.S_ISLNK(mode)) {
                return false
            }
            link.delete()
        }
        Os.symlink(target.absolutePath, link.path)
        return true
    }
}