package app.term.one

import androidx.preference.PreferenceDataStore
import java.io.File
import java.io.IOException
import java.util.Properties

object TermConfigFile {

    // Sets one key and keeps everything else (comments, order) as it was.
    @Synchronized
    fun set(file: File, key: String, value: String) {
        val lines = if (file.isFile) file.readLines() else emptyList()
        val pattern = Regex("^\\s*" + Regex.escape(key) + "\\s*[=:].*$")
        val out = ArrayList<String>()
        var written = false
        for (line in lines) {
            if (pattern.matches(line)) {
                if (!written) {
                    out.add("$key=$value")
                    written = true
                }
            } else {
                out.add(line)
            }
        }
        if (!written) {
            out.add("$key=$value")
        }
        file.parentFile?.mkdirs()
        val text = out.joinToString("\n") + "\n"
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    fun get(file: File, key: String): String? {
        if (!file.isFile) {
            return null
        }
        val props = Properties()
        return try {
            file.inputStream().use { props.load(it) }
            props.getProperty(key)
        } catch (e: IOException) {
            null
        }
    }
}

// Lets the settings screen read and write term.properties directly.
class PropertiesDataStore(private val file: File) : PreferenceDataStore() {

    override fun getString(key: String, defValue: String?): String? =
        TermConfigFile.get(file, key)?.trim() ?: defValue

    override fun putString(key: String, value: String?) {
        if (value != null) {
            TermConfigFile.set(file, key, value)
        }
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        TermConfigFile.get(file, key)?.trim()?.toBooleanStrictOrNull() ?: defValue

    override fun putBoolean(key: String, value: Boolean) {
        TermConfigFile.set(file, key, value.toString())
    }

    override fun getInt(key: String, defValue: Int): Int =
        TermConfigFile.get(file, key)?.trim()?.toIntOrNull() ?: defValue

    override fun putInt(key: String, value: Int) {
        TermConfigFile.set(file, key, value.toString())
    }
}