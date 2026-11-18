package app.term.one

import android.app.Application

class TermApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val paths = TermPaths.get(this)
        paths.ensureDirectories()
        TermConfig.writeDefaults(paths)
        TermScripts.install(this, paths)
    }
}