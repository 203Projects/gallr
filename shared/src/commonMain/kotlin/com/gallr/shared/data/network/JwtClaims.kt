package com.gallr.shared.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/**
 * A string claim from a JWT's payload, or null when the token is not a JWT whose payload can be read. The signature is
 * not checked: use the value only as a local hint or key, never to authorise anything.
 */
internal fun jwtStringClaim(
    token: String,
    claim: String,
): String? {
    val parts = token.split('.')
    if (parts.size != 3) return null
    val payload =
        parts[1]
            .replace('-', '+')
            .replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }

    // A token this cannot read has no claim; callers treat it as absent.
    return runCatching {
        Json
            .parseToJsonElement(Base64.Default.decode(payload).decodeToString())
            .jsonObject[claim]
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()
}
