# Contract: `draft-curator-notes` Edge Function

`POST /functions/v1/draft-curator-notes`, JSON only, body at most 16 KB. Composition follows the
repository pattern: `index.ts` wires `handler.ts` to `backend.ts`; the handler is tested with a fake
backend.

## Authentication

| Mode | Credential | Check |
|---|---|---|
| `scheduled` | `Authorization: Bearer <schedule token>` | Constant-time equality with `CURATOR_NOTES_SCHEDULE_TOKEN` |
| `single` | The staff member's Supabase session JWT | `public.admin_current_staff()` through a publishable-key client with the caller's token; role must be `publisher` or `admin` |

Any other caller gets `401 { "error": "unauthorized" }`. Browser calls (`single`) answer the CORS
preflight for the Admin origins only.

## Requests

```json
{ "mode": "scheduled", "limit": 20 }
{ "mode": "single", "exhibition_id": "…", "request_kind": "single" | "regenerate" }
```

## Responses

```json
200 { "drafted": 3, "unusable": 1, "skipped": 0, "failed": 0, "remaining": 12,
      "results": [{ "exhibition_id": "…", "outcome": "drafted" | "unusable" | "failed", "code": null | "…" }] }
400 { "error": "invalid_request" }
401 { "error": "unauthorized" }
404 { "error": "exhibition_not_qualifying" }      // single mode, not a candidate
503 { "error": "provider_unavailable" }           // single mode, Claude call failed after retries
```

`scheduled` always returns 200 once authenticated; per-exhibition failures are in `results` and
logged. The run stops drafting when 50 s have elapsed and reports `remaining`.

## Drafting steps (per exhibition)

1. Take the packet from the candidate row (`scheduled`) or from `request_curator_note_draft`
   (`single`), recompute the hash, refuse on mismatch (`code: "source_changed"`).
2. Build the request: system prompt (`prompt/system.md` + `prompt/examples.json`, one cached block),
   user turn with the packet JSON and the instruction to write KO and, only when `description_en`
   is present, EN. `model` from `CURATOR_NOTES_MODEL` (default `claude-opus-5-5`),
   `max_tokens: 8000`, `output_config: { effort: "high", format: zodOutputFormat(NoteDraftSchema) }`,
   `betas: ["server-side-fallback-2026-07-01"]`, `fallbacks: "default"`, SDK timeout 90 s,
   `maxRetries: 2`.
3. If `stop_reason` is `refusal` or `parsed_output` is null: store `unusable` with
   `provider_refused` or `parse_failed`.
4. Validate (R3): grounding keys subset of `source_fields`; every `\d{4}` and every integer in the
   output appears in the packet text; Hangul present in every KO field and absent from EN fields;
   lengths; 2–4 KO insights, 0 or 2–4 EN insights. First failure sets `rejection_code`.
5. Store through `store_curator_note_draft` with `model = response.model`, `prompt_version`,
   `source_fields`, `source_hash`, `request_kind`, `requested_by`, `generated_at`.
6. Log one line: `{ "operation": "curator_note_drafted" | "curator_note_unusable" |
   "curator_note_provider_failed", "exhibition_id", "version_id", "model", "prompt_version",
   "input_tokens", "cache_read_input_tokens", "output_tokens", "code" }`.

## Output schema (`schema.ts`)

```json
{
  "type": "object", "additionalProperties": false,
  "required": ["ko", "en"],
  "properties": {
    "ko": { "$ref": "#/$defs/lang" },
    "en": { "anyOf": [{ "$ref": "#/$defs/lang" }, { "type": "null" }] }
  },
  "$defs": {
    "lang": {
      "type": "object", "additionalProperties": false,
      "required": ["hook", "insights", "tip"],
      "properties": {
        "hook": { "type": "string", "maxLength": 120 },
        "insights": {
          "type": "array", "minItems": 2, "maxItems": 4,
          "items": {
            "type": "object", "additionalProperties": false,
            "required": ["text", "grounding"],
            "properties": {
              "text": { "type": "string", "maxLength": 420 },
              "grounding": { "type": "array", "minItems": 1, "items": { "type": "string" } }
            }
          }
        },
        "tip": { "type": "string", "maxLength": 240 }
      }
    }
  }
}
```

The Zod source of this schema is the single definition; the JSON above is its rendering.

## Environment

| Name | Source | Use |
|---|---|---|
| `ANTHROPIC_API_KEY` | 1Password → `supabase secrets set` | Claude API |
| `CURATOR_NOTES_MODEL` | function secret, optional | Model id override |
| `CURATOR_NOTES_SCHEDULE_TOKEN` | 1Password; same value stored in the vault as `gallr_curator_notes_schedule_token` | `scheduled` auth |
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, publishable key | platform | Storage and the staff check |

## Tests (`deno task test`)

Handler: method and body validation, both auth paths (good, bad, missing), `single` for a
non-candidate, the time budget with a slow fake backend, response shapes. Drafting: packet
canonicalisation and hash equality with the SQL definition (fixture shared with the pgTAP suite),
each validation rule with a passing and a failing draft, refusal and parse failure outcomes, the
`model` recorded from the answer. The Claude client is behind the backend interface; no network in
tests.
