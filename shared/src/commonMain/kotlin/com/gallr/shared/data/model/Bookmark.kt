package com.gallr.shared.data.model

import kotlin.time.Instant

data class Bookmark(
    val exhibitionId: String,
    val savedAt: Instant,
)
