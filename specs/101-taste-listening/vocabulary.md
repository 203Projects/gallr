# Vocabulary expansion (T045)

Status: **Decided** by the owner on 2026-11-04: all 22 proposed terms, with the default answer to each question
below. T042, T044, T046 and T047 implement exactly the rows in the table (migration
`20261104120000_art_vocabulary_expansion`, pgTAP 069, `shared/taste/ArtTermVocabulary.kt`).

The proposal started from the candidates in the design doc (`docs/designs/2026-10-26-mobile-taste-ai-design.md`,
Stage 0 item 2). The owner decided before the ten-exhibition tagging assignment; terms found missing while tagging
are a later, separate expansion.

Rules the list follows (contract and data model):
- Ids are new and stable: `category:slug`, lower-case ASCII words joined by `-`. No existing id or label changes.
- Categories stay the existing four (`medium`, `style`, `theme`, `mood`); `visit` terms are never inferred from text
  and are not part of this list.
- `sort_order` continues after each category's last existing row (medium after 9, style after 5, theme after 5,
  mood after 5).
- Each term needs Korean substrings and English whole words that point to it without ambiguity (T047); a term that
  cannot be detected from text is still valid for staff tagging but does not help the ≥70% detection floor.

## Existing terms (unchanged, for reference)

| Category | Terms |
|---|---|
| medium | 회화, 조각, 사진, 설치, 비디오, 디지털, 퍼포먼스, 드로잉, 판화, 공예 |
| style | 추상, 구상, 미니멀, 개념, 다큐멘터리, 실험 |
| theme | 정체성, 기억, 자연, 도시, 기술, 사회 |
| mood | 고요함·명상적, 역동적, 유희적, 불안함, 친밀함, 기념비적 |

## Decided additions (22)

Detection words are what `ArtTermVocabulary.kt` matches: Korean as substrings, English as whole words. T047 keeps
only unambiguous words, so a few proposed ones were dropped while building (noted in the last column).

| Id | Category | 한국어 | English | Sort | Detection words (T047) | Dropped from the proposal |
|---|---|---|---|---|---|---|
| `medium:ceramics` | medium | 도예 | Ceramics | 10 | 도예, 도자기, 세라믹, 백자, 분청, 고려청자 / ceramic(s), porcelain, stoneware | 도자 (inside 지도자) |
| `medium:textile` | medium | 섬유 | Textile | 11 | 섬유, 직물, 패브릭, 텍스타일, 직조 / textile(s), fabric(s), weaving, woven | |
| `medium:sound` | medium | 사운드 | Sound | 12 | 사운드 / sound art, sound installation(s), sound work(s) | 소리 작업; bare "sound" |
| `medium:media-art` | medium | 미디어아트 | Media art | 13 | 미디어아트, 미디어 아트 / media art | |
| `medium:architecture-design` | medium | 건축·디자인 | Architecture & design | 14 | 건축 / architecture, architectural | 디자인, design (exhibition and graphic design credits) |
| `style:dansaekhwa` | style | 단색화 | Dansaekhwa | 6 | 단색화 / dansaekhwa | |
| `style:minhwa` | style | 민화 | Minhwa | 7 | 민화 / minhwa | |
| `style:korean-painting` | style | 한국화 | Korean painting | 8 | 한국화, 수묵 / ink painting(s), korean painting | |
| `style:pop` | style | 팝 | Pop | 9 | 팝아트, 팝 아트 / pop art | |
| `style:surreal` | style | 초현실 | Surreal | 10 | 초현실 / surreal, surrealism, surrealist | |
| `theme:body` | theme | 신체 | Body | 6 | 신체 / bodily, corporeal(ity), embodied, embodiment | 몸; "body" ("a body of work") |
| `theme:time` | theme | 시간 | Time | 7 | 시간성, 시간의 흐름, 시간의 층위 / temporality, passage of time | bare 시간 (opening hours), "time" |
| `theme:ecology` | theme | 생태 | Ecology | 8 | 생태, 기후 / ecology, ecological, climate | |
| `theme:labor` | theme | 노동 | Labor | 9 | 노동 / labor, labour | |
| `theme:home` | theme | 집 | Home | 10 | staff only | 집, 거주, home, dwelling |
| `theme:gender` | theme | 젠더 | Gender | 11 | 젠더, 페미니즘, 페미니스트 / gender, feminism, feminist | 여성 ("여성 작가" names the artists, not the theme) |
| `theme:archive` | theme | 아카이브 | Archive | 12 | 아카이브, 아카이빙 / archive(s), archival | 기록 (any record) |
| `theme:landscape` | theme | 풍경 | Landscape | 13 | 풍경, 산수화 / landscape(s) | bare 산수 (inside 산수유) |
| `mood:dark` | mood | 어두운 | Dark | 6 | staff only | |
| `mood:warm` | mood | 따뜻한 | Warm | 7 | staff only | |
| `mood:humorous` | mood | 유머 | Humorous | 8 | 유머, 해학 / humor, humour, humorous | |
| `mood:melancholy` | mood | 쓸쓸한 | Melancholy | 9 | staff only | |

## Owner's answers (2026-11-04)

1. **Overlaps:** keep both. Narrower terms sit beside the broader existing ones, so a show can carry 공예 and 도예,
   or 디지털 and 미디어아트. The existing terms' detection words are unchanged, so text that names 도예 tags both.
2. **`theme:home` (집):** staff-only. Editors attach it; text never implies it.
3. **Moods:** keep 어두운, 따뜻한 and 쓸쓸한 as staff-only tags; 유머 is detected.
4. **Tagging-pass additions:** none yet. Terms found missing while tagging go into a later expansion with its own
   migration; ids here never change meaning.

Decision: **Decided** (owner, 2026-11-04).
