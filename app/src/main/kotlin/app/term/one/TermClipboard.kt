package app.term.one

import java.io.File
import java.io.IOException

object TermClipboard {

    private const val MAX_CHARS = 4_000_000
    private const val MAX_ENTRIES = 10

    class Entry(val file: File) {

        val id: String get() = file.name

        val sizeBytes: Long get() = file.length()

        fun text(): String = try {
            file.readText()
        } catch (e: IOException) {
            ""
        }

        fun preview(maxChars: Int): String = try {
            file.reader().use { reader ->
                val buffer = CharArray(maxChars)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
        } catch (e: IOException) {
            ""
        }
    }

    private fun directory(paths: TermPaths): File =
        File(paths.docs, ".term_clipboard_history").also { it.mkdirs() }

    @Synchronized
    fun set(paths: TermPaths, text: String) {
        if (text.isEmpty()) {
            return
        }
        val value = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        val newest = history(paths).firstOrNull()
        if (newest != null && newest.text() == value) {
            return
        }
        val dir = directory(paths)
        var stamp = System.currentTimeMillis()
        var file = File(dir, String.format("%013d.txt", stamp))
        while (file.exists()) {
            stamp++
            file = File(dir, String.format("%013d.txt", stamp))
        }
        try {
            file.writeText(value)
        } catch (e: IOException) {
            file.delete()
            return
        }
        history(paths).drop(MAX_ENTRIES).forEach { it.file.delete() }
    }

    @Synchronized
    fun history(paths: TermPaths): List<Entry> {
        val files = directory(paths).listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return emptyList()
        return files.sortedByDescending { it.name }.map { Entry(it) }
    }

    @Synchronized
    fun get(paths: TermPaths): String = history(paths).firstOrNull()?.text() ?: ""

    @Synchronized
    fun remove(paths: TermPaths, entry: Entry) {
        entry.file.delete()
    }

    @Synchronized
    fun clear(paths: TermPaths) {
        history(paths).forEach { it.file.delete() }
    }
}