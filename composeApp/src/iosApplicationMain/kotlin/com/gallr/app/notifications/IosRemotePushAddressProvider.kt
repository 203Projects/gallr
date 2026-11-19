package com.gallr.app.notifications

import com.gallr.shared.data.model.RemotePushAddress
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSNotificationCenter
import kotlin.coroutines.resume

class IosRemotePushAddressProvider : RemotePushAddressProvider {
    override val platform: String = "ios"
    private val pending = mutableListOf<CancellableContinuation<RemotePushAddress?>>()

    override suspend fun currentAddress(): RemotePushAddress? =
        suspendCancellableCoroutine { continuation ->
            pending += continuation
            continuation.invokeOnCancellation { pending.remove(continuation) }
            NSNotificationCenter.defaultCenter.postNotificationName(
                aName = REMOTE_REGISTRATION_REQUEST,
                `object` = null,
            )
        }

    fun acceptToken(
        token: String,
        environment: String,
    ) {
        val address =
            runCatching {
                RemotePushAddress(
                    platform = platform,
                    provider = "apns",
                    token = token,
                    environment = environment,
                )
            }.getOrNull()
        resumePending(address)
    }

    fun registrationFailed() {
        resumePending(null)
    }

    private fun resumePending(address: RemotePushAddress?) {
        val continuations = pending.toList()
        pending.clear()
        continuations.forEach { continuation ->
            if (continuation.isActive) continuation.resume(address)
        }
    }

    private companion object {
        const val REMOTE_REGISTRATION_REQUEST = "GallrRegisterForRemoteNotifications"
    }
}
