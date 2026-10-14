# Contract: Instagram carousel export (Admin)

Input: one approved `CuratorNoteVersion`, its `CuratorNoteFacts` packet, the cover image URL, the
chosen language (`ko` | `en`; `en` is offered only when the version has English text).

## Archive

`curator-note-{exhibition_id}-v{version}-{lang}.zip` containing:

| Entry | Content |
|---|---|
| `01-cover.png` | Cover slide |
| `NN-insight.png` | 2–4 insight slides |
| `NN-picture.png` | One slide per gallery image the insights do not use (FR-015b), none when there is no such image |
| `NN-closing.png` | Closing slide, always last |
| `caption.txt` | Caption with hashtags (UTF-8) |
| `report.txt` | Present only when text was left out: the omitted sentences, verbatim |

`NN` is the slide's place in the carousel, from `02`: a picture slide follows each insight that has
slides, in gallery order, and the rest come before the closing slide. An archive holds four to ten
slides, the range `record_curator_note_export` accepts.

Every PNG is exactly 1080×1350. The zip uses stored entries (`fflate.zipSync` with `level: 0`).

## Slides

All slides: 0dp corners, 72px side margins, Inter for Latin and Gothic A1 for Korean (loaded with
`document.fonts.load` before drawing), the gallr wordmark at 40px in the bottom-left, slide number
`2 / 5` bottom-right at 32px.

- **Cover**: the poster drawn `cover`-fit over the full slide, a bottom wash from the darkest
  palette tone (alpha 0 at 45% height to 0.85 at the bottom), the hook in white at 64px/1.15 line
  height, at most four lines, bottom-aligned above the exhibition name and venue at 36px.
- **Insight**: paper from the lightest palette tone that keeps contrast ≥ 7:1 with the darkest tone
  (else `#FFFFFF`), type in the darkest tone; one insight per slide at 44px/1.4, max 11 lines; the
  slide label `큐레이터의 시선` / `CURATOR'S EYE` at 32px uppercase letter-spaced at the top; the
  five palette swatches as 24px squares under the label.
- **Picture** (FR-015b): a gallery image the insights do not use, drawn `cover`-fit over the full
  slide with the scrim DESIGN.md describes for picture slides, and its alt text in white at 32px,
  at most four lines, stacked up from the footer. A caption line past the fourth goes to
  `report.txt`.
- **Closing**: paper white, the dates (`2026.10.01 – 11.30`), venue, district and hours at 44px;
  the viewing tip at 44px medium weight; the QR (`uqr`, error correction M, four-module quiet zone,
  dark modules in the darkest tone when its luminance is ≤ 0.09, else black) 320px on a white tile
  linking to the exhibition's public page as `publicExhibitionUrl` builds it; the disclosure line
  `AI 초안 · <이름> 검수` / `Drafted with AI, reviewed by <name>` at 32px.

Layout is computed by a pure function `layoutCarousel(note, facts, language, measure)` that takes a
text-measuring function and returns the slides' line arrays and the omitted sentences; the canvas
renderer only draws what the layout returns. Text that does not fit at 44px moves to the next
insight slide; after the fourth insight slide the remainder is reported, never shrunk (FR-015 c).

## Caption

```text
<hook>

<first insight>

<dates> · <venue>
<viewing tip>

#gallr #전시 #서울전시 #<venue hashtag> #<district hashtag> #<term name hashtags, up to 6>
```

Hashtags come only from the packet: venue name, `region_ko` (or `region_en` for `en`), and the
reviewed terms' names with spaces removed; nothing else is invented.

## Behaviour

- Export runs entirely in the browser; the button is disabled while rendering; completion within
  30 s on a laptop (SC-006) is checked in the quickstart.
- Poster fetch failure or a missing cover: the export is refused with the reason (edge case) and no
  archive is produced.
- A gallery image that fails to load is left out and `report.txt` says how many were.
- On success `record_curator_note_export(version_id, language, slide_count)` is called; a failure
  there is shown but does not undo the download.

## Tests (Vitest)

`layoutCarousel`: splits across slides with a fake measurer, respects the four-slide limit and
reports leftovers, never emits an empty slide, caption hashtags from the packet only. Archive:
entry names and count, PNG dimensions by decoding the header bytes. Renderer: smoke test with the
canvas mock asserting one `toBlob` per slide.
