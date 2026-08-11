package com.gallr.shared.data.network

import com.gallr.shared.data.model.Editor
import com.gallr.shared.data.network.dto.EditorDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class EditorApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
) {
    private val restBase = "$supabaseUrl/rest/v1"

    /**
     * Fetches all editors. The selector ViewModel splits them into "Currently
     * curating" and "Past editors" based on Editor.isCurrentlyActive(today).
     *
     * DB-side sort: is_active desc, active_from desc. Then the pure
     * promoteHouseEditor() pass pulls the gallr-editors row to position 0.
     */
    suspend fun fetchAllEditors(): List<Editor> {
        val query = "select=*&order=is_active.desc,active_from.desc"
        val editors =
            client
                .get("$restBase/editors?$query")
                .body<List<EditorDto>>()
                .map { it.toDomain() }
        return promoteHouseEditor(editors)
    }

    /**
     * Fetches a single editor by slug. Returns null when no row matches.
     */
    suspend fun fetchEditorById(id: String): Editor? {
        val query = "select=*&id=eq.$id&limit=1"
        return client
            .get("$restBase/editors?$query")
            .body<List<EditorDto>>()
            .firstOrNull()
            ?.toDomain()
    }
}

/**
 * Pulls the Editor.HOUSE_EDITOR_ID row to position 0 of the list.
 * If it isn't present, returns the list unchanged.
 *
 * Extracted as a top-level pure function (not a method) so the contract
 * can be unit-tested without an HTTP mock. See EditorApiClientSortTest.
 */
internal fun promoteHouseEditor(editors: List<Editor>): List<Editor> {
    val (house, rest) = editors.partition { it.id == Editor.HOUSE_EDITOR_ID }
    return house + rest
}
