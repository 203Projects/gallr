package com.gallr.shared.data.network

import io.ktor.http.HeadersBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

internal fun HeadersBuilder.appendSupabaseApiKey(apiKey: String) {
    val key = apiKey.trim()
    require(key.isNotEmpty()) { "Supabase API key is required" }
    require(!key.startsWith("sb_secret_")) {
        "Supabase secret API keys are not allowed in public clients"
    }
    require(key.legacyJwtRole() != "service_role") {
        "Supabase service role API keys are not allowed in public clients"
    }

    append("apikey", key)
    if (key.isLegacyJwtApiKey()) {
        append("Authorization", "Bearer $key")
    }
}

private fun String.isLegacyJwtApiKey(): Boolean = startsWith("eyJ") && count { it == '.' } == 2

private fun String.legacyJwtRole(): String? {
    if (!isLegacyJwtApiKey()) return null
    val payload =
        split('.')[1]
            .replace('-', '+')
            .replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }

    return runCatching {
        Json
            .parseToJsonElement(Base64.Default.decode(payload).decodeToString())
            .jsonObject["role"]
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()
}
