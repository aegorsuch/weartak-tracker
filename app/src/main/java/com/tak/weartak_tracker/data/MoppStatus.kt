package com.tak.weartak_tracker.data

/** Manually reported PPE, not a commanded level or a sensor measurement. */
enum class MoppStatus(val label: String) {
    NONE("None"),
    MOPP_0("MOPP 0"),
    MOPP_1("MOPP 1"),
    MOPP_2("MOPP 2"),
    MOPP_3("MOPP 3"),
    MOPP_4("MOPP 4");

    companion object {
        fun fromStored(value: String?): MoppStatus =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}
