package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.data.TrackerConfig

internal fun TrackerConfig.identityChangedFrom(previous: TrackerConfig?): Boolean =
    previous != null &&
        (callsign != previous.callsign || team != previous.team || role != previous.role)
