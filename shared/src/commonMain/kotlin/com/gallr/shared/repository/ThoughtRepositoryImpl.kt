package com.gallr.shared.repository

import com.gallr.shared.data.model.Thought
import com.gallr.shared.data.network.dto.ProfileDto
import com.gallr.shared.data.network.dto.ThoughtDto
import com.gallr.shared.data.network.dto.ThoughtInsert
import com.gallr.shared.observability.AppLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

private val thoughtRepositoryLog = AppLog.tagged("ThoughtRepository")

class ThoughtRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : ThoughtRepository {
    override suspend fun getThoughtsForExhibition(
        exhibitionId: String,
        limit: Int,
    ): List<Thought> {
        // Fetch thoughts
        val thoughts =
            supabaseClient.postgrest
                .from("thoughts")
                .select {
                    filter {
                        eq("exhibition_id", exhibitionId)
                        eq("is_approved", true)
                    }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }.decodeList<ThoughtDto>()

        if (thoughts.isEmpty()) return emptyList()

        // Batch-fetch profiles for all thought authors (avoid N+1)
        val userIds = thoughts.map { it.userId }.distinct()
        val profiles =
            supabaseClient.postgrest
                .from("profiles")
                .select {
                    filter { isIn("id", userIds) }
                }.decodeList<ProfileDto>()
                .associateBy { it.id }

        return thoughts.map { dto -> dto.copy(profiles = profiles[dto.userId]).toDomain() }
    }

    override suspend fun getUserThoughts(userId: String): List<Thought> {
        val thoughts =
            supabaseClient.postgrest
                .from("thoughts")
                .select {
                    filter { eq("user_id", userId) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<ThoughtDto>()

        if (thoughts.isEmpty()) return emptyList()

        val profile =
            supabaseClient.postgrest
                .from("profiles")
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<ProfileDto>()

        return thoughts.map { it.copy(profiles = profile).toDomain() }
    }

    override suspend fun submitThought(
        exhibitionId: String,
        content: String,
    ) {
        supabaseClient.postgrest
            .from("thoughts")
            .insert(ThoughtInsert(exhibitionId = exhibitionId, content = content))
    }

    override suspend fun updateThought(
        thoughtId: String,
        content: String,
    ) {
        supabaseClient.postgrest
            .from("thoughts")
            .update({ set("content", content) }) {
                filter { eq("id", thoughtId) }
            }
    }

    override suspend fun deleteThought(thoughtId: String) {
        supabaseClient.postgrest
            .from("thoughts")
            .delete { filter { eq("id", thoughtId) } }
    }

    override suspend fun getUserThoughtForExhibition(exhibitionId: String): Thought? {
        val userId =
            supabaseClient.auth.currentUserOrNull()?.id
                ?: try {
                    supabaseClient.auth.retrieveUserForCurrentSession().id
                } catch (error: Exception) {
                    thoughtRepositoryLog.warn("resolve_current_user", error)
                    null
                }
                ?: return null
        val dto =
            supabaseClient.postgrest
                .from("thoughts")
                .select {
                    filter {
                        eq("exhibition_id", exhibitionId)
                        eq("user_id", userId)
                    }
                }.decodeSingleOrNull<ThoughtDto>() ?: return null
        return dto.toDomain()
    }

    override suspend fun getUserThoughtCount(userId: String): Int =
        supabaseClient.postgrest
            .from("thoughts")
            .select { filter { eq("user_id", userId) } }
            .decodeList<ThoughtDto>()
            .size

    override suspend fun getPendingThoughts(): List<Thought> {
        val thoughts =
            supabaseClient.postgrest
                .from("thoughts")
                .select {
                    filter { eq("is_approved", false) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<ThoughtDto>()

        if (thoughts.isEmpty()) return emptyList()

        val userIds = thoughts.map { it.userId }.distinct()
        val profiles =
            supabaseClient.postgrest
                .from("profiles")
                .select { filter { isIn("id", userIds) } }
                .decodeList<ProfileDto>()
                .associateBy { it.id }

        return thoughts.map { dto -> dto.copy(profiles = profiles[dto.userId]).toDomain() }
    }

    override suspend fun approveThought(thoughtId: String) {
        supabaseClient.postgrest
            .from("thoughts")
            .update({ set("is_approved", true) }) {
                filter { eq("id", thoughtId) }
            }
    }

    override suspend fun rejectThought(thoughtId: String) {
        supabaseClient.postgrest
            .from("thoughts")
            .delete { filter { eq("id", thoughtId) } }
    }
}
