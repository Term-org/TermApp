package app.term.one.core

object NativePty {

    init {
        System.loadLibrary("termone_pty")
    }

    @JvmStatic
    external fun open(
        command: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        outPid: IntArray
    ): Int

    @JvmStatic
    external fun setSize(fd: Int, rows: Int, cols: Int, widthPx: Int, heightPx: Int)

    @JvmStatic
    external fun read(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    @JvmStatic
    external fun write(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun kill(pid: Int, signal: Int)

    @JvmStatic
    external fun close(fd: Int)
}