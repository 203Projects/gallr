package com.gallr.shared.data.network

import com.gallr.shared.repository.AccountDeletionAuthenticationRequiredException
import com.gallr.shared.repository.AccountDeletionRateLimitedException
import com.gallr.shared.repository.AccountDeletionReauthenticationRequiredException
import com.gallr.shared.repository.AccountDeletionSource
import com.gallr.shared.repository.AccountDeletionStatusUnknownException
import com.gallr.shared.repository.AccountDeletionSupportRequiredException
import com.gallr.shared.repository.AccountDeletionUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class AccountDeletionApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
) : AccountDeletionSource {
    private val endpoint = "${supabaseUrl.trimEnd('/')}/functions/v1/delete-account"

    override suspend fun deleteAccount(accessToken: String) {
        if (accessToken.isBlank()) throw AccountDeletionAuthenticationRequiredException()

        val response =
            client.post(endpoint) {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        val body = response.bodyAsText()
        if (body.length > MAX_RESPONSE_CHARACTERS) throw AccountDeletionUnavailableException()
        val payload = body.toJsonObjectOrNull()

        if (response.status.isSuccess()) {
            if (payload?.string("status") == "deleted" && payload.string("request_id")?.isUuid() == true) {
                return
            }
            throw AccountDeletionUnavailableException()
        }

        when (payload?.get("error")?.jsonObjectOrNull()?.string("code")) {
            "authentication_required" -> throw AccountDeletionAuthenticationRequiredException()
            "reauthentication_required" -> throw AccountDeletionReauthenticationRequiredException()
            "support_required" -> throw AccountDeletionSupportRequiredException()
            "rate_limited" -> throw AccountDeletionRateLimitedException()
            "deletion_status_unknown" -> throw AccountDeletionStatusUnknownException()
            else -> throw AccountDeletionUnavailableException()
        }
    }
}

private fun String.toJsonObjectOrNull(): JsonObject? =
    runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull()

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun String.isUuid(): Boolean = UUID_PATTERN.matches(this)

private const val MAX_RESPONSE_CHARACTERS = 16 * 1024
private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
