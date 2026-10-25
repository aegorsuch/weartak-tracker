package com.tak.weartak_tracker.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

internal object DebugPreferences {
    private val developer = booleanPreferencesKey("developer_mode")
    private val diagnostics = booleanPreferencesKey("diagnostics_enabled")

    fun developerMode(preferences: Preferences): Boolean = preferences[developer] ?: false
    fun diagnostics(preferences: Preferences): Boolean = preferences[diagnostics] ?: false

    fun write(preferences: MutablePreferences, developerMode: Boolean, diagnosticsEnabled: Boolean) {
        preferences[developer] = developerMode
        preferences[diagnostics] = diagnosticsEnabled
    }
}
