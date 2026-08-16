package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AuthState
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.ExhibitionVisitSnapshot
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.GallrUser
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class MyGallrAccountNudgeTest {
    @Test
    fun `anonymous visitor becomes eligible after three combined personal records`() {
        val state =
            MyGallrUiState(
                visits = listOf(visit("one"), visit("two")),
                followedGalleryRecords = listOf(followedGallery()),
                isAccountNudgeLoaded = true,
            )

        assertTrue(state.shouldShowAccountNudge(AuthState.Anonymous))
    }

    @Test
    fun `fewer than three records is not eligible`() {
        val state =
            MyGallrUiState(
                visits = listOf(visit("one"), visit("two")),
                isAccountNudgeLoaded = true,
            )

        assertFalse(state.shouldShowAccountNudge(AuthState.Anonymous))
    }

    @Test
    fun `dismissed invitation stays hidden`() {
        val state =
            MyGallrUiState(
                visits = listOf(visit("one"), visit("two"), visit("three")),
                isAccountNudgeDismissed = true,
                isAccountNudgeLoaded = true,
            )

        assertFalse(state.shouldShowAccountNudge(AuthState.Anonymous))
    }

    @Test
    fun `invitation stays hidden during auth loading and after sign in`() {
        val state =
            MyGallrUiState(
                visits = listOf(visit("one"), visit("two"), visit("three")),
                isAccountNudgeLoaded = true,
            )

        assertFalse(state.shouldShowAccountNudge(AuthState.Loading))
        assertFalse(
            state.shouldShowAccountNudge(
                AuthState.Authenticated(GallrUser(id = "member", displayName = "Member", avatarUrl = null)),
            ),
        )
    }

    private fun visit(id: String) =
        ExhibitionVisit(
            clientRecordId = "record-$id",
            exhibitionId = id,
            snapshot =
                ExhibitionVisitSnapshot(
                    nameKo = "전시 $id",
                    nameEn = "Exhibition $id",
                    venueNameKo = "갤러리",
                    venueNameEn = "Gallery",
                    openingDate = LocalDate(2026, 8, 1),
                    closingDate = LocalDate(2026, 8, 31),
                    coverImageUrl = null,
                ),
            createdAt = Instant.parse("2026-08-14T00:00:00Z"),
        )

    private fun followedGallery() =
        FollowedGallery(
            galleryKey = "gallery",
            snapshot =
                FollowedGallerySnapshot(
                    nameKo = "갤러리",
                    nameEn = "Gallery",
                    cityKo = "서울",
                    cityEn = "Seoul",
                    regionKo = "종로구",
                    regionEn = "Jongno-gu",
                ),
            knownExhibitionIds = emptySet(),
            followedAt = Instant.parse("2026-08-14T00:00:00Z"),
        )
}
