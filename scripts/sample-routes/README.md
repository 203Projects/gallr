# Sample routes (local only)

Creates a handful of realistic personal routes in a **local** Supabase stack so the route features can be
tested in the app against real exhibitions. Routes are created through `save_personal_route` and
`publish_personal_route` as a sample author, the same functions the app calls. The runner refuses any
target other than a running local `supabase/postgres` container; it never touches staging or production.

| Route | How stops are chosen |
|---|---|
| 한남동 워크 | Open shows in the Hannam area, one per venue, nearest walking order, up to 6 |
| 컨템포러리 워크 | Hand-curated contemporary shows (Samcheong → Gwanghwamun → Hannam), listed order |
| 삼청동 갤러리 산책 | Open shows in Samcheong, nearest order, up to 6 |
| 서촌·시청 미술관 데이 | Museums and spaces between Seochon and City Hall, up to 5 |
| 이번 주 마감 전시 | Shows closing within 7 days, up to 5 |
| 연희동 동네 산책 | Yeonhui/Hongje spaces, up to 3, left **private** (unpublished) |

Area routes are re-chosen from whatever is open on the run date, so they keep working as shows close. The
curated route skips closed shows; any route with fewer than 2 open stops is reported as skipped. Route ids
are derived from each route's key, so a rerun updates the same routes. Edit `routes.json` to add routes.

## Run

1. Start a local stack with auth and REST (see the root `CLAUDE.md` and the local verification notes), e.g.
   from a scratch Supabase workdir: `supabase start -x realtime,storage-api,imgproxy,mailpit,postgres-meta,studio,edge-runtime,logflare,vector,supavisor`.
2. Export a catalogue snapshot (public exhibition rows) to a file outside the repository with a read-only
   query, for example `select * from public.exhibition_catalog_v2 where closing_date >= (now() at time zone 'Asia/Seoul')::date;`.
   Never commit the snapshot.
3. Create the routes:

   ```bash
   node scripts/sample-routes/create-local-sample-routes.mjs --container supabase_db_<project_id> --catalogue /path/to/snapshot.json
   ```

   Omit `--catalogue` to reuse the catalogue already in the local database; `--today YYYY-MM-DD` plans for
   another Asia/Seoul date. Gallery, editor and event links are cleared from snapshot rows.

   Add `--list` to put every published sample in 추천 동선: the sample author is given an active editor
   membership (a local `public.editors` row named `sample-routes-editor`), so `request_route_listing` approves
   each route at once, as it does for real editors. Routes appear in the Map route sheet once all their stops
   share an open day.

## See them in the app

Build a debug APK against the local stack and the canonical catalogue, install it, and sign in with the
sample author from `routes.json` (`sample-routes@gallr.test`, password `author.localPassword`; a local-only
`.test` account). The routes appear under MY → 동선. To copy or report a listed sample, sign in as the
sample reader instead (`sample-reader@gallr.test`, `reader.localPassword`): authors cannot copy or report their own
routes.

```bash
GALLR_EXHIBITION_CATALOG_SOURCE=canonical-v2 GALLR_SUPABASE_URL=http://10.0.2.2:<api_port> \
  GALLR_SUPABASE_PUBLISHABLE_KEY=<local publishable key> ./gradlew androidApp:assembleDebug
```

Debug builds allow cleartext HTTP only to `10.0.2.2`, `localhost` and `127.0.0.1`
(`androidApp/src/debug/res/xml/debug_network_security_config.xml`); release builds are unaffected.

## Review in a local Admin

The runner also creates `sample-staff@gallr.test` (`staff.localPassword`) as an active Admin member. Run Admin
against the local stack and sign in with that account to review listing requests and reports under 동선:

```bash
cd admin
VITE_SUPABASE_URL=http://127.0.0.1:<api_port> VITE_SUPABASE_PUBLISHABLE_KEY=<local publishable key> npm run dev
```

## Test

```bash
node --test scripts/sample-routes/sample-routes.test.mjs
```
