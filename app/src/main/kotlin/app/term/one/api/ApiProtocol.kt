package app.term.one.api

// Keep this file identical to app/term/api/ApiProtocol.kt of TermAPI.
object ApiProtocol {

    const val API_LEVEL = 1

    const val MSG_PING = 1
    const val MSG_SHIZUKU_STATUS = 2
    const val MSG_SHIZUKU_AUTHORIZE = 3
    const val MSG_SHIZUKU_EXEC = 4

    // A reply carries what = request + REPLY_OFFSET and echoes arg1 of the request.
    const val REPLY_OFFSET = 100

    const val KEY_VERSION_NAME = "versionName"
    const val KEY_VERSION_CODE = "versionCode"
    const val KEY_API_LEVEL = "apiLevel"
    const val KEY_SHIZUKU = "shizuku"
    const val KEY_STATE = "state"
    const val KEY_COMMAND = "command"
    const val KEY_TIMEOUT = "timeout"
    const val KEY_STDOUT = "stdout"
    const val KEY_STDERR = "stderr"
    const val KEY_EXIT_CODE = "exitCode"
    const val KEY_ERROR = "error"
}