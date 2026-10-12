package com.gallr.shared.data.network

import com.gallr.shared.data.network.dto.CuratorNotePage
import com.gallr.shared.data.network.dto.decodeCuratorNotePage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder

/** Reads approved curator notes from the public `curator_notes_published` view (spec 090 R6). */
interface CuratorNoteApi {
    suspend fun fetchPublished(limit: Int): CuratorNotePage

    suspend fun fetchForExhibition(exhibitionId: String): CuratorNotePage
}

internal fun buildPublishedCuratorNotesUrl(
    restBase: String,
    limit: Int,
): String =
    URLBuilder("$restBase/curator_notes_published")
        .apply {
            parameters.append("select", "*")
            parameters.append("order", "approved_at.desc")
            parameters.append("limit", limit.toString())
        }.buildString()

internal fun buildCuratorNoteForExhibitionUrl(
    restBase: String,
    exhibitionId: String,
): String =
    URLBuilder("$restBase/curator_notes_published")
        .apply {
            parameters.append("select", "*")
            parameters.append("exhibition_id", "eq.${postgrestFilterLiteral(exhibitionId)}")
            parameters.append("limit", "1")
        }.buildString()

class CuratorNoteApiClient(
    private val client: HttpClient,
    supabaseUrl: String,
) : CuratorNoteApi {
    private val restBase = "$supabaseUrl/rest/v1"

    override suspend fun fetchPublished(limit: Int): CuratorNotePage =
        decodeCuratorNotePage(client.get(buildPublishedCuratorNotesUrl(restBase, limit)).bodyAsText())

    override suspend fun fetchForExhibition(exhibitionId: String): CuratorNotePage =
        decodeCuratorNotePage(client.get(buildCuratorNoteForExhibitionUrl(restBase, exhibitionId)).bodyAsText())
}
