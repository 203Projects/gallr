package com.gallr.app.notifications

import com.gallr.shared.data.model.RemotePushAddress

interface RemotePushAddressProvider {
    val platform: String

    suspend fun currentAddress(): RemotePushAddress?
}
