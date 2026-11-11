package com.gallr.shared.data.model

sealed class AuthState {
    data object Anonymous : AuthState()

    data object Loading : AuthState()

    data class Authenticated(
        val user: GallrUser,
        /**
         * True while the session could not be refreshed (offline or a server error) and the auth SDK is still retrying.
         * The user is still signed in, but requests go out without the session until the refresh lands; the state for
         * the same user then arrives again with this false.
         */
        val isSessionRefreshPending: Boolean = false,
    ) : AuthState()
}
