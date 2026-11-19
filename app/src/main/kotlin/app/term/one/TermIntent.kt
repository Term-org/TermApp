package app.term.one

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

// Opens and shares things on behalf of the terminal. Every request needs a confirmation,
// because any program that prints the right escape sequence can ask for it.
class TermIntents(
    private val activity: AppCompatActivity,
    private val paths: TermPaths,
    private val notice: (String) -> Unit
) {

    fun open(args: Map<String, String>, finished: () -> Unit) {
        val target = args["target"].orEmpty().trim()
        if (target.isEmpty()) {
            finished()
            return
        }
        val intent = try {
            buildOpenIntent(target, args["type"].orEmpty().trim())
        } catch (e: IllegalArgumentException) {
            notice(e.message ?: "Unable to open $target")
            finished()
            return
        }
        confirm("Open with another app?", "A program in the terminal wants to open:\n\n$target", "Open", finished) {
            launch(intent)
        }
    }

    fun share(args: Map<String, String>, finished: () -> Unit) {
        val kind = args["kind"].orEmpty()
        val path = args["path"].orEmpty().trim()
        val title = args["title"].orEmpty().trim().take(100)
        val type = args["type"].orEmpty().trim()
        if (path.isEmpty()) {
            finished()
            return
        }
        when (kind) {
            "text" -> shareText(path, title, finished)
            "file" -> shareFile(path, title, type, finished)
            else -> finished()
        }
    }

    // ------------------------------------------------------------------ open

    private fun buildOpenIntent(target: String, type: String): Intent {
        if (!target.startsWith("/")) {
            val uri = Uri.parse(target)
            val scheme = uri.scheme?.lowercase()
            if (scheme == null || scheme !in LINK_SCHEMES) {
                throw IllegalArgumentException("Unsupported link: $target")
            }
            return Intent(Intent.ACTION_VIEW, uri)
        }
        val file = checkedFile(target)
        val mime = if (isMime(type)) type else guessMime(file.name, "*/*")
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ------------------------------------------------------------------ share

    private fun shareText(path: String, title: String, finished: () -> Unit) {
        val file = tempFile(path)
        if (file == null) {
            finished()
            return
        }
        val cleanup = {
            file.delete()
            finished()
        }
        if (file.length() > MAX_TEXT_BYTES) {
            notice("The text is too large to share. Share it as a file instead.")
            cleanup()
            return
        }
        val text = try {
            file.readText()
        } catch (e: IOException) {
            notice("Unable to read the text")
            cleanup()
            return
        }
        val preview = if (text.length > 300) text.take(300) + "..." else text
        confirm("Share this text?", "A program in the terminal wants to share:\n\n$preview", "Share", cleanup) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
            launch(Intent.createChooser(send, title.ifEmpty { "Share" }))
        }
    }

    private fun shareFile(path: String, title: String, type: String, finished: () -> Unit) {
        val file = try {
            checkedFile(path)
        } catch (e: IllegalArgumentException) {
            notice(e.message ?: "Unable to share $path")
            finished()
            return
        }
        val uri = try {
            uriFor(file)
        } catch (e: IllegalArgumentException) {
            notice("This file cannot be shared")
            finished()
            return
        }
        val mime = if (isMime(type)) type else guessMime(file.name, "application/octet-stream")
        confirm("Share this file?", "A program in the terminal wants to share:\n\n${file.path}", "Share", finished) {
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            launch(Intent.createChooser(send, title.ifEmpty { "Share" }))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun confirm(
        title: String,
        message: String,
        positive: String,
        finished: () -> Unit,
        onConfirm: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            finished()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finished() }
            .show()
    }

    private fun launch(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            notice("No app can handle this")
        } catch (e: SecurityException) {
            notice("Access was denied. For shared storage run term-storage first.")
        }
    }

    private fun checkedFile(path: String): File {
        val file = try {
            File(path).canonicalFile
        } catch (e: IOException) {
            throw IllegalArgumentException("Invalid path: $path")
        }
        if (!file.isFile) {
            throw IllegalArgumentException("Not a file: $path")
        }
        val allowed = inside(file, activity.filesDir) || inside(file, Environment.getExternalStorageDirectory())
        if (!allowed) {
            throw IllegalArgumentException("Only files in the app storage or the shared storage can be used")
        }
        return file
    }

    private fun tempFile(path: String): File? = try {
        val file = File(path).canonicalFile
        if (file.isFile && inside(file, paths.tmp)) file else null
    } catch (e: IOException) {
        null
    }

    private fun inside(file: File, root: File): Boolean = try {
        val base = root.canonicalPath
        val actual = file.canonicalPath
        actual == base || actual.startsWith("$base/")
    } catch (e: IOException) {
        false
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)

    private fun isMime(value: String): Boolean = value.isNotEmpty() && MIME_PATTERN.matches(value)

    private fun guessMime(name: String, fallback: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) {
            return fallback
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: fallback
    }

    private companion object {
        val LINK_SCHEMES = setOf("http", "https", "mailto", "tel", "geo")
        val MIME_PATTERN = Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+*-]+$")
        const val MAX_TEXT_BYTES = 400_000L
    }
}