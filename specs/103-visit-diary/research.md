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
  `my_gallr_archive_json` with the new fields (1.15 ignores them). Whether feeling and tags are columns here depends on
  the spec's open question on syncing them.
- **Thoughts**: add a moderation status (`pending` / `approved` / `rejected`), a rejection reason, decided-by/at, and an
  approval-seen flag, kept in step with `is_approved` so 1.15 keeps reading correctly. Change the trigger so a non-staff
  content change sets status back to pending and `is_approved` to false (fixes the defect in §1). Rejection keeps the
  row (status `rejected`, `is_approved = false`).
  - 1.15 caveat: a 1.15 author's app shows any own unapproved row as 검토 중 (`ThoughtsSection.kt:142-170`), so a
    rejected 감상 will read 검토 중 on 1.15; acceptable given 4 rows, or raise the minimum version (spec 098).
  - 1.15 caveat: a staff member using the 1.15 mobile queue still hard-deletes on reject; staff should use Admin only.
- **Reports**: `public.thought_reports` modelled on `route_reports` (one per account per approved text), a
  `report_thought` RPC, and staff RPCs to list pending, list reported, decide and resolve; Admin section modelled on
  `RouteModerationWorkspace.tsx`. Cascade on account deletion; update `delete-account` README.
- **Transition (spec FR-030)**: if the owner chooses to refuse 감상 without a diary entry, enforce it in the insert path
  by requiring a `my_gallr_visits` row for the same user and exhibition; mind the outbox order (the visit must sync
  before the 감상 insert).
- **Shared-first split**: diary domain model and a pure `buildDiary` (grouping, undated group, closed-saved-shows count,
  merge rule) and the feeling-aware ranking in `shared/commonMain`; the entry sheet, diary list and moderation status
  ViewModels in `composeApp/commonMain`; move the visit save out of the `App.kt` callback into a ViewModel; hidden
  authors in a device-only DataStore repository; Admin section in `admin/`.
- **Deploy order**: (1) migrations that only add columns, statuses, tables and RPCs, with 1.15 behaviour unchanged;
  (2) Admin 감상 section; (3) app 1.16; (4) after 1.16 adoption, any server rule that would break 1.15 (per FR-030).

## Corrections to the brief

- The emoji is on the "감상 남기기" button in `ThoughtsSection.kt:232-233`, not in `ThoughtComposer.kt`; the accent
  counter is in `ThoughtComposer.kt:91-100` as stated.
- Visits weigh 0.60–0.75× a save (text 0.60, artist and reviewed term about 0.70, title artist and detected term 0.75),
  slightly wider than "roughly 0.6–0.7×".
- Mobile moderation is gated in the UI on `profiles.is_admin`, but the database only lets an active staff `admin`
  approve or read others' pending thoughts; a moderator needs both.
- The exhibition page already shows the author's own pending 감상 labelled 검토 중; only `MyThoughtsScreen` lacks it.
