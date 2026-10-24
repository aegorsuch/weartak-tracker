package com.tak.weartak_tracker.cot

/**
 * ATAK's standard emergency types, matched to WearOS. Other presets and every typed or saved
 * custom alert stay plain `b-a-o` with their own label as the emergency type.
 */
enum class StandardEmergencyType(val label: String, val cotType: String, val cotDescription: String) {
    NINE_ONE_ONE("911 Alert", "b-a-o-tbl", "911 Alert"),
    RING_THE_BELL("Ring The Bell", "b-a-o-pan", "Ring The Bell"),
    GEOFENCE_BREACHED("Geofence Breached", "b-a-g", "Geo-fence Breached"),
    IN_CONTACT("In Contact", "b-a-o-opn", "Troops In Contact"),
    ;

    companion object {
        const val GENERIC_COT_TYPE = "b-a-o"
        const val CANCEL_COT_TYPE = "b-a-o-can"

        fun of(description: String): StandardEmergencyType? {
            val text = description.trim()
            return entries.firstOrNull { text.equals(it.label, true) || text.equals(it.cotDescription, true) }
        }

        fun cotType(description: String): String = of(description)?.cotType ?: GENERIC_COT_TYPE

        fun emergencyType(description: String): String = of(description)?.cotDescription ?: description
    }
}
