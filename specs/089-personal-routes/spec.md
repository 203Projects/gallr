# Feature Specification: Routes (personal and public)

**Feature Branch**: `089-personal-routes`
**Created**: 2026-10-08
**Status**: Draft
**Input**: User description: "089-personal-routes: Personal routes for gallr. Authors pick current or upcoming exhibitions, name a route, drag to reorder with live map and timing, save and share it as a monochrome route card and a public page at gallrmap.com/route/{id}; recipients read it without an account and get per-stop Naver Map directions. The approved design and all decisions are in docs/designs/2026-10-07-personal-routes-design.md (office-hours design, engineering review E-D1–E-D21, design review DR-D1–DR-D31, engineering re-review RR1–RR6 and RO1–RO6, plus the "Amended plan summary" sections). Treat that document as the source of truth for scope, behaviour, states, copy and success criteria."
**Amended 2026-10-08**: The owner combined public routes into this feature (no separate 090). Added scope: "Authors list a saved, published route publicly from the 내 동선 row with a consent dialog and see listing status; staff review listing requests, decisions bound to the reviewed revision, reports and unlisting in Admin; readers browse 추천 동선 (top 3 expanding to 10, ranked by 30-day copies) in the Map route sheet, open a read-only preview evaluated at the first shared day, copy a route into their draft (counted once, resumes after sign-in) and report it." Source of truth for that scope: docs/designs/2026-10-08-public-routes-design.md (status APPROVED).

## Context

gallr already builds 2–5 stop routes on the device, but the planner chooses and orders the stops, nothing is kept, and nothing can be sent to anyone. This feature lets a visitor author a route of their own: pick exhibitions, name it, put them in their order, and send it to a friend the way an exhibition is sent today, as an image card and a link.

The design, its premises and every decision are recorded in [the personal routes design document](../../docs/designs/2026-10-07-personal-routes-design.md). That document is the source of truth; this specification states the user-facing behaviour and the measurable outcomes. Decision identifiers in parentheses point to its decision ledgers: E-D (first engineering review), DR-D (design review), RR and RO (engineering re-review and its outside voice).

Public routes (User Stories 7–11) follow [the public routes design document](../../docs/designs/2026-10-08-public-routes-design.md), which the owner combined into this feature on 2026-10-08. Its identifiers are R1–R17 (engineering review decision ledger), DD1–DD22 (design review decisions) and D24–D28 (design review rules for copy, accessibility and layout). Those decisions are settled and are not reopened here.

Premises carried from the design:

- A route is authored. Composing needs no account; sign-in is asked for only at save or share. Reading a shared route never needs an account.
- The author's order is authoritative. Timing and hours conflicts are recomputed and flagged; stops are never re-sorted or dropped.
- Likes, author profiles, notifications, a separate routes-feed screen and an in-app reader for shared links are out of scope for this release. Public routes appear as a section of the Map tab route sheet.
- Listing a route publicly is opt-in and separate from link sharing. A shared route stays unlisted until its author asks; its author agreed to "anyone with the link", not to an in-app list showing their name.
- Copying a route into one's own draft is the only ranking signal. Only live routes are listed (every stop running or upcoming), so no time decay is needed. Editors seed the list and carry an 에디터 label; other authors' listings are pre-approved by staff.
- Mobile first (constitution Principle VII): routes are composed, saved, listed, copied and reported only in the app. The web shows a single shared route read-only at its link; popular routes are app-only.
- The numbers that decide the next slice are how many shared routes a recipient opens within seven days and, for public routes, how often readers who see 추천 동선 copy a route.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Compose and order a route (Priority: P1)

A visitor collects exhibitions while browsing ("동선에 추가" on an exhibition's page) or picks several at once from a picker, names the route, and drags stops into the order they want. After every change the route's map, distance, walking times and an overall verdict ("모두 열림" or "1곳 시간 충돌 · 3번") update, and any stop that does not work on the planned day says why.

**Why this priority**: Composing is the author's whole experience and the source of every shared route.

**Independent Test**: With no account, add four exhibitions from detail pages and the picker, reorder them, and confirm the verdict, per-stop status lines, map and totals update on each change and survive an app restart.

**Acceptance Scenarios**:

1. **Given** no draft, **When** the visitor taps "동선에 추가" on an exhibition's page, **Then** a draft is created with that stop and a confirmation shows the stop count with a way to open the editor (DR-D16).
2. **Given** a draft with stops, **When** the visitor drags stop 3 above stop 2, **Then** the order changes as dropped, the verdict line and stop status lines are recomputed, and no stop is moved or removed by the system.
3. **Given** a stop whose venue is closed on the planned day, or where the visit would start after closing, run short, fall before the exhibition opens, or follow its end, **When** the route is evaluated, **Then** that stop shows its specific status line ("! 휴관일", "! 17:50 도착 · 17:00 마감", "! 관람 20분밖에 없어요", "! 10.24 개막", "! 종료된 전시") and the summary counts conflicts (DR-D8).
4. **Given** it is evening and the first stop has closed for the day, **When** the editor shows the route, **Then** the plan is for the next day the first stop opens, labelled with that day and opening time, within seven days (design "Reference time").
5. **Given** a draft with ten stops, **When** the visitor tries to add another, **Then** the add is refused with an explanation, and the picker shows how full the route is ("3개 추가 · 현재 7/10").
6. **Given** a route of ten stops on a small phone, **When** the visitor drags stop 10 toward the top, **Then** the list scrolls so it can reach position 1; the same move is also available from each row's menu and announced to screen readers (DR-D26).
7. **Given** the visitor removes a stop, **When** they tap undo within five seconds, **Then** the stop returns to its position, unless that would exceed ten stops or duplicate a stop, in which case undo explains why it cannot (DR-D17, RO4).
8. **Given** an exhibition with no map location, **When** the visitor tries to add it, **Then** it cannot be added and shows "위치 정보 없음" (RO5).
9. **Given** a planner route, **When** the visitor taps "내 동선으로 복사", **Then** a draft is created with those stops in that order, ready to edit.

---

### User Story 2 - Save and share a route (Priority: P1)

The author taps 저장 to keep the route or 공유 to send it. If signed out, they sign in and the action finishes afterwards. Before the first share they are told the link is public and shows their display name. Sharing produces a monochrome route card and a link to the route's public page.

**Why this priority**: Sharing is how a route reaches a recipient; without it the success metric cannot move.

**Independent Test**: Signed out, compose a three-stop route, tap 공유, sign in, tap 공유 again when prompted, and confirm a card and a working link are produced and the route appears in the author's list as public.

**Acceptance Scenarios**:

1. **Given** a draft with fewer than two stops, **When** the author views the editor, **Then** 저장 and 공유 are unavailable with a hint to add more (design "App").
2. **Given** a signed-out author taps 저장, **When** they finish signing in, even if the app was closed during sign-in or they had to verify their email, **Then** the save completes once without another tap, provided the draft is unchanged and sign-in finished within 30 minutes (RR1, RO2).
3. **Given** a signed-out author taps 공유, **When** they finish signing in, **Then** the route is saved and published and the editor shows "공유 준비됐어요" with 공유 ready to tap; the share sheet is not opened by itself (RR1).
4. **Given** the author edits the draft or cancels sign-in after tapping 공유, **When** they later sign in, **Then** nothing is published automatically (RO2).
5. **Given** a route that has never been shared, **When** the author is about to share, **Then** a line states that anyone with the link can see the route and the author's display name (DR-D15).
6. **Given** a shared route, **When** the author edits it, **Then** the editor shows it is public and the save action reads "변경사항 저장"; after saving, readers see the new version within about two minutes (DR-D15, E-D13).
7. **Given** a save is in progress, **When** the author adds a stop from an exhibition page before the save finishes, **Then** the new stop is kept and the route shows as not yet saved (RO3).
8. **Given** the network is unavailable, **When** the author taps 저장 or 공유, **Then** an inline error appears, no link is produced, and the draft is kept.

---

### User Story 3 - A recipient reads and walks a shared route (Priority: P1)

A friend taps the link in a chat. Without an account they see what the page is (gallr, "전시 동선"), the route name, whether it works today ("오늘(목) · 3곳 중 2곳 열림 · 토요일엔 3곳 중 3곳 열림"), a drawing of the visit order, and the stops with today's hours and walking estimates. They tap "첫 전시 길찾기" and, after each visit, the next stop's "길찾기" link, which opens directions in Naver Map.

**Why this priority**: The recipient's experience is the success metric and the reason the feature exists.

**Independent Test**: Open a published route's link on a phone with no gallr account and confirm the page answers whether it works today, every stop has directions, and the link preview in a chat names the route as a gallr route.

**Acceptance Scenarios**:

1. **Given** a published route, **When** the link is pasted into a chat app, **Then** the preview names the route as a gallr route with its stop count and distance (DR-D20).
2. **Given** a published route, **When** a recipient opens it, **Then** the first screen shows the gallr name, "전시 동선", the route name and today's verdict line before the drawing (DR-D3, DR-D4).
3. **Given** every stop is running and open today, **When** the page is shown, **Then** the verdict says "모두 열림"; otherwise it states how many are open and names stops that are closed, ended, removed or of unknown hours, and suggests a day within the next week only when more stops are open that day (RO1).
4. **Given** a route on a phone, **When** the recipient scrolls, **Then** the "첫 전시 길찾기" action stays reachable, and each stop row offers its own "길찾기" (DR-D14).
5. **Given** a stop whose exhibition was removed from the catalogue, **When** the page is shown, **Then** that stop shows its saved name and district with "더 이상 볼 수 없는 전시" and no directions link.
6. **Given** a missing, private or removed route, **When** its link is opened, **Then** a page says the route can no longer be seen and offers current exhibitions (DR-D12).
7. **Given** the route service is temporarily unavailable, **When** the link is opened, **Then** a retryable error page is shown, never the "no longer available" page (E-D13).

---

### User Story 4 - Keep and manage routes (Priority: P2)

An author finds their routes in "내 동선": the 동선 section of the MY tab, beside 방문 and 팔로잉, and the Map tab's route sheet. It lists the draft in progress, saved routes and whether each is public, and starts a new route. They open, share or delete them.

**Why this priority**: Without a list, a saved route is reachable only through its own link.

**Independent Test**: Save two routes, publish one, and confirm both appear with their public state, the draft appears first, and deleting the public one warns that its link will stop working.

**Acceptance Scenarios**:

1. **Given** a signed-in author with no routes, **When** they open "내 동선", **Then** an empty state invites them to make a first route (DR-D10).
2. **Given** a signed-out visitor with a draft, **When** they open "내 동선", **Then** they see the draft and a prompt to sign in to see saved routes.
3. **Given** a public route, **When** the author deletes it, **Then** a confirmation warns that shared links will stop opening.
4. **Given** an unpublished saved route, **When** the author shares it from the list, **Then** it is published and the share sheet opens.
5. **Given** an author signs out and another account signs in on the same phone, **When** the second account saves the draft, **Then** it is saved as a new route of the second account (E-D8).

---

### User Story 5 - Staff remove an offending route (Priority: P2)

A staff member pastes a reported route link into the staff workspace, sees the route's name, author, stops and status, confirms, and removes it from public view.

**Why this priority**: Route names are public user content and need a takedown path before launch.

**Independent Test**: As staff, look up a published route, revoke it, and confirm its page shows "no longer available" within about two minutes and its author is told it was removed.

**Acceptance Scenarios**:

1. **Given** a staff member and a route link, **When** they look it up, **Then** they see the name, author display name, stops, status and published date before any change (DR-D13).
2. **Given** a non-staff account, **When** it tries to look up or revoke a route, **Then** it is refused.
3. **Given** a revoked route, **When** its author next saves it, **Then** they are told it was removed and can save the draft as a new route, removing any stops that are no longer listed (E-D18).

---

### User Story 6 - Measure whether shared routes are opened (Priority: P3)

The team can see, from launch day, what share of routes published in a period were opened by a recipient within seven days, and how often recipients start directions.

**Why this priority**: This number decides whether likes and profile routes are built next.

**Independent Test**: Publish routes, open some links as a recipient (and once as a link-preview fetcher), and confirm the aggregate shows the correct opened share, excludes the preview fetch and separates the author's own preview from shared opens.

**Acceptance Scenarios**:

1. **Given** published routes, **When** recipients open some of their shared links, **Then** the aggregate reports the share of routes opened within seven days of first publication (E-D14).
2. **Given** a chat app fetches a link preview, **When** the page is requested, **Then** no open is counted.
3. **Given** authors start drafts, publish and share, **When** the app analytics pipeline is enabled, **Then** drafts started, routes published and share sheets opened are counted as aggregates; until it is enabled these read as unavailable, not zero (E-D6).

---

### User Story 7 - List a route publicly (Priority: P2)

An author who wants other people to use their route opens the route's ⋯ menu in 내 동선 and chooses "공개 목록에 올리기" (or "공개하고 목록에 올리기" when the route has not been shared yet). A dialog says exactly what becomes visible. Afterwards the row tells them where the request stands and, when the route is off the list, why.

**Why this priority**: Listed routes are the supply for 추천 동선; editors seed it, and author listings grow it.

**Independent Test**: Signed in as a non-editor, list a published route and confirm the consent dialog, the snackbar and the row's 목록 검토 중 state; have staff approve it and confirm 목록 승인됨; edit its stops and confirm the warning before saving and the return to review after.

**Acceptance Scenarios**:

1. **Given** a saved, published route, **When** the author chooses "공개 목록에 올리기" and confirms the dialog, **Then** a review is requested, a snackbar reads "검토를 요청했어요 · 보통 하루 안에 처리돼요", and the row reads "{n}곳 · 링크 공개 · 목록 검토 중" (DD13, DD14, DD17).
2. **Given** a saved route that has never been shared, **When** the author chooses "공개하고 목록에 올리기", **Then** the dialog also states that the link becomes public and stays open even if the request is withdrawn or declined, and confirming publishes the route and requests review (DD17, R2).
3. **Given** an author with an active editor membership at the moment of the request, **When** they list a route, **Then** it is approved at once, the snackbar reads "목록에 올렸어요", and the route appears in 추천 동선 if it is eligible (R5, DD14).
4. **Given** a route waiting for review, **When** the author chooses "목록에서 내리기", **Then** the request is withdrawn and the row reads 목록 미등록.
5. **Given** a non-editor route that is waiting for review or approved, **When** the author changes its name or stops in the composer, **Then** the composer warns "저장하면 목록에서 빠지고 다시 검토를 받아요" before saving, and saving returns the route to review and removes it from the list until approved again (DD15, R10).
6. **Given** an approved route with a stop that has ended, a stop no longer in the catalogue, or no day on which every stop can be visited, **When** the author views 내 동선, **Then** the row stays 목록 승인됨 and a third line gives the single most important reason, and the route is not shown to readers (DD13, R4, R12).
7. **Given** a declined route, **When** the author views it, **Then** the row reads 목록 반려됨 with the reason's message and any staff note, and its ⋯ menu offers "수정하기" and "다시 요청" (DD16).
8. **Given** the network fails while requesting, **When** the request cannot be sent, **Then** a snackbar reads "요청하지 못했어요" with "다시 시도", and the row does not change until the request succeeds (DD12).

---

### User Story 8 - Staff review listings and reports (Priority: P2)

A staff member opens the route workspace's review queue, reads each waiting route in the existing preview, and approves it or declines it with a reason. A second view collects reported routes, where staff dismiss reports or take the route off the list.

**Why this priority**: Non-editor routes reach readers only after review, so the queue gates supply; reports are the safety valve after approval.

**Independent Test**: Request a listing as a non-editor, approve it in Admin and confirm it appears to readers; request another, edit it after Admin loaded it, and confirm the decision is refused and the route reloads; report an approved route and confirm dismissing keeps it listed while unlisting removes it.

**Acceptance Scenarios**:

1. **Given** routes waiting for review, **When** staff open the queue, **Then** rows show the name, author, time waited, stop count, and "수정됨" when the route was re-requested after an approval, oldest first (DD11).
2. **Given** a waiting route, **When** staff decline it, **Then** they must choose a reason from 이름·설명 수정 필요, 홍보·광고, 동선 구성 or 기타, and may add a note the author will see (DD16).
3. **Given** the author changed the route after staff loaded it, **When** staff approve or decline, **Then** the decision is refused and the route reloads with "내용이 바뀌었어요 · 다시 확인해 주세요" (R11).
4. **Given** a reported route, **When** staff dismiss the reports, **Then** the route stays listed; **When** staff unlist it instead (after confirming), **Then** it leaves the list, its author sees 목록에서 내려짐, and its link keeps working.
5. **Given** an unlisted-by-staff route, **When** staff restore it, **Then** it becomes 목록 미등록; only staff can move a route out of this state, and editor auto-approval never applies to it.
6. **Given** reports on a route, **When** no staff member has acted, **Then** the route stays listed; reports never hide a route by themselves (R16, design "Admin").
7. **Given** a non-staff account, **When** it tries any review or report action, **Then** it is refused.

---

### User Story 9 - Browse popular routes (Priority: P2)

A visitor planning a day opens the Map tab's route sheet. Below 내 동선 they see "추천 동선", noted as ordered by copies in the last 30 days, with the top three routes and "동선 더 보기" for up to ten. Tapping a route opens a read-only preview: its map, its stops with their status lines, who made it, and a button to copy it.

**Why this priority**: This is how readers meet routes other people made; without it, listing has no audience.

**Independent Test**: With several approved live routes with different copy counts, open the Map route sheet and confirm the three top rows in order, expansion to the rest, row details, and the preview of a route whose stops all open next week.

**Acceptance Scenarios**:

1. **Given** eligible approved routes, **When** the route sheet opens, **Then** the section shows the heading "추천 동선" with "최근 30일 복사 많은 순" and the three highest-ranked routes; "동선 더 보기" expands in place to at most ten and becomes "접기" (DD2, DD6).
2. **Given** the ranking, **When** routes are ordered, **Then** they are ordered by copies made in the last 30 days on the version currently approved, with the more recently approved route first on a tie (R3, R14).
3. **Given** a row, **When** it is shown, **Then** it gives the name and one line "{first district}–{last district} · {n}곳 · {에디터 | author} · 복사 {n}", where the count is the 30-day count and is omitted at zero, the 에디터 label reflects current editor membership, and "{M}월 {D}일부터" is added when the first day every stop is open is after today (DD5, DD20, R5, R12).
4. **Given** the list is loading, empty or failed, **When** the sheet is shown, **Then** it shows the heading with three placeholder rows, hides the section, or shows "추천 동선을 불러오지 못했어요" with "다시 시도" respectively (DD7, R8).
5. **Given** a route with any stop ended, missing from the catalogue, or no common open day, or one that is not approved, published and unrevoked, **When** the list is built, **Then** that route is not shown (R4, R12).
6. **Given** a route whose stops are all open together only from a later date, **When** the reader opens its preview, **Then** stop statuses and hours are judged for that day and the preview says "{M}월 {D}일 기준" (DD21).
7. **Given** a visitor without an account, **When** they open the sheet, **Then** they can browse the section and open previews.
8. **Given** the reader owns the route, **When** they open its preview, **Then** the button reads "내 동선에서 열기" and opens their saved route, and there is no report action (DD22).
9. **Given** an author with many saved routes, **When** the route sheet shows 내 동선, **Then** it lists the draft and at most three saved routes with "모두 보기" leading to the MY tab's 동선 section (DD3).

---

### User Story 10 - Copy and report a public route (Priority: P2)

A reader who likes a route taps "내 동선으로 복사". The route becomes their own draft, ready to edit and save, and the copy counts toward the route's ranking once. A reader who finds a route inappropriate reports it with a reason.

**Why this priority**: Copying is the reader's payoff and the ranking signal; reporting keeps the list trustworthy.

**Independent Test**: Signed out, open a preview, tap copy, sign in, and confirm the copy completes into the draft with the copied note and counts once even after repeated taps; then report a route and confirm it cannot be reported twice.

**Acceptance Scenarios**:

1. **Given** a signed-in reader with no unsaved draft, **When** they tap "내 동선으로 복사", **Then** the button reads "복사 중…" while working, the copy counts once, the draft becomes the route under the route's name as a new unsaved route, and the composer opens showing "복사했어요 · 저장하면 내 동선에 남아요" (R9, DD8, DD9).
2. **Given** a draft with unsaved stops, **When** the reader taps copy, **Then** the replace-draft confirmation appears first, and declining cancels without counting.
3. **Given** the draft changed between the confirmation and the copy, **When** the copy finishes, **Then** the draft is left alone and the preview shows "초안이 바뀌었어요 · 다시 시도"; retrying does not count twice (R13).
4. **Given** the network fails, **When** copying, **Then** the draft is untouched and the preview shows "복사하지 못했어요 · 다시 시도".
5. **Given** the route left the list since it was shown, **When** copying, **Then** the preview shows "이 동선은 더 이상 공개 목록에 없어요" and the list reloads.
6. **Given** a signed-out reader, **When** they tap copy and sign in, **Then** they return to the same preview and the copy continues, with the replace-draft confirmation when needed; cancelling sign-in changes nothing (DD18).
7. **Given** a reader who already copied this approved version, **When** they copy again, **Then** the copy is made but not counted again; an author copying their own route is never counted; a re-approved route counts copies afresh (R14).
8. **Given** a signed-in reader, **When** they choose "신고" in the preview's ⋯ menu, pick a reason (부적절한 이름·내용, 홍보·광고, 잘못된 정보, 기타) and send, **Then** "신고했어요" is shown and the menu item becomes "신고함", disabled, while their report is open (DD10).
9. **Given** sending a report fails, **When** the request cannot be sent, **Then** a snackbar reads "신고하지 못했어요" with "다시 시도" (DD12).

---

### User Story 11 - Measure whether readers use public routes (Priority: P3)

The team can see, from launch to week four, how often readers see 추천 동선 when they open the route sheet and how often a view leads to a copy, and applies a stop rule at week four.

**Why this priority**: These two numbers decide whether to add a more visible surface, keep the section, or stop.

**Independent Test**: Open the route sheet with the section shown several times, copy one route, and confirm the aggregates show section views per route-sheet open and copies per section view without any route or reader identity.

**Acceptance Scenarios**:

1. **Given** the section is shown, **When** analytics is enabled, **Then** one aggregate view is counted with how many rows were shown and no route ids (R15).
2. **Given** launch to week four, **When** the team reads the aggregates, **Then** they can compute exposure (section views per route-sheet open) and interest (counted copies per section view).
3. **Given** week four, **When** exposure meets its threshold but interest does not, **Then** the stop rule applies: no further surfaces, likes, profiles or notifications, editors stop seeding, and the owner decides whether the section stays (R17).

---

### Edge Cases

- Composing in the evening, just before and after midnight in Seoul: the planned day and its label follow the Seoul calendar, not the device's time zone.
- The first stop has unknown hours, has ended or was removed: the plan starts now, without rolling to another day, and that stop is flagged.
- A route whose stops have not opened yet, or have all ended: the page never says "모두 열림".
- Two saves of the same route at the same moment (double tap or two phones): both succeed and the result is exactly one of them.
- The reply to a first save is lost and the author taps again: one route exists, not two.
- A stop is undone after the route reached ten stops or the same exhibition was re-added: undo explains why it cannot apply.
- An exhibition is unpublished after being added: the route keeps it with its saved name and district; reordering an existing route still works; saving the draft as a new route asks to remove it first.
- The author denied location permanently: no "현재 위치에서 출발" button is shown, and timing starts from stop 1 (RR6, RO6).
- Long route names and titles: names are limited to 60 characters after trimming; long titles wrap to two lines in the editor and one line with ellipsis on the card.
- A route spanning cities outside Seoul: stop labels show the city and district.
- Reduced motion or a screen reader: rows reorder without animation and every move is announced.
- A listed route at Seoul midnight: a stop closing today is still eligible at 23:59 and not at 00:00 Seoul time.
- A stop's exhibition is hidden by its gallery, unpublished or deleted while the route is listed: the route leaves the list until edited and approved again.
- Stops whose dates never overlap (for example one closes before another opens): the route is never listed, and its author is told there is no common day.
- An editor loses editor membership: routes already approved stay listed without the 에디터 label; new requests wait for review.
- Staff approve a route while its author is saving an edit: only the version staff saw can be approved; otherwise staff are asked to check again.
- A reader taps copy several times, or copies again later: the route is counted once for that approved version.
- The route is approved again after an edit: earlier copies no longer count toward its rank, and earlier copiers may copy it again.
- An author changes their display name: listed routes show the new name.

## Requirements *(mandatory)*

### Functional Requirements

**Composing**

- **FR-001**: Visitors MUST be able to compose a route without an account, kept on the device across restarts as a single draft.
- **FR-002**: A route MUST hold 2–10 distinct exhibitions in the author's order and a name of 1–60 characters after trimming.
- **FR-003**: Visitors MUST be able to add exhibitions from an exhibition's page, start a draft from a planner route ("내 동선으로 복사", which they are expected to edit), and add from a multi-select picker that shows current capacity, marks exhibitions already in the route, and offers only current and upcoming exhibitions that have a map location (DR-D9, DR-D16, RO5).
- **FR-004**: Visitors MUST be able to reorder stops by dragging, with edge scrolling, and by a per-row menu offering move up, move down, move to top and delete (DR-D26, DR-D29).
- **FR-005**: Every change MUST recompute, in the author's order, the planned day, per-stop arrival and visit timing, per-stop status, walking estimates, total distance and time, and the verdict line; the system MUST never reorder or drop stops.
- **FR-006**: Each stop MUST show exactly one status from: open, closed on the planned day, arrives after closing, visit cut short, not yet opened, ended, no longer listed, hours unknown, each with its own wording; blocking statuses MUST be visually distinct from informational ones (DR-D8).
- **FR-007**: The planned day MUST be today, or, when today cannot be walked from the first stop, the next day within seven days on which the first stop's venue opens and its exhibition runs; the summary MUST name that day and opening time (design "Reference time", E-D15).
- **FR-008**: A departure time MUST be shown only when a starting location is known and departure is later than now (DR-D31). Location MUST be requested only when the author taps "현재 위치에서 출발", never on opening the editor, and that button MUST be hidden after a permanent denial (DR-D30, RR6, RO6).
- **FR-009**: Removing a stop MUST offer an undo that restores its position and obeys the 10-stop and no-duplicate rules (DR-D17, RO4).
- **FR-010**: Changes made from any screen MUST appear immediately in an open editor, and no change may overwrite another (RR3).
- **FR-011**: The editor MUST show the empty, one-stop, saving, saved, not saved, public, save-error, revoked and blocked-stops states with the copy in the design document (DR-D7).

**Saving and sharing**

- **FR-012**: Saving and sharing MUST ask for sign-in only when signed out, and MUST keep the draft through sign-in.
- **FR-013**: An interrupted save or share MUST resume after sign-in, even across an app restart or email verification, only if the draft is unchanged and within 30 minutes; a resumed share MUST stop at a ready state rather than opening the share sheet by itself (RR1, RO2).
- **FR-014**: Saving the same route repeatedly, including after a lost reply, MUST never create a second route (E-D17), and simultaneous saves of one route MUST leave exactly one complete version (E-D12).
- **FR-015**: A saved route MUST store, for each stop, the exhibition's names, venue names, location and district as listed in the catalogue at save time; authors MUST NOT be able to supply these details themselves (E-D7, RR2).
- **FR-016**: Sharing MUST save the current edits, publish the route if needed, and then offer a route card and a link; sharing an unpublished route from the list MUST publish it first.
- **FR-017**: Before a route's first share, the author MUST be told that anyone with the link can see the route and their display name; a public route MUST be marked public in the editor and the list, and its save action MUST say that changes update the public page (DR-D15).
- **FR-018**: A save confirmation MUST apply only to the version that was sent; edits made while a save is in progress MUST be kept and shown as not yet saved (RO3).
- **FR-019**: A saved draft opened under a different account MUST be saved as a new route of that account (E-D8).
- **FR-020**: When a save is refused because the route was revoked, the author MUST be told and be able to save the draft as a new route; stops that are no longer listed MUST be named with a one-tap removal (E-D18).
- **FR-021**: The route card MUST be a monochrome 9:16 image with the gallr name, "전시 동선", route name, visit-order drawing, up to ten numbered stop titles, stop count and distance, and a QR code to the route link (DR-D11).

**Reading a shared route**

- **FR-022**: Anyone with the link MUST be able to read a published, unrevoked route without an account; unpublished, revoked and missing routes MUST show the same "no longer available" page.
- **FR-023**: The page MUST open with the gallr name, "전시 동선", the route name and a verdict line for today in Seoul time, then the author's display name, stop count and distance, the visit-order drawing and the stops (DR-D3, DR-D4).
- **FR-024**: The verdict line MUST say "모두 열림" only when every stop's exhibition runs today and its hours say open; otherwise it MUST state the number open and name stops that are closed, ended, no longer listed or of unknown hours, and MAY suggest a day within the next week when more stops are open that day (RO1).
- **FR-025**: Each stop MUST show today's status in the same wording as the editor, today's hours, its district (with the city outside Seoul), and the walking estimate from the previous stop; stops still listed in the catalogue MUST offer a "길찾기" link that opens Naver Map directions (DR-D14, DR-D28, RR2).
- **FR-026**: The page MUST keep the primary "첫 전시 길찾기" action reachable on phones, pointing at the first open stop (DR-D14).
- **FR-027**: The page MUST draw the visit order as a schematic labelled "방문 순서", not as a street map (DR-D19).
- **FR-028**: The link preview MUST name the route as a gallr route with its stop count and distance (DR-D20).
- **FR-029**: The page MUST show a retryable error page when route data cannot be read, and MUST NOT show the "no longer available" page in that case (E-D13).
- **FR-030**: Edits and removals MUST reach readers within about two minutes; a freshly shared link MUST show exactly the version just saved (E-D13, E-D16).
- **FR-031**: The page MUST read well at phone and desktop widths in Korean and English, and meet the accessibility rules in the design document (landmarks, text alternative for the drawing, focus, link and contrast rules, 44 px targets) (DR-D25, DR-D27).

**Managing routes**

- **FR-032**: Signed-in authors MUST see their saved routes, with public state, in "내 동선", alongside the draft in progress, and MUST be able to open, share and delete them (DR-D10). "내 동선" MUST appear as the MY tab's 동선 section, with a route count beside the visit and following counts and a "+ 새 동선 만들기" action, as well as in the Map tab's route sheet, which shows the draft and at most three saved routes (amended 2026-10-08; FR-053).
- **FR-033**: Deleting a public route MUST warn that its shared links will stop working.

**Moderation**

- **FR-034**: Staff MUST be able to look up any route by link or id, see its name, author, stops and status, and revoke it after confirmation; non-staff and signed-out users MUST be refused (E-D4, DR-D13).
- **FR-035**: Only the route's owner MUST be able to save, publish or delete it, and no one MUST be able to change a route's public or revoked state except through these actions.

**Measurement**

- **FR-036**: The system MUST count route page opens and direction starts per route per day, distinguishing shared links from the author's own preview and excluding link-preview fetchers, without recording reader identity (E-D5).
- **FR-037**: The system MUST record each route's first publication time so the share of routes opened within seven days of publication can be computed (E-D14).
- **FR-038**: Drafts started, routes published and share sheets opened MUST be counted as aggregates through the app's existing analytics consent and release controls (E-D6).

**Unchanged behaviour**

- **FR-039**: The existing route planner, exhibition share card, app analytics events and web pages MUST behave as before (E-D11). The Map tab route sheet MUST behave as before except for the 추천 동선 section (FR-051) and the three-route cap on its 내 동선 list (FR-053); the shared route page MUST keep its responses and content while reading routes by id (FR-040, R10).

**Privacy of shared routes**

- **FR-040**: A published route MUST be readable only by its id; no one without a route's link MUST be able to list or enumerate published routes or their authors through the app's data service. Only a route's owner reads their own routes directly (R1, T075).

**Listing publicly**

- **FR-041**: Signed-in authors MUST be able to request public listing of a saved route only from that route's ⋯ menu in 내 동선: "공개 목록에 올리기" for a published route, "공개하고 목록에 올리기" for an unpublished one (publish, then request), and "목록에서 내리기" to withdraw or turn listing off. The composer offers no listing control (R6).
- **FR-042**: Every listing request MUST first show a consent dialog stating that, once reviewed, the route and the author's display name appear in 추천 동선 (editors: right away); the publish-and-list option MUST also state that the link becomes public and stays open even if the request is withdrawn or declined (DD17, R2).
- **FR-043**: A route's listing MUST follow these states: not listed, waiting for review, approved, declined, unlisted by staff. Requests from an author with an active editor membership at request time MUST be approved at once; others wait for staff. Declined routes MAY be edited and requested again without limit. Only staff MUST be able to move a route out of "unlisted by staff", and editor auto-approval MUST NOT apply to it. Turning listing off or deleting the route leaves the list; staff revoke (089) also leaves it (design "Listing lifecycle", R2, R5).
- **FR-044**: Saving a change to the name or stops of a non-editor route that is waiting or approved MUST return it to review in the same save and remove it from the list until approved; editors' changes stay approved. Before such a save, the composer MUST warn "저장하면 목록에서 빠지고 다시 검토를 받아요" (DD15, R10).
- **FR-045**: Each 내 동선 row MUST show the stop count, link state and listing state on its second line, and at most one reason on a third line in this order of precedence: link revoked, unlisted by staff, decline reason, ended stop, stop no longer in the catalogue, no common open day. "목록 승인됨" MUST NOT claim the route is currently visible (DD13).
- **FR-046**: Staff decline reasons MUST be a fixed list (name or description needs fixing, promotional, route composition, other) plus an optional note shown to the author; each reason MUST map to the author message in the design (DD16).
- **FR-047**: A listing request MUST confirm with "검토를 요청했어요 · 보통 하루 안에 처리돼요", or "목록에 올렸어요" for editors (DD14).

**Eligibility and ranking**

- **FR-048**: A route MUST be shown to readers only while it is approved, published, not revoked, every stop's exhibition is still in the catalogue and running or upcoming in Seoul time, and there is at least one day on which every stop is running (R4, R12).
- **FR-049**: Routes MUST be ranked by copies made in the last 30 days on the currently approved version, ties going to the more recently approved route, and the list MUST hold at most ten routes. Ranking and eligibility are decided by the service; the app shows the order it receives (R3, R14).
- **FR-050**: The 에디터 label MUST reflect current editor membership; a former editor's approved routes stay listed under their display name (R5).

**Browsing**

- **FR-051**: The Map tab route sheet MUST show 추천 동선 below 내 동선 with its heading and order note, three rows, and an in-place "동선 더 보기" / "접기" for the rest; rows MUST follow the anatomy in User Story 9; the section MUST show placeholder rows while loading, hide when empty, and show an error with retry when loading fails (DD2, DD5, DD6, DD7, R8).
- **FR-052**: Anyone, signed in or not, MUST be able to browse 추천 동선 and open a route's read-only preview with its map, stops and status lines, author and copy button; the preview MUST judge stops for the first common open day when that is after today and say so (DD1, DD21).
- **FR-053**: The route sheet's 내 동선 MUST show the draft and at most three saved routes with "모두 보기" to the MY tab's 동선 section; the MY tab section stays complete (DD3).

**Copying and reporting**

- **FR-054**: Copying MUST need sign-in and resume after sign-in in the same preview. It MUST ask before replacing a draft with unsaved stops, count the copy before changing the draft, and apply it only to the draft the reader confirmed; each outcome (success, changed draft, network failure, route no longer listed) MUST show its message from User Story 10 (R9, R13, DD18).
- **FR-055**: A copy MUST be counted at most once per account per approved version and never for the route's own author; the copy MUST become an ordinary unsaved draft under the route's name with a new identity and no link to the original (R14, R7).
- **FR-056**: The preview of a reader's own route MUST offer "내 동선에서 열기" instead of copy and no report action (DD22).
- **FR-057**: Signed-in readers MUST be able to report a listed route with one of the fixed reasons; an account MUST have at most one open report per route and MUST NOT be able to report its own route. Reports MUST NOT hide a route automatically (DD10, design "Admin").
- **FR-058**: Small actions (copy, report, listing requests) MUST show a busy state, change state only after the service confirms, and on failure offer a retry of the same action (DD8, DD12).

**Staff review**

- **FR-059**: Staff MUST have a review queue (oldest first) and a reports view in the route workspace, approve or decline with a reason, dismiss reports, unlist and restore routes; non-staff MUST be refused (DD11).
- **FR-060**: A staff decision MUST apply only to the version staff reviewed; if the route changed, the decision MUST be refused and the route reloaded (R11).
- **FR-061**: Unlisting MUST remove a route from the list while keeping its link working; revoking (FR-034) removes both.

**Platform and presentation**

- **FR-062**: Listing, copying and reporting MUST be available only in the app. The web MUST show only a single shared route, read-only, at its link, with no popular-routes page and no copy counts (constitution Principle VII; owner decision 2026-10-08).
- **FR-063**: New text MUST be bilingual, with English casing following the existing route screens and the English wording set in the design (D24, D25). New surfaces MUST provide headings, one combined spoken description per row, announced busy and status changes, focus handling for the consent dialog, a radio-group report sheet and 44dp targets (D26); rows MUST wrap rather than truncate at large text sizes and motion MUST respect reduced-motion settings (D27); layouts stay single-column at every width (D28). The copy button is the standard black button; the orange accent is not used (DD19).

**Measurement**

- **FR-064**: Each time 추천 동선 is shown, the app MUST count an aggregate view with the number of rows shown and no route or reader identity, through the existing analytics consent and release controls (R15).

### Key Entities

- **Personal route**: an authored, named, ordered set of 2–10 exhibitions owned by one account; public or private; may be revoked by staff; records when it was first published.
- **Route stop**: one exhibition in a route at a position, with its catalogue names, venue names, location and district as they were when the route was last saved.
- **Draft**: the single route being composed on a device, with a revision that changes on every edit, the account it belongs to, and the saved route it corresponds to, if any.
- **Pending action**: a save or share the author asked for while signed out, tied to one draft revision and valid for 30 minutes.
- **Route page counts**: daily aggregates of opens and direction starts per route, split by whether the shared link was used.
- **Listing**: a personal route's public-list state (not listed, waiting for review, approved, declined, unlisted by staff), when it was requested and decided, by whom, and the decline reason and note.
- **Route copy**: one account's copy of a listed route, tied to the approved version it was made on and when; counts toward ranking.
- **Route report**: one account's report of a listed route with a reason, open until staff dismiss or uphold it.
- **Public route list**: the ranked, eligible routes readers see, computed from listings, copies and the catalogue for a Seoul date.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From launch day, the team can report what share of routes first published in a week were opened by a recipient within seven days, and how many opens led to a direction start.
- **SC-002**: No shared route page ever shows a stop as open on a day its listed hours or exhibition dates say it is closed, and no page says "모두 열림" unless every stop is open (checked against the published-catalogue fixture and the shared hours test cases).
- **SC-003**: A first-time author can compose a four-stop route, reorder it and produce a share card and link in under three minutes.
- **SC-004**: On the smallest supported phones, an author can move stop 10 to position 1 by dragging and by the row menu.
- **SC-005**: Recipients on a 375 px-wide screen see the gallr name, the route name and today's verdict without scrolling, and can start directions to the first open stop in one tap.
- **SC-006**: A route revoked by staff stops being readable by recipients within two minutes.
- **SC-007**: Retried and simultaneous saves never produce duplicate routes or mixed stop orders.
- **SC-008**: Saves interrupted by sign-in complete after sign-in in every tested path (OAuth return, app restart, email verification within 30 minutes), and no route is ever published without the author tapping share on that version.
- **SC-009**: The shared page passes the project's automated accessibility check in both themes and both languages.
- **SC-010**: Within four weeks of launch, at least 30 approved live routes are listed, at least 10 of them from non-editor authors.
- **SC-011**: The median time from a listing request to a staff decision is under 24 hours.
- **SC-012**: From launch to week four, exposure (section views per route-sheet open) and interest (counted copies per section view) are reported weekly against these thresholds (owner decision 2026-10-08): exposure is met when at least 50% of route-sheet opens show the section, and interest is met when at least 3% of section views lead to a counted copy. Exposure below 50% means adding a 추천 strip (R15).
- **SC-013**: At week four, if exposure meets its threshold but interest does not, expansion stops: no 추천 strip, likes, author profiles or notifications; editors stop seeding new routes; and the owner decides whether the section stays (R17).
- **SC-014**: No reader ever sees a listed route with an ended stop, a stop no longer in the catalogue, or no common open day, checked against fixed dates including Seoul midnight.
- **SC-015**: Repeated copy taps never count a route twice, and no staff decision ever applies to a version staff did not see.
- **SC-016**: Without a route's link, no one, signed in or not, can list published routes or their authors.
- **SC-017**: A reader can go from 추천 동선 to an editable copy of a route in at most three taps when signed in.

## Assumptions

- The route planner improvements from spec 088 (opening-hours reading, walking estimates, departure timing, route map panel) are available; this feature builds on them.
- Accounts, display names and staff membership already exist; publishing a route makes the author's display name public, as a Thought does.
- The app analytics pipeline from spec 072 is integrated but switched off in production; author-loop counts depend on its separate activation.
- Recipients mostly open links on phones from KakaoTalk; desktop is supported with a single column.
- Naver Map is the directions provider, matching the existing web exhibition pages.
- Release order follows the design document: database changes, then the staff workspace, then the web page, then the mobile release.
- Editors compose and list at least ten routes in the app before public routes are promoted; until any route is approved and eligible, the 추천 동선 section stays hidden, so the feature can ship before seeding. Who writes those routes and by when is an owner decision outside this spec (public routes design, Open Question 1).
- Editor status comes from the existing editor membership and staff authority from the staff membership, never from the client.
- Exposure and interest (SC-012) depend on the app analytics pipeline from spec 072 being activated.
- The shared web page does not show copy counts (public routes design, Open Question 2).
