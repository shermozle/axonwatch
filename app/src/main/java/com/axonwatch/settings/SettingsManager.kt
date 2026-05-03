package com.axonwatch.settings

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Comma-separated list of uppercase MAC prefixes to match, e.g. "00:25:DF,AA:BB:CC" */
    var macPrefixes: List<String>
        get() {
            val raw = prefs.getString(KEY_MAC_PREFIXES, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
            return raw.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }
        }
        set(value) = prefs.edit()
            .putString(KEY_MAC_PREFIXES, value.joinToString(","))
            .apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var apiToken: String
        get() = prefs.getString(KEY_API_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_TOKEN, value.trim()).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    /** Stable device identifier generated once and persisted. */
    val reporterId: String
        get() = prefs.getString(KEY_REPORTER_ID, null)
            ?: UUID.randomUUID().toString().also { id ->
                prefs.edit().putString(KEY_REPORTER_ID, id).apply()
            }

    companion object {
        private const val PREFS_NAME = "axonwatch_settings"
        private const val DEFAULT_PREFIX = "00:25:DF"
        private const val KEY_MAC_PREFIXES = "mac_prefixes"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_REPORTER_ID = "reporter_id"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
    }
}
