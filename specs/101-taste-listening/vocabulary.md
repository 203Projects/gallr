# Vocabulary expansion (T045): PROPOSAL for the owner

Status: **proposed, not decided.** T045 says the owner finalises the list after tagging ten exhibitions. This
proposal starts from the candidates in the design doc (`docs/designs/2026-10-26-mobile-taste-ai-design.md`,
Stage 0 item 2) so that decision is an edit, not a blank page. Nothing is built from it until the owner marks it
**Decided** here; then T042, T044, T046 and T047 implement exactly the rows below.

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

## Proposed additions (22)

| Id | Category | 한국어 | English | Sort | Detection notes (T047) |
|---|---|---|---|---|---|
| `medium:ceramics` | medium | 도예 | Ceramics | 10 | 도예, 도자, 세라믹 / ceramic(s), porcelain |
| `medium:textile` | medium | 섬유 | Textile | 11 | 섬유, 직물, 패브릭 / textile, fabric, weaving |
| `medium:sound` | medium | 사운드 | Sound | 12 | 사운드, 소리 작업 / sound |
| `medium:media-art` | medium | 미디어아트 | Media art | 13 | 미디어아트, 미디어 아트 / media art |
| `medium:architecture-design` | medium | 건축·디자인 | Architecture & design | 14 | 건축, 디자인 / architecture, design |
| `style:dansaekhwa` | style | 단색화 | Dansaekhwa | 6 | 단색화 / dansaekhwa |
| `style:minhwa` | style | 민화 | Minhwa | 7 | 민화 / minhwa |
| `style:korean-painting` | style | 한국화 | Korean painting | 8 | 한국화, 수묵 / ink painting |
| `style:pop` | style | 팝 | Pop | 9 | 팝아트, 팝 아트 / pop art |
| `style:surreal` | style | 초현실 | Surreal | 10 | 초현실 / surreal(ism) |
| `theme:body` | theme | 신체 | Body | 6 | 신체, 몸 / body |
| `theme:time` | theme | 시간 | Time | 7 | 시간 / time (whole word only) |
| `theme:ecology` | theme | 생태 | Ecology | 8 | 생태, 기후 / ecology, climate |
| `theme:labor` | theme | 노동 | Labor | 9 | 노동 / labor, labour |
| `theme:home` | theme | 집 | Home | 10 | 집, 거주 / home, dwelling |
| `theme:gender` | theme | 젠더 | Gender | 11 | 젠더, 여성, 페미니즘 / gender, feminism |
| `theme:archive` | theme | 아카이브 | Archive | 12 | 아카이브, 기록 / archive |
| `theme:landscape` | theme | 풍경 | Landscape | 13 | 풍경, 산수 / landscape |
| `mood:dark` | mood | 어두운 | Dark | 6 | (staff only; too ambiguous to detect) |
| `mood:warm` | mood | 따뜻한 | Warm | 7 | (staff only) |
| `mood:humorous` | mood | 유머 | Humorous | 8 | 유머, 해학 / humor, humour |
| `mood:melancholy` | mood | 쓸쓸한 | Melancholy | 9 | (staff only) |

## Questions for the owner

1. **Overlaps.** `medium:ceramics` sits inside 공예 and `medium:media-art` beside 디지털 and 비디오. Keep both as
   narrower terms (a show can carry 공예 and 도예), or drop one?
2. **`theme:home` (집)** is a one-syllable Korean word; detection would need a stricter rule than a substring. Keep it
   staff-only, or choose another label such as 거주?
3. **Moods.** Three of the four proposed moods cannot be detected from text reliably. Keep them as staff-only tags,
   or leave moods as they are?
4. **Anything you wished existed while tagging ten shows** (the Assignment): add it here before deciding.

Decision: _pending_ (owner, date).
