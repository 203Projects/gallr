package com.gallr.shared.data.network

import com.gallr.shared.observability.AppLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
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
 * Handle an incoming deeplink URL (OAuth callback).
 * Call this from the platform layer when the app receives a URL via the custom scheme.
 *
 * Supports both flows:
 * - PKCE: com.gallr.app://login-callback?code=...
 * - Implicit: com.gallr.app://login-callback#access_token=...
 */
suspend fun handleAuthDeeplink(
    supabaseClient: SupabaseClient,
    url: String,
) {
    authDeeplinkLog.info("auth_deeplink")
    try {
        // Check for PKCE flow (code in query params)
        val queryPart = url.substringAfter("?", "").substringBefore("#")
        val queryParams =
            queryPart.split("&").filter { it.contains("=") }.associate {
                val (key, value) = it.split("=", limit = 2)
                key to value
            }
        val code = queryParams["code"]

        if (code != null) {
            // PKCE flow
            supabaseClient.auth.exchangeCodeForSession(code)
            return
        }

        // Check for implicit flow (tokens in fragment)
        val fragment = url.substringAfter("#", "")
        if (fragment.isNotEmpty()) {
            // Implicit flow
            val params =
                fragment.split("&").filter { it.contains("=") }.associate {
                    val (key, value) = it.split("=", limit = 2)
                    key to value
                }
            val accessToken = params["access_token"] ?: return
            val refreshToken = params["refresh_token"] ?: return
            supabaseClient.auth.importSession(
                io.github.jan.supabase.auth.user.UserSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600,
                    tokenType = params["token_type"] ?: "bearer",
                    type = params["type"] ?: "bearer",
                ),
            )
        }
    } catch (e: Exception) {
        authDeeplinkLog.error("auth_deeplink", e)
    }
}
