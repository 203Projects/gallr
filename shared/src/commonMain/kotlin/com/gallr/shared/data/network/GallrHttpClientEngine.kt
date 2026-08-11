package com.gallr.shared.data.network

import io.ktor.client.engine.HttpClientEngine

internal expect fun createGallrHttpClientEngine(): HttpClientEngine
