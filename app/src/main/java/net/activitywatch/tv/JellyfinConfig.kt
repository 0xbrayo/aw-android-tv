package net.activitywatch.tv

import android.content.Context

class JellyfinConfig(context: Context) {
    private val prefs = context.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) { prefs.edit().putString("server_url", value.trimEnd('/')).apply() }

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) { prefs.edit().putString("api_key", value.trim()).apply() }

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}
