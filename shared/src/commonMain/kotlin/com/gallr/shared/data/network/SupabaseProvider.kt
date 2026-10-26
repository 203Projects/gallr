package com.gallr.shared.data.network

import com.gallr.shared.observability.AppLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
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
            install(Auth) {
                flowType = FlowType.PKCE
                scheme = "com.gallr.app"
                host = "login-callback"
            }
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
 * Accept only the configured PKCE callback. The SDK requires the locally saved
 * verifier, sends it with the code, and deletes it after successful exchange.
 * Fragment tokens are bearer credentials, not proof of a login initiated here.
 */
suspend fun handleAuthDeeplink(
    supabaseClient: SupabaseClient,
    url: String,
) {
    try {
        if (url.substringBefore("?") != "com.gallr.app://login-callback" || '#' in url) return
        val parameters = parseQueryString(url.substringAfter("?", ""))
        val codes = parameters.getAll("code") ?: return
        val code = codes.singleOrNull()?.takeIf { it.isNotBlank() } ?: return
        supabaseClient.auth.awaitInitialization()
        if (supabaseClient.auth.currentSessionOrNull() != null) return
        if (supabaseClient.auth.codeVerifierCache.loadCodeVerifier() == null) return
        supabaseClient.auth.exchangeCodeForSession(code)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        authDeeplinkLog.error("auth_deeplink", error)
    }
}
