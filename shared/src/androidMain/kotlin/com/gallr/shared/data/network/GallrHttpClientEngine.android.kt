package com.gallr.shared.data.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createGallrHttpClientEngine(): HttpClientEngine = OkHttp.create()
