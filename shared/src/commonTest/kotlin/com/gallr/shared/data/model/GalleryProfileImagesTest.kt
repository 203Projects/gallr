package com.gallr.shared.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GalleryProfileImagesTest {
    @Test
    fun `gallery id match wins over a different name match`() {
        val byId = image(galleryId = "g-1", nameKo = "국제갤러리", nameEn = "Kukje Gallery")
        val byName = image(galleryId = "g-2", nameKo = "다른 갤러리", nameEn = "Other Gallery")
        val images = GalleryProfileImages(listOf(byId, byName))

        assertEquals(byId, images.find(galleryId = "g-1", nameKo = "다른 갤러리", nameEn = "Other Gallery"))
    }

    @Test
    fun `name key match ignores case and repeated whitespace`() {
        val kukje = image(galleryId = "g-1", nameKo = "국제갤러리", nameEn = "Kukje Gallery")
        val images = GalleryProfileImages(listOf(kukje))

        assertEquals(kukje, images.find(galleryId = null, nameKo = " 국제갤러리 ", nameEn = "kukje   GALLERY"))
    }

    @Test
    fun `unknown gallery id falls back to the name key`() {
        val kukje = image(galleryId = "g-1", nameKo = "국제갤러리", nameEn = "Kukje Gallery")
        val images = GalleryProfileImages(listOf(kukje))

        assertEquals(kukje, images.find(galleryId = "g-unknown", nameKo = "국제갤러리", nameEn = "Kukje Gallery"))
    }

    @Test
    fun `name key shared by different images never matches`() {
        val first = image(galleryId = "g-1", nameKo = "COEX", nameEn = "COEX", imageUrl = "https://x/g-1/1.jpg")
        val second = image(galleryId = "g-2", nameKo = "COEX", nameEn = "COEX", imageUrl = "https://x/g-2/2.jpg")
        val images = GalleryProfileImages(listOf(first, second))

        assertNull(images.find(galleryId = null, nameKo = "COEX", nameEn = "COEX"))
        assertEquals(second, images.find(galleryId = "g-2", nameKo = "COEX", nameEn = "COEX"))
    }

    @Test
    fun `name key shared by records with the same image still matches`() {
        val first = image(galleryId = "g-1", nameKo = "COEX", nameEn = "COEX", imageUrl = "https://x/g-1/same.jpg")
        val second = image(galleryId = "g-2", nameKo = "COEX", nameEn = "COEX", imageUrl = "https://x/g-2/same.jpg")
        val images = GalleryProfileImages(listOf(first, second))

        assertEquals(
            "same.jpg",
            images
                .find(galleryId = null, nameKo = "COEX", nameEn = "COEX")
                ?.imageUrl
                ?.substringAfterLast('/'),
        )
    }

    @Test
    fun `partial or different names do not match`() {
        val images = GalleryProfileImages(listOf(image(galleryId = "g-1", nameKo = "P21", nameEn = "P21")))

        assertNull(images.find(galleryId = null, nameKo = "P21 갤러리", nameEn = "P21 Gallery"))
        assertNull(images.find(galleryId = null, nameKo = "P21", nameEn = ""))
    }

    @Test
    fun `empty index finds nothing`() {
        assertNull(GalleryProfileImages.EMPTY.find(galleryId = "g-1", nameKo = "국제갤러리", nameEn = "Kukje Gallery"))
    }

    private fun image(
        galleryId: String,
        nameKo: String,
        nameEn: String,
        imageUrl: String = "https://cdn.example/gallery-profile-images/$galleryId/a.jpg",
    ) = GalleryProfileImage(
        galleryId = galleryId,
        nameKo = nameKo,
        nameEn = nameEn,
        kind = GalleryProfileImage.Kind.LOGO,
        imageUrl = imageUrl,
        credit = null,
    )
}
