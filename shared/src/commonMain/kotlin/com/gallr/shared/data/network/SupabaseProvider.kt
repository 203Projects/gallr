package com.gallr.shared.data.network

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

/**
 * Creates a SupabaseClient configured for gallr.
 * Called once in each platform entry point (MainActivity, MainViewController).
 */
fun createGallrSupabaseClient(
    supabaseUrl: String,
    supabaseKey: String,
): SupabaseClient = createSupabaseClient(
    supabaseUrl = supabaseUrl,
    supabaseKey = supabaseKey,
) {
    install(Auth) {
        scheme = "com.gallr.app"
        host = "login-callback"
    }
    install(Postgrest)
    install(Storage)
}

/**
 * Handle an incoming deeplink URL (OAuth callback).
 * Call this from the platform layer when the app receives a URL via the custom scheme.
 *
 * Supports both flows:
 * - PKCE: com.gallr.app://login-callback?code=...
 * - Implicit: com.gallr.app://login-callback#access_token=...
 */
suspend fun handleAuthDeeplink(supabaseClient: SupabaseClient, url: String) {
    // Log deeplink without tokens
    println("DEEPLINK_RECEIVED: ${url.substringBefore("#").substringBefore("?")}")
    try {
        // Check for PKCE flow (code in query params)
        val queryPart = url.substringAfter("?", "").substringBefore("#")
        val queryParams = queryPart.split("&").filter { it.contains("=") }.associate {
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
            val params = fragment.split("&").filter { it.contains("=") }.associate {
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
                )
            )
        }
    } catch (e: Exception) {
        println("DEEPLINK_ERROR: ${e::class.simpleName}")
    }
}
