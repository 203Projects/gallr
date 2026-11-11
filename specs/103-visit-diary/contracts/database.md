# Contract: database (Supabase)

All four migrations are additive, idempotent (`if not exists`, `create or replace`, `drop … if exists` before
re-creating a policy or trigger) and concrete. Every `security definer` function pins `set search_path = ''` and
schema-qualifies every object. Public wrappers are thin `security invoker` SQL over `content_private` impls, as in
`20261010160000_public_route_review.sql:246-271`.

## M1 `20261112100000_thought_moderation_status.sql`

**Columns on `public.thoughts`** (see data-model): `moderation_status`, `rejection_reason`, `requested_at`,
`decided_at`, `decided_by`, `approved_at`, `approval_seen_at`.

**Backfill** (prod: 4 rows, all approved, 0 null `is_approved`, read 2026-11-11):
`is_approved := coalesce(is_approved, false)`; `moderation_status := case when is_approved then 'approved' else
'pending' end`; `approved_at := coalesce(updated_at, created_at)` where approved; `requested_at := created_at`. Then
`alter column is_approved set not null`, `set default false`, and add:

- `thoughts_status_matches_is_approved check (is_approved = (moderation_status = 'approved'))`
- `thoughts_moderation_status_valid check (moderation_status in ('pending','approved','rejected'))`
- `thoughts_rejection_reason_valid check ((moderation_status = 'rejected') = (rejection_reason is not null) and
  (rejection_reason is null or rejection_reason in ('not_about_exhibition','promotional','inappropriate_language',
  'personal_information','other')))`
- index `thoughts_pending_queue_idx (requested_at) where moderation_status = 'pending'`
- index `thoughts_public_by_exhibition_idx (exhibition_id, created_at desc) where moderation_status = 'approved'`

**Trigger** `content_private.enforce_thought_moderation()` replaced (`create or replace`, same trigger name, still
`before insert or update`). It replaces the body at `20260721043214_cms_foundation.sql:326-356`:

```text
moderating := current_setting('gallr.thought_moderation', true) = 'on'
              and content_private.has_staff_role('publisher')
INSERT, not moderating (any caller with a uid, including the 1.16 sync impl and 1.15 table inserts):
  moderation_status := 'pending'; is_approved := false; requested_at := now();
  rejection_reason, decided_at, decided_by, approved_at, approval_seen_at := null
UPDATE, not moderating:
  user_id, exhibition_id := old values
  if new.content is distinct from old.content or (new.moderation_status = 'pending' and old.moderation_status <> 'pending'):
    reset to pending exactly as on INSERT (keep created_at)          -- fixes the live defect
  else:
    every moderation column := old value, except approval_seen_at may go from null to now()
    when old.moderation_status = 'approved'
moderating (only inside the M2 staff impls, which set the flag with set_config(..., true)):
  is_approved := (new.moderation_status = 'approved'); everything else as written by the impl
legacy staff path (1.15 mobile queue, until M3 removes its RLS reach): caller is an active staff 'admin', the row is
  not the caller's, and only is_approved changed:
  false → true on a pending row: moderation_status := 'approved', approved_at := now(), decided_by := caller,
  decided_at := now(); any other change: every moderation column := old value
caller uid null (service role, migrations): unchanged values, then is_approved := (moderation_status = 'approved')
```

The order of the checks is: moderating, legacy staff path, author path. A staff member editing their own 감상 is an
author. Grants on the function stay revoked (`:358-359`). After M3 the legacy path is unreachable (RLS no longer lets
staff update another person's row); M5 removes it from the body.

**pgTAP 076 `thought_moderation_status`** (written first): backfill result; an author INSERT with `is_approved true`
or `moderation_status 'approved'` lands `pending`; an author UPDATE of an approved row's `content` returns it to
`pending` with `is_approved false` and it disappears from the anon read (SC-005 1.15 path); an author UPDATE that
only sets `moderation_status 'approved'` changes nothing; a staff `admin` approving another person's pending row
through the table (1.15 queue) lands `approved` with `decided_by`, and the same staff member editing their own row is
an author; a `publisher` has no legacy path; the check constraint holds after every case; anon and authenticated
grants unchanged.

## M2 `20261112100100_thought_reports_and_review.sql`

**Table `public.thought_reports`** — data-model columns; checks for `reason` and
`(resolved_at is null) = (resolution is null)`, `resolution in ('dismissed','upheld')`; unique index
`thought_reports_one_per_account_text (thought_id, account_id, approved_at)`; index
`thought_reports_open_idx (thought_id, created_at) where resolved_at is null`. RLS enabled with **no policies**;
`revoke all … from public, anon, authenticated`; `grant select … to service_role` (route_reports pattern,
`20261010150000_public_route_ranking.sql:50-55`).

**Reader RPCs**

| Function | Who | Behaviour | Errors |
|---|---|---|---|
| `public.list_exhibition_thoughts(p_exhibition_id text, p_limit int default 20) returns jsonb` (definer impl, stable) | anon, authenticated | approved 감상 of the exhibition, newest first, `p_limit` clamped to 1–50, **excluding any the caller has an open report on**; each item `{id, user_id, content, created_at, updated_at, author_display_name, author_avatar_url, reported_by_me}` where `reported_by_me` = the caller has a resolved report on the current `approved_at` | `22023 thought_list_invalid` for a blank id |
| `public.report_thought(p_id uuid, p_reason text) returns void` | authenticated (non-anonymous JWT) | inserts one open report with the 감상's current `approved_at` | `42501 thought_report_unauthenticated`; `22023 thought_report_invalid_reason`; `PT404 thought_not_found`; `PT409 thought_not_public` (not approved); `42501 thought_report_own`; `PT409 thought_report_exists` (unique violation) |

**Staff RPCs** (each impl calls `content_private.has_staff_role('publisher')`, else `42501 thought_not_staff`; sets
`gallr.thought_moderation` locally; writes `content.audit_log`). None reads `content_private.my_gallr_visits`, so no
feeling, tag, visit date or private note can reach a staff surface (FR-026).

| Function | Returns / does | Errors |
|---|---|---|
| `public.list_thought_review_queue() returns jsonb` | pending 감상, oldest `requested_at` first: `{id, content, exhibition_id, exhibition_name_ko, exhibition_name_en, author_display_name, requested_at, created_at, was_approved_before}`; names from `public.exhibition_catalog_v2` (null when gone) | |
| `public.decide_thought(p_id uuid, p_decision text, p_reason text, p_expected_requested_at timestamptz) returns jsonb` | `approve` → approved, `approved_at = now()`, `approval_seen_at = null`; `reject` → rejected with reason; both set `decided_*`; returns the item | `22023 thought_decision_invalid`, `22023 thought_rejection_reason_required`, `PT404 thought_not_found`, `PT409 thought_review_stale` (requested_at moved: new text), `PT409 thought_review_invalid_transition` (not pending) |
| `public.list_reported_thoughts() returns jsonb` | approved 감상 with open reports, **oldest open report first**: `{id, content, exhibition_id, exhibition_name_ko, exhibition_name_en, author_display_name, approved_at, open_count, first_reported_at, reasons: {reason: count}}` | |
| `public.resolve_thought_reports(p_id uuid, p_resolution text, p_reason text, p_expected_approved_at timestamptz) returns jsonb` | `dismissed`: resolves all open reports, 감상 stays public; `upheld`: rejects with `p_reason` (rejection list) and resolves all open reports as upheld; records `resolved_by` | `22023 thought_report_invalid_resolution`, `22023 thought_rejection_reason_required`, `PT409 thought_reports_stale` (approved_at moved), `PT409 thought_no_open_reports` |

Grants: impls `grant execute … to authenticated` (route pattern), public wrappers revoked from public and anon and
granted to `authenticated` (reader list also to `anon`).

**pgTAP 077 `thought_reports_and_review`**: reader list excludes the caller's open-reported 감상 and only for that
caller (SC-004: reader A reports, reader B still sees it; three reporters, a fourth reader still sees it); dismiss
returns it to the reporter with `reported_by_me`; re-report of the same text refused, of re-approved text accepted;
own and non-public refused; anon cannot report; publisher and admin can list and decide, contributor and plain
authenticated get `thought_not_staff`; staleness guards; take-down hides it from everyone and the author's row shows
`rejected` with the reason; audit rows carry no content; queue payloads have no `feeling`, `tags`, `visit_date` or
`note` keys; reporter deletion cascades their reports; 감상 deletion cascades its reports.

## M3 `20261112100200_retire_mobile_thought_moderation.sql`

Re-creates the three `public.thoughts` policies without their staff clauses
(`20260721043214_cms_foundation.sql:375-416`):

- select, authenticated: `is_approved = true or (select auth.uid()) = user_id`
- update, authenticated: `(select auth.uid()) = user_id` (using and with check)
- delete, authenticated: `(select auth.uid()) = user_id`

Anon read and the insert policy are unchanged. **pgTAP 078**: an active `admin` staff member can no longer read,
update or delete another person's pending or approved 감상 through the table; authors keep all three on their own
rows; the 1.15 author flow (insert, read own pending, delete own) still passes.

## M4 `20261112100300_visit_diary_sync.sql`

**Columns on `content_private.my_gallr_visits`**: `visit_date date null`, `feeling text null`, `tags text[] not null
default '{}'`, `note text null`, `entry_updated_at timestamptz null`; checks: `feeling in ('loved','fine',
'not_for_me')`; `content_private.visit_tags_valid(tags)` (immutable SQL function: ≤ 3, distinct, each in the nine
wire ids); `note is null or char_length(note) between 1 and 562`; `visit_date >= date '2000-01-01'`. Grants unchanged
(no client grant at all, `…account_archive.sql:86-103`).

**`content_private.my_gallr_archive_json(uuid)`** (replaces `:105-164`): each visit gains `visit_date`, `feeling`,
`tags`, `note`, `entry_updated_at`; the archive gains

```json
"public_notes": [
  { "exhibition_id": "…", "thought_id": "…", "content": "…", "status": "pending|approved|rejected",
    "rejection_reason": null, "requested_at": "…", "decided_at": null, "approved_at": null,
    "approval_seen": false }
]
```

for every `public.thoughts` row of the user, with or without a visit. 1.15 decodes `MyGallrAccountArchiveDto`
(`MyGallrAccountApiClient.kt:145-195`) through a client with `ignoreUnknownKeys = true`
(`SupabaseProvider.kt:89-92`), so it ignores both additions.

**`content_private.sync_my_gallr_archive_impl(jsonb)`** (replaces `:166-359`, every existing branch byte-for-byte in
behaviour): allowed kinds gain six. All new kinds raise only for a malformed payload (`22023 visit_invalid`,
`mutation_record_invalid`); semantic conflicts are no-ops, so one stale mutation can never block an outbox.

| Kind | Record | Effect |
|---|---|---|
| `update_visit` | `exhibition_id`, `client_record_id`, `snapshot`, `created_at`, `visit_date`, `feeling`, `tags`, `note`, `entry_updated_at` (required) | insert the row if missing; else replace the four diary fields when the incoming `entry_updated_at` is later than the stored one (or the stored one is null); if the resulting note is null, delete the user's thought for that exhibition |
| `merge_visit` | as `update_visit` | insert if missing; else fill only null/empty fields; when both notes are non-null and differ, `note := account || E'\n\n' || device`; `entry_updated_at := greatest(...)` |
| `delete_entry` | `exhibition_id` | delete the visit row and the user's thought for that exhibition |
| `publish_note` | `exhibition_id` | if the visit row has a note of ≤ 280 characters: upsert the user's thought with `content := note`, asking `moderation_status := 'pending'` unless the row is already approved with identical content (then it stays approved); the trigger applies the reset; otherwise no-op |
| `unpublish_note` | `exhibition_id` | delete the user's thought for that exhibition |
| `acknowledge_note_approval` | `exhibition_id` | set `approval_seen_at = now()` on the user's approved thought if null |

`add_visit` (`do nothing` on conflict) and `remove_visit` (visit row only, 감상 untouched) keep their 1.15 meaning.
Receipts, revision bump, the per-user advisory lock and the 100-mutation cap are unchanged.

**pgTAP 079 `visit_diary_sync`**: each kind; last-write-wins both directions and on ties; the merge case table shared
with `EntryMergeTest` (account empty, device empty, both equal, both differ, tags, 280 + 280); publish copies the note
and lands `pending` (SC-005 1.16 path) and an approved row edited through `publish_note` leaves the anon read at once;
publish with no visit or no note is a no-op that still records a receipt; `delete_entry` removes both; `remove_visit`
leaves the 감상; the archive carries the new keys and `public_notes` (including a 감상 with no visit); anon and
authenticated still cannot select `my_gallr_visits`; a staff publisher cannot read diary fields through any staff RPC;
deleting the auth user cascades visits, thoughts and reports; the existing 034 assertions still pass.

## M5 (follow-up, not in 1.16)

After the minimum app version is ≥ 1.16.0 on both platforms: the trigger's INSERT branch, for a non-moderating caller,
raises `PT409 thought_requires_diary_entry` when no `content_private.my_gallr_visits` row exists for the same user and
exhibition. The 1.16 path is unaffected because `publish_note` runs after the visit row exists in the same function.
Optional cleanup in the same migration: drop `public.profiles.is_admin` and its mirror triggers once no supported
client reads it.

## Account deletion

No function change: `delete-account` deletes the auth user (`backend.ts:194-210`) and data goes by cascade. Verified
cascades: `thoughts.user_id` (`006_create_thoughts.sql:5`), `my_gallr_visits.user_id` (`…account_archive.sql:11`) and
the new `thought_reports.account_id` and `.thought_id`; `decided_by` and `resolved_by` set null. The README
(`supabase/functions/delete-account/README.md:23-25`) is updated to list the My Gallr tables and `thought_reports`.
