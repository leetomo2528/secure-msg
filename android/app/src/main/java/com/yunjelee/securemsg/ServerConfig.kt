package com.yunjelee.securemsg

import android.content.Context

/**
 * Single source of truth for the relay URL preference. Previously the same
 * SharedPreferences key/default was read in MainActivity (twice) and
 * SmsBridgeService; keep every read/write here.
 */
object ServerConfig {
    const val DEFAULT_URL = "https://msg.yunjelee.com"
    private const val PREFS = "securemsg_config"
    private const val KEY = "server_url"

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT_URL) ?: DEFAULT_URL

    fun save(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, url).apply()
    }
}
