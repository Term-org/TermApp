package app.term.one

import android.content.Context
import app.term.one.core.TerminalTheme
import java.io.File
import java.io.IOException
import java.util.Properties

object TermTheme {

    private val HEX = Regex("^#?[0-9a-fA-F]{6}$")

    fun create(paths: TermPaths): TerminalTheme = TerminalTheme().also { apply(paths, it) }

    // Resets the theme to the defaults, then applies colors.properties when it exists.
    fun apply(paths: TermPaths, theme: TerminalTheme) {
        val defaults = TerminalTheme()
        theme.foreground = defaults.foreground
        theme.background = defaults.background
        theme.cursor = defaults.cursor
        System.arraycopy(defaults.palette, 0, theme.palette, 0, defaults.palette.size)

        val file = paths.colorsProperties
        if (!file.isFile) {
            return
        }
        val props = Properties()
        try {
            file.inputStream().use { props.load(it) }
        } catch (e: IOException) {
            return
        }

        color(props, "foreground")?.let { theme.foreground = it }
        color(props, "background")?.let { theme.background = it }
        color(props, "cursor")?.let { theme.cursor = it }
        for (i in 0 until 16) {
            color(props, "color$i")?.let { theme.palette[i] = it }
        }
    }

    private fun color(props: Properties, key: String): Int? {
        val value = props.getProperty(key)?.trim() ?: return null
        if (!HEX.matches(value)) {
            return null
        }
        return 0xFF000000.toInt() or value.removePrefix("#").toInt(16)
    }
}

object TermThemeFiles {

    // Copies the built-in themes into the system folder. They are rewritten on every start.
    fun install(context: Context, paths: TermPaths) {
        val dir = File(paths.systemDir, "themes")
        dir.mkdirs()
        val names = try {
            context.assets.list("themes")
        } catch (e: IOException) {
            null
        } ?: return

        for (name in names) {
            try {
                val data = context.assets.open("themes/$name").use { it.readBytes() }
                val target = File(dir, name)
                if (!target.isFile || !target.readBytes().contentEquals(data)) {
                    target.writeBytes(data)
                }
                target.setReadable(true, false)
            } catch (e: IOException) {
                continue
            }
        }
    }
}