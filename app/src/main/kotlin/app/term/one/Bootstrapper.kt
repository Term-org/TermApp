package app.term.one

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

class BootstrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

class Bootstrapper(private val context: Context, private val paths: TermPaths) {

    interface Progress {
        fun onProgress(stage: String, percent: Int)
    }

    fun install(progress: Progress) {
        try {
            perform(progress)
        } catch (e: BootstrapException) {
            throw e
        } catch (e: IOException) {
            throw BootstrapException(e.message ?: "I/O error", e)
        } catch (e: ErrnoException) {
            throw BootstrapException("System error: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------ flow

    private fun perform(progress: Progress) {
        val arch = architecture()
        val config = loadConfig()
        // Debug builds use the small TermBoot archive, release builds use TP.
        // term.properties can force one of them: bootstrap=boot or bootstrap=tp.
        val source = config.getProperty("bootstrap")?.trim()?.lowercase()
            ?: if (BuildConfig.DEBUG) "boot" else "tp"
        val url = config.getProperty("bootstrap-url")?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (source == "boot") {
                "$BOOT_BASE/termboot-$arch.zip"
            } else {
                "$DEFAULT_BASE/bootstrap-${paths.appId}-$arch.zip"
            }
        if (!url.startsWith("https://")) {
            throw BootstrapException("The bootstrap URL must use https")
        }
        paths.ensureDirectories()

        progress.onProgress("Fetching checksum", -1)
        val expected = expectedChecksum(config, url)

        val archive = File(paths.tmp, "bootstrap.zip")
        val actual = download(url, archive, progress)
        if (!actual.equals(expected, ignoreCase = true)) {
            archive.delete()
            throw BootstrapException("Checksum mismatch. Expected $expected but received $actual")
        }

        extract(archive, progress)
        progress.onProgress("Activating", -1)
        activate(arch)
        archive.delete()
        progress.onProgress("Done", 100)
    }

    private fun architecture(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "x86_64" -> return "x86_64"
            }
        }
        throw BootstrapException("Unsupported CPU architecture: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    private fun loadConfig(): Properties {
        val props = Properties()
        if (paths.termProperties.isFile) {
            try {
                paths.termProperties.inputStream().use { props.load(it) }
            } catch (e: IOException) {
                props.clear()
            }
        }
        return props
    }

    // ------------------------------------------------------------------ network

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "${context.packageName}/${BuildConfig.VERSION_NAME}")
        return connection
    }

    private fun expectedChecksum(config: Properties, url: String): String {
        val configured = config.getProperty("bootstrap-sha256")?.trim()?.lowercase()
        val value = if (!configured.isNullOrEmpty()) {
            configured
        } else {
            fetchText("$url.sha256").trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: ""
        }
        if (!SHA256.matches(value)) {
            throw BootstrapException("Invalid SHA-256 checksum for the bootstrap archive")
        }
        return value
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw BootstrapException("Unable to fetch $url (HTTP $code)")
            }
            return connection.inputStream.use { input ->
                val bytes = ByteArray(4096)
                val n = input.read(bytes)
                if (n <= 0) "" else String(bytes, 0, n, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, progress: Progress): String {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw BootstrapException("Unable to download the bootstrap (HTTP $code)")
            }
            val total = connection.contentLengthLong
            val part = File(target.path + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(65536)
                    var done = 0L
                    var last = -2
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) {
                            break
                        }
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != last) {
                            last = percent
                            progress.onProgress("Downloading bootstrap", percent)
                        }
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) {
                throw BootstrapException("Unable to store the downloaded archive")
            }
            return digest.digest().joinToString("") { String.format("%02x", it) }
        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------------------------ extraction

    private fun extract(archive: File, progress: Progress) {
        deleteTree(paths.stagingPrefix)
        if (!paths.stagingPrefix.mkdirs()) {
            throw BootstrapException("Unable to create ${paths.stagingPrefix}")
        }
        val root = paths.stagingPrefix.canonicalPath
        val total = max(1L, archive.length())
        val counting = CountingInputStream(FileInputStream(archive))
        val symlinks = ArrayList<Pair<String, String>>()
        var last = -1

        ZipInputStream(BufferedInputStream(counting, 65536)).use { zip ->
            val buffer = ByteArray(65536)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name

                if (name == "SYMLINKS.txt") {
                    parseSymlinks(String(zip.readBytes(), Charsets.UTF_8), symlinks)
                    continue
                }

                val target = File(paths.stagingPrefix, name)
                val canonical = target.canonicalPath
                if (canonical != root && !canonical.startsWith("$root/")) {
                    throw BootstrapException("Unsafe path in the bootstrap archive: $name")
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    var executable = false
                    var first = true
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) {
                                break
                            }
                            if (first && n > 0) {
                                executable = looksExecutable(buffer, n)
                                first = false
                            }
                            out.write(buffer, 0, n)
                        }
                    }
                    Os.chmod(target.path, if (executable) EXEC_MODE else FILE_MODE)
                }

                val percent = (counting.count * 100 / total).toInt().coerceIn(0, 100)
                if (percent != last) {
                    last = percent
                    progress.onProgress("Extracting", percent)
                }
            }
        }

        progress.onProgress("Creating links", -1)
        val stagingPath = paths.stagingPrefix.toPath()
        for ((linkTarget, link) in symlinks) {
            val file = File(paths.stagingPrefix, link)
            if (!file.toPath().normalize().startsWith(stagingPath)) {
                throw BootstrapException("Unsafe symbolic link in the bootstrap archive: $link")
            }
            file.parentFile?.mkdirs()
            deleteTree(file)
            Os.symlink(linkTarget, file.path)
        }

        if (!existsNoFollow(File(paths.stagingPrefix, "bin/bash"))) {
            throw BootstrapException("The bootstrap archive does not contain bin/bash")
        }
    }

    private fun parseSymlinks(text: String, out: MutableList<Pair<String, String>>) {
        for (line in text.lineSequence()) {
            if (line.isBlank()) {
                continue
            }
            val parts = line.split('\u2190')
            if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw BootstrapException("Malformed line in SYMLINKS.txt: $line")
            }
            out.add(Pair(parts[0], parts[1].removePrefix("./")))
        }
    }

    private fun looksExecutable(b: ByteArray, n: Int): Boolean {
        val elf = n >= 4 &&
            b[0] == 0x7F.toByte() &&
            b[1] == 'E'.code.toByte() &&
            b[2] == 'L'.code.toByte() &&
            b[3] == 'F'.code.toByte()
        val script = n >= 2 && b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte()
        return elf || script
    }

    private fun max(a: Long, b: Long): Long = if (a > b) a else b

    private fun activate(arch: String) {
        paths.bootstrapMarker.delete()
        deleteTree(paths.prefix)
        if (!paths.stagingPrefix.renameTo(paths.prefix)) {
            throw BootstrapException("Unable to move the new prefix into place")
        }
        File(paths.prefix, "tmp").mkdirs()
        paths.bootstrapMarker.writeText("arch=$arch\ninstalled=${System.currentTimeMillis()}\n")
    }

    // ------------------------------------------------------------------ file helpers

    private fun existsNoFollow(file: File): Boolean = try {
        Os.lstat(file.path)
        true
    } catch (e: ErrnoException) {
        false
    }

    // Deletes a tree without following symbolic links.
    private fun deleteTree(file: File) {
        val mode = try {
            Os.lstat(file.path).st_mode
        } catch (e: ErrnoException) {
            return
        }
        if (OsConstants.S_ISDIR(mode)) {
            file.list()?.forEach { deleteTree(File(file, it)) }
        }
        file.delete()
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                count++
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
            }
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) {
                count += skipped
            }
            return skipped
        }
    }

    private companion object {
        const val DEFAULT_BASE = "https://github.com/Term-org/TP/releases/latest/download"
        const val BOOT_BASE = "https://github.com/Term-org/TermBoot/releases/latest/download"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val EXEC_MODE = "755".toInt(8)
        val FILE_MODE = "644".toInt(8)
    }
}