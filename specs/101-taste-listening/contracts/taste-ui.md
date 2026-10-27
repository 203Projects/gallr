# Contract: taste UI (composeApp/commonMain)

Copy is final unless the owner changes it; every string has KO and EN through `AppLanguage`. Shapes and type follow
DESIGN.md (Home's 16 dp cards, 12 dp small pieces, black primary button, no accent outside its three roles).

## Taste pick sheet

- Title `어떤 전시를 좋아하세요?` / `What exhibitions do you like?`; subline `3개 이상 고르면 내 취향이 시작돼요 · 이 기기에만 저장` /
  `Pick three or more to start · Saved on this device only`.
- Term chips (outline; selected = filled `onBackground`), then `또는 좋아하는 전시를 고르세요` / `Or pick exhibitions you
  like` and a 3 × 2 grid of covers (12 dp, selected = 2 dp outline and a check).
- Buttons: `건너뛰기` / `SKIP` (text), `시작하기 (n)` / `START (n)` (black, disabled below 3 with
  `n개 더 골라 주세요` / `Pick n more`). Opened from a chip or the prompt card the button reads `저장` / `SAVE`.
- 덜 보기 row (only when dismissed terms exist): label `덜 보기` / `SEEING LESS`, chips with `되돌리기` / `UNDO`.
- Opens on its own only when `shouldOfferTastePick` is true; never over `RequiredUpdateScreen`; waits for the update
  nudge to be answered and for the `Account` screen to close. Back dismisses without recording a skip.
- Accessibility: chips and covers are toggle buttons with selected state; the count is announced on change.

## Home 내 취향 section

- With personal cards: the banner, filtered to personal cards (1–6). Subtitle counts picks first when there are
  any: `선택 n · 저장 n · 방문 n · 팔로우 n 기반` / `BASED ON n PICKED · n SAVED · …` (other counts unchanged).
- Chips row: label `내 취향` / `YOUR TASTE`, up to four chips as buttons, plus `편집` / `EDIT`; each opens the sheet with
  current picks.
- No signal, or after a skip: prompt card (Home card, hairline outline, 16 dp padding):
  `전시를 고르거나 저장하면 취향에 맞춰 골라드려요` / `Pick or save exhibitions and the picks follow your taste`,
  button `취향 고르기` / `PICK YOUR TASTE`.
- Seed but nothing matches: `선택한 취향에 맞는 전시가 아직 없어요` / `Nothing running matches your picks yet`, button
  `취향 바꾸기` / `CHANGE PICKS`.
- Loading: skeleton cards at final height.

## Reasons

- New reason strings: `좋아하는 {분류}: {용어}` / `YOUR PICK · {CATEGORY}: {TERM}` (e.g. `좋아하는 스타일: 미니멀`);
  `좋아하는 "{anchor}"와 같은 작가: {artist}` / `YOUR PICK "{anchor}" · SAME ARTIST: {ARTIST}`;
  `좋아하는 "{anchor}"와 공통 {category}: {term}` / `YOUR PICK "{anchor}" · SHARED {CATEGORY}: {TERM}`;
  `좋아하는 "{anchor}"와 비슷한 전시` / `YOUR PICK "{anchor}" · SIMILAR EXHIBITION`.
- Exhibition page opened from 내 취향: the joined reasons under the title (`labelSmall`, `onSurfaceVariant`), included in
  the title's accessibility description.

## 덜 보기

- Card overflow (44 dp target, `⋯`): `저장` / `SAVE` (or `저장 취소`), `덜 보기` / `LESS LIKE THIS`, `공유` / `SHARE`.
- After 덜 보기: snackbar `숨겼어요 · 종료일까지` / `Hidden until it closes`, action `되돌리기` / `UNDO`; the list keeps
  the card until the next refresh, shown at 50% opacity.
- Available on 모두 보기 cards and Home 내 취향 rail cards; not on editorial sections.
