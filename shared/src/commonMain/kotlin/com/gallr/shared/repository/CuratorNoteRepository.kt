package com.gallr.shared.repository

import com.gallr.shared.curatornote.CuratorNote
import com.gallr.shared.data.model.ExhibitionImage

/** Approved curator notes as visitors read them; drafts never reach this contract (spec 090 FR-006). */
interface CuratorNoteRepository {
    /** The newest approvals across the catalogue; the home feed keeps those about visible exhibitions. */
    suspend fun getPublishedNotes(): Result<List<CuratorNote>>

    /** The newest approved note for one exhibition, or null when it has none. */
    suspend fun getNote(exhibitionId: String): Result<CuratorNote?>

    /**
     * The exhibition's published gallery images in staff order; empty when it has only a cover. They are read here,
     * beside the note, because the exhibition page shows them at its top and the note places them with its insights.
     */
    suspend fun getGalleryImages(exhibitionId: String): Result<List<ExhibitionImage>>
}
