package com.gallr.app.ui.detail

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

// Value: protects=the 감상 section's empty line invites a first note only where one can be written, and says when
// every 감상 is by someone the reader hid (spec 103 e2e defect 13); fails_when=an upcoming show or a page of hidden
// authors asks the reader to be the first; why_new=the line had one wording; seam=thoughtsEmptyLine;
// no_claim=where the line sits in the section

class ThoughtsEmptyLineTest {
    @Test
    fun anOpenedShowInvitesTheFirstNote() {
        assertEquals("아직 감상이 없어요. 첫 번째로 나눠보세요.", thoughtsEmptyLine(AppLanguage.KO, true, false))
        assertEquals("No notes yet. Be the first to share.", thoughtsEmptyLine(AppLanguage.EN, true, false))
    }

    @Test
    fun anUpcomingShowSaysThereAreNoneWithoutAnInvitation() {
        assertEquals("아직 감상이 없어요.", thoughtsEmptyLine(AppLanguage.KO, false, false))
        assertEquals("No notes yet.", thoughtsEmptyLine(AppLanguage.EN, false, false))
    }

    @Test
    fun aPageOfHiddenAuthorsSaysSo() {
        for (canWrite in listOf(true, false)) {
            assertEquals("숨긴 감상만 있어요.", thoughtsEmptyLine(AppLanguage.KO, canWrite, true))
            assertEquals("Only notes you've hidden.", thoughtsEmptyLine(AppLanguage.EN, canWrite, true))
        }
    }
}
