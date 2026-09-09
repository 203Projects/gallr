package com.gallr.shared.data.network

import com.gallr.shared.data.model.RemotePushAddress
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class GalleryAlertSubscriptionState(
    val galleryId: String,
    val enabled: Boolean,
    val revision: Int,
)

data class GalleryAlertInstallationState(
    val revision: Int,
    val subscriptions: List<GalleryAlertSubscriptionState>,
)

data class GalleryAlertPushTokenState(
    val revision: Int,
    val status: String,
)

interface GalleryAlertCommandSource {
    suspend fun registerInstallation(
        installationId: String,
        installationSecret: String,
        platform: String,
        locale: String,
        expectedRevision: Int,
    ): GalleryAlertInstallationState

    suspend fun registerPushToken(
        installationId: String,
        installationSecret: String,
        address: RemotePushAddress,
        expectedRevision: Int,
    ): GalleryAlertPushTokenState

    suspend fun setSubscription(
        installationId: String,
        installationSecret: String,
        galleryId: String,
        enabled: Boolean,
        expectedRevision: Int,
    ): GalleryAlertInstallationState
}

/**
 * Enrollment travels through the `gallery-alert-enrollment` Edge Function rather
 * than the enrollment RPCs directly. Installation identities are chosen on the
 * device, so only a server boundary can derive a source key the caller cannot
 * pick and meter durable growth against it. Request and response shapes are
 * otherwise the database payloads, so revision-checked retries are unchanged.
 */
class GalleryAlertApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
    private val accessTokenProvider: suspend () -> String? = { null },
) : GalleryAlertCommandSource {
    private val enrollmentEndpoint =
        "${supabaseUrl.trimEnd('/')}/functions/v1/gallery-alert-enrollment"

    override suspend fun registerInstallation(
        installationId: String,
        installationSecret: String,
        platform: String,
        locale: String,
        expectedRevision: Int,
    ): GalleryAlertInstallationState =
        enroll(
            RegisterInstallationRequestDto(
                action = "register_installation",
                installationId = installationId,
                installationSecret = installationSecret,
                platform = platform,
                locale = locale,
                expectedRevision = expectedRevision,
            ),
        ).body<InstallationStateDto>().toDomain()

    override suspend fun registerPushToken(
        installationId: String,
        installationSecret: String,
        address: RemotePushAddress,
        expectedRevision: Int,
    ): GalleryAlertPushTokenState =
        enroll(
            RegisterPushTokenRequestDto(
                action = "register_push_token",
                installationId = installationId,
                installationSecret = installationSecret,
                provider = address.provider,
                providerToken = address.token,
                providerEnvironment = address.environment,
                expectedRevision = expectedRevision,
            ),
        ).body<PushTokenStateDto>().toDomain()

    override suspend fun setSubscription(
        installationId: String,
        installationSecret: String,
        galleryId: String,
        enabled: Boolean,
        expectedRevision: Int,
    ): GalleryAlertInstallationState =
        enroll(
            SetSubscriptionRequestDto(
                action = "set_subscription",
                installationId = installationId,
                installationSecret = installationSecret,
                galleryId = galleryId,
                enabled = enabled,
                expectedRevision = expectedRevision,
            ),
        ).body<InstallationStateDto>().toDomain()

    /**
     * Signed-out devices enrol without a bearer token; the function treats a
     * missing or anonymous session as no account.
     */
    private suspend inline fun <reified T : Any> enroll(request: T) =
        client.post(enrollmentEndpoint) {
            accessTokenProvider()?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}

@Serializable
private data class RegisterInstallationRequestDto(
    val action: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("installation_secret") val installationSecret: String,
    val platform: String,
    val locale: String,
    @SerialName("expected_revision") val expectedRevision: Int,
)

@Serializable
private data class RegisterPushTokenRequestDto(
    val action: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("installation_secret") val installationSecret: String,
    val provider: String,
    @SerialName("provider_token") val providerToken: String,
    @SerialName("provider_environment") val providerEnvironment: String,
    @SerialName("expected_revision") val expectedRevision: Int,
)

@Serializable
private data class SetSubscriptionRequestDto(
    val action: String,
    @SerialName("installation_id") val installationId: String,
    @SerialName("installation_secret") val installationSecret: String,
    @SerialName("gallery_id") val galleryId: String,
    val enabled: Boolean,
    @SerialName("expected_revision") val expectedRevision: Int,
)

@Serializable
private data class InstallationStateDto(
    val revision: Int,
    val subscriptions: List<SubscriptionStateDto> = emptyList(),
) {
    fun toDomain() =
        GalleryAlertInstallationState(
            revision = revision,
            subscriptions = subscriptions.map(SubscriptionStateDto::toDomain),
        )
}

@Serializable
private data class SubscriptionStateDto(
    @SerialName("gallery_id") val galleryId: String,
    val enabled: Boolean,
    val revision: Int,
) {
    fun toDomain() = GalleryAlertSubscriptionState(galleryId, enabled, revision)
}

@Serializable
private data class PushTokenStateDto(
    @SerialName("push_token_revision") val revision: Int,
    @SerialName("push_token_status") val status: String,
) {
    fun toDomain() = GalleryAlertPushTokenState(revision = revision, status = status)
}
