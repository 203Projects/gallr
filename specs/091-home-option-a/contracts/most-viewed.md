# Contract: 지금 가장 많이 본 (revision 2, 2026-10-12)

## Database (`supabase/migrations/20261012120000_most_viewed_exhibitions.sql`)

```sql
public.list_most_viewed_exhibition_ids(p_days integer default 7, p_limit integer default 12)
  returns setof text
```

- Reads `content.mobile_analytics_daily`: rows with `event_name = 'exhibition_opened'` and a
  non-blank `exhibition_id`, over the last `p_days` Seoul days including today.
- Returns only ids present in the published catalogue (`public.exhibition_catalog_v2`), so an
  exhibition that was opened and later unpublished never appears.
- Counts each exhibition per day and caps a day at twenty opens. Orders by the summed capped opens
  descending, then by id. Returns ids only, never counts.
- An exhibition needs at least three counted opens on two or more different days to rank.
- `p_days` clamps to 7..30 and `p_limit` to 1..50; null arguments use the defaults. A window
  shorter than a week is not offered, so one day's activity cannot be read out.
- **Known limit.** The aggregate has no notion of a visitor, so a single client inside the ingest
  quota can still add up to twenty counted opens a day to an exhibition, day after day. The cap and
  the two-day floor bound a burst; they do not make the ranking tamper-proof. Counting distinct
  sources needs a server-side record that links a device digest to an exhibition, which the current
  analytics privacy design does not keep. Decide that before treating this ranking as a signal that
  matters (see TODOS.md).
- `stable`, `security definer`, empty `search_path`; `execute` granted to `anon` and
  `authenticated` only. The aggregate table stays unreadable to both.
- pgTAP `supabase/tests/database/059_most_viewed_exhibitions.test.sql` (11 assertions): ordering and
  ties with the daily cap, the open and two-day floors, unpublished ids, the window and its minimum,
  impressions and blank ids excluded, clamps and null defaults, role privileges, and a read as
  `anon`.

## Shared (`shared/commonMain`)

```kotlin
interface MostViewedExhibitionApi { suspend fun fetchIds(days: Int, limit: Int): List<String> }
class MostViewedExhibitionApiClient(client: HttpClient, supabaseUrl: String) : MostViewedExhibitionApi
interface MostViewedExhibitionRepository { suspend fun mostViewedIds(): Result<List<String>> }
class MostViewedExhibitionRepositoryImpl(api: MostViewedExhibitionApi) : MostViewedExhibitionRepository
```

- The client posts `{"p_days":7,"p_limit":12}`. A body that is not a JSON list (an error object
  before the migration is applied, or not JSON) reads as an empty ranking; rows that are not a
  non-blank string are dropped; repeats collapse.
- `buildHomeFeed(…, popularExhibitionIds)` builds `HomeCollectionKind.MOST_VIEWED` first among the
  collections: the server's order, limited to exhibitions the catalogue shows, at most ten, and only
  when at least three remain.

## App

`HomeViewModel` reads it once per ViewModel; a failure logs `home_most_viewed_load_failed` and
hides the tile. The tile opens the standard collection list.

## Release dependencies

Two things outside this spec: the migration applied to the hosted project, and mobile analytics
collection enabled for release builds (off today). Until both, the tile is hidden.
