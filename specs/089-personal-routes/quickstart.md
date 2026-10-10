# Quickstart: verifying routes (personal and public)

Run from the repository root unless noted. In worktrees, prefix Gradle with `ANDROID_HOME=$HOME/Library/Android/sdk` when `local.properties` is absent.

## 1. Database

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
```

Then run the pgTAP suite in an isolated scratch Supabase workdir (see `docs/database-migration-lineage.md`) and the two-session script:

```bash
supabase/tests/personal_routes_concurrency.sh
```

Expect: every case in `contracts/database-functions.md` passes; simultaneous saves both succeed with stops equal to one of them; a reader during a save sees one version.

## 2. Shared logic and app

```bash
./gradlew shared:ktlintCheck shared:allTests
./gradlew composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

Expect: `NeighborhoodRoutePlannerTest` passes unedited; evaluator fixed-clock cases, draft repository cases (restart with pending SAVE, expiry, edit clears pending, append during save, guarded undo) and parity host test pass.

## 3. Web page

```bash
cd web && npm test
```

Expect: route page handler cases, verdict-line cases, accessibility check on the route fixture, parity test and Playwright at 375 px and 1280 px pass; existing tests pass unedited.

On a Vercel preview deploy, fetch a published route as a link-preview scraper:

```bash
curl -sI -A "facebookexternalhit/1.1" "https://<preview-host>/route/<route-id>?s=share"
```

Expect `content-type: text/html` and the per-route `og:title` in the body.

## 4. Admin

```bash
cd admin && npm run typecheck && npm test && npm run build
```

Expect: look-up, preview, revoke, not-found, already-revoked and non-staff cases pass.

## 5. Manual walkthroughs (Android emulator and iOS simulator)

1. Signed out, add two exhibitions from detail pages, open the composer, name the route, drag stop 2 above stop 1.
2. Add stops to ten; confirm the picker shows "현재 10/10" and refuses an eleventh.
3. On a 640 dp-tall emulator profile and the smallest iPhone simulator, drag stop 10 to position 1 (auto-scroll) and repeat with the row menu under a screen reader.
4. Tap 공유, sign in with Google; confirm "공유 준비됐어요"; tap 공유 and open the link on another device.
5. Edit the route, confirm "변경사항 저장" and that the page updates within about two minutes.
6. Deny location twice (Android) or once (iOS); confirm the "현재 위치에서 출발" button disappears.
7. Delete the published route from "내 동선" and confirm the warning and the 404 page.
8. As staff in Admin, look up and revoke a test route; confirm the author sees the removal notice.

## 6. Measurement

Recipient loop (from launch day, no app release needed): read the aggregate view as `service_role`.

```sql
select published_week, routes_published, routes_opened_within_7_days,
       round(100.0 * routes_opened_within_7_days / nullif(routes_published, 0), 1) as opened_share_pct,
       shared_opens, direction_starts
from public.personal_route_recipient_loop
order by published_week desc;
```

- `routes_opened_within_7_days` counts routes with at least one page open from a shared link (`?s=share`) within seven days of first publication. Author previews (no `s=share`) and known link-preview fetchers are excluded by the page and its events endpoint.
- Label page counts as "unauthenticated traffic": they come from anonymous page scripts, are not deduplicated by person, and include any visitor who has the link.
- The view's pgTAP case lives in `supabase/tests/database/050_personal_routes.test.sql`.

Author loop: `route_draft_started`, `route_published` (first publish, with the stop count) and `route_shared` (share sheet opened, with the stop count) flow through the mobile analytics pipeline into `content.mobile_analytics_daily`. They read as unavailable, not zero, until the pipeline's separate production activation.

## 7. Release order

Migrations (`20261010014900_personal_routes`, `20261010120000_route_author_analytics`, `20261010130000_published_route_reads`, then the public routes migrations `20261010140000_public_route_listing`, `20261010150000_public_route_ranking`, `20261010160000_public_route_review` and `20261010170000_public_routes_viewed_event`), then the `mobile-analytics` function deploy, then the Admin deploy, then the web deploy (root `api/route.js` and the root `vercel.json` rewrites), then the mobile release. Author-loop analytics and `public_routes_viewed` appear only after the separate 072 activation. Before 추천 동선 is promoted, editors list at least ten routes in the app; until then the section stays hidden whenever no route is eligible.

## 8. Public routes (User Stories 7–11)

Local data: `scripts/sample-routes/` creates six realistic routes from a catalogue snapshot in a local stack and signs in as a sample author (see its README). Approve listings for those routes through the staff functions in the local database, or in a local Admin, to fill 추천 동선.

Automated:

```bash
supabase test db supabase/tests/database --local     # public-routes suite: contracts/public-routes-functions.md
./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests
cd admin && npm run typecheck && npm test && npm run build
cd supabase/functions/mobile-analytics && npx -y deno@2.9.4 task test
```

Manual (Android emulator and iOS simulator, KO and EN, light and dark):

1. As a non-editor author, list a published route from 내 동선 ⋯; confirm the consent dialog, the snackbar and "목록 검토 중"; list an unpublished route with "공개하고 목록에 올리기" and confirm the link-stays-open sentence.
2. In Admin, approve one request and decline another with a reason and note; confirm the author rows read "목록 승인됨" and "목록 반려됨" with the reason; edit the approved route's stops and confirm the warning and the return to review.
3. Edit a route after Admin opened it, then approve: confirm "내용이 바뀌었어요 · 다시 확인해 주세요".
4. Open the Map route sheet: confirm three rows in rank order, "동선 더 보기" to ten, the row line, the loading placeholders, the hidden empty state, and the error with retry (airplane mode).
5. Open a route whose stops all open together only next week: confirm "{M}월 {D}일 기준" and statuses judged for that day.
6. Signed out, tap "내 동선으로 복사", sign in, confirm the copy resumes; repeat with an unsaved draft (replace confirm), offline (draft untouched), and after staff unlist (no longer listed).
7. Report a route, confirm "신고함"; in Admin dismiss and uphold reports and confirm list membership; restore an unlisted route.
8. Open your own listed route from 추천 동선: confirm "내 동선에서 열기" and no report action.
9. Accessibility: TalkBack and VoiceOver read each row as one sentence, headings are reachable, copy busy and status changes are announced; at 200% font scale rows wrap and line 3 is never cut.
10. Web: `gallrmap.com/route/{id}` for a listed route shows no copy count and no list (FR-062).

Measurement (after the 072 activation): from launch to week four, exposure = `public_routes_viewed` per route-sheet open (`surface_viewed` with surface `map`, entry point `route`), interest = counted copies (`route_saves` on the current approved versions) per `public_routes_viewed`; thresholds 50% and 3% (SC-012); apply the stop rule at week four (SC-013).

Weekly report as `service_role` (`psql -v launch=YYYY-MM-DD`; weeks count from the 추천 동선 release day, Seoul dates):

```sql
with weeks as (
  select week, (:'launch'::date + 7 * week) as starts
  from generate_series(0, 3) as week
),
analytics as (
  select (occurred_on - :'launch'::date) / 7 as week,
         sum(event_count) filter (
           where event_name = 'surface_viewed' and surface = 'map' and entry_point = 'route'
         ) as sheet_opens,
         sum(event_count) filter (where event_name = 'public_routes_viewed') as section_views
  from content.mobile_analytics_daily
  where occurred_on between :'launch'::date and :'launch'::date + 27
  group by 1
),
copies as (
  select ((save.created_at at time zone 'Asia/Seoul')::date - :'launch'::date) / 7 as week,
         count(*) as counted_copies
  from public.route_saves as save
  join public.personal_routes as route
    on route.id = save.route_id and route.listing_last_approved_at = save.approved_at
  where (save.created_at at time zone 'Asia/Seoul')::date between :'launch'::date and :'launch'::date + 27
  group by 1
)
select weeks.week + 1 as week, weeks.starts,
       coalesce(analytics.sheet_opens, 0) as sheet_opens,
       coalesce(analytics.section_views, 0) as section_views,
       coalesce(copies.counted_copies, 0) as counted_copies,
       round(100.0 * analytics.section_views / nullif(analytics.sheet_opens, 0), 1) as exposure_pct,
       round(100.0 * copies.counted_copies / nullif(analytics.section_views, 0), 1) as interest_pct,
       coalesce(analytics.section_views >= 0.5 * analytics.sheet_opens, false) as exposure_met,
       coalesce(copies.counted_copies >= 0.03 * analytics.section_views, false) as interest_met
from weeks
left join analytics using (week)
left join copies using (week)
order by weeks.week;
```

- Exposure below 50% means adding a 추천 strip (R15). At week four, exposure met with interest not met triggers the stop rule (SC-013).
- `public_routes_viewed` counts each showing of the section once with the rows on screen; expanding in place is not a new view. Readers who returned to the sheet from a preview are counted again, as are their sheet opens.
- Copies are counted once per account per approved version, never for the author, and only those on each route's current approved version are read here. Rows read as unavailable, not zero, until the 072 activation, because the analytics side is empty until then.

