package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.data.TrackerConfig

/** Changes to anything PLI publishes about the wearer (identity or medical profile) trigger a fresh-fix PLI. */
internal fun TrackerConfig.identityChangedFrom(previous: TrackerConfig?): Boolean =
    previous != null &&
        (callsign != previous.callsign || team != previous.team || role != previous.role ||
            medicalProfile != previous.medicalProfile)
