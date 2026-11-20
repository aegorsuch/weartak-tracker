package com.tak.weartak_tracker.data

enum class TakGovAccessLevel {
    CIV,
    MIL,
}

enum class TakGovLinkStatus(val subtitle: String) {
    UNLINKED("Unlinked"),
    LINKED("Linked"),
    CIV("Linked (Civ)"),
    MIL("Linked (Mil)"),
}

fun takGovLinkStatusFor(linked: Boolean, accessLevel: TakGovAccessLevel?): TakGovLinkStatus =
    when {
        !linked -> TakGovLinkStatus.UNLINKED
        accessLevel == TakGovAccessLevel.MIL -> TakGovLinkStatus.MIL
        accessLevel == TakGovAccessLevel.CIV -> TakGovLinkStatus.CIV
        else -> TakGovLinkStatus.LINKED
    }
