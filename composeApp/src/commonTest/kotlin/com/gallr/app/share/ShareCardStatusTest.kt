package com.gallr.app.share

import com.gallr.shared.data.model.AppLanguage
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareCardStatusTest {
    private val today = LocalDate(2026, 9, 23)

    private fun status(
        open: LocalDate,
        close: LocalDate,
        lang: AppLanguage = AppLanguage.KO,
    ) = shareCardStatus(open, close, today, lang)

    @Test
    fun `upcoming shows the absolute opening date`() {
        assertEquals(
            ShareCardStatus("곧 시작 · 10.2 개막", emphasized = false),
            status(LocalDate(2026, 10, 2), LocalDate(2026, 11, 1)),
        )
        assertEquals("Opens Oct 2", status(LocalDate(2026, 10, 2), LocalDate(2026, 11, 1), AppLanguage.EN).text)
    }

    @Test
    fun `opening day is emphasized`() {
        assertEquals(ShareCardStatus("오늘 개막", emphasized = true), status(today, LocalDate(2026, 10, 24)))
        assertEquals("Opens today", status(today, LocalDate(2026, 10, 24), AppLanguage.EN).text)
    }

    @Test
    fun `last two weeks count down to closing`() {
        assertEquals(
            ShareCardStatus("지금 진행 중 · D-7 마감", emphasized = true),
            status(LocalDate(2026, 9, 10), LocalDate(2026, 9, 30)),
        )
        assertEquals(
            "On now · 7 days left",
            status(LocalDate(2026, 9, 10), LocalDate(2026, 9, 30), AppLanguage.EN).text,
        )
        assertEquals("지금 진행 중 · D-14 마감", status(LocalDate(2026, 9, 1), LocalDate(2026, 10, 7)).text)
    }

    @Test
    fun `closing day is emphasized`() {
        assertEquals(ShareCardStatus("오늘 마감", emphasized = true), status(LocalDate(2026, 9, 1), today))
        assertEquals("Closes today", status(LocalDate(2026, 9, 1), today, AppLanguage.EN).text)
    }

    @Test
    fun `long running show is simply on now`() {
        assertEquals(
            ShareCardStatus("지금 진행 중", emphasized = false),
            status(LocalDate(2026, 9, 1), LocalDate(2026, 10, 8)),
        )
        assertEquals("On now", status(LocalDate(2026, 9, 1), LocalDate(2026, 10, 8), AppLanguage.EN).text)
    }

    @Test
    fun `ended show says so`() {
        assertEquals(
            ShareCardStatus("전시 종료", emphasized = false),
            status(LocalDate(2026, 8, 1), LocalDate(2026, 9, 22)),
        )
        assertEquals("Ended", status(LocalDate(2026, 8, 1), LocalDate(2026, 9, 22), AppLanguage.EN).text)
    }

    @Test
    fun `future reception wins over hours`() {
        assertEquals(
            "오프닝 리셉션 10.2 (금) 16:00",
            shareCardDetailLine(LocalDate(2026, 10, 2), "16:00", "10am - 6pm\nTuesday - Sunday", today, AppLanguage.KO),
        )
        assertEquals(
            "Opening reception Fri, Oct 2, 16:00",
            shareCardDetailLine(LocalDate(2026, 10, 2), "16:00", null, today, AppLanguage.EN),
        )
        assertEquals("오프닝 리셉션 9.23 (수)", shareCardDetailLine(today, null, null, today, AppLanguage.KO))
    }

    @Test
    fun `past reception falls back to hours with localized days`() {
        assertEquals(
            "화–일 10am – 6pm",
            shareCardDetailLine(LocalDate(2026, 9, 17), "14:00", "10am - 6pm\nTuesday - Sunday", today, AppLanguage.KO),
        )
        assertEquals(
            "Tue–Sun 10am – 6pm",
            shareCardDetailLine(null, null, "10am - 6pm\nTuesday - Sunday", today, AppLanguage.EN),
        )
    }

    @Test
    fun `hours notes after the second line are dropped`() {
        assertEquals(
            "월–일 1pm – 7pm",
            shareCardDetailLine(
                null,
                null,
                "1pm - 7pm\nMonday - Sunday\nClsoed on 9/25 Friday ",
                today,
                AppLanguage.KO,
            ),
        )
    }

    @Test
    fun `single line hours are kept as written`() {
        assertEquals("11am – 7pm", shareCardDetailLine(null, null, "11am - 7pm", today, AppLanguage.KO))
    }

    @Test
    fun `no reception and no hours gives no line`() {
        assertNull(shareCardDetailLine(null, null, null, today, AppLanguage.KO))
        assertNull(shareCardDetailLine(null, null, "  ", today, AppLanguage.KO))
    }
}
