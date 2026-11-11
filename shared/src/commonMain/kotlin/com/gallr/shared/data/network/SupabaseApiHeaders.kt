package com.gallr.shared.data.network

import io.ktor.http.HeadersBuilder

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

private fun String.legacyJwtRole(): String? = if (isLegacyJwtApiKey()) jwtStringClaim(this, "role") else null
