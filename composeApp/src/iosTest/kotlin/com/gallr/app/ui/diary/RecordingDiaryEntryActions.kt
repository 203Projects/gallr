package com.gallr.app.ui.diary

import com.gallr.app.viewmodel.DiaryEntryActions
import com.gallr.app.viewmodel.DiaryVisibility
import com.gallr.shared.diary.VisitFeeling
import com.gallr.shared.diary.VisitTag
import kotlinx.datetime.LocalDate

/** Records how the page opened the entry sheet: `open`, or `open-note` for the note-first opening. */
internal class RecordingDiaryEntryActions : DiaryEntryActions {
    val calls = mutableListOf<String>()

    override fun startEditing(focusNote: Boolean) {
        calls += if (focusNote) "open-note" else "open"
    }

    override fun selectDate(date: LocalDate) = Unit

    override fun toggleFeeling(feeling: VisitFeeling) = Unit

    override fun toggleTag(tag: VisitTag) = Unit

    override fun updateNote(text: String) = Unit

    override fun selectVisibility(visibility: DiaryVisibility) = Unit

    override fun askAgain() = Unit

    override fun save() = Unit

    override fun delete() = Unit

    override fun discardEditing() = Unit
}
