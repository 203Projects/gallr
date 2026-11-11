package com.gallr.shared.diary

import com.gallr.shared.data.model.AppLanguage

/**
 * What a diary entry noticed about the visit, at most [MAX_PER_ENTRY] per entry. Private like the feeling and never
 * moderated. [moodTermId] names the reviewed art term a tag stands for when it is on a 좋았어요 visit (research P8);
 * MOVING stays unmapped on purpose.
 */
enum class VisitTag(
    val wire: String,
    val moodTermId: String?,
    private val labelKo: String,
    private val labelEn: String,
) {
    THE_WORK("the_work", null, "작품이 좋았어요", "The work"),
    THE_SPACE("the_space", null, "공간이 좋았어요", "The space"),
    FOOD_FOR_THOUGHT("food_for_thought", null, "생각할 거리가 많았어요", "Food for thought"),
    CALM_TO_LINGER("calm_to_linger", "mood:quiet-meditative", "조용히 머물기 좋았어요", "Calm to linger"),
    PLAYFUL("playful", "mood:playful", "즐거웠어요", "Playful"),
    MOVING("moving", null, "마음에 남았어요", "Moving"),
    OVERWHELMING("overwhelming", "mood:monumental", "압도적이었어요", "Overwhelming"),
    HEAVY("heavy", "mood:dark", "마음이 무거웠어요", "Heavy"),
    SEE_AGAIN("see_again", null, "다시 보고 싶어요", "Would see again"),
    ;

    fun label(language: AppLanguage): String =
        when (language) {
            AppLanguage.KO -> labelKo
            AppLanguage.EN -> labelEn
        }

    companion object {
        const val MAX_PER_ENTRY = 3

        /** Null for a value this build does not know, so a newer tag never breaks a payload. */
        fun fromWire(value: String?): VisitTag? = entries.firstOrNull { it.wire == value }
    }
}
