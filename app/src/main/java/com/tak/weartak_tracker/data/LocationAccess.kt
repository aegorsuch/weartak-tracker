package com.tak.weartak_tracker.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Priority

/** Which location permission the user granted. Tracking runs with either precise or approximate access. */
enum class LocationAccess {
    PRECISE, APPROXIMATE, NONE;

    val granted: Boolean get() = this != NONE

    /** Approximate fixes are coarsened by the system anyway, so a balanced request saves power. */
    val priority: Int
        get() = if (this == PRECISE) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

    companion object {
        fun of(fine: Boolean, coarse: Boolean): LocationAccess = when {
            fine -> PRECISE
            coarse -> APPROXIMATE
            else -> NONE
        }

        fun current(context: Context): LocationAccess = of(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }
}
