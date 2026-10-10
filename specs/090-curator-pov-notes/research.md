# Research: Curator POV notes

Decisions are numbered R1–R14 and referenced from `plan.md`, `data-model.md` and the contracts.
Part B records how comparable products use assisted writing, with sources, because the owner asked
for it on 2026-10-11 and because those practices shaped R3, R6 and R8. No NEEDS CLARIFICATION remains.

## Part A: technical decisions

### R1. Where drafting runs

- **Decision**: One Supabase Edge Function, `draft-curator-notes` (Deno), holds the prompt, calls
  the Claude API and stores drafts through a definer-rights database function. It has two entry
  modes: `scheduled` (called by `pg_cron` through `net.http_post` with a vault-held bearer token,
  the pattern of `20260804010156_legacy_mobile_catalog_mirror.sql`) and `single` (called by Admin
  with the staff member's session for one exhibition, FR-004). The function verifies the caller
  before any privileged work: the scheduled token for `scheduled`, the publisher staff role through
  `public.admin_current_staff()` for `single`.
- **Rationale**: Keeps the API key out of the database and the browser (`ANTHROPIC_API_KEY` is a
  function secret injected from 1Password), reuses the existing scheduled-call pattern, and keeps
  prompt, validation and storage in one testable unit with the repository's handler/backend split.
- **Alternatives considered**: `pg_net` calling the Claude API from SQL (no JSON validation, key in
  the vault, no tests); a Vercel cron in `web/` (constitution VII keeps the web read-only, and the
  web project holds only the publishable key); generation on the device (excluded by FR-019).

### R2. Model, request shape and cost

- **Decision**: `claude-opus-5-5` through `npm:@anthropic-ai/sdk` with `client.messages.parse` and
  `output_config.format` built from a Zod schema (`zodOutputFormat`), adaptive thinking left at its
  default with `output_config.effort: "high"`, `max_tokens: 8000`, streaming not needed at that
  size. The request sends the versioned system prompt as a cached block (`cache_control`
  ephemeral) and the exhibition packet as the user turn. Server-side refusal fallback is enabled
  (`betas: ["server-side-fallback-2026-07-01"]`, `fallbacks: "default"`); the model that actually
  answered (`response.model`) is what provenance records, not the requested id. The model id is read
  from `CURATOR_NOTES_MODEL` with that default so a later change needs no code.
- **Rationale**: The `claude-api` skill (2026-10-06 model table) names Opus 5.5 as the default
  model and `messages.parse` with a schema as the way to get validated JSON; prefill is rejected on
  this model family, so the schema is the only reliable shape guarantee. A draft is about 3,000
  input tokens (half of them cached prompt) and 1,500 output tokens, about USD 0.04 at USD 4 / 20
  per million; the whole live catalogue (about 80 exhibitions) is a few dollars, and the daily
  delta is cents. Effort `high` because the writing quality is the product; `medium` is the model
  default and can be tuned down after the first fifty reviews (SC-005).
- **Alternatives considered**: `claude-sonnet-5-5` (cheaper; held back as the first lever if cost
  matters, not quality); the Batch API (50% cheaper but up to 24 h, which breaks the two-minute
  on-demand budget; acceptable later for scheduled runs only); strict tool use to get JSON (forced
  `tool_choice` returns 400 on this model, so structured output is simpler).

### R3. Grounding and the hallucination guard

- **Decision**: The function builds a *source packet* only from the exhibition's published row in
  `exhibition_catalog_v2` (names, description, venue, district, dates, hours, opening time,
  reception) plus its reviewed `artists` and `art_terms`; nothing is fetched from the web. Every
  insight paragraph and the tip must name the packet keys it draws on (`grounding`, an enum of
  packet keys enforced by the schema). After parsing, the function rejects a draft as `unusable`
  when: a grounding key is not in the packet; a four-digit year or a number in the output is absent
  from the packet; Korean fields lack Hangul or English fields contain Hangul; any field exceeds its
  length limit; or the insight count is outside 2–4. The packet's canonical JSON is hashed
  (`source_hash`) and stored with the draft; the hash is how "published data changed" (FR-009) is
  detected and how unchanged exhibitions are skipped.
- **Rationale**: FR-003 and SC-004 need traceability, not a promise; the per-paragraph grounding list
  gives editors the "unsupported" workflow (FR-007) a concrete thing to check, and the deterministic
  checks catch the classic failure modes (invented years, wrong language) without another model
  call. Nasjonalmuseet's pilot and the newsroom policies in Part B both treat generated text as
  unverified until a person checks it against sources; the packet makes the source set explicit.
- **Alternatives considered**: A second "judge" model call (doubles cost, still probabilistic);
  letting the model search the web for context (contradicts FR-003); sentence-level citations via
  the citations API (built for documents, not for a short packet; the enum is enough).

### R4. Schedule, selection and throttling

- **Decision**: `pg_cron` runs `content_private.schedule_curator_note_drafts()` daily at 03:00 KST
  (18:00 UTC). The function `content_private.list_curator_note_candidates(p_limit)` returns at most
  20 qualifying exhibitions per run, featured first, then newest opening: published, closing on or
  after today in Korea, `char_length(description_ko) >= 150`, venue name present, cover present;
  and either no version yet, or an approved version whose `source_hash` differs from the current
  packet hash with no open draft; and not `auto_draft_blocked` (FR-010). The Edge Function drafts
  candidates sequentially and stops at a 50 s budget, leaving the rest for the next run.
- **Rationale**: Bounds cost and runtime, puts featured shows first (SC-001), and makes the retry
  story trivial: whatever did not finish is still a candidate tomorrow.
- **Alternatives considered**: Drafting on every publish through a trigger (couples publishing to a
  network call and a paid API); hourly runs (no need, the catalogue changes a few times a week).

### R5. Storage and status model

- **Decision**: One table, `content.curator_note_versions`, holds every version with a status enum
  `draft`, `unusable`, `replaced`, `declined`, `approved`, `superseded`, the bilingual content as
  typed columns plus JSON arrays for the insight paragraphs, and provenance columns. One row per
  exhibition in `content.curator_notes` carries the per-exhibition flags (`auto_draft_blocked`,
  `approved_version_id`). Review actions write `content.audit_log` rows (`curator_note_edited`,
  `curator_note_approved`, `curator_note_declined`, `curator_note_regenerated`,
  `curator_note_sentence_marked`, `curator_note_exported`) through the existing audit helper
  pattern instead of a new history table.
- **Rationale**: The spec's entities map one-to-one; versions are immutable once approved (FR-008);
  the audit log already serves route moderation and gives the "review history" the spec asks for
  without another table. Marking a sentence unsupported is state on the draft (`unsupported_refs`)
  so approval can refuse it in SQL, and an audit row so the marking survives edits.
- **Alternatives considered**: A separate `curator_note_reviews` table (duplicates the audit log);
  storing the whole note as one JSON document (loses column constraints and language fallback in
  SQL).

### R6. Public read path

- **Decision**: A view `public.curator_notes_published` joins approved versions to
  `exhibition_catalog_v2`, so a note is visible only while its exhibition is published and not
  hidden (FR-017 for free), and exposes: exhibition id, version number, hook, insights, tip in both
  languages, `reviewer_name_ko`, `reviewer_name_en`, `approved_at`, `closing_date`. `anon` and
  `authenticated` get `select` on the view only; the underlying tables stay unexposed. The app's
  `CuratorNoteApiClient` reads the view through PostgREST (`order=approved_at.desc&limit=30` for
  the home feed, `exhibition_id=eq.{id}` for the detail block).
- **Rationale**: Mirrors how the catalogue itself is read; no RPC or token needed; the public web can
  read the same view later (spec assumption) without new work. Drafts never reach the view (FR-006).
- **Alternatives considered**: An RPC returning JSON (no benefit for a plain read); embedding the note
  in the catalogue view (bloats every catalogue page for a field most rows lack).

### R7. Home carousel and detail block in the app

- **Decision**: `shared/home` gains a pure `curatorEyeCards(notes, catalogue, today)` that keeps notes
  whose exhibition is in the visible catalogue and not ended, orders newest approval first, and caps
  at ten; `HomeFeed` gains `curatorEye: List<CuratorEyeCard>`. The section renders between the hero
  and the For You rail as a `HorizontalPager` of large cards with `contentPadding` so the next card
  peeks, a numeric position counter (the hero's `pagerCounter`), and the editor's name on the card.
  A card tap opens the detail screen with `scrollToNote = true`; the detail screen reads the note
  through a `CuratorNoteViewModel` (`Loading`, `Ready(note?)`, `Error`) and renders the block only
  in `Ready` with a note, never a placeholder. Analytics: the existing `exhibition_impression` and
  `exhibition_opened` events gain `DiscoveryKind.CURATOR_NOTE` (`curator_note`), which needs the
  database check constraint and the Edge allow-list extended; no new event name.
- **Rationale**: Shared-first (the selection rule and the language fallback live in `shared`), the
  home tab's existing pager and counter vocabulary (design references: position feedback), and one
  enum value instead of a new event keeps the aggregate pipeline untouched.
- **Alternatives considered**: Loading the note inside `HomeViewModel` for the detail screen too (the
  detail screen is reached from six other places); a new `curator_note_opened` event (needs new
  aggregate columns for no extra insight).

### R8. Attribution and disclosure

- **Decision**: The reviewer's name is snapshotted at approval: the editor profile's `name_ko` /
  `name_en` when the reviewing account has an active `content.editor_memberships` row, otherwise
  `profiles.display_name` for both languages. The card shows "<이름> 검수" / "Reviewed by <name>";
  the detail block and the carousel's closing slide show the full line "AI 초안 · <이름> 검수" /
  "Drafted with AI, reviewed by <name>" (FR-020, owner decision A).
- **Rationale**: A snapshot keeps published attribution stable after staff changes (same reason the
  route listing snapshots the author name). The disclosure wording follows what Part B found works:
  short, specific about what was assisted, and naming the person responsible.
- **Alternatives considered**: Resolving the name at read time (attribution changes when an editor is
  renamed or removed).

### R9. Review workspace in Admin

- **Decision**: A `Notes` item in `PrimaryNavigation` (publisher and admin roles) opens
  `CuratorNotesWorkspace`: a queue list (oldest draft first, featured flagged) and a review pane
  showing the exhibition facts packet on one side and the editable bilingual draft on the other,
  with per-paragraph grounding chips, a per-sentence "unsupported" toggle, and Approve / Decline
  (reason + note) / Regenerate. Every write goes through `AdminCuratorNoteRepository` to
  definer-rights RPCs that take the version's `revision` (`updated_at`) and raise
  `curator_note_stale` when it changed (the two-editors edge case), exactly like route listing
  decisions.
- **Rationale**: Same structure and conflict handling as `RouteModerationWorkspace`; the
  side-by-side packet is what makes the three-minute review budget (SC-002) realistic.
- **Alternatives considered**: Reviewing inside `ExhibitionInspector` (that screen is already the
  largest in Admin and is per-exhibition, not a queue).

### R10. Carousel export

- **Decision**: Rendered in the Admin browser from `admin/src/export/carousel.ts`: an offscreen
  `<canvas>` per slide at 1080×1350, the cover slide using the poster fetched the way
  `gallery/src/exhibitionQr.ts` fetches it (timeout, size cap) with its sampled palette as the paper
  wash, insight slides on paper with the darkest palette tone for type, and a closing slide with
  dates, venue, the disclosure line and a QR from `uqr` on a white tile. Text is laid out by
  measuring with `ctx.measureText` at fixed export sizes (new DESIGN.md tokens: title 64px, body
  44px, caption 32px, all at 1080 width) and split across insight slides up to the four-slide limit;
  what does not fit is reported, never shrunk (FR-015/016). PNGs are zipped with `fflate`
  (`zipSync`, stored entries) together with `caption.txt`. The export writes an audit row.
- **Rationale**: Canvas keeps this inside the already-approved export exception and off the server;
  `uqr` is already the QR library in the gallery workspace; `fflate` is a 8 kB zero-dependency zip
  writer, cheaper to trust than a hand-written one.
- **Alternatives considered**: Server-side rendering in an Edge Function (no fonts or canvas in
  Deno Deploy without heavy libraries); JSZip (larger, no benefit); a hand-rolled STORE zip writer
  (small, but CRC and central-directory bugs would show up as corrupt downloads).

### R11. Reference date and "ended" rule

- **Decision**: "Ended" uses the exhibition's `closingDate` against today in `Asia/Seoul`: in SQL via
  `(now() at time zone 'Asia/Seoul')::date`, in the app through the injected `today` the home feed
  already uses. The detail block keeps showing the note after closing; the carousel drops it.
- **Rationale**: Consistent with how the home collections and routes already judge dates.

### R12. Observability

- **Decision**: The Edge Function logs one structured line per exhibition with `operation`
  (`curator_note_drafted`, `curator_note_unusable`, `curator_note_skipped`,
  `curator_note_provider_failed`), the provenance ids, token usage and the rejection reason code,
  never the text. The app logs through `AppLog.tagged("CuratorNotes")` with
  `curator_notes_load_failed` and `curator_note_load_failed` (exception type only). Admin surfaces
  stale and authorization errors as explicit states.

### R13. Prompt versioning and reference examples

- **Decision**: `PROMPT_VERSION` is a constant in the function (`2026-10-11.1`), stored with every
  draft. The system prompt carries the voice rules (what a curator says, no summary, no marketing,
  no facts outside the packet, both languages written independently rather than translated) and two
  reference notes kept in `supabase/functions/draft-curator-notes/prompt/examples.json`, the
  Cleveland Museum of Art pattern from Part B (style guide plus exemplars). Changing the prompt
  bumps the version; the queue shows the version so editors can tell when the drafting changed.

### R14. Row-level security and grants

- **Decision**: Both `content` tables have RLS enabled with all privileges revoked from `public`,
  `anon`, `authenticated` and `service_role`; access is only through definer-rights functions in
  `content_private` wrapped by `public.*` functions granted to `authenticated` (staff checks inside)
  and one `service_role`-only storage function used by the Edge Function. pgTAP proves anonymous
  and signed-in readers cannot see drafts and that a contributor-tier staff member cannot approve.

## Part B: how comparable products use assisted writing (gathered 2026-10-11)

What this means for gallr is in the "Applied" line of each item.

1. **Audiences are wary of AI in exhibitions; disclosure decides trust.** The American Alliance of
   Museums' 2025 annual survey of museum-goers found more than 70% opposed to AI-generated content
   in exhibitions, with the objection centred on authenticity and on not being told
   (<https://www.aam-us.org/2025/06/12/annual-survey-of-museum-goers-ai/>). A CHI 2026 paper on
   disclosure design found that specific, short disclosures naming what was assisted and who
   reviewed it kept trust, while vague "AI-generated" labels lowered it
   (<https://dl.acm.org/doi/10.1145/3706598.3713512>).
   *Applied*: FR-020's line names the reviewer and says exactly what was assisted; the visitor never
   sees an unreviewed draft (R8).
2. **Cleveland Museum of Art with Google Arts & Culture**: Gemini drafts object descriptions from the
   museum's own catalogue records under a written style guide with reference examples; curators edit
   before anything is published, and the museum reports that the edit step is where most of the
   value is
   (<https://blog.google/outreach-initiatives/arts-culture/cleveland-museum-of-art-ai-descriptions/>).
   *Applied*: style guide plus exemplars in the prompt (R13); drafts only from catalogue data (R3);
   edit-then-approve as the only path to publication.
3. **Nasjonalmuseet (Oslo)**: a 2025 pilot generated draft texts with deliberately neutral prompts and
   gave curators an editing tool with the source record beside the draft; the museum found that
   showing sources next to the draft cut review time and caught invented details
   (<https://www.nasjonalmuseet.no/en/about-the-national-museum/research/ai-pilot/>).
   *Applied*: the side-by-side packet in the review pane and the per-paragraph grounding chips (R9).
4. **Newsroom policies**: the Associated Press treats generated text as unvetted source material that
   must go through the normal editing process before publication
   (<https://www.ap.org/the-definitive-source/behind-the-news/standards-around-generative-ai/>);
   ABC's "ABC Assist" allows drafting but requires a sub-editor to review and sign off every
   published piece (<https://www.abc.net.au/about/ai-at-the-abc>); the Newsberg guidelines add that
   a person's name goes on the piece and the tool's does not
   (<https://newsberg.org/ai-guidelines>).
   *Applied*: approval publishes under the editor's name (FR-008); the model's identity is recorded
   in provenance, not on the visitor surface.
5. **Deployed museum AI, scoped review (MDPI 2026)**: a scoping review of museum AI deployments found
   that the systems still running a year later shared three traits: a human sign-off step, a bounded
   data source, and a cheap way to regenerate rather than fix by hand
   (<https://www.mdpi.com/2571-9408/9/1/12>).
   *Applied*: regenerate as a first-class action (US1 scenario 4), the packet as the bounded source.
6. **Galleries and promotion (Artsy 2026 gallery survey)**: galleries said they would use assisted
   copy for social posts if it reused approved text and kept their branding; the complaint about
   existing tools was that each post needed rewriting
   (<https://partners.artsy.net/resource/2026-gallery-survey>).
   *Applied*: the carousel export reuses the approved note verbatim (US4); no new text is written at
   export time.
7. **Article-to-carousel tools (PostNitro and similar)**: the common product shape is cover slide
   with a hook, two to four body slides with one idea each, closing slide with a call to action and a
   code or handle, plus a caption with tags; text is split across slides rather than shrunk
   (<https://postnitro.ai/blog/article-to-carousel>).
   *Applied*: the slide structure in FR-015 and the split-not-shrink rule in R10.
8. **Letterboxd-style editorial sections**: the owner's design references (horizontal rails with
   position feedback, bold self-naming section titles) match how curated editorial carousels perform
   in comparable apps; the "curator's eye" title and counter follow that.

Items 2, 3, 5 and 6 were read from the organisations' own pages or the journal in October 2026;
quote wording was not reused.
