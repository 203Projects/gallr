package com.gallr.shared.route

/** A route's place in the public list, set by its author and staff (spec 089 US7, data-model.md). */
enum class RouteListingState(
    internal val wire: String,
) {
    Unlisted("unlisted"),
    Requested("requested"),
    Approved("approved"),
    Declined("declined"),

    /** Taken off the list by staff; only staff can move it back to [Unlisted]. */
    Removed("removed"),
    ;

    internal companion object {
        /** Unknown or missing values read as [Unlisted], so an older server never shows a false state. */
        fun fromWire(value: String?): RouteListingState = entries.firstOrNull { it.wire == value } ?: Unlisted
    }
}

/** The single reason an author's route is not shown to readers, highest precedence first (DD13). */
enum class RouteListingBlocker(
    internal val wire: String,
) {
    Revoked("revoked"),
    Removed("removed"),
    Declined("declined"),
    EndedStop("ended_stop"),
    MissingStop("missing_stop"),
    NoSharedDay("no_shared_day"),
    ;

    internal companion object {
        fun fromWire(value: String?): RouteListingBlocker? = entries.firstOrNull { it.wire == value }
    }
}

/** Why staff declined a listing request (DD16). */
enum class RouteDeclineReason(
    internal val wire: String,
) {
    NameOrDescription("name_or_description"),
    Promotional("promotional"),
    Composition("composition"),
    Other("other"),
    ;

    internal companion object {
        fun fromWire(value: String?): RouteDeclineReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Why a reader reports a listed route (DD10). */
enum class RouteReportReason(
    internal val wire: String,
) {
    Inappropriate("inappropriate"),
    Promotional("promotional"),
    WrongInformation("wrong_information"),
    Other("other"),
}
