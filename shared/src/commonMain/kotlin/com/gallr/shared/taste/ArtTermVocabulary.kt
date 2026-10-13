package com.gallr.shared.taste

import com.gallr.shared.data.model.ArtTerm
import com.gallr.shared.data.model.ArtTermCategory

/** One reviewed taxonomy term and the words that imply it in an exhibition's own text. */
internal class ArtTermPattern(
    val term: ArtTerm,
    /** Matched as substrings: Korean attaches particles directly to the noun. */
    val korean: List<String>,
    /** Matched as whole words, case-insensitively. */
    val english: List<String>,
) {
    /** One compiled pattern per term: compiling a regex per word and exhibition was visibly slow on iOS. */
    val englishWords: Regex by lazy {
        Regex("(^|[^a-z])(${english.joinToString("|", transform = Regex::escape)})([^a-z]|$)")
    }

    fun matches(
        koreanText: String,
        englishText: String,
    ): Boolean =
        korean.any(koreanText::contains) ||
            (englishText.isNotEmpty() && englishWords.containsMatchIn(englishText))
}

/**
 * The catalogue's 28 reviewed terms (`content.art_taxonomy_terms`) in their published order, each with
 * the conservative synonyms that describe a show rather than its venue or logistics. Ambiguous words
 * (설치 as "install", 기술 as "technique", print, play) are left out on purpose: a missing tag is
 * quieter than a wrong one.
 */
internal val ART_TERM_VOCABULARY: List<ArtTermPattern> =
    listOf(
        medium(
            "painting",
            "회화",
            "Painting",
            korean = listOf("회화", "페인팅", "유화", "아크릴", "캔버스"),
            english = listOf("painting", "paintings", "canvas", "canvases", "oil on", "acrylic"),
        ),
        medium(
            "sculpture",
            "조각",
            "Sculpture",
            korean = listOf("조각"),
            english = listOf("sculpture", "sculptures", "sculptural"),
        ),
        medium(
            "photography",
            "사진",
            "Photography",
            korean = listOf("사진전", "사진 작", "사진작", "사진가", "사진 시리즈", "사진 연작", "포토그래피"),
            english = listOf("photograph", "photographs", "photography", "photographic"),
        ),
        medium(
            "installation",
            "설치",
            "Installation",
            korean = listOf("설치 작", "설치작", "설치미술", "설치 미술", "인스톨레이션"),
            english = listOf("installation", "installations"),
        ),
        medium(
            "video",
            "비디오",
            "Video",
            korean =
                listOf(
                    "비디오",
                    "영상 작",
                    "영상작",
                    "영상 설치",
                    "무빙 이미지",
                    "무빙이미지",
                    "싱글 채널",
                    "싱글채널",
                    "필름",
                ),
            english =
                listOf(
                    "video",
                    "videos",
                    "moving image",
                    "moving-image",
                    "single-channel",
                    "film",
                    "films",
                ),
        ),
        medium(
            "digital",
            "디지털",
            "Digital",
            korean = listOf("디지털", "미디어 아트", "미디어아트", "뉴미디어", "뉴 미디어", "인공지능", "알고리즘", "생성형"),
            english =
                listOf(
                    "digital",
                    "media art",
                    "new media",
                    "artificial intelligence",
                    "generative",
                    "algorithm",
                    "algorithmic",
                ),
        ),
        medium(
            "performance",
            "퍼포먼스",
            "Performance",
            korean = listOf("퍼포먼스", "행위 예술", "행위예술"),
            english = listOf("performance", "performances", "performative"),
        ),
        medium(
            "drawing",
            "드로잉",
            "Drawing",
            korean = listOf("드로잉", "소묘"),
            english = listOf("drawing", "drawings"),
        ),
        medium(
            "printmaking",
            "판화",
            "Printmaking",
            korean = listOf("판화", "실크스크린", "리소그래프", "에칭"),
            english =
                listOf(
                    "printmaking",
                    "woodcut",
                    "etching",
                    "lithograph",
                    "lithographs",
                    "silkscreen",
                    "screenprint",
                    "screen print",
                ),
        ),
        medium(
            "craft",
            "공예",
            "Craft",
            korean = listOf("공예", "도자", "도예", "세라믹", "텍스타일", "직조"),
            english =
                listOf(
                    "craft",
                    "crafts",
                    "ceramic",
                    "ceramics",
                    "textile",
                    "textiles",
                    "weaving",
                ),
        ),
        style(
            "abstract",
            "추상",
            "Abstract",
            korean = listOf("추상"),
            english = listOf("abstract", "abstraction"),
        ),
        style(
            "figurative",
            "구상",
            "Figurative",
            korean = listOf("구상 회화", "구상회화", "구상적", "인물화", "형상적"),
            english = listOf("figurative", "figuration"),
        ),
        style(
            "minimalist",
            "미니멀",
            "Minimalist",
            korean = listOf("미니멀"),
            english = listOf("minimal", "minimalist", "minimalism"),
        ),
        style(
            "conceptual",
            "개념",
            "Conceptual",
            korean = listOf("개념미술", "개념 미술", "개념적"),
            english = listOf("conceptual", "conceptualism"),
        ),
        style(
            "documentary",
            "다큐멘터리",
            "Documentary",
            korean = listOf("다큐멘터리", "다큐"),
            english = listOf("documentary"),
        ),
        style(
            "experimental",
            "실험",
            "Experimental",
            korean = listOf("실험적", "실험 영화", "실험영화", "실험"),
            english = listOf("experimental"),
        ),
        theme(
            "identity",
            "정체성",
            "Identity",
            korean = listOf("정체성"),
            english = listOf("identity", "identities"),
        ),
        theme(
            "memory",
            "기억",
            "Memory",
            korean = listOf("기억"),
            english = listOf("memory", "memories", "remembrance"),
        ),
        theme(
            "nature",
            "자연",
            "Nature",
            korean = listOf("자연", "풍경", "생태", "식물"),
            english =
                listOf(
                    "nature",
                    "landscape",
                    "landscapes",
                    "ecology",
                    "ecological",
                    "botanical",
                ),
        ),
        theme(
            "city",
            "도시",
            "City",
            korean = listOf("도시", "메트로폴리스"),
            english = listOf("city", "cities", "urban", "metropolis"),
        ),
        theme(
            "technology",
            "기술",
            "Technology",
            korean = listOf("테크놀로지", "과학기술", "기계", "로봇", "사이버"),
            english =
                listOf(
                    "technology",
                    "technological",
                    "technologies",
                    "machine",
                    "machines",
                    "robot",
                    "robots",
                    "cyber",
                ),
        ),
        theme(
            "society",
            "사회",
            "Society",
            korean = listOf("사회", "공동체", "노동", "정치"),
            english =
                listOf(
                    "society",
                    "social",
                    "community",
                    "labor",
                    "labour",
                    "political",
                    "politics",
                ),
        ),
        mood(
            "quiet-meditative",
            "고요함·명상적",
            "Quiet / meditative",
            korean = listOf("고요", "명상", "사유", "관조", "정적인", "침묵"),
            english =
                listOf(
                    "quiet",
                    "meditative",
                    "meditation",
                    "contemplative",
                    "contemplation",
                    "stillness",
                    "silence",
                    "serene",
                ),
        ),
        mood(
            "energetic",
            "역동적",
            "Energetic",
            korean = listOf("역동", "에너지", "활력", "강렬"),
            english = listOf("energetic", "dynamic", "energy", "vibrant", "intense"),
        ),
        mood(
            "playful",
            "유희적",
            "Playful",
            korean = listOf("유희", "유머", "위트", "장난", "놀이"),
            english = listOf("playful", "humor", "humour", "humorous", "witty", "whimsical"),
        ),
        mood(
            "unsettling",
            "불안함",
            "Unsettling",
            korean = listOf("불안", "기괴", "불편", "낯선", "섬뜩"),
            english =
                listOf(
                    "unsettling",
                    "uncanny",
                    "eerie",
                    "anxiety",
                    "anxious",
                    "disquiet",
                    "uneasy",
                ),
        ),
        mood(
            "intimate",
            "친밀함",
            "Intimate",
            korean = listOf("친밀", "내밀", "사적인", "일상"),
            english = listOf("intimate", "intimacy", "everyday", "domestic"),
        ),
        mood(
            "monumental",
            "기념비적",
            "Monumental",
            korean = listOf("기념비", "거대한", "대규모", "대형", "웅장"),
            english =
                listOf(
                    "monumental",
                    "monumentality",
                    "large-scale",
                    "large scale",
                    "massive",
                ),
        ),
    )

private fun medium(
    slug: String,
    nameKo: String,
    nameEn: String,
    korean: List<String>,
    english: List<String>,
) = pattern(ArtTermCategory.MEDIUM, slug, nameKo, nameEn, korean, english)

private fun style(
    slug: String,
    nameKo: String,
    nameEn: String,
    korean: List<String>,
    english: List<String>,
) = pattern(ArtTermCategory.STYLE, slug, nameKo, nameEn, korean, english)

private fun theme(
    slug: String,
    nameKo: String,
    nameEn: String,
    korean: List<String>,
    english: List<String>,
) = pattern(ArtTermCategory.THEME, slug, nameKo, nameEn, korean, english)

private fun mood(
    slug: String,
    nameKo: String,
    nameEn: String,
    korean: List<String>,
    english: List<String>,
) = pattern(ArtTermCategory.MOOD, slug, nameKo, nameEn, korean, english)

private fun pattern(
    category: ArtTermCategory,
    slug: String,
    nameKo: String,
    nameEn: String,
    korean: List<String>,
    english: List<String>,
) = ArtTermPattern(
    term = ArtTerm(id = "${category.name.lowercase()}:$slug", category = category, nameKo = nameKo, nameEn = nameEn),
    korean = korean,
    english = english,
)
