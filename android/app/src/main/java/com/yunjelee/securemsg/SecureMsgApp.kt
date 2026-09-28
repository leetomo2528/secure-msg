package com.yunjelee.securemsg

import android.app.Application

class SecureMsgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // First, so the crash handler and the exit-reason sweep cover
        // everything that starts after it (receivers, the bridge service).
        Diagnostics.init(this)
    }

    companion object {
        lateinit var instance: SecureMsgApp
            private set
    }
}
