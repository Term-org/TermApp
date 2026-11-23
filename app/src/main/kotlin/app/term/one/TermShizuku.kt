package app.term.one

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException

object TermShizuku {

    const val REQUEST_CODE = 4107

    enum class State { NOT_RUNNING, UNSUPPORTED, NEEDS_PERMISSION, DENIED, READY }

    fun state(): State {
        return try {
            when {
                !Shizuku.pingBinder() -> State.NOT_RUNNING
                Shizuku.isPreV11() -> State.UNSUPPORTED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
                Shizuku.shouldShowRequestPermissionRationale() -> State.DENIED
                else -> State.NEEDS_PERMISSION
            }
        } catch (e: RuntimeException) {
            State.NOT_RUNNING
        }
    }

    fun request() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: RuntimeException) {
            return
        }
    }

    // The term-shizuku command waits for this file to carry its token.
    fun writeState(paths: TermPaths, token: String, state: State) {
        try {
            paths.configDir.mkdirs()
            File(paths.configDir, "shizuku.state").writeText("${state.name}\n$token\n")
        } catch (e: IOException) {
            return
        }
    }
}