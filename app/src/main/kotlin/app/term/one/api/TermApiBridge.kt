package app.term.one.api

import android.os.Bundle
import app.term.one.TermPaths
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Carries a call from the term-api command to the TermAPI app and writes the answer to files
// in the temporary folder, where the command picks it up.
object TermApiBridge {

    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "term-api-bridge").apply { isDaemon = true }
    }
    private val TOKEN = Regex("^[A-Za-z0-9_.-]{8,64}$")

    fun handle(paths: TermPaths, args: Map<String, String>) {
        val token = args["token"].orEmpty()
        if (!TOKEN.matches(token)) {
            return
        }
        val problem = notConnected()
        if (problem.isNotEmpty()) {
            finish(paths, token, "", problem + "\n", 1)
            return
        }

        when (args["op"].orEmpty()) {
            "status" -> {
                val info = TermApiClient.info
                finish(
                    paths, token,
                    "TermAPI ${info?.versionName.orEmpty()} (API ${info?.apiLevel ?: 0})\nShizuku: ${info?.shizuku.orEmpty()}\n",
                    "", 0
                )
            }
            "shizuku-status" -> {
                TermApiClient.request(ApiProtocol.MSG_SHIZUKU_STATUS, null, 10_000) { data ->
                    state(paths, token, data)
                }
            }
            "shizuku-authorize" -> {
                TermApiClient.request(ApiProtocol.MSG_SHIZUKU_AUTHORIZE, null, 140_000) { data ->
                    state(paths, token, data)
                }
            }
            "shizuku-exec" -> {
                val command = args["cmd"].orEmpty()
                if (command.isEmpty()) {
                    finish(paths, token, "", "No command was given\n", 2)
                    return
                }
                val timeout = (args["timeout"]?.toIntOrNull() ?: 60).coerceIn(1, 600)
                val data = Bundle().apply {
                    putString(ApiProtocol.KEY_COMMAND, command)
                    putInt(ApiProtocol.KEY_TIMEOUT, timeout)
                }
                TermApiClient.request(ApiProtocol.MSG_SHIZUKU_EXEC, data, (timeout + 30) * 1000L) { reply ->
                    exec(paths, token, reply)
                }
            }
            else -> finish(paths, token, "", "Unknown operation\n", 2)
        }
    }

    private fun notConnected(): String = when (TermApiClient.state) {
        TermApiClient.State.CONNECTED -> ""
        TermApiClient.State.NOT_INSTALLED ->
            "TermAPI is not installed. Download it from ${TermApiClient.RELEASES_URL}"
        TermApiClient.State.DENIED ->
            "TermAPI refused the connection. Release builds of both apps must be signed with the same key."
        TermApiClient.State.CONNECTING ->
            "TermAPI is still connecting. Try again in a moment."
        TermApiClient.State.DISCONNECTED ->
            "TermAPI is not connected. Turn on \"Connect to TermAPI\" in the settings."
    }

    private fun state(paths: TermPaths, token: String, data: Bundle?) {
        if (data == null) {
            finish(paths, token, "", "TermAPI did not answer\n", 1)
            return
        }
        val name = data.getString(ApiProtocol.KEY_STATE).orEmpty()
        finish(paths, token, "$name\n", "", if (name == "READY") 0 else 1)
    }

    private fun exec(paths: TermPaths, token: String, reply: Bundle?) {
        if (reply == null) {
            finish(paths, token, "", "TermAPI did not answer\n", 1)
            return
        }
        val error = reply.getString(ApiProtocol.KEY_ERROR).orEmpty()
        val exit = reply.getInt(ApiProtocol.KEY_EXIT_CODE, -1)
        finish(
            paths, token,
            reply.getString(ApiProtocol.KEY_STDOUT).orEmpty(),
            reply.getString(ApiProtocol.KEY_STDERR).orEmpty() + if (error.isNotEmpty()) "$error\n" else "",
            if (exit in 0..255) exit else 1
        )
    }

    // The .done file is written last; the command waits for it.
    private fun finish(paths: TermPaths, token: String, out: String, err: String, code: Int) {
        io.execute {
            try {
                paths.tmp.mkdirs()
                File(paths.tmp, "term-api.$token.out").writeText(out)
                File(paths.tmp, "term-api.$token.err").writeText(err)
                File(paths.tmp, "term-api.$token.code").writeText(code.toString())
                File(paths.tmp, "term-api.$token.done").writeText("")
            } catch (e: IOException) {
                return@execute
            }
        }
    }
}