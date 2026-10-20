package com.gallr.shared.data.network

import com.gallr.shared.app.AppPlatform
import com.gallr.shared.app.AppVersion
import com.gallr.shared.app.UpdatePolicy
import com.gallr.shared.observability.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reads the minimum and recommended app version staff published for a platform (spec 098). */
interface AppVersionPolicyApi {
    /** The policy, or null when it could not be read: the caller keeps its last answer (FR-005). */
    suspend fun fetch(platform: AppPlatform): UpdatePolicy?
}

internal fun appVersionPolicyRequestBody(platform: AppPlatform): String =
    buildJsonObject { put("p_platform", platform.wireName) }.toString()

/**
 * `{"minimum_version": "N.N.N", "recommended_version": "N.N.N"}` is a policy; `null` (no row), an error object, a
 * missing field or a version that is not three numbers is a failed read.
 */
internal fun decodeUpdatePolicy(body: String): UpdatePolicy? {
    val row = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    val minimum = row.version("minimum_version") ?: return null
    val recommended = row.version("recommended_version") ?: return null
    return UpdatePolicy(minimum = minimum, recommended = recommended)
}

private fun JsonObject.version(name: String): AppVersion? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(AppVersion::parse)

private val policyLog = AppLog.tagged("AppVersionPolicy")

class AppVersionPolicyApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
) : AppVersionPolicyApi {
    private val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/get_app_version_policy"

    override suspend fun fetch(platform: AppPlatform): UpdatePolicy? =
        try {
            val response =
                client.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(appVersionPolicyRequestBody(platform))
                }
            if (response.status.isSuccess()) decodeUpdatePolicy(response.bodyAsText()) else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            policyLog.warn("app_version_policy_read_failed", error)
            null
        }
}
