# Contract: curator note database functions

All `public.*` functions are `security definer` wrappers over `content_private.*_impl` with
`set search_path = ''`, granted to `authenticated` only; the staff check runs inside. Service-role
functions are granted to `service_role` only. Errors are raised with stable messages; Admin maps
them to typed errors.

| Error (SQLSTATE / message) | When |
|---|---|
| `42501` `curator_note_not_staff` | Caller lacks the `publisher` staff role |
| `PT409` `curator_note_stale` | `p_expected_revision` differs from the row's `updated_at` |
| `PT409` `curator_note_invalid_transition` | Action not allowed from the row's status |
| `PT409` `curator_note_unsupported` | Approval while `unsupported_refs` is not empty |
| `22023` `curator_note_invalid_reason` / `_invalid_note` / `_invalid_fields` / `_invalid_ref` | Argument validation |
| `P0002` `curator_note_not_found` | Unknown version or exhibition |

## `content_private.list_curator_note_candidates(p_limit integer) returns jsonb`

Array of `{ "exhibition_id", "is_featured", "packet": <packet>, "packet_hash", "source_version_id" }`,
at most `p_limit` (1–50), ordered `is_featured desc, opening_date desc, exhibition_id`. A row
qualifies when, in `exhibition_catalog_v2`: `closing_date >= (now() at time zone 'Asia/Seoul')::date`,
`opening_date` and `closing_date` not null, `char_length(btrim(description_ko)) >= 150`,
`btrim(venue_name_ko) <> ''`, `cover_image_url` not null; and in `curator_notes`: no row, or
`auto_draft_blocked = false` with no `draft` version and (`approved_version_id is null` or the
approved version's `source_hash <> packet_hash`).

The packet is the canonical JSON (sorted keys, no whitespace) of:

```json
{
  "id": "…", "name_ko": "…", "name_en": "…",
  "description_ko": "…", "description_en": "…",
  "venue_name_ko": "…", "venue_name_en": "…",
  "region_ko": "…", "region_en": "…", "city_ko": "…", "city_en": "…",
  "opening_date": "2026-10-01", "closing_date": "2026-11-30",
  "hours": "…", "opening_time": "…", "reception_date": "…",
  "artists": [{ "name_ko": "…", "name_en": "…", "role": "…" }],
  "art_terms": [{ "id": "medium:painting", "category": "medium", "name_ko": "…", "name_en": "…" }]
}
```

Null or blank fields are omitted; `source_fields` is the list of keys present. `packet_hash` is
`encode(sha256(packet::text::bytea), 'hex')`; the Edge Function recomputes it and refuses to store
a draft when the two differ (a changed row between select and store).

## `content_private.store_curator_note_draft(p_payload jsonb) returns jsonb`

Payload:

```json
{
  "exhibition_id": "…", "status": "draft" | "unusable", "rejection_code": null | "…",
  "hook_ko": "…", "hook_en": "…",
  "insights_ko": [{ "text": "…", "grounding": ["description_ko", "artists"] }],
  "insights_en": [...], "tip_ko": "…", "tip_en": "…",
  "producer": "claude", "model": "…", "prompt_version": "…",
  "source_fields": ["…"], "source_hash": "…", "source_version_id": "…" | null,
  "request_kind": "scheduled" | "single" | "regenerate", "requested_by": null | "<uuid>",
  "generated_at": "…"
}
```

Behaviour: inserts `curator_notes` if absent; assigns `version_number = max + 1`; for `draft`,
raises `curator_note_invalid_transition` if a `draft` already exists; sets
`curator_notes.last_source_hash`; writes `curator_note_drafted`. Returns the stored version as
`get_curator_note` renders it. Grounding keys must be in `source_fields` (`22023`
`curator_note_invalid_fields` otherwise), which is the database's own copy of rule R3.

## `public.list_curator_note_queue() returns jsonb`

`[{ "version_id", "exhibition_id", "name_ko", "name_en", "is_featured", "closing_date",
"version_number", "prompt_version", "generated_at", "request_kind", "unsupported_count",
"edited", "revision" }]` for `status = 'draft'`, ordered `is_featured desc, generated_at asc`.

## `public.get_curator_note(p_exhibition_id text) returns jsonb`

```json
{
  "exhibition_id": "…", "auto_draft_blocked": false, "approved_version_id": "…" | null,
  "packet": <packet or null when the exhibition is not in the catalogue>, "packet_hash": "…",
  "versions": [ <version>, ... ]   // newest first
}
```

`<version>` carries every column of `curator_note_versions` except `requested_by`, with
`revision = updated_at`.

## `public.save_curator_note_edit(p_version_id uuid, p_fields jsonb, p_expected_revision timestamptz)`

`p_fields` may contain any of `hook_ko`, `hook_en`, `insights_ko`, `insights_en`, `tip_ko`,
`tip_en` (same shapes and limits as the table); other keys raise `curator_note_invalid_fields`.
Requires status `draft`. Sets `edited = true`, bumps `updated_at`, writes `curator_note_edited`
(changed field names only). Returns the version.

## `public.mark_curator_note_sentence(p_version_id uuid, p_ref text, p_unsupported boolean, p_expected_revision timestamptz)`

`p_ref` matches `^(ko|en):(hook|insight|tip):[0-9]+$`. Adds to or removes from
`unsupported_refs`; writes `curator_note_sentence_marked`. Returns the version.

## `public.approve_curator_note(p_version_id uuid, p_expected_revision timestamptz)`

Requires `draft`, empty `unsupported_refs`, non-blank `hook_ko`, 2–4 `insights_ko`, non-blank
`tip_ko`. Snapshots the reviewer name (editor profile name through `content.editor_memberships`
joined to `public.editors`, else `profiles.display_name` for both languages); sets `approved`,
`approved_at = decided_at = clock_timestamp()`; marks the previous approved version `superseded`;
updates `curator_notes.approved_version_id`; writes `curator_note_approved`. Returns the version.

## `public.decline_curator_note(p_version_id uuid, p_reason text, p_note text, p_expected_revision timestamptz)`

Reason in `not_insightful`, `wrong_facts`, `tone`, `exhibition_not_suitable`, `other`; note
`<= 500`. Requires `draft`. Sets `declined`, `auto_draft_blocked = true`; writes
`curator_note_declined`. Returns the version.

## `public.request_curator_note_draft(p_exhibition_id text) returns jsonb`

Clears `auto_draft_blocked`; marks an existing `draft` as `replaced`; writes
`curator_note_regenerated`. Returns `{ "exhibition_id", "packet", "packet_hash",
"source_version_id" }` for Admin to pass to the Edge Function in `single` mode. Raises
`curator_note_not_found` when the exhibition is not in the catalogue.

## `public.record_curator_note_export(p_version_id uuid, p_language text, p_slide_count integer)`

Requires status `approved`; language `ko` or `en`; slide count 4–6. Writes `curator_note_exported`.

## `content_private.schedule_curator_note_drafts() returns void`

Reads `gallr_curator_notes_function_url` and `gallr_curator_notes_schedule_token` from the vault,
validates them the way the legacy mirror scheduler does, and posts
`{ "mode": "scheduled", "limit": 20 }` with the bearer token through `net.http_post`. Scheduled by
`cron.schedule('curator_notes_daily', '0 18 * * *', …)` in the migration (18:00 UTC = 03:00 KST).

## pgTAP coverage (suite `057_curator_notes.test.sql`)

Candidates: each qualification rule in and out; featured-first order; the blocked, open-draft and
unchanged-hash exclusions. Store: version numbering, single open draft, grounding key check,
unusable rows. Queue and get as publisher; denied as contributor, anonymous and signed-in
visitor. Edit, mark, approve (including `curator_note_unsupported`, supersession and the reviewer
snapshot for an editor-profile user and a staff-only user), decline, request, export; stale
revision on every write; the published view hides every non-approved status and drops a hidden
exhibition. Analytics: `curator_note` accepted by the discovery-kind constraint.
