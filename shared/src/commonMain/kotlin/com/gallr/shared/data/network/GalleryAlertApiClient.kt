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

class GalleryAlertApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
    private val accessTokenProvider: suspend () -> String? = { null },
) : GalleryAlertCommandSource {
    private val rpcEndpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc"

    override suspend fun registerInstallation(
        installationId: String,
        installationSecret: String,
        platform: String,
        locale: String,
        expectedRevision: Int,
    ): GalleryAlertInstallationState {
        val accessToken = accessTokenProvider()?.takeIf { it.isNotBlank() }
        return client
            .post("$rpcEndpoint/register_gallery_alert_installation") {
                if (accessToken != null) bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterInstallationRequestDto(
                        installationId = installationId,
                        installationSecret = installationSecret,
                        platform = platform,
                        locale = locale,
                        expectedRevision = expectedRevision,
                    ),
                )
            }.body<InstallationStateDto>()
            .toDomain()
    }

    override suspend fun registerPushToken(
        installationId: String,
        installationSecret: String,
        address: RemotePushAddress,
        expectedRevision: Int,
    ): GalleryAlertPushTokenState {
        val accessToken = accessTokenProvider()?.takeIf { it.isNotBlank() }
        return client
            .post("$rpcEndpoint/register_gallery_alert_push_token") {
                if (accessToken != null) bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterPushTokenRequestDto(
                        installationId = installationId,
                        installationSecret = installationSecret,
                        provider = address.provider,
                        providerToken = address.token,
                        providerEnvironment = address.environment,
                        expectedRevision = expectedRevision,
                    ),
                )
            }.body<PushTokenStateDto>()
            .toDomain()
    }

    override suspend fun setSubscription(
        installationId: String,
        installationSecret: String,
        galleryId: String,
        enabled: Boolean,
        expectedRevision: Int,
    ): GalleryAlertInstallationState {
        val accessToken = accessTokenProvider()?.takeIf { it.isNotBlank() }
        return client
            .post("$rpcEndpoint/set_gallery_alert_subscription") {
                if (accessToken != null) bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    SetSubscriptionRequestDto(
                        installationId = installationId,
                        installationSecret = installationSecret,
                        galleryId = galleryId,
                        enabled = enabled,
                        expectedRevision = expectedRevision,
                    ),
                )
            }.body<InstallationStateDto>()
            .toDomain()
    }
}

@Serializable
private data class RegisterInstallationRequestDto(
    @SerialName("p_installation_id") val installationId: String,
    @SerialName("p_installation_secret") val installationSecret: String,
    @SerialName("p_platform") val platform: String,
    @SerialName("p_locale") val locale: String,
    @SerialName("p_expected_revision") val expectedRevision: Int,
)

@Serializable
private data class RegisterPushTokenRequestDto(
    @SerialName("p_installation_id") val installationId: String,
    @SerialName("p_installation_secret") val installationSecret: String,
    @SerialName("p_provider") val provider: String,
    @SerialName("p_provider_token") val providerToken: String,
    @SerialName("p_provider_environment") val providerEnvironment: String,
    @SerialName("p_expected_revision") val expectedRevision: Int,
)

@Serializable
private data class SetSubscriptionRequestDto(
    @SerialName("p_installation_id") val installationId: String,
    @SerialName("p_installation_secret") val installationSecret: String,
    @SerialName("p_gallery_id") val galleryId: String,
    @SerialName("p_enabled") val enabled: Boolean,
    @SerialName("p_expected_revision") val expectedRevision: Int,
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
