package com.gallr.app

import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.repository.ExhibitionRepository
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.time.Clock

@OptIn(ExperimentalNativeApi::class)
internal fun requireNativeDebugFixture(catalogue: List<Exhibition>): List<Exhibition> {
    check(Platform.isDebugBinary) { "Native fixtures require a debug framework" }
    return catalogue
}

private fun nativeExhibitionFixture(): Exhibition {
    val today = Clock.System.todayIn(TimeZone.of("Asia/Seoul"))
    return Exhibition(
        id = "native-fixture",
        nameKo = "테스트 전시",
        nameEn = "Test Exhibition",
        venueNameKo = "테스트 갤러리",
        venueNameEn = "Test Gallery",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로",
        regionEn = "Jongno",
        openingDate = today.minus(7, DateTimeUnit.DAY),
        closingDate = today.plus(21, DateTimeUnit.DAY),
        isFeatured = true,
        latitude = 37.577,
        longitude = 126.982,
        descriptionKo = "테스트 전시 상세",
        descriptionEn = "Test exhibition details",
        addressKo = "서울",
        addressEn = "Seoul",
        coverImageUrl = null,
    )
}

fun nativeExhibitionFixtures(): List<Exhibition> {
    val primary = nativeExhibitionFixture()
    val overlapping =
        primary.copy(
            id = "native-fixture-overlap",
            nameKo = "테스트 전시 두 번째",
            nameEn = "Second Test Exhibition",
            closingDate = Clock.System.todayIn(TimeZone.of("Asia/Seoul")).plus(1, DateTimeUnit.DAY),
            isFeatured = false,
        )
    return listOf(primary, overlapping)
}

internal class NativeFixtureExhibitionRepository(
    private val catalogue: List<Exhibition>,
) : ExhibitionRepository {
    override suspend fun getFeaturedExhibitions() = Result.success(catalogue.filter(Exhibition::isFeatured))

    override suspend fun getExhibitions() = Result.success(catalogue)
}
