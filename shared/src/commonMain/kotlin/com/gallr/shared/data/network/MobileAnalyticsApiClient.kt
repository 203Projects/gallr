package com.gallr.shared.data.network

import com.gallr.shared.analytics.MobileAnalyticsBatch
import com.gallr.shared.analytics.MobileAnalyticsSink
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/** Anonymous, identity-free delivery client for Gallr-owned aggregate analytics. */
class MobileAnalyticsApiClient internal constructor(
    private val client: HttpClient,
    supabaseUrl: String,
) : MobileAnalyticsSink {
    private val endpoint = "${supabaseUrl.trimEnd('/')}/functions/v1/mobile-analytics"

    override suspend fun deliver(batch: MobileAnalyticsBatch) {
        val response =
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Origin, "app://gallr")
                setBody(batch)
            }
        check(response.status == HttpStatusCode.NoContent) {
            "Mobile analytics delivery failed."
        }
    }
}

/** Uses the dedicated header-free client even when the app retains a legacy anon JWT elsewhere. */
fun createMobileAnalyticsApiClient(
    clients: GallrNetworkClients,
    supabaseUrl: String,
): MobileAnalyticsSink {
    val createDelegate = { MobileAnalyticsApiClient(clients.anonymousClient, supabaseUrl) }
    return lazyMobileAnalyticsSink(createDelegate)
}

internal fun lazyMobileAnalyticsSink(createDelegate: () -> MobileAnalyticsSink): MobileAnalyticsSink {
    val delegate = lazy(createDelegate)
    return MobileAnalyticsSink { batch -> delegate.value.deliver(batch) }
}
