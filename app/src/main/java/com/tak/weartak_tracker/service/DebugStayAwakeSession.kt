package com.tak.weartak_tracker.service

import android.content.Context
import android.util.Log

internal object DebugStayAwakeSession {
    private const val TAG = "WearTAK-DebugStayAwake"
    private const val PREFERENCES = "debug_stay_awake"
    private const val KEY_EXPLICITLY_ENABLED = "explicitly_enabled"
    private const val KEY_RESUME_AFTER_UPDATE = "resume_after_update"

    fun explicitlyEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_EXPLICITLY_ENABLED, false)

    fun resumeAfterUpdate(context: Context): Boolean =
        preferences(context).getBoolean(KEY_RESUME_AFTER_UPDATE, false)

    fun shouldResumeAfterUpdate(explicitlyEnabled: Boolean, resumeAfterUpdate: Boolean): Boolean =
        explicitlyEnabled && resumeAfterUpdate

    fun isEligibleForUpdateResume(context: Context): Boolean =
        shouldResumeAfterUpdate(explicitlyEnabled(context), resumeAfterUpdate(context))

    fun setExplicitlyEnabled(context: Context, enabled: Boolean): Boolean =
        write(context, KEY_EXPLICITLY_ENABLED, enabled)

    fun setResumeAfterUpdate(context: Context, enabled: Boolean): Boolean =
        write(context, KEY_RESUME_AFTER_UPDATE, enabled)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun write(context: Context, key: String, value: Boolean): Boolean {
        val saved = preferences(context).edit().putBoolean(key, value).commit()
        if (!saved) Log.e(TAG, "Unable to persist Stay awake setting: $key")
        return saved
    }
}
