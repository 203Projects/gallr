package com.gallr.shared.data.network

import com.gallr.shared.observability.AppLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.parseQueryString
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

private val authDeeplinkLog = AppLog.tagged("AuthDeeplink")

private const val AUTH_CALLBACK_SCHEME = "com.gallr.app"
private const val AUTH_CALLBACK_HOST = "login-callback"
private const val AUTH_CALLBACK_URL = "$AUTH_CALLBACK_SCHEME://$AUTH_CALLBACK_HOST"

class GallrNetworkClients internal constructor(
    val supabaseClient: SupabaseClient,
    val restClient: HttpClient,
    private val engine: HttpClientEngine,
) {
    private val anonymousClientDelegate = lazy { createRestClient(engine) }
    val anonymousClient: HttpClient
        get() = anonymousClientDelegate.value

    suspend fun close() {
        try {
            supabaseClient.close()
        } finally {
            try {
                restClient.close()
            } finally {
                try {
                    anonymousClientDelegate.value.close()
                } finally {
                    engine.close()
                }
            }
        }
    }
}

/** Creates the application-owned Supabase and REST clients over one platform connection pool. */
fun createGallrNetworkClients(
    supabaseUrl: String,
    supabaseKey: String,
): GallrNetworkClients {
    val engine = createGallrHttpClientEngine()
    val supabaseClient =
        createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey,
        ) {
            httpEngine = engine
            requestTimeout = NETWORK_REQUEST_TIMEOUT
            install(Auth) { useGallrSignInCallback() }
            install(Postgrest)
            install(Storage)
        }
    val restClient = createRestClient(engine, supabaseKey)
    return GallrNetworkClients(
        supabaseClient = supabaseClient,
        restClient = restClient,
        engine = engine,
    )
}

private fun createRestClient(
    engine: HttpClientEngine,
    supabaseKey: String? = null,
) = HttpClient(engine) {
    install(HttpTimeout) {
        requestTimeoutMillis = NETWORK_REQUEST_TIMEOUT.inWholeMilliseconds
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            },
        )
    }
    supabaseKey?.let { key -> defaultRequest { headers.appendSupabaseApiKey(key) } }
}

private val NETWORK_REQUEST_TIMEOUT = 10.seconds

/**
 * Browser and email sign-ins return to the app callback with an authorization code (PKCE). A code
 * is worth nothing without the verifier this device saved when it started the sign-in, so a link
 * crafted elsewhere cannot sign the app in to another account.
 */
internal fun AuthConfig.useGallrSignInCallback() {
    flowType = FlowType.PKCE
    scheme = AUTH_CALLBACK_SCHEME
    host = AUTH_CALLBACK_HOST
}

/**
 * Handle a URL the platform delivered through the app's custom scheme.
 *
 * Only `com.gallr.app://login-callback?code=…` with exactly one code is exchanged, and only while
 * the app is signed out and holds the verifier of a sign-in it started. Tokens in a fragment are
 * bearer credentials that prove nothing about who started the sign-in, so they are never imported.
 */
suspend fun handleAuthDeeplink(
    supabaseClient: SupabaseClient,
    url: String,
) {
    authDeeplinkLog.info("auth_deeplink")
    try {
        val code = authCallbackCode(url) ?: return authDeeplinkLog.debug("auth_deeplink")
        val auth = supabaseClient.auth
        auth.awaitInitialization()
        val canExchange = auth.currentSessionOrNull() == null && auth.codeVerifierCache.loadCodeVerifier() != null
        if (!canExchange) return authDeeplinkLog.debug("auth_deeplink")
        auth.exchangeCodeForSession(code)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        authDeeplinkLog.error("auth_deeplink", error)
    }
}

/** The single authorization code of an exact sign-in callback URL, or null for anything else. */
internal fun authCallbackCode(url: String): String? {
    if ('#' in url || url.substringBefore('?') != AUTH_CALLBACK_URL) return null
    val codes = parseQueryString(url.substringAfter('?', "")).getAll("code") ?: return null
    return codes.singleOrNull()?.takeIf { it.isNotBlank() }
}
