package com.tak.weartak_tracker.ui

import com.tak.weartak_tracker.data.TrackerConfig

internal fun sitxConfigurationLabel(config: TrackerConfig): String =
    if (config.sitxEnabled) "Enabled" else "Disabled"
