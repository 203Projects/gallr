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
- Deploy to staging first, draft one exhibition with `single` mode from Admin,
  and read its log line before enabling the production schedule.

Run `deno task test` and `deno task check` in this directory. Tests use a fake
backend; no network or key is needed.
