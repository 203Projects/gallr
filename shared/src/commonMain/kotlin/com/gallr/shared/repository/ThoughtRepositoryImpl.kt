package com.gallr.shared.repository

import com.gallr.shared.data.model.Thought
import com.gallr.shared.data.network.dto.ProfileDto
import com.gallr.shared.data.network.dto.ThoughtDto
import com.gallr.shared.data.network.dto.ThoughtInsert
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

class ThoughtRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : ThoughtRepository {

    override suspend fun getThoughtsForExhibition(exhibitionId: String, limit: Int): List<Thought> {
        // Fetch thoughts
        val thoughts = supabaseClient.postgrest
            .from("thoughts")
            .select {
                filter {
                    eq("exhibition_id", exhibitionId)
                    eq("is_approved", true)
                }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<ThoughtDto>()

        if (thoughts.isEmpty()) return emptyList()

        // Batch-fetch profiles for all thought authors (avoid N+1)
        val userIds = thoughts.map { it.userId }.distinct()
        val profiles = supabaseClient.postgrest
            .from("profiles")
            .select {
                filter { isIn("id", userIds) }
            }
            .decodeList<ProfileDto>()
            .associateBy { it.id }

        return thoughts.map { dto ->
            val profile = profiles[dto.userId]
            Thought(
                id = dto.id,
                userId = dto.userId,
                exhibitionId = dto.exhibitionId,
                content = dto.content,
                isApproved = dto.isApproved,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
                authorDisplayName = profile?.displayName ?: "",
                authorAvatarUrl = profile?.avatarUrl,
            )
        }
    }

    override suspend fun submitThought(exhibitionId: String, content: String) {
        supabaseClient.postgrest
            .from("thoughts")
            .insert(ThoughtInsert(exhibitionId = exhibitionId, content = content))
    }

    override suspend fun updateThought(thoughtId: String, content: String) {
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
        val userId = supabaseClient.auth.currentUserOrNull()?.id
            ?: try { supabaseClient.auth.retrieveUserForCurrentSession()?.id } catch (_: Exception) { null }
            ?: return null
        val dto = supabaseClient.postgrest
            .from("thoughts")
            .select {
                filter {
                    eq("exhibition_id", exhibitionId)
                    eq("user_id", userId)
                }
            }
            .decodeSingleOrNull<ThoughtDto>() ?: return null
        return dto.toDomain()
    }

    override suspend fun getUserThoughtCount(userId: String): Int =
        supabaseClient.postgrest
            .from("thoughts")
            .select { filter { eq("user_id", userId) } }
            .decodeList<ThoughtDto>()
            .size

    override suspend fun getPendingThoughts(): List<Thought> {
        val thoughts = supabaseClient.postgrest
            .from("thoughts")
            .select {
                filter { eq("is_approved", false) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<ThoughtDto>()

        if (thoughts.isEmpty()) return emptyList()

        val userIds = thoughts.map { it.userId }.distinct()
        val profiles = supabaseClient.postgrest
            .from("profiles")
            .select { filter { isIn("id", userIds) } }
            .decodeList<ProfileDto>()
            .associateBy { it.id }

        return thoughts.map { dto ->
            val profile = profiles[dto.userId]
            Thought(
                id = dto.id,
                userId = dto.userId,
                exhibitionId = dto.exhibitionId,
                content = dto.content,
                isApproved = dto.isApproved,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
                authorDisplayName = profile?.displayName ?: "",
                authorAvatarUrl = profile?.avatarUrl,
            )
        }
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
