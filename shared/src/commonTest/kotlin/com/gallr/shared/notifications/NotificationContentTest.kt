package com.gallr.shared.notifications

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationContentTest {

    @Test
    fun `closing soon - English`() {
        val (title, body) = NotificationContent.render(
            type = TriggerType.CLOSING,
            language = AppLanguage.EN,
            exhibitionName = "Color Field",
            venueName = "Pace Gallery",
        )
        assertEquals("gallr", title)
        assertEquals("Color Field closes in 3 days — don't miss it.", body)
    }

    @Test
    fun `closing soon - Korean`() {
        val (title, body) = NotificationContent.render(
            type = TriggerType.CLOSING,
            language = AppLanguage.KO,
            exhibitionName = "색면",
            venueName = "페이스 갤러리",
        )
        assertEquals("gallr", title)
        assertEquals("색면 마감 3일 전입니다. 놓치지 마세요.", body)
    }

    @Test
    fun `opening soon - English`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.OPENING,
            language = AppLanguage.EN,
            exhibitionName = "Color Field",
            venueName = "Pace Gallery",
        )
        assertEquals("Color Field opens in 3 days.", body)
    }

    @Test
    fun `opening soon - Korean`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.OPENING,
            language = AppLanguage.KO,
            exhibitionName = "색면",
            venueName = "페이스 갤러리",
        )
        assertEquals("색면 개막 3일 전입니다.", body)
    }

    @Test
    fun `reception today - English uses venue name`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.RECEPTION,
            language = AppLanguage.EN,
            exhibitionName = "Color Field",
            venueName = "Pace Gallery",
        )
        assertEquals("Reception today at Pace Gallery.", body)
    }

    @Test
    fun `reception today - Korean uses venue name`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.RECEPTION,
            language = AppLanguage.KO,
            exhibitionName = "색면",
            venueName = "페이스 갤러리",
        )
        assertEquals("오늘 페이스 갤러리에서 오프닝 리셉션이 열립니다.", body)
    }

    @Test
    fun `inactivity - English ignores exhibition + venue args`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.INACTIVITY,
            language = AppLanguage.EN,
            exhibitionName = "",
            venueName = "",
        )
        assertEquals("Your list hasn't changed in a while — check what's closing soon.", body)
    }

    @Test
    fun `inactivity - Korean ignores exhibition + venue args`() {
        val (_, body) = NotificationContent.render(
            type = TriggerType.INACTIVITY,
            language = AppLanguage.KO,
            exhibitionName = "",
            venueName = "",
        )
        assertEquals("마이 리스트를 업데이트한 지 꽤 됐어요. 곧 마감되는 전시를 확인해보세요.", body)
    }
}
