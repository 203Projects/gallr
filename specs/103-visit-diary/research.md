# Research: Visit diary with 감상 joined in

**Spec**: [spec.md](spec.md) · **Verified against**: `origin/develop` at `66ee59d3` (2026-11-11)

This file holds the technical facts behind the specification so the plan can use them. Every reference was read on
`origin/develop`; production counts were read with an aggregate, read-only query on 2026-11-11.

## 1. 감상 (thoughts) today

### Schema and moderation

| Fact | Where |
|------|-------|
| `public.thoughts`: one row per `(user_id, exhibition_id)`, `content` ≤ 280 characters, `is_approved` boolean | `supabase/migrations/006_create_thoughts.sql:3-12` |
| New thoughts default to unapproved | `supabase/migrations/009_admin_thought_moderation.sql:4` |
| Moderation trigger `content_private.enforce_thought_moderation()`: a non-admin INSERT forces `is_approved := false` | `supabase/migrations/20260721043214_cms_foundation.sql:326-356` (insert branch 346-347) |
| **Defect**: a non-admin UPDATE keeps `user_id`, `exhibition_id` and `is_approved` from the old row, so editing the `content` of an approved thought publishes the new text unreviewed | same file, `348-351` |
| Trigger attached `before insert or update` | same file, `361-365` |
| Current RLS: anon reads approved only; authenticated reads approved, own, or any as staff admin; owner or staff admin may update and delete | same file, `367-416` |
| Grants: `select` to anon/authenticated; `insert, update, delete` to authenticated | same file, `1138-1139` |
| "Admin" in the database means an active `content.staff_members` row with role `admin` (trigger, `337-343`) or `content_private.has_staff_role('admin')` (policies) | same file, `337-343`, `382`, `400`, `415` |

No later migration redefines the trigger or the policies (`grep -l thought supabase/migrations` returns only 006, 009,
011 and the CMS foundation; 011 only drops duplicate policy names).

### Client code

| Fact | Where |
|------|-------|
| `ThoughtRepository.updateThought(thoughtId, content)` exists and updates only `content` | `shared/src/commonMain/kotlin/com/gallr/shared/repository/ThoughtRepository.kt:18-21`, `ThoughtRepositoryImpl.kt:79-88` |
| `updateThought` has **no caller** in app code | `git grep updateThought -- '*.kt'` finds only the interface and the implementation |
| Reject **hard-deletes** the row; the author is never told | `ThoughtRepositoryImpl.kt:155-158` |
| Approve sets `is_approved = true` | `ThoughtRepositoryImpl.kt:147-152` |
| Mobile moderation: `PendingThoughtsScreen` / `PendingThoughtsViewModel`, opened from Profile only when `profile.isAdmin` (`profiles.is_admin`) | `composeApp/.../ui/profile/PendingThoughtsScreen.kt:49`, `ui/profile/ProfileScreen.kt:132-150, 352-370`, `viewmodel/ProfileViewModel.kt:85-86` |
| The mobile gate (`profiles.is_admin`) and the database permission (staff role `admin`) differ: a moderator needs both | compare `ProfileDto.kt:13` with the CMS foundation lines above |
| Admin may delete anyone's thought from the exhibition page | `composeApp/.../ui/detail/ThoughtsSection.kt:174-185` |
| Free composer offered on every exhibition, with no status check and no public/review disclosure | `ui/detail/ExhibitionDetailScreen.kt:492-515` renders `ThoughtsSection`; `ThoughtsSection.kt:191-238`; `ThoughtComposer.kt:34-156` |
| Composer counter turns the accent (`GallrAccent.activeIndicator`) within 20 characters of the limit, against DESIGN.md's accent rules | `ThoughtComposer.kt:91-100` |
| The "감상 남기기" button carries a writing-hand emoji (U+270D U+FE0F) | `ThoughtsSection.kt:232-233` (not in `ThoughtComposer.kt`, see Corrections) |
| The author sees their own pending thought on the exhibition page labelled 검토 중, and the line "당신의 감상이 곧 전시됩니다" | `ThoughtsSection.kt:142-170, 200-211` |
| `MyThoughtsScreen` (내 감상) shows no pending label | `ui/profile/MyThoughtsScreen.kt:35` onward; no `pending`, `검토` or `isApproved` in the file |
| Profile 전시 일기 grid is built from `tabsViewModel.filteredExhibitions` filtered by the author's thought exhibition ids | `ui/profile/ProfileScreen.kt:158-165, 292-340` |
| `filteredExhibitions` drops closed shows and shows opening more than 14 days ahead (`isVisibleInCatalog`), and applies the LIST tab's city, filters, 내 전시 toggle and search | `viewmodel/FilterWorkflow.kt:70-86, 162-181`; `viewmodel/ExhibitionVisibility.kt:10-12` |
| No report or block exists for thoughts | no thought report table, RPC or UI in `supabase/`, `admin/src` or `composeApp/` |
| Staff Admin has no 감상 section (the only mention is the archive dialog copy) | `admin/src/staff-i18n-messages.ts:165, 891` |
| The public web does not show thoughts | `grep -ril thoughts web/` finds only `web/privacy.html` |
| Account deletion removes `thoughts` | `supabase/functions/delete-account/README.md:23` |

### Pattern to copy: public route reports

| Piece | Where |
|-------|-------|
| `public.route_reports`: one report per account per approved version (`approved_at`), reason from a fixed list, `resolved_at`/`resolution in ('dismissed','upheld')`; reports never hide a route by themselves | `supabase/migrations/20261010150000_public_route_ranking.sql:25-48` |
| `report_route(p_id, p_reason)` → `content_private.report_route_impl`, unique-violation handled | same file, `198-243` |
| Staff RPCs: `list_route_listing_queue`, `decide_route_listing`, `list_reported_routes`, `resolve_route_reports` (role `publisher` via `require_route_staff`) | `supabase/migrations/20261010160000_public_route_review.sql:48-56, 80, 151, 191, 246-260` |
| Admin UI `RouteModerationWorkspace.tsx` and repository | `admin/src/components/RouteModerationWorkspace.tsx`, `admin/src/repositories/SupabaseAdminRouteRepository.ts:41-63` |
| App report sheet (radio rows, black 신고하기) | `composeApp/.../ui/route/publicroutes/RouteReportSheet.kt:36-71` |

Note the role mismatch: route moderation uses staff role `publisher`, thought RLS uses `admin`. The plan should pick one
for 감상 (spec Assumptions: the role that moderates routes).

## 2. Visits today

| Fact | Where |
|------|-------|
| `ExhibitionVisit(clientRecordId, exhibitionId, snapshot, createdAt)`: no visit date, feeling, tags or note | `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionVisit.kt:48-58` |
| Snapshot keeps names, venue, dates, cover, gallery id | same file, `8-45` |
| Guest store: `DataStoreVisitRepository`, JSON with `ignoreUnknownKeys = true`, `encodeDefaults = true` | `shared/.../repository/DataStoreVisitRepository.kt:25-32` |
| Server: `content_private.my_gallr_visits`, primary key `(user_id, exhibition_id)`, `visited_at` = the client's record time | `supabase/migrations/20260814140000_my_gallr_account_archive.sql:10-24, 246-262` |
| Access only through `public.sync_my_gallr_archive(p_mutations)` (returns the archive) | same file, `361-379` |
| Fixed mutation kinds: `add_visit`, `remove_visit`, `follow_gallery`, `unfollow_gallery`, `acknowledge_gallery` | same file, `215-221` |
| `add_visit … on conflict (user_id, exhibition_id) do nothing`: no update path | same file, `239-262` |
| No later migration touches the visit table or the RPC | `grep -l 'sync_my_gallr_archive\|my_gallr_visits' supabase/migrations` returns only this file |
| Per-user outbox and sync: `MyGallrAccountSyncCoordinator`; guest visits and follows merge on sign-in as `AddVisit`/`FollowGallery` mutations; acknowledged guest records are cleared | `shared/.../repository/MyGallrAccountSync.kt:53-63, 120-153, 250-256, 267-315` |
| The REST client used for sync ignores unknown keys, so new response fields do not break 1.15 | `shared/.../data/network/SupabaseProvider.kt:80-95`; client passed in `composeApp/src/iosMain/.../MainViewController.kt:218-223` and `androidApp/src/main/kotlin/com/gallr/app/MainActivity.kt:225` |
| Detail-page prompt rule: `shouldOfferVisitPrompt = closingDate < today && !isVisited` (spec 065 FR-003) | `composeApp/.../viewmodel/MyGallrViewModel.kt:62-66` |
| Bulk-add rule already allows running shows: `isVisitEligible = openingDate <= today` | same file, `68-71`, used at `210, 253, 477` |
| Prompt copy "이 전시를 방문했나요?" / "기록하기"; its save logic sits in an `App.kt` callback (application logic in the composition root, against the architecture rules) | `ui/detail/ExhibitionDetailScreen.kt:314-385`; `App.kt:913-939` |
| MY tab sections: 방문 / VISITS, 팔로잉, 동선 (hidden while `ReleaseFeatures.ROUTES = false`); "+ 지난 전시 추가" | `ui/mygallr/MyGallrScreen.kt:122, 167, 491, 554`; `ui/mygallr/AddPastVisitsScreen.kt` |

## 3. Recommender

| Fact | Where |
|------|-------|
| Visited exhibitions are removed from 내 취향 candidates | `shared/.../recommendation/LocalExhibitionRecommender.kt:113-119` |
| Visits are positive anchors at 0.60–0.75× a save: artist 0.35/0.50, title artist 0.30/0.40, reviewed term 0.25/0.35, detected term 0.06/0.08, text 0.12/0.20 | same file, `265-287, 957-977` |
| Thoughts are not used | no `thought` reference in the file |
| 덜 보기's `dismissalPenalty`: per reviewed term and credited artist, capped at a saved term's and artist's weight, subtracted after positive evidence, floored at zero | same file, `202-215, 387-392` |
| Picked terms (`CHOSEN_TERM_WEIGHT` 0.30, each further one half the step before) and picked covers (`CHOSEN_ANCHOR_SHARE` 0.6) | same file, `357-366, 983-989` |
| Device-only taste store | `shared/.../repository/TastePreferenceRepository.kt` |

### Proposed tag → reviewed mood term mapping (for the plan to confirm)

Existing mood terms: `mood:quiet-meditative` 고요함·명상적, `mood:energetic` 역동적, `mood:playful` 유희적,
`mood:unsettling` 불안함, `mood:intimate` 친밀함, `mood:monumental` 기념비적
(`supabase/migrations/20260831020202_explainable_art_metadata.sql:169-174`); `mood:dark` 어두운, `mood:warm` 따뜻한,
`mood:humorous` 유머, `mood:melancholy` 쓸쓸한 (`supabase/migrations/20261104120000_art_vocabulary_expansion.sql:32-35`).

| Tag | Term |
|-----|------|
| 조용히 머물기 좋았어요 / Calm to linger | `mood:quiet-meditative` |
| 즐거웠어요 / Playful | `mood:playful` |
| 압도적이었어요 / Overwhelming | `mood:monumental` |
| 마음이 무거웠어요 / Heavy | `mood:dark` |
| 마음에 남았어요 / Moving | `mood:intimate` (weak fit; the plan may leave it unmapped) |
| 작품이 좋았어요, 공간이 좋았어요, 생각할 거리가 많았어요, 다시 보고 싶어요 | none (no ranking weight) |

## 4. Analytics and privacy

| Fact | Where |
|------|-------|
| Mobile analytics is release-disabled and the release build refuses to enable it | `androidApp/build.gradle.kts:57, 136-137, 203-204` |
| `VISIT_RECORDED` detail intent already exists (no new field is needed) | `App.kt:931` |
| Privacy page lists "thoughts" and "recorded visits" as stored/synced content and thoughts as never sent to analytics; it needs diary fields and reports added (spec FR-033) | `web/privacy.html:48, 64-72` |

## 5. Production (read-only aggregate, 2026-11-11)

Query run with `supabase db query --linked --project-ref <gallr-korea>`: counts only, no rows.

| Count | Value |
|-------|-------|
| 감상 | 4 (all approved) |
| 감상 authors | 3 |
| 감상 created in the last 30 days | 0 |
| Accounts | 157 |
| Accounts with synced visits | 5 |
| Synced visits | 21 |
| 감상 whose author has a visit on the same exhibition | 0 |

Migration risk and moderation load are near zero; the diary is built on the visit record, and the four 감상 become
undated entries with the note published.

## 6. Sketch for the plan (not binding)

- **Additive migrations only.** Recorded migrations are immutable; run
  `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` first.
- **Visits**: new nullable columns on `content_private.my_gallr_visits` for visit date, feeling, tags, note and
  entry-updated time; a new `update_visit` mutation kind with last-write-wins on entry-updated time; `add_visit` keeps
  `do nothing` for 1.15 but a 1.16 merge fills only empty fields (sign-in merge rule in the spec). Extend
  `my_gallr_archive_json` with the new fields (1.15 ignores them). Feeling and tags are columns here too: the owner
  decided they sync as private fields (spec owner decision 7), readable only by their owner, like the note.
- **Thoughts**: add a moderation status (`pending` / `approved` / `rejected`), a rejection reason, decided-by/at, and an
  approval-seen flag, kept in step with `is_approved` so 1.15 keeps reading correctly. Change the trigger so a non-staff
  content change sets status back to pending and `is_approved` to false (fixes the defect in §1). Rejection keeps the
  row (status `rejected`, `is_approved = false`).
  - 1.15 caveat: a 1.15 author's app shows any own unapproved row as 검토 중 (`ThoughtsSection.kt:142-170`), so a
    rejected 감상 will read 검토 중 on 1.15; acceptable while 1.15 posts are few, and it ends when the owner raises the
    minimum version (spec 098, owner decision 5).
  - 1.15 caveat: a staff member using the 1.15 mobile queue still hard-deletes on reject; staff should use Admin only.
- **Reports**: `public.thought_reports` modelled on `route_reports` (one per account per approved text), a
  `report_thought` RPC, and staff RPCs to list pending, list reported, decide and resolve; Admin section modelled on
  `RouteModerationWorkspace.tsx`. Cascade on account deletion; update `delete-account` README. An open report hides
  the 감상 from its reporter only (filter on the reader's own open reports) and from no one else until staff decide,
  within 48 hours (spec owner decision 6), so the reported list needs the oldest open report time.
- **Transition (spec FR-030, owner decision 5)**: 1.16 keeps accepting a 1.15 감상 without a diary entry, into review
  (the trigger fix above keeps it unpublished). Only after the owner raises the minimum app version to 1.16 or higher
  (spec 098) may a separate, later migration refuse it in the insert path by requiring a `my_gallr_visits` row for the
  same user and exhibition; mind the outbox order (the visit must sync before the 감상 insert).
- **Shared-first split**: diary domain model and a pure `buildDiary` (grouping, undated group, closed-saved-shows count,
  merge rule) and the feeling-aware ranking in `shared/commonMain`; the entry sheet, diary list and moderation status
  ViewModels in `composeApp/commonMain`; move the visit save out of the `App.kt` callback into a ViewModel; hidden
  authors in a device-only DataStore repository; Admin section in `admin/`.
- **Deploy order**: (1) migrations that only add columns, statuses, tables and RPCs, with 1.15 behaviour unchanged;
  (2) Admin 감상 section; (3) app 1.16; (4) only after the owner raises the minimum app version to 1.16 or higher, the
  refusal of 감상 without a diary entry (FR-030 follow-up; not part of the 1.16 release).

## Corrections to the brief

- The emoji is on the "감상 남기기" button in `ThoughtsSection.kt:232-233`, not in `ThoughtComposer.kt`; the accent
  counter is in `ThoughtComposer.kt:91-100` as stated.
- Visits weigh 0.60–0.75× a save (text 0.60, artist and reviewed term about 0.70, title artist and detected term 0.75),
  slightly wider than "roughly 0.6–0.7×".
- Mobile moderation is gated in the UI on `profiles.is_admin`, but the database only lets an active staff `admin`
  approve or read others' pending thoughts; a moderator needs both.
- The exhibition page already shows the author's own pending 감상 labelled 검토 중; only `MyThoughtsScreen` lacks it.

## Plan research (2026-11-11, branch `103-visit-diary-plan` at `6cfae8fb`)

Decisions the plan had to research. Hosted state was read with aggregate, read-only queries
(`supabase db query --linked … < /dev/null`); no row, name or text was read.

### P1. One sync channel for the diary and its publication

- **Decision**: diary fields are columns on `content_private.my_gallr_visits`; entry saves, the sign-in merge,
  entry deletion, publish, unpublish and approval acknowledgement are six new mutation kinds of
  `sync_my_gallr_archive` (`update_visit`, `merge_visit`, `delete_entry`, `publish_note`, `unpublish_note`,
  `acknowledge_note_approval`). `publish_note` copies the note the server already holds into `public.thoughts`.
- **Rationale**: the visit table already has the right key `(user_id, exhibition_id)` (`…account_archive.sql:24`),
  owner-only access through a definer RPC with no client grants (`:86-103`), idempotent receipts (`:77-84, 222-234`),
  a per-user lock (`:199-201`) and an offline outbox in the app (`MyGallrAccountSync.kt:42-61, 267-313`). Mutations
  apply in order, so a publish can never reach the server before the entry it copies (§6's outbox caveat
  disappears), and 보내는 중 is simply a queued mutation. A batch fails as a whole on any exception (`:355-357`), so
  new kinds treat conflicts as no-ops.
- **Alternatives**: a separate `diary_entries` table and RPC (rejected: a second offline queue, a second merge and
  duplicate ownership of the same visit); publishing through the existing table insert (rejected: no ordering with
  the entry sync, and it keeps the client writing moderation-relevant rows directly); a dedicated publish RPC outside
  the sync (rejected: needs its own retry and offline state).

### P2. Moderation status and the edit defect

- **Decision**: `moderation_status` (`pending`/`approved`/`rejected`) plus `rejection_reason`, `requested_at`,
  `decided_*`, `approved_at`, `approval_seen_at`; a check keeps `is_approved = (moderation_status = 'approved')`; the
  trigger is replaced so any author change of `content`, or an author request, returns the row to `pending`; authors
  can never set a decided state; staff decisions are trusted only inside staff impls that set a transaction-local flag
  while holding the publisher role; the 1.15 admin approve keeps working until M3 removes its reach.
- **Rationale**: the current trigger keeps `is_approved` from the old row on an author UPDATE
  (`20260721043214_cms_foundation.sql:348-351`), so editing an approved text publishes it unreviewed; RLS allows that
  UPDATE (`:394-405`). 1.15 reads only `is_approved` (`ThoughtRepositoryImpl.kt:28-29`), so keeping it in step keeps
  1.15 correct. Rejection keeps the row so the author can see the reason (FR-017); today it is a hard delete
  (`ThoughtRepositoryImpl.kt:155-158`).
- **Alternatives**: a separate moderation table (rejected: two rows to keep consistent for one text); trusting the
  staff role alone in the trigger (rejected: a staff member editing their own 감상 would self-approve).

### P3. Reporter-only hiding

- **Decision**: 1.16 reads 감상 through `public.list_exhibition_thoughts`, a definer RPC that leaves out 감상 the caller
  has an open report on and returns the author's name and avatar (replacing the second `profiles` query,
  `ThoughtRepositoryImpl.kt:39-45`). Reports live in `public.thought_reports` with no client grants, like
  `route_reports` (`20261010150000_public_route_ranking.sql:25-55`).
- **Rationale**: no per-reader filter exists anywhere today; reports must stay unreadable to clients, so a filtered
  RLS view would need a report-reading policy; a definer function keeps reports private and the filter on the server.
  1.15 cannot report, so its direct read needs no filter.
- **Alternatives**: client-side filtering by a device list of reported ids (rejected: lost on reinstall, wrong across
  devices); a `security_invoker` view (rejected: needs a client grant on reports).

### P4. Staff role for 감상 moderation

- **Decision**: `publisher`, through `content_private.has_staff_role('publisher')` (`cms_foundation.sql:112-145`;
  `admin` satisfies it, `:134-138`). The app stops reading `profiles.is_admin`; M3 removes the `has_staff_role('admin')`
  staff clauses from the three thoughts policies (`:375-416`), so staff moderate only in Admin and no app version can
  hard-delete another person's 감상. `profiles.is_admin` stays as the server-maintained mirror until the minimum app
  version reaches 1.16, then may be dropped.
- **Rationale**: `docs/account-identity-and-access.md:56` says staff RPCs use the hierarchical role helper, and
  `:58-60` that `profiles.is_admin` is a legacy mirror never to be used for authorization; route review, the closest
  precedent (reversible decisions on user-written public content), uses `publisher`
  (`20261010160000_public_route_review.sql:3-7`) and keeps `admin` for the irreversible revoke. No 감상 decision is
  irreversible (the author can always ask again). Production has 2 active staff, both `admin` (P11), so nobody gains or
  loses access today. The spec's assumption ("the permission that moderates public routes") is met.
- **Alternatives**: `admin` (rejected: diverges from routes and from the helper's intent).

### P5. Sign-in merge and the 280-character limit

- **Decision**: a server-side `merge_visit` fills only empty account fields and joins two differing notes as
  `account + "\n\n" + device`; the stored note may reach 562 characters; the sheet shows the length over 280 and blocks
  publishing until the note is trimmed; typed input still stops at 280. The same rule is a shared `mergeEntry` used by
  the local reducer, tested on one case table with pgTAP 079.
- **Rationale**: the spec's edge case says no text is lost; the 280 limit exists for the public text
  (`006_create_thoughts.sql:7`). Merging on the server is atomic against the account's current row; merging on the
  device could overwrite a newer edit from another device.
- **Alternatives**: truncate (loses text); keep the device note only on the device (a second, unsynced field).

### P6. Order of the reported list

- **Decision**: oldest open report first.
- **Rationale**: owner decision 6 sets a 48-hour limit from each report's sending and US5 scenario 5 asks for the
  oldest open report time per item; deadline-first ordering puts the item nearest its limit on top. US5's opening
  sentence says "newest report first"; listed as an owner question with this recommendation.

### P7. Feeling weights

- **Decision**: 좋았어요 → the visit anchor is scored with the saved weights (source stays `VISITED`, so the reason says
  방문한); 그저 그랬어요 or none → unchanged; 아쉬웠어요 → no anchor, and per shared reviewed term and credited artist a
  penalty count of 0.5 added to the 덜 보기 count inside the existing cap:
  `min(DISMISSAL_STEP × (dismissals + 0.5 × disliked), DISMISSAL_CAP_SHARE × base)`.
- **Rationale**: constants at `LocalExhibitionRecommender.kt:957-996`; one 덜 보기 costs `min(0.25, 0.175) = 0.175` per
  term and `min(0.25, 0.25) = 0.25` per artist; one 아쉬웠어요 costs 0.125 for each, below both (FR-028), and shares
  the cap so a visit and a hide together never exceed what 덜 보기 alone may cost (spec 101 FR-013). The penalty applies
  after positive evidence and the tier is decided before it (`:187-242`), so a card never leaves the personal group.
  `RecommendationContext.visits` already carries `ExhibitionVisit` (`ExhibitionRecommendation.kt:128-149`), so no
  context change is needed.
- **Alternatives**: a full 덜 보기 per 아쉬웠어요 (rejected: the spec requires less); a negative text-similarity anchor
  (rejected: text similarity is a guess; spec 101 keeps penalties to reviewed terms and credited artists).

### P8. Tags to mood terms

- **Decision**: 조용히 머물기 좋았어요 → `mood:quiet-meditative`, 즐거웠어요 → `mood:playful`, 압도적이었어요 →
  `mood:monumental`, 마음이 무거웠어요 → `mood:dark`; 마음에 남았어요 (Moving) unmapped; the other four carry no term.
  Counted only on 좋았어요 visits, like picked terms (`CHOSEN_TERM_WEIGHT` 0.30, each further one half the step
  before), with a reason that names the visit and the term, never the tag.
- **Rationale**: term ids at `20260831020202_explainable_art_metadata.sql:169-174` and
  `20261104120000_art_vocabulary_expansion.sql:32-35`. `mood:intimate` (친밀함) is about closeness and scale; "moving"
  can come from a monumental or a dark show as easily, so mapping it would teach a taste the visitor never stated.
  "Heavy" on a loved visit says the visitor values weight, which `mood:dark` (어두운) names; `mood:melancholy` (쓸쓸한)
  was considered and is narrower.

### P9. Refresh-pending sessions (PRs #402, #405)

- **Decision**: the diary keeps the account active and saves locally while `isSessionRefreshPending`; a queued publish
  reads 보내는 중, never a failure; 감상 lists do not reload and reports wait until the session is back.
- **Rationale**: `App.kt:269-310` keeps `activateAccount` and skips server work while pending; requests go out without
  the session then (`:275-276`), so a 감상 read would run as anonymous and drop the reporter filter, and a flush fails
  without a token and keeps the outbox (`MyGallrAccountSync.kt:306-312`; the token is likely null while pending, not
  verified on a device); `CloudBookmarkRepository` treats pending as offline (`:157-162, 229`).

### P10. Store disclosures

- **Decision**: no new data type on either store; widen the purpose text of App Store "Other User Content"
  (`docs/mobile-analytics-store-disclosures.md:102`) to diary entries and reports; the owner confirms in Play Console
  that "Other user-generated content" is declared (the doc records only the analytics delta, `:60-63`).
- **Rationale**: thoughts and the profile bio are already declared as Other User Content, linked, for app
  functionality (`:102`, `iosApp/iosApp/PrivacyInfo.xcprivacy:44-50`); private notes, feelings, tags and report
  reasons are the same kind of data for the same purpose; visits are covered under Product Interaction (`:105`).
  Analytics keeps its allowlist (`supabase/functions/mobile-analytics/handler.ts:70-80`).

### P11. Hosted state (2026-11-11)

| Read | Staging (`gallr-staging`) | Production (`gallr-korea`) |
|---|---|---|
| Latest recorded migration | `20261104130000` (122 recorded) | `20261104130000` (122 recorded) |
| `20261111051000_security_storage_boundaries` (on `develop`) | not applied | not applied |
| Active staff by role | not read | `admin`: 2 |
| `profiles.is_admin = true` | not read | 2 |
| 감상: total / approved / null `is_approved` / longest | not read | 4 / 4 / 0 / 275 characters |
| 감상 whose exhibition is in `exhibition_catalog_v2` / closed | not read | 4 / 4 |
| Synced visits / off the catalogue | not read | 21 / 0 |

No open pull request and no recent unmerged branch adds a migration or pgTAP suite; `develop` ends at
`20261111051000` and pgTAP `075`; the lineage validator passes (123 files, 7 recovered versions) with Node 22.23.1.
Next free versions: `20261112100000`–`20261112100300`; suites 076–079.

### P12. Agent context script

`update-agent-context.sh` was not run: the plan adds no technology, and CLAUDE.md asks to keep per-feature
tech-stack lists out of that file ("Keep out of this file"). CLAUDE.md's Recent Changes gets its entry when the
feature ships.
