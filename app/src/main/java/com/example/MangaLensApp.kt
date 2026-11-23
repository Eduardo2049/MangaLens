package com.example

import android.app.Application
import com.example.diagnostics.CrashReporter

class MangaLensApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing: a crash anywhere afterwards (app screen or floating translator) is kept for the user.
        CrashReporter.install(this)
    }
}
