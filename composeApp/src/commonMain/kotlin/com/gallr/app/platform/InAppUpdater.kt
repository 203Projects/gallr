package com.gallr.app.platform

/**
 * The platform's own in-app update flow, tried before the store page when an update is required (spec 098 R6).
 * Google Play's immediate update is the intended Android implementation; it is not wired in this release because
 * local and emulator builds are not Play-distributed, so [None] leaves every tap to the store listing.
 */
fun interface InAppUpdater {
    /** Returns true when the platform took over the update; false sends the visitor to the store listing. */
    fun startImmediateUpdate(): Boolean

    companion object {
        /** No platform flow: the notice opens the store page. */
        val None: InAppUpdater = InAppUpdater { false }
    }
}
