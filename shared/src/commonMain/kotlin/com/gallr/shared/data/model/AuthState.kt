package com.gallr.shared.data.model

sealed class AuthState {
    data object Anonymous : AuthState()
    data object Loading : AuthState()
    data class Authenticated(val user: GallrUser) : AuthState()
}
