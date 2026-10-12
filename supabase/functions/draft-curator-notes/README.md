# Curator note drafting

`draft-curator-notes` writes the first draft of a curator's note (spec 090) for
exhibitions that qualify, grounded only in the exhibition's published catalogue
row and its reviewed artists and art terms. Nothing it writes is visible to
visitors: a draft becomes public only when a staff editor approves it in Admin
(`approve_curator_note`), and the app reads approved notes from the
`curator_notes_published` view.

## Modes

| Mode        | Caller                                                                         | Credential                                                     | Effect                                                                                                                                |
| ----------- | ------------------------------------------------------------------------------ | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `scheduled` | `pg_cron` through `content_private.schedule_curator_note_drafts()` (03:00 KST) | `Authorization: Bearer <CURATOR_NOTES_SCHEDULE_TOKEN>`         | Drafts up to `limit` (≤ 50, default 20) candidates from `list_curator_note_candidates`, stopping after 50 s and reporting `remaining` |
| `single`    | Admin, after `request_curator_note_draft(exhibition_id)`                       | The staff member's session JWT; the publisher tier is required | Drafts one exhibition on demand (`request_kind` `single` or `regenerate`)                                                             |

Request bodies are JSON of at most 16 KB:

```json
{ "mode": "scheduled", "limit": 20 }
{ "mode": "single", "exhibition_id": "…", "request_kind": "single" }
```

Responses: `200 { drafted, unusable, skipped, failed, remaining, results[] }`,
`400 invalid_request`, `401 unauthorized`, `404 exhibition_not_qualifying`
(single), `503 provider_unavailable` (single). The full contract is in
[`specs/090-curator-pov-notes/contracts/draft-curator-notes-function.md`](../../../specs/090-curator-pov-notes/contracts/draft-curator-notes-function.md).

## How a draft is made

1. The database builds a _packet_ (canonical JSON of the published fields,
   artists and terms) and its SHA-256; the function recomputes the hash and
   refuses a stale packet.
2. The Claude API is called with the versioned system prompt
   (`prompt/system.md`, two reference notes in `prompt/examples.json`,
   `PROMPT_VERSION` in `drafting.ts`) and a Zod structured output, so the answer
   always has the note's shape.
3. The draft is validated before storage: every paragraph's `grounding` keys
   must exist in the packet, every year or number must appear in the packet
   text, Korean fields must contain Hangul and English fields must not, lengths
   and insight counts must be within limits. A failing draft is stored as
   `unusable` with a reason code and never shown as reviewable.
4. `store_curator_note_draft` records the version with provenance: the model
   that answered, the prompt version, the packet keys and hash, the request kind
   and time.

## Configuration

| Secret                          | Source                                                                               | Purpose                                                             |
| ------------------------------- | ------------------------------------------------------------------------------------ | ------------------------------------------------------------------- |
| `ANTHROPIC_API_KEY`             | 1Password → `supabase secrets set`                                                   | Claude API                                                          |
| `CURATOR_NOTES_SCHEDULE_TOKEN`  | 1Password; the same value is stored in Vault as `gallr_curator_notes_schedule_token` | Authenticates the scheduled call                                    |
| `CURATOR_NOTES_MODEL`           | optional                                                                             | Overrides the model id (default `claude-opus-5-5`)                  |
| `CURATOR_NOTES_ALLOWED_ORIGINS` | optional                                                                             | Comma-separated Admin origins for the `single` mode CORS allow-list |

The hosted runtime supplies `SUPABASE_URL`, the publishable key and
`SUPABASE_SECRET_KEYS` (`draft_curator_notes` or `default`). Vault also needs
`gallr_curator_notes_function_url`, the function's full URL on that project.
Never create a custom secret with the reserved `SUPABASE_` prefix.

## Operations

- Each exhibition produces one structured log line: `operation`
  (`curator_note_drafted`, `curator_note_unusable`, `curator_note_skipped`,
  `curator_note_provider_failed`), the exhibition and version ids, model, prompt
  version, token usage and a reason code. Note text is never logged.
- Cost: about 3,000 input tokens (half of them a cached prompt) and 1,500 output
  tokens per draft, about USD 0.04 at Claude Opus 5.5 rates; the whole live
  catalogue is a few dollars.
- Changing the prompt or the examples requires bumping `PROMPT_VERSION`; Admin
  shows the version on every draft so editors can see when drafting changed.
- Deploy to staging first (see "Staging deployment" below), draft one exhibition
  with `single` mode from Admin, and read its log line before enabling the
  production schedule.

Run `deno task test` and `deno task check` in this directory. Tests use a fake
backend; no network or key is needed.

## Staging deployment

Operator prerequisites: the 1Password CLI (`op`) with the desktop-app
integration enabled, `supabase login`, and `psql` 15 or newer. Every value below
comes from the `gallr-staging` items through `op` secret references; never type
a secret into a command line, and never reuse a production value. Replace the
`<...>` placeholders with the staging item and field names.

1. Verify the target. The staging ref must differ from the production ref.

   ```bash
   supabase projects list
   export GALLR_STAGING_PROJECT_REF='<20-character-staging-ref>'
   supabase link --project-ref "$GALLR_STAGING_PROJECT_REF"
   supabase migration list --linked
   supabase db push --dry-run
   ```

   The dry run must list `20261011010000_legacy_reader_grants_events_editors`
   and `20261012000000_curator_notes` (plus any earlier migration staging has
   not yet received) and nothing unexpected.

2. Apply the migrations and confirm both versions are now recorded.

   ```bash
   supabase db push
   supabase migration list --linked
   ```

3. Store the function secrets from a generated, ignored env file. The schedule
   token is a staging-only random value of at least 32 characters stored in its
   own 1Password item; create it with `openssl rand -hex 32` if the item does
   not exist yet.

   ```bash
   cat > curator-notes.env.tpl <<'TPL'
   ANTHROPIC_API_KEY=op://DEV/<anthropic-staging-item>/credential
   CURATOR_NOTES_SCHEDULE_TOKEN=op://DEV/<curator-notes-staging-item>/credential
   CURATOR_NOTES_ALLOWED_ORIGINS=<staging-admin-origin>
   TPL
   op inject -i curator-notes.env.tpl \
     -o supabase/functions/draft-curator-notes/.env.staging.local
   git check-ignore supabase/functions/draft-curator-notes/.env.staging.local
   supabase secrets set \
     --env-file supabase/functions/draft-curator-notes/.env.staging.local \
     --project-ref "$GALLR_STAGING_PROJECT_REF"
   rm supabase/functions/draft-curator-notes/.env.staging.local curator-notes.env.tpl
   supabase secrets list --project-ref "$GALLR_STAGING_PROJECT_REF"
   ```

4. Deploy the function. `supabase/config.toml` already sets `verify_jwt = false`
   because the function checks its own credentials.

   ```bash
   supabase functions deploy draft-curator-notes \
     --project-ref "$GALLR_STAGING_PROJECT_REF"
   supabase functions list --project-ref "$GALLR_STAGING_PROJECT_REF"
   ```

5. Configure Vault so the daily schedule can call the function. `\getenv` keeps
   both values out of the command line; the statements are idempotent.

   ```bash
   cat > curator-notes-vault.sql <<'SQL'
   \set ON_ERROR_STOP on
   \getenv function_url GALLR_CURATOR_NOTES_FUNCTION_URL
   \getenv schedule_token CURATOR_NOTES_SCHEDULE_TOKEN
   select vault.create_secret(:'function_url', 'gallr_curator_notes_function_url', 'draft-curator-notes endpoint')
   where not exists (select 1 from vault.secrets where name = 'gallr_curator_notes_function_url');
   select vault.update_secret(id, :'function_url')
   from vault.secrets where name = 'gallr_curator_notes_function_url';
   select vault.create_secret(:'schedule_token', 'gallr_curator_notes_schedule_token', 'draft-curator-notes schedule token')
   where not exists (select 1 from vault.secrets where name = 'gallr_curator_notes_schedule_token');
   select vault.update_secret(id, :'schedule_token')
   from vault.secrets where name = 'gallr_curator_notes_schedule_token';
   select name from vault.secrets where name like 'gallr_curator_notes_%' order by name;
   SQL
   GALLR_CURATOR_NOTES_FUNCTION_URL="https://${GALLR_STAGING_PROJECT_REF}.supabase.co/functions/v1/draft-curator-notes" \
   CURATOR_NOTES_SCHEDULE_TOKEN='op://DEV/<curator-notes-staging-item>/credential' \
   GALLR_STAGING_DATABASE_URL='op://DEV/<staging-database-item>/connection string' \
   op run -- sh -c 'psql "$GALLR_STAGING_DATABASE_URL" -f curator-notes-vault.sql'
   rm curator-notes-vault.sql
   ```

   The migration already registered the `curator_notes_daily` cron job (18:00
   UTC). Until this step it fails closed with
   `curator_notes_schedule_not_configured`; after it, staging drafts up to 20
   candidates a day at real Claude cost (about USD 0.04 each).

6. Test with real data. Run Admin Preview against staging and sign in as a
   staging staff member with the `publisher` or `admin` role:

   ```bash
   cd admin
   VITE_SUPABASE_URL='op://DEV/<staging-item>/hostname' \
   VITE_SUPABASE_PUBLISHABLE_KEY='op://DEV/<staging-item>/credential' \
   op run -- npm run dev
   ```

   Open Notes, pick one qualifying exhibition and choose Draft now. Expect a
   reviewable draft within about a minute, with the model id and
   `prompt_version` shown on the draft. Then confirm the provenance row and the
   log line:

   ```sql
   select status, model, prompt_version, rejection_code, request_kind, generated_at
   from content.curator_note_versions
   order by generated_at desc
   limit 5;
   ```

   In the Supabase dashboard, Edge Functions → draft-curator-notes → Logs, find
   the `curator_note_drafted` (or `curator_note_unusable`) line and check
   `input_tokens`, `cache_read_input_tokens` and `output_tokens`. Note text
   never appears in logs.

7. Optional: fire one scheduled run by hand from the SQL editor and read the
   `net._http_response` row for the returned request id:

   ```sql
   select content_private.schedule_curator_note_drafts();
   ```

Production repeats the same steps with the `gallr` items after the release PR is
merged; the production ref must match the reviewed digest documented in
`scripts/staging-rehearsal/README.md`.
