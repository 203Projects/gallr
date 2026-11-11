# Contract: diary UI (composeApp/commonMain)

Copy is final unless the owner changes it; every string is inline KO/EN through `AppLanguage`. All shapes come from
`GallrShapeTokens` (`card` 16dp, `small` 12dp, `sheetTop`); every target is at least 44dp; no emoji; the accent keeps
its three roles only (none of these surfaces uses `ctaPrimary` except the empty state's `GallrEmptyState` CTA). EN
follows the app's casing: uppercase for labels and buttons, sentence case for explanations.

## DESIGN.md additions (land before slices 5–8, FR-032)

New sections under Component Patterns: **Diary row on the exhibition page**, **Diary entry sheet**, **MY 일기**,
**감상 card menu and report sheet**, **Hidden people (Settings)**; a Decisions Log row (2026-11, spec 103: diary entry
replaces the visit prompt and the free composer; selected chips filled `onBackground`; note counter neutral). Remove
the `labelLarge` usage example "EXHIBITION DIARY" from the Type Scale table (the Profile grid it named is gone).

## Exhibition page

**Diary row** (replaces the visit prompt, `ExhibitionDetailScreen.kt:314-388`). Only when `canRecordVisit`. A
full-width row on the screen margin, 52dp minimum, hairline `outlineVariant` above and below, one merged Button node.

| State | Text (`bodyMedium`) | Trailing |
|---|---|---|
| no entry | `다녀왔어요` / `I'VE BEEN` | `→` |
| dated | `11월 9일 다녀옴 · 좋았어요` / `VISITED NOV 9 · LOVED IT` (feeling part omitted when none) | `수정` / `EDIT` (`labelLarge`, 44dp) |
| undated | `다녀옴 · 날짜 미정` / `VISITED · DATE NOT SET` | `수정` / `EDIT` |
| save failed | `! 기록을 저장하지 못했어요. 다시 시도해 주세요.` / `! Couldn't save the entry. Please try again.` (`bodySmall`, error semantics) | — |

**감상 section** (`ThoughtsSection`): heading `감상` / `NOTES` as today. Under the list, on opened shows only, a text
button `감상 남기기` / `WRITE A NOTE` (no emoji; opens the entry sheet with the note focused). No composer, no
own-pending block (the author sees the status in the diary), no admin delete.

**감상 card menu**: a 44dp `⋯` (content description `더 보기` / `MORE`) on each card.

| Card | Items |
|---|---|
| another person's | `신고` / `REPORT` (or `신고함` / `REPORTED`, disabled, when `reportedByMe`); `이 사용자의 감상 숨기기` / `HIDE THIS PERSON'S NOTES` |
| the reader's own | `수정` / `EDIT` (opens the entry sheet); `공개 중단` / `UNPUBLISH` |

- Unpublish confirm: `감상 공개를 중단할까요? 메모는 일기에 남아요.` / `Stop publishing this note? It stays in your
  diary.` — `공개 중단` / `UNPUBLISH`, `취소` / `CANCEL`.
- Hide snackbar: `<이름>님의 감상을 숨겼어요` / `Notes from <name> are hidden`, action `되돌리기` / `UNDO` (long
  duration; stays with a screen reader until acted on).
- Guest choosing 신고: sign-in opens over the page; after sign-in the report sheet opens.
- While a session refresh is pending: `연결을 확인한 뒤 다시 시도해 주세요` / `Check your connection and try again`
  (snackbar) instead of the sheet.

**Report sheet** (layout of `RouteReportSheet.kt:41-124`): title `이 감상을 신고하는 이유` / `WHY ARE YOU REPORTING
THIS NOTE?` (`titleMedium`, heading); radio rows in a `selectableGroup`, selection shown by the 3dp `activeIndicator`
leading bar (its selection role): `부적절한 내용` / `Inappropriate`, `광고·홍보` / `Promotional`, `전시와 관련 없음` /
`Not about the exhibition`, `개인정보 노출` / `Personal information`, `기타` / `Other`; standard black `신고하기` /
`REPORT`, disabled until a reason is chosen, `보내는 중…` / `SENDING…` while busy. Success snackbar `신고했어요` /
`Reported`; already sent: `이미 신고했어요` / `Already reported`; failure `! 신고하지 못했어요. 다시 시도해 주세요.` /
`! Couldn't send the report. Please try again.`

## Diary entry sheet

Full-height `ModalBottomSheet`, `GallrShapeTokens.sheetTop`, background colour, content in a testable
`DiaryEntrySheetContent`. Header: exhibition name (`titleMedium`, two lines) and venue (`labelSmall`,
`onSurfaceVariant`). Sections with `labelLarge` headings and `heading()` semantics:

1. `언제 다녀오셨어요?` / `WHEN DID YOU GO?` — chips `오늘` / `TODAY`, `어제` / `YESTERDAY`, `날짜 선택` /
   `PICK A DATE` (offered per `visitDateOptions`). 날짜 선택 opens a Material3 `DatePickerDialog` limited to the run up
   to today, with explicit monochrome colours (selected day `onBackground` fill, today outlined, no accent), buttons
   `확인` / `OK` and `취소` / `CANCEL`; the chosen date then shows in the chip (`11월 3일` / `NOV 3`). Undated entries
   show `날짜를 정해 주세요` / `Choose the date` under the heading.
2. `어땠어요?` / `HOW WAS IT?` — three single-choice chips (feeling labels); a second tap clears.
3. `무엇이 남았나요?` / `WHAT STAYED WITH YOU?` — nine multi-choice chips (tag labels); a fourth choice is refused
   with `태그는 3개까지 고를 수 있어요` / `You can pick up to three` (`labelSmall`, polite live region).
4. `메모` / `NOTE` — 12dp text field, placeholder `기억하고 싶은 것을 적어 보세요` / `Write what you want to remember`,
   counter `n/280` in `labelSmall` `onSurfaceVariant` at every length (never the accent); input stops at 280. A merged
   note over 280 shows `! 280자 이내로 줄이면 공개할 수 있어요` / `! Shorten it to 280 characters to publish`.
5. Publish block (only with a non-empty note):
   - signed in: row `감상으로 공개` / `PUBLISH AS A NOTE` with a `Switch` (checked thumb and track `onBackground`, no
     accent), then the disclosure (`bodySmall`, `onSurfaceVariant`): `공개하면 검토 후 이 전시 페이지에 내 이름과 함께
     보여요. 기분과 태그는 공개되지 않아요.` / `If you publish, this note appears on the exhibition page with your name
     after review. Your feeling and tags stay private.`
   - status line under it when there is one (see Status words), with the reason for 공개 안 됨:
     `사유: <reason>` / `Reason: <reason>`.
   - published and the note text differs from the public text: outlined `수정한 내용으로 다시 요청` / `ASK AGAIN WITH
     THE EDIT`; not published: outlined `수정해서 다시 요청` / `EDIT AND ASK AGAIN` (focuses the note, then saving asks
     again).
   - guest: text button `로그인하면 감상으로 공개할 수 있어요` / `Sign in to publish your note` (opens sign-in over the
     sheet; the sheet returns after).
   - empty note: the switch is disabled and the disclosure reads `메모를 쓰면 감상으로 공개할 수 있어요` / `Write a note
     to publish it`.
6. One-time line (approved, not yet seen) at the top: `감상이 공개됐어요` / `Your note is now public` (`bodySmall`,
   polite live region); shown once, then acknowledged.

Footer pinned under the scrolling content (hairline above while scrolling): standard black full-width 52dp `저장` /
`SAVE`, disabled until a date is chosen for a new entry; editing an existing entry adds a text button `기록 삭제` /
`DELETE ENTRY` on the left. Delete confirm: `이 기록을 삭제할까요?` / `Delete this entry?`, and when a note is public or
in review `이 기록을 삭제할까요? 공개한 감상도 함께 삭제돼요.` / `Delete this entry? Your published note will be deleted
too.`; buttons `삭제` / `DELETE`, `취소` / `CANCEL`. Back or a drag down with unsaved changes asks `저장하지 않고 닫을까요?`
/ `Close without saving?` with `닫기` / `CLOSE` and `계속 쓰기` / `KEEP EDITING`.

Selected chips are filled `onBackground` with `background` text (the taste sheet's chips), unselected have a 1dp
`outlineVariant` border; chips are toggle buttons with selected state, 44dp tall.

## MY → 일기

The section tab and count read `일기` / `DIARY` (were 방문 / VISITS). Top to bottom:

- Ended-saved strip (when shown): Home-style card, hairline `outlineVariant`, 16dp padding: `저장한 전시 중 끝난 전시
  N개` / `N SAVED SHOWS HAVE ENDED`, text action `추가하기` / `ADD`, a 44dp close with description `닫기` / `CLOSE`.
  Opens `MyGallrMode.ADD_ENDED_SAVED`: the bulk-add screen titled `끝난 저장 전시` / `ENDED SAVED SHOWS`, listing only
  those shows, save button `N개 기록 추가` / `ADD N ENTRIES`.
- Filter chips: `전체` / `ALL`, `공개한 감상` / `PUBLISHED NOTES` (one always selected, `onBackground` fill).
- `+ 지난 전시 추가` / `+ ADD PAST VISITS` (unchanged outlined button).
- Month headings (`labelLarge`, heading): `2026년 11월` / `NOVEMBER 2026`; last group `날짜 미정` / `DATE NOT SET`.
- Rows (72dp minimum, hairline dividers, one Button node): 56dp cover thumbnail (`small` shape; `surfaceVariant` when
  missing), title `titleSmall` (two lines), venue `labelSmall` `onSurfaceVariant`, `11월 9일 · 좋았어요` /
  `NOV 9 · LOVED IT` (`labelMedium`), the note's first line (`bodySmall`, one line, ellipsis), the status word
  (`labelSmall`) when the note has a public status. A 감상 on a show no longer in the catalogue reads `전시 정보 없음` /
  `EXHIBITION UNAVAILABLE` as its title.
- One-time line above the list when any approval is unseen: `감상이 공개됐어요` / `Your note is now public`.
- Empty: `GallrEmptyState` `다녀온 전시를 기록해 보세요` / `Record the shows you've been to`, CTA `지난 전시 추가` /
  `ADD PAST VISITS`. Empty under the filter: `공개한 감상이 아직 없어요` / `No published notes yet`.
- Load failure keeps today's quiet retry pattern: `일기를 불러오지 못했어요.` / `Couldn't load your diary.` with
  `다시 시도` / `RETRY`.

## Status words (row, sheet and the 공개한 감상 filter)

| Status | KO | EN |
|---|---|---|
| PRIVATE | 비공개 | PRIVATE |
| SENDING | 보내는 중 | SENDING |
| RETRY | 보내지 못했어요 · 다시 시도 | NOT SENT · RETRY |
| IN_REVIEW | 검토 중 | IN REVIEW |
| PUBLISHED | 공개됨 | PUBLISHED |
| NOT_PUBLISHED | 공개 안 됨 | NOT PUBLISHED |

Rejection reasons shown to the author: `전시와 관련 없는 내용` / `Not about the exhibition`, `광고·홍보` / `Promotional`,
`부적절한 표현` / `Inappropriate language`, `개인정보 포함` / `Contains personal information`, `기타` / `Other`.

## Profile

The 전시 일기 grid, `DiaryCard`, the admin block and `검토 대기 (n)` are removed. The 감상 count counts own public
notes of any status and opens MY → 일기 with `공개한 감상` selected (`MyTabRequest.DIARY_PUBLISHED`).

## Settings → 숨긴 사용자

Row in 환경 설정: `숨긴 사용자` / `HIDDEN PEOPLE`, value = count when above zero. Screen title the same; rows with the
display name (`bodyMedium`) and a 44dp text button `숨기기 해제` / `UNHIDE`; empty `숨긴 사용자가 없어요` / `No hidden
people`. Available to guests.

## UI tests (iosTest, `runComposeUiTest` v2, written before each screen)

`DiaryEntrySheetTest` (date chips per run, save disabled for a closed show until a date, fourth tag refused, counter
never accent, guest publish line, disclosure text, delete confirm wording with and without a public note);
`DiaryListTest` (month grouping, 날짜 미정 last, filter, strip shows and hides by count); `ThoughtCardMenuTest` (own vs
other items, 신고함 disabled, hide snackbar undo); `ThoughtReportSheetTest` (send disabled until a reason); a detail
test for the diary row's three states and that upcoming shows show no row and no 감상 남기기.
