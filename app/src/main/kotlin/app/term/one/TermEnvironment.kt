package app.term.one

import java.io.File

object TermEnvironment {

    private val INHERITED = listOf(
        "ANDROID_ROOT",
        "ANDROID_DATA",
        "ANDROID_RUNTIME_ROOT",
        "ANDROID_ART_ROOT",
        "ANDROID_I18N_ROOT",
        "ANDROID_TZDATA_ROOT",
        "BOOTCLASSPATH",
        "DEX2OATBOOTCLASSPATH",
        "EXTERNAL_STORAGE"
    )

    private val PRELOAD_CANDIDATES = listOf(
        "libtermux-exec-ld-preload.so",
        "libtermux-exec.so"
    )

    fun build(paths: TermPaths): Array<String> {
        val env = linkedMapOf<String, String>()
        for (name in INHERITED) {
            System.getenv(name)?.let { env[name] = it }
        }
        env["PREFIX"] = paths.prefix.absolutePath
        env["HOME"] = paths.home.absolutePath
        env["TMPDIR"] = paths.tmp.absolutePath
        env["DOCS"] = paths.docs.absolutePath
        env["PATH"] = "${paths.bin.absolutePath}:/system/bin:/system/xbin"
        env["SHELL"] = if (paths.bash.canExecute()) paths.bash.absolutePath else "/system/bin/sh"
        if (!paths.bash.canExecute()) {
            env["ENV"] = paths.systemMkshrc.absolutePath
        }
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"
        env["TERM_APP_ID"] = paths.appId
        env["TERMONE_VERSION"] = BuildConfig.VERSION_NAME

        env["TERMUX_APP__PACKAGE_NAME"] = paths.appId
        env["TERMUX_APP__DATA_DIR"] = "/data/data/${paths.appId}"
        env["TERMUX__ROOTFS"] = paths.rootDir.absolutePath
        env["TERMUX__HOME"] = paths.home.absolutePath
        env["TERMUX__PREFIX"] = paths.prefix.absolutePath

        for (name in PRELOAD_CANDIDATES) {
            val library = File(paths.lib, name)
            if (library.isFile) {
                env["LD_PRELOAD"] = library.absolutePath
                break
            }
        }
        return env.map { (key, value) -> "$key=$value" }.toTypedArray()
    }
}