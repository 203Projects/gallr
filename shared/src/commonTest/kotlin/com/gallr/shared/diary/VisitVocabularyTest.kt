package com.gallr.shared.diary

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** data-model.md "Feeling and tag vocabularies": wire ids and labels are fixed. */
class VisitVocabularyTest {
    @Test
    fun feelingsCarryTheirWireIdsAndLabels() {
        assertEquals(
            listOf(
                Triple("loved", "좋았어요", "Loved it"),
                Triple("fine", "그저 그랬어요", "It was fine"),
                Triple("not_for_me", "아쉬웠어요", "Not for me"),
            ),
            VisitFeeling.entries.map { Triple(it.wire, it.label(AppLanguage.KO), it.label(AppLanguage.EN)) },
        )
    }

    @Test
    fun tagsCarryTheirWireIdsAndLabels() {
        assertEquals(
            listOf(
                Triple("the_work", "작품이 좋았어요", "The work"),
                Triple("the_space", "공간이 좋았어요", "The space"),
                Triple("food_for_thought", "생각할 거리가 많았어요", "Food for thought"),
                Triple("calm_to_linger", "조용히 머물기 좋았어요", "Calm to linger"),
                Triple("playful", "즐거웠어요", "Playful"),
                Triple("moving", "마음에 남았어요", "Moving"),
                Triple("overwhelming", "압도적이었어요", "Overwhelming"),
                Triple("heavy", "마음이 무거웠어요", "Heavy"),
                Triple("see_again", "다시 보고 싶어요", "Would see again"),
            ),
            VisitTag.entries.map { Triple(it.wire, it.label(AppLanguage.KO), it.label(AppLanguage.EN)) },
        )
    }

    @Test
    fun fromWireReadsEveryKnownIdAndNothingElse() {
        VisitFeeling.entries.forEach { assertEquals(it, VisitFeeling.fromWire(it.wire)) }
        VisitTag.entries.forEach { assertEquals(it, VisitTag.fromWire(it.wire)) }
        assertNull(VisitFeeling.fromWire(null))
        assertNull(VisitFeeling.fromWire("ecstatic"))
        assertNull(VisitFeeling.fromWire("LOVED"))
        assertNull(VisitTag.fromWire(null))
        assertNull(VisitTag.fromWire("the_food"))
    }

    @Test
    fun anEntryHoldsAtMostThreeTags() {
        assertEquals(3, VisitTag.MAX_PER_ENTRY)
    }

    @Test
    fun exactlyFourTagsCarryAMoodTermAndMovingIsNotOne() {
        assertEquals(
            mapOf(
                VisitTag.CALM_TO_LINGER to "mood:quiet-meditative",
                VisitTag.PLAYFUL to "mood:playful",
                VisitTag.OVERWHELMING to "mood:monumental",
                VisitTag.HEAVY to "mood:dark",
            ),
            VisitTag.entries.filter { it.moodTermId != null }.associateWith { it.moodTermId },
        )
        assertNull(VisitTag.MOVING.moodTermId)
    }
}
