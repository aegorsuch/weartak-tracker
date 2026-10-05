package com.tak.weartak_tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.data.TrackerState

/** Receives Activity Recognition updates used by the dynamic reporting strategy. */
class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val detected = ActivityRecognitionResult.extractResult(intent)?.mostProbableActivity ?: return
        if (detected.confidence < MIN_CONFIDENCE) return
        TrackerState.activity.value = when (detected.type) {
            DetectedActivity.IN_VEHICLE -> Activity.IN_VEHICLE
            DetectedActivity.ON_BICYCLE -> Activity.BICYCLING
            DetectedActivity.ON_FOOT -> Activity.ON_FOOT
            DetectedActivity.RUNNING -> Activity.RUNNING
            DetectedActivity.WALKING -> Activity.WALKING
            DetectedActivity.STILL -> Activity.STILL
            DetectedActivity.TILTING -> Activity.TILTING
            else -> Activity.UNKNOWN
        }
    }

    companion object { const val MIN_CONFIDENCE = 50 }
}
