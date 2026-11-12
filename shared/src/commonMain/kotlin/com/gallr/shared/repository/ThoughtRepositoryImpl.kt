package com.gallr.shared.repository

import com.gallr.shared.data.network.dto.PublicThoughtDto
import com.gallr.shared.observability.AppLog
import com.gallr.shared.thoughts.PublicThought
import com.gallr.shared.thoughts.ThoughtReportReason
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

private val thoughtRepositoryLog = AppLog.tagged("ThoughtRepository")

private val publicThoughtJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

class ThoughtRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : ThoughtRepository {
    override suspend fun exhibitionThoughts(
        exhibitionId: String,
        limit: Int,
    ): List<PublicThought> {
        val result =
            supabaseClient.postgrest.rpc(
                "list_exhibition_thoughts",
                buildJsonObject {
                    put("p_exhibition_id", exhibitionId)
                    put("p_limit", limit)
                },
            )
        val rows = publicThoughtJson.parseToJsonElement(result.data).jsonArray
        val thoughts =
            rows.mapNotNull { row ->
                runCatching {
                    publicThoughtJson.decodeFromJsonElement(PublicThoughtDto.serializer(), row).toDomain()
                }.getOrNull()
            }
        // One malformed row must not hide the others; the count of dropped rows is not logged with any id.
        if (thoughts.size < rows.size) thoughtRepositoryLog.warn("decode_public_thought")
        return thoughts
    }

    override suspend fun report(
        thoughtId: String,
        reason: ThoughtReportReason,
    ) {
        try {
            supabaseClient.postgrest.rpc(
                "report_thought",
                buildJsonObject {
                    put("p_id", thoughtId)
                    put("p_reason", reason.wire)
                },
            )
        } catch (error: RestException) {
            throw reportFailureOf(error) ?: error
        } catch (error: IOException) {
            // supabase-kt's HttpRequestException is an IOException: no connection, or a refresh that cannot run.
            throw ThoughtReportConnectionException()
        } catch (error: HttpRequestTimeoutException) {
            throw ThoughtReportConnectionException()
        }
    }

    private fun reportFailureOf(error: RestException): Exception? =
        when (error.error) {
            "thought_report_exists" -> ThoughtReportAlreadySentException()
            "thought_report_unauthenticated" -> ThoughtReportSignInRequiredException()
            "thought_not_public", "thought_not_found" -> ThoughtNoLongerPublicException()
            else -> if (error.statusCode == UNAUTHORIZED) ThoughtReportSignInRequiredException() else null
        }
}

private const val UNAUTHORIZED = 401
