package com.tak.weartak_tracker.data

import android.Manifest
import android.os.Build

internal fun heartRatePermission(sdkInt: Int = Build.VERSION.SDK_INT): String =
    if (sdkInt >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
