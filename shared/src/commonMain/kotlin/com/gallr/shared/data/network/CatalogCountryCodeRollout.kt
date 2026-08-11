package com.gallr.shared.data.network

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

/**
 * Keeps mobile readers compatible while the additive country column rolls out.
 * Once a backend reports the column missing, this client instance omits it and
 * lets the DTO's temporary Korea default decode the legacy response.
 */
internal class CatalogCountryCodeRollout {
    private var includeCountryCode = true

    suspend fun <T> fetch(
        request: suspend (includeCountryCode: Boolean) -> T,
        isMissingCountryCodeColumn: suspend (Throwable) -> Boolean,
    ): T {
        val requestedCountryCode = includeCountryCode
        return try {
            request(requestedCountryCode)
        } catch (failure: Throwable) {
            if (!requestedCountryCode || !isMissingCountryCodeColumn(failure)) throw failure
            includeCountryCode = false
            request(false)
        }
    }
}

internal suspend fun Throwable.isMissingCountryCodeColumnResponse(): Boolean {
    val failure = this as? ClientRequestException ?: return false
    if (failure.response.status != HttpStatusCode.BadRequest) return false
    val responseText = try {
        failure.response.bodyAsText()
    } catch (_: Throwable) {
        failure.message
    }
    return "country_code" in responseText &&
        ("does not exist" in responseText || "42703" in responseText)
}
