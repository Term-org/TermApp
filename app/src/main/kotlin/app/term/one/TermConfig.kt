package app.term.one

import java.io.File

object TermConfig {

    private const val DEFAULT_TERM_PROPERTIES = """# TermOne configuration
# Font size in sp
font-size=14
# Terminal type exported to TERM
term-type=xterm-256color
# Enable inline image protocols
sixel=true
iterm2-images=true
# Show session tabs at the top
session-tabs-top=true
# Maximum sessions in combined view (1-4)
max-combined-sessions=4
# Bell behavior: vibrate, beep, ignore
bell=vibrate
# Cursor style: block, underline, bar
cursor-style=block
cursor-blink=true
# Scrollback lines
scrollback=2000
"""

    private const val DEFAULT_EXTRAKEYS = """# Extra keys layout, keys separated by comma
# Row 1 and row 2 are the normal rows.
# vnc-row is revealed by swiping the extra keys to the left.
row1=ESC,TAB,CTRL,ALT,SHIFT,UP,DOWN,HOME,END
row2=LEFT,RIGHT,PGUP,PGDN,-,/,|,~,FN
vnc-row=VNC_LEFT_CLICK,VNC_RIGHT_CLICK,VNC_MIDDLE_CLICK,VNC_SCROLL_UP,VNC_SCROLL_DOWN,VNC_KEYBOARD,VNC_FIT,VNC_CLOSE
"""

    fun writeDefaults(paths: TermPaths) {
        writeIfMissing(paths.termProperties, DEFAULT_TERM_PROPERTIES)
        writeIfMissing(paths.extraKeysProperties, DEFAULT_EXTRAKEYS)
    }

    private fun writeIfMissing(file: File, content: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }
}