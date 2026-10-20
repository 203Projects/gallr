# Data model: Required app update

## Server

### `content.app_version_policy` (new, migration `20261020100000_app_version_policy.sql`)

| Column | Type | Rule |
|--------|------|------|
| `platform` | `text` primary key | `android` or `ios` |
| `minimum_version` | `text` | `^\d+\.\d+\.\d+$`; builds below it are stopped |
| `recommended_version` | `text` | same format; ≥ `minimum_version` (check) |
| `latest_released_version` | `text` null | same format when present; Admin warns when `minimum_version` is newer |
| `updated_at` | `timestamptz` | set by the write RPC |
| `updated_by` | `uuid` null | the staff member, set by the write RPC |

RLS enabled; no grants to `anon` or `authenticated`. Seeded with `android` and `ios` at `1.13.1` / `1.13.1`.

### RPCs

- `public.get_app_version_policy(p_platform text) returns jsonb` — definer rights, `anon, authenticated`:
  `{"minimum_version": "1.13.1", "recommended_version": "1.13.1"}` or `null` for an unknown platform.
- `public.admin_set_app_version_policy(p_platform text, p_minimum text, p_recommended text, p_latest_released text)
  returns jsonb` — definer rights, `authenticated` only, asserts staff through `content_private.admin_assert_staff()`;
  raises on a bad format or `p_recommended < p_minimum`; returns the stored row.

## App (`shared`)

- **`AppVersion(major, minor, patch)`**: comparable; `parse(text)` ignores suffixes, returns null for fewer than three
  numbers.
- **`UpdatePolicy(minimum: AppVersion, recommended: AppVersion)`**.
- **`UpdateRequirement`**: `NONE`, `RECOMMENDED`, `REQUIRED`.
- **`updateRequirement(installed, policy)`**: REQUIRED when installed < minimum; RECOMMENDED when installed <
  recommended; else NONE.
- **`AppUpdateRepository`**: `requirement: StateFlow<UpdateRequirement>`, `nudgeDismissedOn: Flow<LocalDate?>`,
  `suspend fun refresh()`, `suspend fun dismissNudge(today: LocalDate)`.

## Device storage (DataStore, private keys)

| Key | Value | Purpose |
|-----|-------|---------|
| `app_version_policy` | `minimum|recommended` | the last successfully read policy, applied until replaced |
| `app_update_nudge_dismissed` | `yyyy-MM-dd` | the day the visitor tapped 나중에 |

## State transitions

```text
launch:   stored policy → requirement (NONE when none)
refresh:  RPC ok → store policy → requirement; RPC fails → unchanged (log app_version_policy_read_failed)
REQUIRED: root shows RequiredUpdateScreen; back does nothing; cleared only by a refresh with installed ≥ minimum
RECOMMENDED: Home shows UpdateNudge unless dismissed today
```
