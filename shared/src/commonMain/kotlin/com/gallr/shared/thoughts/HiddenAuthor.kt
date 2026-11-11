package com.gallr.shared.thoughts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A person whose 감상 this device no longer shows (이 사용자의 감상 숨기기, FR-024). Kept on this device only. */
@Serializable
data class HiddenAuthor(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("hidden_at") val hiddenAt: Instant,
)
