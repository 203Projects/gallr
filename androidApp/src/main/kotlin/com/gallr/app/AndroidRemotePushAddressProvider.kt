package com.gallr.app

import android.content.Context
import com.gallr.app.notifications.RemotePushAddressProvider
import com.gallr.shared.data.model.RemotePushAddress
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class AndroidFirebaseConfiguration(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val senderId: String,
) {
    val isConfigured: Boolean
        get() =
            projectId.isNotBlank() &&
                applicationId.isNotBlank() &&
                apiKey.isNotBlank() &&
                senderId.isNotBlank()
}

class AndroidRemotePushAddressProvider(
    private val context: Context,
    private val configuration: AndroidFirebaseConfiguration,
) : RemotePushAddressProvider {
    override val platform: String = "android"
    private var firebaseApp: FirebaseApp? = null
    private val registrationMutex = Mutex()

    override suspend fun currentAddress(): RemotePushAddress? =
        registrationMutex.withLock {
            if (!configuration.isConfigured) return null
            return runCatching {
                if (firebaseApp == null) firebaseApp = initializeFirebase()
                val registration = androidFcmRegistrationCoordinator.beginRegistration()
                FirebaseMessaging.getInstance().apply {
                    isAutoInitEnabled = true
                    register().await()
                }
                val installationId =
                    withTimeoutOrNull(REGISTRATION_TIMEOUT_MILLIS) { registration.await() }
                        ?: return@runCatching null
                RemotePushAddress(
                    platform = platform,
                    provider = "fcm",
                    token = installationId,
                    environment = "production",
                )
            }.getOrNull()
        }

    private fun initializeFirebase(): FirebaseApp {
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null) return existing
        val options =
            FirebaseOptions
                .Builder()
                .setProjectId(configuration.projectId)
                .setApplicationId(configuration.applicationId)
                .setApiKey(configuration.apiKey)
                .setGcmSenderId(configuration.senderId)
                .build()
        return requireNotNull(FirebaseApp.initializeApp(context, options)) {
            "Firebase could not initialize the default application"
        }
    }

    private companion object {
        const val REGISTRATION_TIMEOUT_MILLIS = 15_000L
    }
}

class GallrFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        androidFcmRegistrationCoordinator.acceptRegistration(installationId)
    }
}

private val androidFcmRegistrationCoordinator = FcmRegistrationCoordinator()

internal class FcmRegistrationCoordinator {
    private val lock = Any()
    private var pending: CompletableDeferred<String>? = null

    fun beginRegistration(): CompletableDeferred<String> =
        synchronized(lock) {
            CompletableDeferred<String>().also { pending = it }
        }

    fun acceptRegistration(installationId: String) {
        if (installationId.length !in 20..4096) return
        synchronized(lock) {
            pending?.complete(installationId)
            pending = null
        }
    }
}
