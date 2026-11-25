package com.madooroy.gregario

import android.app.Application
import timber.log.Timber

/** Gregario's application. Debug builds log to logcat (tag per class), so a ride's `rlog` shows Gregario's side too. */
class GregarioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (com.madooroy.gregario.BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
