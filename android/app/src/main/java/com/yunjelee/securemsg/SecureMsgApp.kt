package com.yunjelee.securemsg

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

        /**
         * Work a user started that must finish even if the screen that started
         * it does not: a photo send reads and shrinks for seconds, and a swipe
         * from recents or a tab switch in that window used to cancel it with
         * nothing written and nothing said. Lives as long as the process, which
         * the bridge's foreground service keeps alive; never cancelled.
         * A supervisor, so one failed send cannot cancel another in flight.
         */
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
