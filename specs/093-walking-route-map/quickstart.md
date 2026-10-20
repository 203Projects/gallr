# Quickstart: verifying walking routes and the full-screen map (093)

Run from the worktree root; prefix Gradle with `ANDROID_HOME=$HOME/Library/Android/sdk`.

## 1. Database

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
```

Replay and run pgTAP in an isolated scratch stack (see `supabase/AGENTS.md`); the new suite is
`067_walking_route_cache.test.sql`.

## 2. Edge Function

```bash
cd supabase/functions/walking-route && deno task test && deno task check
```

Local end-to-end against the scratch stack, with a provider key in an ignored env file (never committed):

```bash
supabase functions serve walking-route --env-file supabase/functions/walking-route/.env.local
curl -s -X POST http://127.0.0.1:<api-port>/functions/v1/walking-route \
  -H 'Content-Type: application/json' -H 'Origin: app://gallr' -H 'apikey: <local publishable key>' \
  -d '{"waypoints":[{"exhibition_id":"sd-03","lat":37.5445,"lng":127.056},{"exhibition_id":"sd-01","lat":37.5345,"lng":127.001}]}'
```

Repeat the call: the second answer must come from the cache (no provider call in the function log counts).

## 3. Shared and app

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

Map library is unchanged, but the route map is touched, so also run the uncached iOS link:

```bash
./gradlew composeApp:linkDebugFrameworkIosSimulatorArm64 -Pgallr.checkNativeLinkage=true
```

## 4. Manual, both platforms (seeded local stack, function served)

1. Build a planner route across the Han River: venue-to-venue lines follow streets and a bridge; the first leg
   from the starting point is dashed; the summary has no "예상 거리" note (it counts venue-to-venue legs only).
2. Turn on airplane mode, build another route: dashed legs and the note; turn it off, reopen: solid legs.
3. In the composer, reorder one stop: only the changed legs are requested (function log), map and times update.
4. Open a public route preview twice: the second time the paths appear at once.
5. Tap each of the three map panels: full-screen map opens fitted; pinch, double-tap, pan; north stays up; fit
   route; close and system back return to the same scroll position.
6. Start from the current location: the first leg stays dashed with estimate wording, and no `walking-route`
   request carries a position-only waypoint (function log counts, request capture).
7. TalkBack / VoiceOver: expand control, fit route and close are announced; the map reads its stop count and
   distance.
