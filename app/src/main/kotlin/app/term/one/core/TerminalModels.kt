package app.term.one.core

import android.graphics.Bitmap

class PlacedImage(
    val bitmap: Bitmap,
    val line: Long,
    val col: Int,
    val cols: Int,
    val rows: Int,
    val destWidth: Int,
    val destHeight: Int
)

interface TerminalCallbacks {
    fun onWriteToPty(data: ByteArray)
    fun onTitleChanged(title: String)
    fun onBell()
    fun onClipboardSet(text: String)
    fun onCwdChanged(cwd: String)
    fun onVncRequest(args: Map<String, String>) {}
    fun onTermCommand(name: String, args: Map<String, String>) {}
}