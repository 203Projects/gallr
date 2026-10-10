package com.gallr.shared.route

/** Why a route operation failed, as the app needs to explain it (spec 089, contracts/database-functions.md). */
sealed interface PersonalRouteFailure {
    /** No signed-in session, or it expired. */
    data object Unauthenticated : PersonalRouteFailure

    /** The route belongs to another account; the draft should be saved as a new route (E-D8). */
    data object NotOwner : PersonalRouteFailure

    /** Staff removed the route; it can be saved again only as a new route. */
    data object Revoked : PersonalRouteFailure

    data object InvalidName : PersonalRouteFailure

    /** Fewer than two, more than ten, or repeated stops. */
    data object InvalidStops : PersonalRouteFailure

    /** These exhibitions have no map location (RO5). */
    data class MissingLocation(
        val exhibitionIds: List<String>,
    ) : PersonalRouteFailure

    /** These exhibitions left the catalogue and this route did not already hold them (E-D18). */
    data class UnavailableStops(
        val exhibitionIds: List<String>,
    ) : PersonalRouteFailure

    data object NotFound : PersonalRouteFailure

    /** Listing needs a published route; the app publishes first (spec 089 US7). */
    data object ListingRequiresPublished : PersonalRouteFailure

    /** The listing action does not apply in the route's current state (for example a route staff removed). */
    data object ListingInvalidTransition : PersonalRouteFailure

    /** The route is no longer shown in the public list, so it cannot be copied or reported. */
    data object NotListed : PersonalRouteFailure

    /** This account already has an open report on the route. */
    data object ReportExists : PersonalRouteFailure

    /** Authors cannot report their own routes. */
    data object ReportOwnRoute : PersonalRouteFailure

    /** The request did not reach the server or no response arrived. */
    data object Network : PersonalRouteFailure

    /** Any other server answer. */
    data object Unexpected : PersonalRouteFailure
}
