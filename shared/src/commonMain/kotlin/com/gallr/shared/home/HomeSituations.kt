package com.gallr.shared.home

import com.gallr.shared.data.model.VisitTag

/**
 * A situation a visitor might be in ("a quiet visit", "somewhere to photograph"), answered by a bundle of art terms
 * (spec 091 FR-021) or by an editor's visit tag (FR-022). An exhibition answers it when any of its reviewed or
 * detected terms is in [termIds], or it carries one of [visitTags]. The tags are what the tile shows after `#`; they
 * name the signal, so the tile never claims more than the terms say.
 */
data class HomeSituation(
    val key: String,
    val titleKo: String,
    val titleEn: String,
    val tagsKo: List<String>,
    val tagsEn: List<String>,
    val termIds: Set<String>,
    val visitTags: Set<VisitTag> = emptySet(),
)

/**
 * The situations Home offers, in the order they appear. Most are built from moods, styles and media the vocabulary
 * can already read from an exhibition. One that needs a judgement about the visitor rather than the work, a visit
 * with children and family, rests on an editor's [VisitTag] alone and has no term bundle.
 */
val HOME_SITUATIONS: List<HomeSituation> =
    listOf(
        HomeSituation(
            key = "situation:quiet",
            titleKo = "조용히 보고 싶은 날",
            titleEn = "A QUIET VISIT",
            tagsKo = listOf("고요함", "미니멀"),
            tagsEn = listOf("QUIET", "MINIMAL"),
            termIds = setOf("mood:quiet-meditative", "style:minimalist"),
        ),
        HomeSituation(
            key = "situation:photo",
            titleKo = "사진 찍기 좋은 공간",
            titleEn = "SPACES TO PHOTOGRAPH",
            tagsKo = listOf("설치", "대형"),
            tagsEn = listOf("INSTALLATION", "LARGE SCALE"),
            termIds = setOf("medium:installation", "mood:monumental", "medium:digital"),
        ),
        HomeSituation(
            key = "situation:together",
            titleKo = "둘이서 천천히",
            titleEn = "SLOWLY, FOR TWO",
            tagsKo = listOf("친밀함", "일상"),
            tagsEn = listOf("INTIMATE", "EVERYDAY"),
            termIds = setOf("mood:intimate"),
        ),
        HomeSituation(
            key = "situation:family",
            titleKo = "아이·가족과 함께",
            titleEn = "WITH CHILDREN AND FAMILY",
            tagsKo = listOf("가족", "아이"),
            tagsEn = listOf("FAMILY", "CHILDREN"),
            termIds = emptySet(),
            visitTags = setOf(VisitTag.FAMILY),
        ),
        HomeSituation(
            key = "situation:lift",
            titleKo = "기분 전환이 필요할 때",
            titleEn = "FOR A LIFT",
            tagsKo = listOf("유희적", "역동적"),
            tagsEn = listOf("PLAYFUL", "ENERGETIC"),
            termIds = setOf("mood:playful", "mood:energetic"),
        ),
        HomeSituation(
            key = "situation:strange",
            titleKo = "낯선 감각을 찾는다면",
            titleEn = "SOMETHING STRANGE",
            tagsKo = listOf("낯섦", "실험"),
            tagsEn = listOf("UNCANNY", "EXPERIMENTAL"),
            termIds = setOf("mood:unsettling", "style:experimental"),
        ),
    )
