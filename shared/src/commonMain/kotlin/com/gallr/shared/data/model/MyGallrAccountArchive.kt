package com.gallr.shared.data.model

import com.gallr.shared.diary.OwnPublicNote

data class MyGallrAccountArchive(
    val revision: Long,
    val visits: List<ExhibitionVisit>,
    val followedGalleries: List<FollowedGallery>,
    /** The account's own 감상, with or without a diary entry behind them (spec 103). */
    val publicNotes: List<OwnPublicNote> = emptyList(),
)

/**
 * One change to the account archive, applied on the device at once and sent in order through
 * `sync_my_gallr_archive`. The diary kinds never fail on a conflict on the server, so one stale change cannot block
 * the outbox (contracts/database.md M4).
 */
sealed interface MyGallrAccountMutation {
    val mutationId: String

    /** Adds a visit if the account has none for the exhibition; never changes an existing one (1.15 meaning). */
    data class AddVisit(
        override val mutationId: String,
        val visit: ExhibitionVisit,
    ) : MyGallrAccountMutation

    /** Removes the visit only; a 감상 for the exhibition stays (1.15 meaning). */
    data class RemoveVisit(
        override val mutationId: String,
        val exhibitionId: String,
    ) : MyGallrAccountMutation

    data class FollowGallery(
        override val mutationId: String,
        val gallery: FollowedGallery,
    ) : MyGallrAccountMutation

    data class UnfollowGallery(
        override val mutationId: String,
        val galleryKey: String,
    ) : MyGallrAccountMutation

    data class AcknowledgeGallery(
        override val mutationId: String,
        val galleryKey: String,
        val knownExhibitionIds: Set<String>,
    ) : MyGallrAccountMutation

    /** A sheet save: creates the entry, or replaces its diary fields when this save is the later one. */
    data class UpdateVisit(
        override val mutationId: String,
        val visit: ExhibitionVisit,
    ) : MyGallrAccountMutation

    /** The sign-in merge of a guest entry (`mergeEntry`). */
    data class MergeVisit(
        override val mutationId: String,
        val visit: ExhibitionVisit,
    ) : MyGallrAccountMutation

    /** 기록 삭제: removes the entry and its 감상. */
    data class DeleteEntry(
        override val mutationId: String,
        val exhibitionId: String,
    ) : MyGallrAccountMutation

    /** Asks for review of the entry's note as the exhibition's 감상. */
    data class PublishNote(
        override val mutationId: String,
        val exhibitionId: String,
    ) : MyGallrAccountMutation

    /** Withdraws the 감상 and keeps the private note. */
    data class UnpublishNote(
        override val mutationId: String,
        val exhibitionId: String,
    ) : MyGallrAccountMutation

    /** Records that the author has seen 감상이 공개됐어요. */
    data class AcknowledgeNoteApproval(
        override val mutationId: String,
        val exhibitionId: String,
    ) : MyGallrAccountMutation
}
