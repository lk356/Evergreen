package com.example.evergreen.capture

import android.content.Context
import java.util.UUID

/**
 * A stable identifier for this installation of the app.
 *
 * Phase 6 syncs listening history between devices and has to be able to tell
 * which device recorded what. That needs an identifier, but it does not need a
 * *hardware* identifier: this is a random UUID generated on first use, so it
 * says nothing about the device or its owner and cannot be correlated with
 * anything outside this app.
 *
 * It lives in SharedPreferences, which `data_extraction_rules.xml` excludes from
 * cloud backup and device transfer. Reinstalling produces a new one, which is
 * the correct behaviour: a fresh install has no history to attribute.
 */
object DeviceId {
    private const val PREFS = "evergreen_device"
    private const val KEY = "device_id"

    @Volatile
    private var cached: String? = null

    fun get(context: Context): String =
        cached ?: synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }

    private fun load(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, generated).apply()
        return generated
    }
}
