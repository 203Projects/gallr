# Feature Specification: Visit diary with 감상 joined in

**Feature Branch**: `103-visit-diary`
**Created**: 2026-11-11
**Status**: Draft
**Release**: 1.16
**Input**: User description: "Visit diary with 감상 joined in" (the 1.16 theme), with the owner's decisions of 2026-11-11
and the conductor's investigation of the code, schema and production counts (technical facts in
[research.md](research.md)).

## Background

gallr has two separate ways to write about an exhibition, and neither works well. A visit (방문) records only that a
person went: no date, no feeling, no words, and the exhibition page offers it only after a show has closed. A 감상 is a
public text of up to 280 characters that can be written on any exhibition, including one the writer has not seen or
that has not opened, with no warning that it will be public under the writer's name after review. A rejected 감상
silently disappears. Nothing in the app lets a reader report a 감상 or stop seeing one person's 감상, which App Store
guideline 1.2 expects of any app with user-written public text. The Profile 전시 일기 grid is built from the LIST tab's
filtered list, so it drops closed shows and shrinks while a LIST search is active.

Usage is small, which makes this the right moment to change the model: in production on 2026-11-11 there are 4 감상
from 3 authors (none in the last 30 days, all four approved), 157 accounts, and 21 synced visits from 5 accounts; no
author has a visit on the exhibition they wrote about.

This release joins the two into one diary. A diary entry is the visit plus what the visitor wants to keep: the date,
how it felt, what stayed with them, and a private note. A 감상 becomes a diary note the visitor chose to publish.

Spec 053 (US3, FR-012, FR-013, FR-020) described private visits with notes and deferred any public opt-in to "a
separate future opt-in feature"; its tasks file states that diary expansion needs a fresh specification. This is that
specification.

## Owner decisions (2026-11-11, binding)

1. A public 감상 comes **only** from a diary entry: it is a diary note the visitor chose to publish. The free composer
   on the exhibition page goes away; there is no 감상 on upcoming or unvisited shows.
2. "다녀왔어요" can be recorded on **any exhibition that has opened** (opening date on or before today), not only after it
   closes. This **reverses spec 065 FR-003** ("The prompt appears only for ended, unrecorded exhibitions").
3. Private diary notes **sync to the signed-in account**. Guests keep them on the device until they sign in; signing
   in merges them into the account.
4. An entry includes a private overall feeling, 좋았어요 / 그저 그랬어요 / 아쉬웠어요 (Loved it / It was fine / Not for
   me), used **only** to tune 내 취향 recommendations on the device; it is never shown publicly, never aggregated, and
   never sent to analytics.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Record a visit with its date and feeling (Priority: P1)

A visitor comes back from a show that is still running. On the exhibition page, one row reads "다녀왔어요 →" /
"I'VE BEEN →". Tapping it opens the diary entry sheet: 언제 다녀오셨어요? / WHEN DID YOU GO? with 오늘 / 어제 / 날짜
선택 (TODAY / YESTERDAY / PICK A DATE), then 어땠어요? / HOW WAS IT? with 좋았어요 / 그저 그랬어요 / 아쉬웠어요, then
무엇이 남았나요? / WHAT STAYED WITH YOU? with up to three tags, then a 메모 / NOTE field of up to 280 characters, and
저장 / SAVE. Only the date is needed; everything else is optional. After saving, the row shows the visit date and the
feeling with 수정 / EDIT, and the entry is in MY → 일기. This works for guests; a guest's entries stay on the device.

**Why this priority**: It is the core of the diary and the precondition for every other story. It also removes the
rule that hid the visit prompt on running shows, which is where most visits happen.

**Independent Test**: On a running exhibition, as a guest, tap 다녀왔어요, choose 어제 and 좋았어요, save. The row shows
yesterday's date and 좋았어요 with 수정; MY → 일기 lists the entry under this month; reopening the app keeps it.

**Acceptance Scenarios**:

1. **Given** an exhibition whose opening date is on or before today and no entry for it, **When** the exhibition page
   shows, **Then** one row "다녀왔어요 →" is shown, whether the show is running or closed.
2. **Given** an exhibition whose opening date is after today, **When** the page shows, **Then** there is neither the
   row nor any way to write a note.
3. **Given** the sheet for a running show, **When** it opens, **Then** 오늘 is preselected, 어제 is offered only if
   yesterday is within the show's run, and 날짜 선택 allows only dates from the opening date to the earlier of today
   and the closing date.
4. **Given** the sheet for a closed show, **When** it opens, **Then** no date is preselected, 오늘 is not offered, 어제
   is offered only if it falls within the run, and 저장 stays disabled until a date is chosen.
5. **Given** the sheet, **When** the visitor taps a feeling, **Then** exactly one feeling is selected; tapping it again
   clears it.
6. **Given** three tags are selected, **When** the visitor taps a fourth, **Then** it is not selected and the sheet
   says that three is the most.
7. **Given** the note field, **When** the visitor types, **Then** a counter shows the length out of 280 in a neutral
   colour (never the accent) and input stops at 280 characters.
8. **Given** a saved entry, **When** the page shows, **Then** the row reads the visit date and the feeling, for example
   "11월 9일 다녀옴 · 좋았어요  수정" / "VISITED NOV 9 · LOVED IT  EDIT", and the feeling is visible only to this
   visitor.
9. **Given** a saved entry, **When** the visitor taps 수정, **Then** the sheet opens with every saved value and a 기록
   삭제 / DELETE ENTRY action.
10. **Given** the device is offline, **When** the visitor saves, **Then** the entry is saved on the device at once and
    reaches the account when the connection returns (signed in) without the visitor doing anything.

---

### User Story 2 - Keep and read the diary in MY (Priority: P1)

The MY tab's 방문 / VISITS section becomes 일기 / DIARY: every entry the person has, grouped by month of the visit date,
newest first. Each row shows the cover, the title, the venue, the visit date, the feeling, the first line of the note
and, if the note has been published, its status word. Tapping a row opens the entry sheet. At the top, a strip
"저장한 전시 중 끝난 전시 N개" / "N SAVED SHOWS HAVE ENDED" leads to a list of those closed saved shows to add as
entries. The existing "+ 지난 전시 추가" / "+ ADD PAST VISITS" bulk add stays. The Profile 전시 일기 grid is removed;
the diary replaces it. A filter "공개한 감상" / "PUBLISHED NOTES" shows only entries whose note was published, and
replaces the separate 내 감상 screen.

**Why this priority**: A diary that cannot be read back is not a diary. It also fixes the Profile grid that dropped
closed shows and shrank under a LIST search.

**Independent Test**: With entries for one running and two closed shows in different months, open MY → 일기: three
rows under two month headings, each with its cover, date and feeling. Type a search on the LIST tab and return: the
diary is unchanged. Save two shows that have closed: the strip reads 2 and leads to adding them.

**Acceptance Scenarios**:

1. **Given** entries in October and November, **When** MY → 일기 opens, **Then** the entries are grouped under
   "2026년 11월" and "2026년 10월" (EN "NOVEMBER 2026", "OCTOBER 2026"), newest visit date first.
2. **Given** an entry for an exhibition that has closed or left the live catalogue, **When** the diary shows, **Then**
   the row still shows its cover, title, venue and dates from the record kept at the time.
3. **Given** a LIST search, city or filter is active, **When** the diary shows, **Then** it lists every entry
   regardless.
4. **Given** an entry with a note, **When** its row shows, **Then** the first line of the note is shown, truncated to
   one line; an entry without a note shows no note line.
5. **Given** an entry with no confirmed visit date (recorded by the bulk add, or from before this release), **When**
   the diary shows, **Then** it is listed in a group "날짜 미정" / "DATE NOT SET" after the dated months, and its sheet
   asks for a date.
6. **Given** three saved exhibitions have closed and two of them have no entry, **When** the diary shows, **Then** the
   strip reads "저장한 전시 중 끝난 전시 2개"; tapping it opens a list of those two to select and add, each added one
   becoming an entry without a confirmed date.
7. **Given** the strip, **When** the visitor closes it, **Then** it stays hidden until the count grows; with a count of
   zero it is never shown.
8. **Given** the 공개한 감상 filter, **When** it is selected, **Then** only entries whose note has a public status are
   listed, each with its status word.
9. **Given** the Profile screen, **When** it shows, **Then** there is no 전시 일기 grid; the 감상 count there opens MY →
   일기 with the 공개한 감상 filter on.
10. **Given** no entries, **When** the diary shows, **Then** an empty state says "다녀온 전시를 기록해 보세요" / "Record
    the shows you've been to" with the bulk add as its action.
11. **Given** the LIST tab's 내 전시 filter, **When** it is used, **Then** it behaves exactly as today.

---

### User Story 3 - Publish a diary note as a 감상 (Priority: P1)

A signed-in visitor writes a note in an entry and turns on 감상으로 공개 / PUBLISH AS A NOTE. A plain line under it
says: "공개하면 검토 후 이 전시 페이지에 내 이름과 함께 보여요. 기분과 태그는 공개되지 않아요." / "If you publish,
this note appears on the exhibition page with your name after review. Your feeling and tags stay private." The entry
then shows 검토 중 / IN REVIEW. When staff approve it, it appears in the 감상 list on the exhibition page, the entry shows
공개됨 / PUBLISHED, and the next time the visitor opens the entry or the diary a one-time line reads "감상이
공개됐어요" / "Your note is now public". If staff do not approve it, the entry shows 공개 안 됨 / NOT PUBLISHED with the
reason and "수정해서 다시 요청" / "EDIT AND ASK AGAIN"; the private note is kept in every case. A guest sees the
choice with "로그인하면 감상으로 공개할 수 있어요" / "Sign in to publish your note", which opens sign-in.

Below the 감상 list on the exhibition page, "감상 남기기" / "WRITE A NOTE" opens the same entry sheet with the note
focused; for a show the visitor has not recorded, saving the sheet records the visit too. There is no other way to write
a 감상.

**Why this priority**: Owner decision 1: the 감상 is part of the diary. Without this story the 1.16 release would remove
the only way to write a public 감상.

**Independent Test**: Signed in, write a note on a recorded show and publish: the entry shows 검토 중 and the exhibition
page does not show it. Approve it in Admin: the exhibition page shows it with the author's name, the entry shows 공개됨
and the one-time line once. Reject another with a reason: the entry shows 공개 안 됨 and the reason; the note text is
still in the entry.

**Acceptance Scenarios**:

1. **Given** an entry with an empty note, **When** the sheet shows, **Then** 감상으로 공개 is disabled.
2. **Given** a signed-in visitor with a note, **When** they turn on 감상으로 공개 and save, **Then** the entry status is
   검토 중 and the note is not visible to anyone else.
3. **Given** a guest, **When** the sheet shows, **Then** the publish choice is replaced by "로그인하면 감상으로 공개할
   수 있어요", which opens sign-in over the sheet and returns to it.
4. **Given** a note in review, **When** staff approve it, **Then** it is shown on that exhibition's page with the
   author's display name, and the entry reads 공개됨.
5. **Given** an approval the author has not yet seen, **When** they next open the diary or that entry, **Then** "감상이
   공개됐어요" is shown once and not again.
6. **Given** staff reject a note with a reason, **When** the author opens the entry, **Then** it reads 공개 안 됨 with
   that reason and "수정해서 다시 요청"; the note text is unchanged in the entry and nothing is shown publicly.
7. **Given** a published note, **When** the author edits the note text and saves, **Then** the public 감상 keeps its
   previous text and the sheet offers "수정한 내용으로 다시 요청" / "ASK AGAIN WITH THE EDIT"; choosing it replaces the
   public 감상 with nothing until the new text is approved, and the status returns to 검토 중.
8. **Given** a published or in-review note, **When** the author turns off 감상으로 공개, **Then** the public 감상 is
   removed, the status becomes 비공개 / PRIVATE, and the private note is kept.
9. **Given** an entry with a published note, **When** the author chooses 기록 삭제, **Then** the confirmation reads
   "이 기록을 삭제할까요? 공개한 감상도 함께 삭제돼요." / "Delete this entry? Your published note will be deleted too.",
   and confirming removes both.
10. **Given** an exhibition page, **When** it is shown, **Then** there is no free-text composer; "감상 남기기" appears
    only on exhibitions that have opened and opens the entry sheet with the note field focused.
11. **Given** the four 감상 that are public today, **When** this release ships, **Then** they stay public and each
    appears in its author's diary as an entry with the note published.

---

### User Story 4 - Report a 감상 or stop seeing one person's 감상 (Priority: P1)

A reader finds a 감상 that is abusive or an advertisement. Each 감상 card has a ⋯ menu with 신고 / REPORT, which opens
a sheet of fixed reasons (부적절한 내용 / Inappropriate, 광고·홍보 / Promotional, 전시와 관련 없음 / Not about the
exhibition, 개인정보 노출 / Personal information, 기타 / Other) and a black 신고하기 / REPORT button, as on public
routes. The same menu offers "이 사용자의 감상 숨기기" / "HIDE THIS PERSON'S NOTES": that person's 감상 disappear from
every exhibition page on this device, with an undo in the snackbar and a list in Settings to bring them back. On their
own 감상, the author sees 수정 / EDIT (opens the entry sheet) and 공개 중단 / UNPUBLISH instead.

**Why this priority**: The release widens user-written public text; App Store guideline 1.2 expects a way to report it
and to block abusive users. It ships with the release, not after it.

**Independent Test**: Signed in, report someone's 감상 with 광고·홍보: the sheet closes with "신고했어요" and the report
is in Admin's reported list. Hide that author: their 감상 vanish from every exhibition on this device and come back from
Settings.

**Acceptance Scenarios**:

1. **Given** a signed-in reader on another person's 감상, **When** they choose 신고, pick a reason and send, **Then** a
   report with that reason reaches staff and the reader sees "신고했어요" / "Reported".
2. **Given** a reader who already reported that 감상 in its current text, **When** they open the menu, **Then** 신고
   reads "신고함" / "REPORTED" and cannot be sent again; after the text is edited and re-approved they can report again.
3. **Given** a guest, **When** they choose 신고, **Then** sign-in opens and the report sheet follows after sign-in.
4. **Given** a reader chooses "이 사용자의 감상 숨기기", **When** any exhibition page shows, **Then** none of that
   person's 감상 are shown on this device; the snackbar's 되돌리기 / UNDO restores them.
5. **Given** hidden authors, **When** the reader opens Settings → 숨긴 사용자 / HIDDEN PEOPLE, **Then** each is listed by
   display name with 숨기기 해제 / UNHIDE.
6. **Given** a 감상 of the reader's own, **When** they open its menu, **Then** it offers 수정 and 공개 중단, never 신고 or
   숨기기.
7. **Given** a reported 감상, **When** it has not yet been decided by staff, **Then** it stays visible to others except
   to the reader who reported it, for whom it is hidden at once.

---

### User Story 5 - Staff moderate 감상 in Admin (Priority: P1)

Staff open a new 감상 section in staff Admin with two lists: 검토 대기 / PENDING (published notes waiting for a first
decision or re-review after an edit) and 신고됨 / REPORTED (public 감상 with open reports, newest report first, with
their reasons and counts). For each 감상 they see the text, the author's display name, the exhibition, and when it was
asked; they approve, or reject with a reason from a fixed list. For a reported 감상 they keep it (closing the reports) or
take it down with a reason. Every decision records who made it and when. The mobile admin queue is retired.

**Why this priority**: Every published note waits for this queue; without it nothing is ever public. Reports need a
place to land.

**Independent Test**: Publish a note from the app; it appears under 검토 대기 in Admin. Reject it with 광고·홍보: it
leaves the list and the author's entry shows that reason. Report an approved 감상 from the app: it appears under 신고됨
with the reason; taking it down removes it from the exhibition page.

**Acceptance Scenarios**:

1. **Given** a note asked for review, **When** a staff member opens Admin → 감상, **Then** it is listed under 검토 대기
   with the text, author, exhibition and time.
2. **Given** a pending 감상, **When** staff approve it, **Then** it becomes public at once and leaves the list.
3. **Given** a pending 감상, **When** staff reject it, **Then** they must choose one reason (전시와 관련 없는 내용 / Not
   about the exhibition, 광고·홍보 / Promotional, 부적절한 표현 / Inappropriate language, 개인정보 포함 / Contains
   personal information, 기타 / Other); the author's private note is untouched.
4. **Given** a public 감상 with reports, **When** staff keep it, **Then** its open reports are closed and it stays
   public; **When** staff take it down, **Then** it leaves the exhibition page and the author sees 공개 안 됨 with the
   chosen reason.
5. **Given** an author edits an approved 감상 and asks again, **When** Admin shows 검토 대기, **Then** the 감상 is listed
   again with its new text and the exhibition page shows nothing for it meanwhile.
6. **Given** a staff member without the moderation permission, **When** they open Admin, **Then** the 감상 section is
   not shown and its lists cannot be read.
7. **Given** the mobile app, **When** any account opens Profile, **Then** there is no admin 감상 queue and no admin
   delete on other people's 감상.

---

### User Story 6 - The diary tunes 내 취향 on the device (Priority: P2)

How a visit felt changes what 내 취향 suggests next. A visit marked 좋았어요 pulls up similar shows as strongly as a save;
그저 그랬어요 keeps today's visit weight; 아쉬웠어요 stops that visit from pulling similar shows up and lowers shows that
share its reviewed terms and artists a little, less than 덜 보기. Tags on a 좋았어요 visit that correspond to a reviewed
mood term count like a term the visitor picked. All of this happens on the device; feelings and tags are never sent for
ranking, shown to anyone else, or counted.

**Why this priority**: It turns the feeling into something the visitor gets back, but the diary stands without it.

**Independent Test**: In the shared made-up profile tests, a profile with one 좋았어요 visit ranks shows sharing its
artist as high as the same profile with a save instead; marking that visit 아쉬웠어요 drops those shows and lowers
others sharing its terms, by less than a 덜 보기 would.

**Acceptance Scenarios**:

1. **Given** a visit marked 좋았어요, **When** 내 취향 is ranked, **Then** it counts with the same weight as a save of
   that exhibition.
2. **Given** a visit marked 그저 그랬어요 or with no feeling, **When** 내 취향 is ranked, **Then** it counts exactly as a
   visit counts today.
3. **Given** a visit marked 아쉬웠어요, **When** 내 취향 is ranked, **Then** it adds no positive evidence and lowers
   shows sharing its reviewed terms or credited artists by a smaller amount than one 덜 보기, never below zero and never
   moving a card out of the personal-evidence section (spec 101 FR-013 rules apply).
4. **Given** a 좋았어요 visit tagged 조용히 머물기 좋았어요, **When** 내 취향 is ranked, **Then** shows carrying the
   corresponding reviewed mood term gain evidence as a picked term would, and the reason line names the visitor's own
   visit, never the tag as if it were a pick.
5. **Given** a reason line that names a visit, **When** it is shown, **Then** it never mentions the feeling or tags.
6. **Given** any ranking, **When** it runs, **Then** no feeling, tag or note leaves the device for ranking or analytics.

---

### Edge Cases

- **Entry on a show whose dates change later**: the entry keeps its visit date; if the new run no longer contains it,
  the entry keeps the date and the sheet limits only new choices to the new run.
- **Show removed from the catalogue**: the entry stays readable from the record kept at the time (spec 053 FR-014);
  a published 감상 on a removed show is no longer shown anywhere public but stays in the author's diary.
- **Undated legacy visits**: the 21 visits recorded before this release carry a record time, not a visit date; they
  appear under 날짜 미정 until the visitor sets a date.
- **An existing 감상 with no visit**: the diary shows it as an undated entry with the note published; the first save of
  that entry records the visit with the chosen date.
- **Guest signs in with entries on both sides**: for each exhibition, the account's values are kept where they exist and
  the device's values fill the empty ones; when both have a different note, the account's note is kept and the device's
  note is added below it, so no text is lost [default chosen, owner may change].
- **Two signed-in devices edit the same entry**: the most recent save of the entry wins.
- **Sign-out**: the account's entries leave the device's view as other account data does today; guest entries made
  before sign-in were merged and are not duplicated.
- **Account deletion**: deletes the account's diary entries, private notes and 감상 with the account.
- **Note exactly at 280 characters**: saves and publishes without loss; the public 감상 limit is the same 280.
- **Note cleared while published**: an empty note cannot stay public; the public 감상 is removed and the status
  becomes 비공개.
- **Publish while offline**: the entry saves on the device and its status reads "보내는 중" / "SENDING" until the
  request reaches the server, then 검토 중; a failure shows a retry on the entry, never a false 검토 중.
- **A hidden author's 감상 on a show the reader also wrote about**: hiding affects only what this device shows; the
  hidden person can still see the reader's 감상.
- **Exhibition opening today**: counts as opened; 오늘 is the only date offered.
- **Recording a visit on a running show**: the show leaves the 내 취향 candidates as visited shows do today; the save,
  if any, stays.
- **App 1.15 still in use**: older apps keep working against the new server (see research.md, deploy order); their
  free composer is handled as decided in FR-030.

## Requirements *(mandatory)*

### Functional Requirements

**Diary entry**

- **FR-001**: The app MUST let a person keep one diary entry per exhibition, holding: the visit, a visit date, an
  optional feeling (one of three), up to three optional tags from a fixed list, and an optional private note of up to
  280 characters.
- **FR-002**: An entry MUST be creatable on any exhibition whose opening date is on or before today, whether running
  or closed. This reverses spec 065 FR-003, and the exhibition page's visit row MUST follow it.
- **FR-003**: The visit date MUST be chosen from 오늘, 어제 or a date picker, each offered only when the date lies within
  the exhibition's run and is not after today; an entry created from the sheet MUST have a date, while entries added in
  bulk MAY have none until the person sets one.
- **FR-004**: The feeling MUST be one of 좋았어요 / 그저 그랬어요 / 아쉬웠어요 (Loved it / It was fine / Not for me), or
  none.
- **FR-005**: The tags MUST come from this fixed list, at most three per entry: 작품이 좋았어요 / The work · 공간이
  좋았어요 / The space · 생각할 거리가 많았어요 / Food for thought · 조용히 머물기 좋았어요 / Calm to linger · 즐거웠어요 /
  Playful · 마음에 남았어요 / Moving · 압도적이었어요 / Overwhelming · 마음이 무거웠어요 / Heavy · 다시 보고 싶어요 / Would
  see again. Tags are private and never moderated.
- **FR-006**: The person MUST be able to edit every field of an entry and to delete the entry; deleting MUST also delete
  its 감상, and the confirmation MUST say so.
- **FR-007**: A guest's entries MUST be stored on the device; on sign-in they MUST merge into the account with the rule
  in Edge Cases (account values kept, device values fill empty fields, no note text lost), without duplicates.
- **FR-008**: A signed-in person's entries, including the private note, MUST sync to the account and appear on any device
  signed in to it; the latest save of an entry wins between devices; saves made offline MUST reach the account when the
  connection returns.
- **FR-009**: Private notes, feelings and tags MUST NOT be readable by anyone except the person who wrote them (not other
  accounts, not gallery owners); only a published note's text becomes public, and only after approval.

**Where entries start**

- **FR-010**: The exhibition page MUST show, on any opened exhibition, one row: "다녀왔어요 →" when there is no entry,
  or the visit date and feeling with 수정 when there is one; upcoming exhibitions MUST show neither.
- **FR-011**: Below the 감상 list, "감상 남기기" MUST open the same entry sheet with the note focused (recording the visit
  on save if none exists), on opened exhibitions only. The free 감상 composer MUST be removed.
- **FR-012**: MY's 방문 section MUST become 일기 / DIARY: entries grouped by month of the visit date, newest first, and a
  final 날짜 미정 group; each row showing cover, title, venue, visit date, feeling, first line of the note, and the
  status word when the note has one. It MUST list every entry regardless of LIST tab search, city or filters.
- **FR-013**: The diary MUST show, while it is above zero and not closed by the person, a strip counting saved
  exhibitions that have closed and have no entry, leading to a list where the person selects which to add.
- **FR-014**: The existing bulk add ("+ 지난 전시 추가") MUST remain.
- **FR-015**: The Profile 전시 일기 grid and the separate 내 감상 screen MUST be removed; the diary MUST offer a 공개한
  감상 filter; the Profile 감상 count MUST open the diary with that filter. LIST → 내 전시 MUST be unchanged.

**Publishing**

- **FR-016**: An entry with a non-empty note MUST offer 감상으로 공개 to a signed-in person, with the disclosure that the
  note will be shown on the exhibition page with their name after review and that feeling and tags stay private; a guest
  MUST see that signing in allows publishing.
- **FR-017**: A published note MUST have one of these author-visible statuses: 비공개 / PRIVATE, 검토 중 / IN REVIEW,
  공개됨 / PUBLISHED, 공개 안 됨 / NOT PUBLISHED; 공개 안 됨 MUST show the staff reason and offer "수정해서 다시 요청".
- **FR-018**: After approval the author MUST see "감상이 공개됐어요" once, the next time they open the diary or that
  entry. Push notification on approval is out of scope.
- **FR-019**: Editing the note MUST NOT change the public text until the author asks again; asking again MUST withdraw
  the public 감상 and return it to review. No edit of public text, from any app version, MAY become public without a
  staff approval.
- **FR-020**: Turning off 감상으로 공개 MUST remove the public 감상 and keep the private note; a rejection MUST never
  delete the private note.
- **FR-021**: The public 감상 MUST show the note text, the author's display name and avatar as today, and nothing else
  from the entry (no feeling, tags or visit date).
- **FR-022**: The 감상 that are public when this release ships MUST stay public and MUST appear in their authors'
  diaries as entries with the note published; an exhibition with a 감상 but no visit MUST appear as an undated entry,
  and the first save of it records the visit. No backfill of other data is performed.

**Trust and safety**

- **FR-023**: Every 감상 card not written by the reader MUST offer 신고 with the fixed reasons in User Story 4; reporting
  requires sign-in; one report per reader per approved text; a report MUST NOT hide the 감상 from others by itself, and
  MUST hide it from the reporter at once.
- **FR-024**: Every 감상 card not written by the reader MUST offer "이 사용자의 감상 숨기기", which hides all of that
  author's 감상 on this device, with undo and a Settings list to reverse it; it MUST work for guests and signed-in
  readers alike and MUST NOT be sent to the server.
- **FR-025**: The author MUST be able to edit (through the entry sheet) and unpublish their own 감상 from its card.
- **FR-026**: Staff Admin MUST have a 감상 section with a pending list and a reported list; staff MUST be able to approve,
  reject with a reason from the fixed list in User Story 5, keep a reported 감상 (closing its reports) or take it down
  with a reason; each decision MUST record the staff member and time; only staff with the moderation permission MAY see
  or act in it.
- **FR-027**: The mobile admin 감상 queue and the mobile admin delete of others' 감상 MUST be removed.

**Recommendations**

- **FR-028**: On the device, a 좋았어요 visit MUST count like a save; a 그저 그랬어요 or feeling-less visit MUST count as a
  visit does today; a 아쉬웠어요 visit MUST add no positive evidence and MUST apply a penalty to shows sharing its reviewed
  terms and artists that is smaller than one 덜 보기's and follows spec 101 FR-013's limits; tags on a 좋았어요 visit that
  correspond to a reviewed mood term MUST count like a picked term. Reasons MUST never name a feeling or tag.
- **FR-029**: Feelings, tags and notes MUST NOT be used for ranking anywhere but on the device, MUST NOT be aggregated,
  and MUST NOT be sent to analytics; the analytics allowlist MUST stay unchanged.

**Compatibility, copy and design**

- **FR-030**: While app 1.15 remains in use, the server MUST keep 1.15 working (catalogue, visits, 감상 list and its own
  pending label) and MUST NOT let any 1.15 action make unreviewed text public. [NEEDS CLARIFICATION: after 1.16 ships,
  should the server refuse a new 감상 that does not come from a diary entry (1.15's composer then shows its existing
  "couldn't post" error), accept 1.15 posts into review until the minimum version is raised to 1.16 under spec 098, or
  accept them indefinitely?]
- **FR-031**: All new copy MUST be bilingual (KO/EN) inline, with no emoji and no "AI" wording, model name, drafting or
  review-by-machine line on any reader-facing surface.
- **FR-032**: All new screens MUST follow DESIGN.md: monochrome, shapes from the shape tokens, the accent only in its
  three roles (the note counter MUST NOT use it); DESIGN.md MUST be updated with the diary list, entry sheet, 감상 card
  menu and report sheet before implementation of those screens.
- **FR-033**: The public privacy page MUST be updated to say that diary entries (visit date, feeling, tags, private note)
  are stored on the device and synced to the account when signed in, that published notes are public after review, and
  that reports are kept for moderation.
- **FR-034**: Significant failures (entry save, sync, publish, report, moderation) MUST be logged with operation names
  and no note text, identifiers or user content.

### Key Entities

- **Diary entry**: one per person per exhibition: the exhibition and a kept record of its names, venue, dates and cover;
  visit date (or none); feeling (or none); up to three tags; private note (or none); when it was last saved. Owned by one
  person; private.
- **Feeling**: one of 좋았어요 / 그저 그랬어요 / 아쉬웠어요; private; input to on-device ranking only.
- **Stayed-with-you tag**: one of nine fixed tags; private; some correspond to reviewed mood terms.
- **Public note (감상)**: the published text of one entry's note: text, author, exhibition, moderation status (in
  review / approved / not approved with reason), when it was asked and decided, and whether the author has seen an
  approval.
- **Moderation decision**: approve, reject with reason, keep after reports, take down with reason; who and when.
- **Report**: one reader's report of one approved text of a 감상, with a fixed reason; open until staff keep or take
  down the 감상.
- **Hidden author**: an author whose 감상 this device does not show; lives on the device only.

## Success Criteria *(mandatory)*

Mobile analytics is release-disabled, so every criterion below is measured from server counts, automated tests or the
Admin queue.

### Measurable Outcomes

- **SC-001**: Eight weeks after 1.16 is live on both stores, at least 20 accounts have one or more synced diary entries
  (5 accounts have synced visits on 2026-11-11).
- **SC-002**: Eight weeks after release, at least half of the synced entries created on 1.16 have a confirmed visit date
  together with a feeling, tags or a note (measured on the server; the feeling part depends on FR-008 including the
  feeling, see Assumptions).
- **SC-003**: The number of published notes waiting for a first decision stays under 10 at all times, and no note waits
  longer than 48 hours, read from the Admin pending list.
- **SC-004**: Every report receives a staff decision within [NEEDS CLARIFICATION: what response time does the owner
  commit staff to for reports (App Review expects a timely response; 24 hours is the common bar), and should a 감상
  with reports be hidden from everyone while it waits, or only from its reporters as specified?].
- **SC-005**: Zero public 감상 texts that were not approved by staff, verified by database tests that edit approved text
  as a non-staff author from both the 1.15 and the 1.16 paths.
- **SC-006**: After the transition rule in FR-030 takes effect, every 감상 created has a matching diary entry for the same
  person and exhibition, verified by a server count of 감상 without a visit (expected zero, excluding the four that
  predate this release).
- **SC-007**: A visit with a date can be recorded from the exhibition page in at most three taps (row, date, save).
- **SC-008**: The diary lists 100% of a person's entries whatever the LIST tab's search and filters, verified by an
  automated test.
- **SC-009**: The shared made-up profile tests prove each FR-028 rule (좋았어요 equals a save, 그저 그랬어요 equals
  today's visit, 아쉬웠어요 lowers by less than one 덜 보기, never below zero) on every pull request.
- **SC-010**: The analytics allowlist is unchanged and no network request carries a feeling, tag or note except the
  person's own account sync, verified by test.
- **SC-011**: The four 감상 public on 2026-11-11 are still public after the release and each appears in its author's
  diary.

## Assumptions

- **Staff moderation of 감상 moves to a section in staff Admin (with reports), and the mobile admin queue is retired.**
  Default chosen, owner may change.
- **A rejected 감상 shows its status and a reason from a fixed list to its author instead of disappearing, and the
  private note is always kept.** Default chosen, owner may change.
- The rejection reasons (전시와 관련 없는 내용, 광고·홍보, 부적절한 표현, 개인정보 포함, 기타) and the report reasons
  (부적절한 내용, 광고·홍보, 전시와 관련 없음, 개인정보 노출, 기타) are defaults chosen; the owner may change them.
- Reporting requires sign-in, as for public routes; hiding an author does not. Default chosen, owner may change.
- When a guest's note and the account's note differ on sign-in, both are kept, the account's first. Default chosen,
  owner may change.
- Entries added in bulk or from the closed-saved-shows strip have no visit date until the person sets one. Default
  chosen, owner may change.
- The closed-saved-shows strip can be closed and returns only when its count grows. Default chosen, owner may change.
- One entry per person per exhibition; repeat visits are out of scope.
- The tag list is fixed for 1.16; which tags correspond to reviewed mood terms is set in the plan from the existing
  vocabulary (research.md proposes a mapping); a tag without a corresponding term carries no ranking weight.
- Publishing requires sign-in; a guest's diary is otherwise complete.
- Whether the feeling and tags sync with the entry to the account is open:
  [NEEDS CLARIFICATION: owner decision 3 syncs private notes, and decision 4 says the feeling is used only on the
  device; should the feeling and tags also sync to the account as private fields (they survive a reinstall or a second
  phone, and SC-002 can count them), or stay on the device only like taste picks and 덜 보기 (spec 101 FR-003)?]
- Staff with the permission that moderates public routes today moderate 감상; the plan aligns the database check with
  it.
- 1.15 keeps working against the new server throughout; the deploy order is in research.md.

## Out of Scope

- A monthly calendar view of the diary.
- A share card or poster after a number of entries.
- Photos on entries.
- Repeat visits to the same exhibition.
- Public counts of tags or feelings (too few accounts; counts would identify individuals).
- Push notification when a 감상 is approved.
- A server-side block list (hiding authors stays on the device).
- Public profiles and following people.
- A year-in-review.
- Any web create or change flow for entries or 감상 (constitution Principle VII); the public web does not show 감상
  today and this release does not add them there.

## Related specifications

- Spec 053 (personal exhibition map): US3, FR-012 and FR-020 are fulfilled by this release (FR-020's "separate future
  opt-in" is 감상으로 공개); FR-013 ("visit state independent of public thoughts") is amended: a public 감상 now requires
  a diary entry.
- Spec 065 (My Gallr activation entry points): FR-003 is reversed by owner decision 2.
- Spec 098 (required app update): the means to retire 1.15 if FR-030 calls for it.
- Spec 101 (taste listening): FR-013's penalty limits apply to 아쉬웠어요; a 좋았어요 visit is a new positive anchor.
