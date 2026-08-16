package com.gallr.shared.data.model

data class MyGallrAccountArchive(
    val revision: Long,
    val visits: List<ExhibitionVisit>,
    val followedGalleries: List<FollowedGallery>,
)

sealed interface MyGallrAccountMutation {
    val mutationId: String

    data class AddVisit(
        override val mutationId: String,
        val visit: ExhibitionVisit,
    ) : MyGallrAccountMutation

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
}
