package com.gallr.shared.data.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun createGallrHttpClientEngine(): HttpClientEngine = Darwin.create()
