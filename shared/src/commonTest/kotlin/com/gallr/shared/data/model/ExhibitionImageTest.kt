package com.gallr.shared.data.model

import com.gallr.shared.data.network.buildExhibitionGalleryImagesUrl
import com.gallr.shared.data.network.dto.decodeExhibitionImages
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExhibitionImageTest {
    private val rows =
        """
        [
          {"exhibition_id": "a", "position": 2, "url": "https://cdn.example.invalid/two.jpg",
           "alt_ko": "", "alt_en": "", "credit": "", "width": 900, "height": 1200},
          {"exhibition_id": "a", "position": 1, "url": "https://cdn.example.invalid/one.jpg",
           "alt_ko": "전시 전경", "alt_en": "Installation view", "credit": "사진 김지원",
           "width": 1600, "height": 1000, "unknown": true}
        ]
        """.trimIndent()

    @Test
    fun `rows decode in staff order with alt text and credit`() {
        val images = decodeExhibitionImages(rows)

        assertEquals(
            listOf("https://cdn.example.invalid/one.jpg", "https://cdn.example.invalid/two.jpg"),
            images.map { it.url },
        )
        assertEquals("사진 김지원", images.first().credit)
        assertEquals("전시 전경", images.first().alt(AppLanguage.KO))
        assertEquals("Installation view", images.first().alt(AppLanguage.EN))
    }

    @Test
    fun `english alt text falls back to korean and a blank one stays blank`() {
        val image =
            ExhibitionImage(
                url = "https://cdn.example.invalid/one.jpg",
                altKo = "전시 전경",
                altEn = "",
                credit = "",
                width = null,
                height = null,
            )

        assertEquals("전시 전경", image.alt(AppLanguage.EN))
        assertEquals("", image.copy(altKo = "").alt(AppLanguage.KO))
    }

    @Test
    fun `a row without a usable address is left out and the rest survive`() {
        val body =
            """
            [
              {"position": 1, "url": ""},
              {"position": 2, "url": "http://cdn.example.invalid/plain.jpg"},
              {"position": 3},
              {"position": "x", "url": "https://cdn.example.invalid/odd.jpg"},
              {"position": 4, "url": "https://cdn.example.invalid/kept.jpg"}
            ]
            """.trimIndent()

        assertEquals(listOf("https://cdn.example.invalid/kept.jpg"), decodeExhibitionImages(body).map { it.url })
        assertTrue(decodeExhibitionImages("{\"message\": \"not found\"}").isEmpty())
        assertTrue(decodeExhibitionImages("").isEmpty())
    }

    @Test
    fun `the frame follows the picture within limits and has a default`() {
        fun ratio(
            width: Int?,
            height: Int?,
        ) = ExhibitionImage("https://cdn.example.invalid/x.jpg", "", "", "", width, height).frameAspectRatio()

        assertEquals(1.6f, ratio(1600, 1000))
        assertEquals(NOTE_IMAGE_WIDEST, ratio(4000, 1000))
        assertEquals(NOTE_IMAGE_TALLEST, ratio(900, 1800))
        assertEquals(NOTE_IMAGE_DEFAULT_RATIO, ratio(null, 1000))
        assertEquals(NOTE_IMAGE_DEFAULT_RATIO, ratio(0, 0))
    }

    @Test
    fun `the request asks for one exhibition's images in order`() {
        val url = buildExhibitionGalleryImagesUrl("https://example.invalid/rest/v1", "who-lived")

        assertTrue(url.startsWith("https://example.invalid/rest/v1/exhibition_gallery_images?"))
        assertTrue(url.contains("exhibition_id=eq.who-lived"))
        assertTrue(url.contains("order=position.asc"))
        assertTrue(url.contains("limit=$GALLERY_IMAGE_LIMIT"))
    }

    @Test
    fun `the gallery starts with the cover and leaves out a repeat of it`() {
        val cover = "https://cdn.example.invalid/cover.jpg"
        val view =
            ExhibitionImage(
                url = "https://cdn.example.invalid/view.jpg",
                altKo = "전시 전경",
                altEn = "Installation view",
                credit = "사진 김지원",
                width = 1600,
                height = 1000,
            )
        val pages = exhibition(cover = cover).galleryPages(listOf(view, view.copy(url = cover)))

        assertEquals(listOf(cover, view.url), pages.map { it.url })
        assertEquals("전시 a", pages.first().alt(AppLanguage.KO))
        assertEquals("Show a", pages.first().alt(AppLanguage.EN))
        assertEquals("", pages.first().credit)
    }

    @Test
    fun `an exhibition without a cover pages through its gallery alone and one without either has no pages`() {
        val view = ExhibitionImage("https://cdn.example.invalid/view.jpg", "", "", "", null, null)

        assertEquals(listOf(view), exhibition(cover = null).galleryPages(listOf(view)))
        assertEquals(listOf(view), exhibition(cover = " ").galleryPages(listOf(view)))
        assertTrue(exhibition(cover = null).galleryPages(emptyList()).isEmpty())
    }

    @Test
    fun `the note takes the first gallery pictures and never the cover again`() {
        val cover = "https://cdn.example.invalid/cover.jpg"
        val views =
            (1..6).map { number ->
                ExhibitionImage("https://cdn.example.invalid/view-$number.jpg", "", "", "", null, null)
            }
        val withCover = listOf(views.first().copy(url = cover)) + views

        assertEquals(views.take(NOTE_IMAGE_LIMIT), exhibition(cover = cover).noteImages(withCover))
        assertEquals(views.take(NOTE_IMAGE_LIMIT), exhibition(cover = null).noteImages(views))
        assertTrue(exhibition(cover = cover).noteImages(emptyList()).isEmpty())
    }

    @Test
    fun `the counter says where the reader is`() {
        assertEquals("1 / 5", galleryCounter(index = 0, count = 5))
        assertEquals("5 / 5", galleryCounter(index = 4, count = 5))
    }

    private fun exhibition(cover: String?) =
        Exhibition(
            id = "a",
            nameKo = "전시 a",
            nameEn = "Show a",
            venueNameKo = "갤러리",
            venueNameEn = "Gallery",
            cityKo = "서울",
            cityEn = "Seoul",
            regionKo = "종로구",
            regionEn = "Jongno-gu",
            openingDate = LocalDate(2026, 9, 2),
            closingDate = LocalDate(2026, 10, 31),
            isFeatured = false,
            latitude = null,
            longitude = null,
            descriptionKo = "",
            descriptionEn = "",
            addressKo = "",
            addressEn = "",
            coverImageUrl = cover,
        )
}
