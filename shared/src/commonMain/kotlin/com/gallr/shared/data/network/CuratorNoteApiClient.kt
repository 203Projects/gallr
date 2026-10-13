package com.gallr.shared.data.network

import com.gallr.shared.data.model.ExhibitionImage
import com.gallr.shared.data.model.GALLERY_IMAGE_LIMIT
import com.gallr.shared.data.network.dto.CuratorNotePage
import com.gallr.shared.data.network.dto.decodeCuratorNotePage
import com.gallr.shared.data.network.dto.decodeExhibitionImages
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder

/**
 * Reads approved curator notes from the public `curator_notes_published` view (spec 090 R6) and an exhibition's
 * published gallery images from `exhibition_gallery_images`.
 */
interface CuratorNoteApi {
    suspend fun fetchPublished(limit: Int): CuratorNotePage

    suspend fun fetchForExhibition(exhibitionId: String): CuratorNotePage

    suspend fun fetchGalleryImages(exhibitionId: String): List<ExhibitionImage>
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

internal fun buildExhibitionGalleryImagesUrl(
    restBase: String,
    exhibitionId: String,
): String =
    URLBuilder("$restBase/exhibition_gallery_images")
        .apply {
            parameters.append("select", "position,url,alt_ko,alt_en,credit,width,height")
            parameters.append("exhibition_id", "eq.${postgrestFilterLiteral(exhibitionId)}")
            parameters.append("order", "position.asc")
            parameters.append("limit", GALLERY_IMAGE_LIMIT.toString())
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

    override suspend fun fetchGalleryImages(exhibitionId: String): List<ExhibitionImage> =
        decodeExhibitionImages(client.get(buildExhibitionGalleryImagesUrl(restBase, exhibitionId)).bodyAsText())
}
