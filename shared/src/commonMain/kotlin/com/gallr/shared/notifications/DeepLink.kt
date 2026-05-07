package com.gallr.shared.notifications

sealed class DeepLink {
    data class Exhibition(val id: String) : DeepLink()
    object MyList : DeepLink()
}
