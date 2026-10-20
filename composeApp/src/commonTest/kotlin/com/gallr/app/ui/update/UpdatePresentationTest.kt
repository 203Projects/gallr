package com.gallr.app.ui.update

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 098 contracts/update-screens.md. */
class UpdatePresentationTest {
    @Test
    fun theRequiredNoticeReadsAsContracted() {
        assertEquals("업데이트가 필요해요", requiredUpdateTitle(AppLanguage.KO))
        assertEquals("UPDATE REQUIRED", requiredUpdateTitle(AppLanguage.EN))
        assertEquals(
            "지금 쓰는 버전은 더 이상 지원되지 않아요. 업데이트하면 계속 쓸 수 있어요.",
            requiredUpdateMessage(AppLanguage.KO),
        )
        assertEquals(
            "This version is no longer supported. Update to keep using gallr.",
            requiredUpdateMessage(AppLanguage.EN),
        )
        assertEquals("업데이트", updateActionLabel(AppLanguage.KO))
        assertEquals("UPDATE", updateActionLabel(AppLanguage.EN))
        assertEquals("스토어에서 gallr를 업데이트해 주세요", storeFallbackMessage(AppLanguage.KO))
        assertEquals("Please update gallr from the store", storeFallbackMessage(AppLanguage.EN))
    }
}
