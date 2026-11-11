package com.gallr.app.ui.detail

import com.gallr.shared.data.model.AppLanguage
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ThoughtDateLabelTest {
    private val seoul = TimeZone.of("Asia/Seoul")

    @Test
    fun `the date reads like exhibition dates in each language`() {
        val createdAt = "2026-11-03T02:15:00.123456+00:00"

        assertEquals("2026.11.03", thoughtDateLabel(createdAt, AppLanguage.KO, seoul))
        assertEquals("Nov 3, 2026", thoughtDateLabel(createdAt, AppLanguage.EN, seoul))
    }

    @Test
    fun `the day is the reader's day rather than the server's`() {
        // 23:30 UTC on the 10th is already the 11th in Seoul.
        assertEquals("2026.11.11", thoughtDateLabel("2026-11-10T23:30:00+00:00", AppLanguage.KO, seoul))
    }

    @Test
    fun `a timestamp that does not parse shows its stored date part`() {
        assertEquals("2026-11-10", thoughtDateLabel("2026-11-10 garbage", AppLanguage.EN, seoul))
    }
}
