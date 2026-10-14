# Quickstart: verifying the public route browse screen (092)

Run everything from the repository root of this worktree. A worktree has no `local.properties`, so prefix
Gradle with `ANDROID_HOME=$HOME/Library/Android/sdk`.

## 1. Database

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs
```

Then replay the migrations and run pgTAP in an isolated local stack (`supabase/AGENTS.md`,
`docs/database-migration-lineage.md`). Run them from a scratch workdir with a copy of `supabase/config.toml`,
so a peer session's local stack is not disturbed. The new suite is
`supabase/tests/database/066_public_route_browse.test.sql`. Suites 054–056 (public routes) must stay green.

Manual check against a local stack seeded with listed routes:

```sql
select public.browse_public_routes('popular', null, 20, 0);
select public.browse_public_routes('newest', '성수동', 20, 0);
select public.list_public_route_districts();
select public.list_public_routes(10);   -- unchanged; its order equals the first ten popular rows
```

## 2. Shared and app logic

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew shared:ktlintCheck shared:allTests
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew composeApp:ktlintCheck composeApp:allTests
```

Focused suites while iterating:
- `shared`: `PublicRouteBrowseTest` (append and de-duplicate, offset, district fallback),
  `PublicRouteApiClientTest` (page and district decoding, malformed rows skipped, bearer only when signed in).
- `composeApp` `commonTest`: `PublicRouteBrowseViewModelTest` (first page, paging, retry, sort and filter
  reset, empty states, scroll anchor), `PublicRoutesPresentationTest` (see-all and empty-heading copy).
- `composeApp` `iosTest`: `PublicRouteBrowseScreenTest` with `runComposeUiTest` from
  `androidx.compose.ui.test.v2`: tapping a row opens the preview, chips announce their selected state.

## 3. Android build gates

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

## 4. Manual check on both platforms

With at least 25 listed routes in the target environment:
1. Home → 지금 주목받는 동선 → 전체 보기 opens 동선 둘러보기, sorted by 인기.
2. Scroll to the end: rows load in pages of 20 and none repeats.
3. Switch to 최신; pick a district chip; tap it again to clear it.
4. Open a route, copy it or go back. On back, the list keeps its sort, filter and scroll position.
5. Profile → 추천 동선 → 전체 보기 reaches the same screen. With no listed routes, the Profile header still
   shows, and the empty screen offers the route composer.
6. Turn on airplane mode: the first-page error and the end-of-list retry each recover after reconnecting.
7. Switch the app language: the rows and chips change language and the filter stays.
8. VoiceOver/TalkBack: each row is read as one description, and the chips announce selected/not selected.

## 5. Analytics phase (only if delivered)

```bash
cd supabase/functions/mobile-analytics && deno test
```

Also run the analytics pgTAP suite (`056_public_routes_viewed_event` plus the new 067). Apply the migration
before deploying the function. The function deploy is a separately authorized release step.
