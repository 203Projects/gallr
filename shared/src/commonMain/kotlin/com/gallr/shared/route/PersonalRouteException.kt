package com.gallr.shared.route

/** A failed route operation, as returned inside a repository [Result]. */
class PersonalRouteException(
    val failure: PersonalRouteFailure,
) : Exception("personal route operation failed")

/** The typed failure behind a route operation's error; anything unrecognised is [PersonalRouteFailure.Unexpected]. */
fun Throwable.routeFailure(): PersonalRouteFailure =
    (this as? PersonalRouteException)?.failure ?: PersonalRouteFailure.Unexpected
