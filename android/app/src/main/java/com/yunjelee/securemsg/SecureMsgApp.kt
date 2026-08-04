package com.yunjelee.securemsg

import android.app.Application

class SecureMsgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SecureMsgApp
            private set
    }
}
