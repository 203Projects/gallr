-- Spec 090 curator POV notes: automatically drafted, staff-reviewed curator notes.
--
--   (none) ──draft──► draft ──approve──► approved ──newer approval──► superseded
--                     │  ▲ edit / mark unsupported (status unchanged)
--                     ├──decline──► declined         (auto drafting blocked until staff request one)
--                     ├──regenerate──► replaced      (a fresh draft takes its place; the old one is kept)
--                     └──validation failed──► unusable
--
-- Drafts are written by the draft-curator-notes Edge Function through store_curator_note_draft (service role
-- only) and never reach a visitor: the app reads public.curator_notes_published, which carries approved versions
-- of exhibitions that are still in exhibition_catalog_v2. Staff functions require the publisher tier, bind
-- every write to the version's revision (updated_at) and write content.audit_log rows.
begin;

create type content.curator_note_status as enum (
  'draft', 'unusable', 'replaced', 'declined', 'approved', 'superseded'
);

create table if not exists content.curator_notes (
  exhibition_id text primary key,
  approved_version_id uuid,
  auto_draft_blocked boolean not null default false,
  last_source_hash text,
  updated_at timestamptz not null default now(),
  constraint curator_notes_exhibition_id_not_blank check (length(btrim(exhibition_id)) between 1 and 128)
);

comment on table content.curator_notes is
  'One row per exhibition that has ever had a curator note drafted: the live version pointer and the auto-draft block.';
comment on column content.curator_notes.auto_draft_blocked is
  'Set by a decline; the scheduler skips the exhibition until staff request a fresh draft (FR-010).';
comment on column content.curator_notes.last_source_hash is
  'Hash of the source packet the newest version was drafted from; a changed hash queues a new draft (FR-009).';

create table if not exists content.curator_note_versions (
  id uuid primary key default gen_random_uuid(),
  exhibition_id text not null references content.curator_notes(exhibition_id) on delete cascade,
  version_number integer not null,
  status content.curator_note_status not null,
  hook_ko text not null default '',
  hook_en text not null default '',
  insights_ko jsonb not null default '[]'::jsonb,
  insights_en jsonb not null default '[]'::jsonb,
  tip_ko text not null default '',
  tip_en text not null default '',
  unsupported_refs text[] not null default '{}',
  edited boolean not null default false,
  producer text not null,
  model text not null,
  prompt_version text not null,
  source_fields text[] not null,
  source_hash text not null,
  source_version_id uuid,
  request_kind text not null,
  requested_by uuid references auth.users(id) on delete set null,
  generated_at timestamptz not null,
  rejection_code text,
  reviewed_by uuid references auth.users(id) on delete set null,
  reviewer_name_ko text,
  reviewer_name_en text,
  decided_at timestamptz,
  decline_reason text,
  decline_note text,
  approved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint curator_note_versions_number_positive check (version_number >= 1),
  constraint curator_note_versions_unique_number unique (exhibition_id, version_number),
  constraint curator_note_versions_hook_length check (char_length(hook_ko) <= 120 and char_length(hook_en) <= 120),
  constraint curator_note_versions_tip_length check (char_length(tip_ko) <= 240 and char_length(tip_en) <= 240),
  constraint curator_note_versions_insights_arrays check (
    jsonb_typeof(insights_ko) = 'array' and jsonb_typeof(insights_en) = 'array'
  ),
  constraint curator_note_versions_producer_not_blank check (length(btrim(producer)) > 0),
  constraint curator_note_versions_model_not_blank check (length(btrim(model)) > 0),
  constraint curator_note_versions_prompt_version_not_blank check (length(btrim(prompt_version)) > 0),
  constraint curator_note_versions_source_hash_hex check (source_hash ~ '^[0-9a-f]{64}$'),
  constraint curator_note_versions_request_kind check (request_kind in ('scheduled', 'single', 'regenerate')),
  constraint curator_note_versions_rejection_code check (
    (status = 'unusable') = (rejection_code is not null)
    and (rejection_code is null or rejection_code in (
      'grounding_unknown_key', 'number_not_in_sources', 'language_mismatch', 'length_exceeded',
      'insight_count', 'provider_refused', 'parse_failed', 'source_changed'
    ))
  ),
  constraint curator_note_versions_decline_reason check (
    (status = 'declined') = (decline_reason is not null)
    and (decline_reason is null or decline_reason in (
      'not_insightful', 'wrong_facts', 'tone', 'exhibition_not_suitable', 'other'
    ))
  ),
  constraint curator_note_versions_decline_note_length check (decline_note is null or char_length(decline_note) <= 500),
  constraint curator_note_versions_approved_fields check (
    status not in ('approved', 'superseded')
    or (approved_at is not null and reviewer_name_ko is not null and reviewer_name_en is not null)
  )
);

comment on table content.curator_note_versions is
  'Every drafted, reviewed and published version of a curator note with its provenance (spec 090 FR-005, FR-008).';
comment on column content.curator_note_versions.unsupported_refs is
  'Sentence references (<lang>:<section>:<index>) an editor marked unsupported; approval refuses a non-empty list (FR-007).';
comment on column content.curator_note_versions.source_fields is
  'Packet keys present when the draft was made; every grounding key in the insights must be one of them (FR-003).';
comment on column content.curator_note_versions.updated_at is
  'The revision Admin sends back with every write; a mismatch raises curator_note_stale.';

create unique index if not exists curator_note_versions_one_open_draft
  on content.curator_note_versions (exhibition_id)
  where status = 'draft';
create index if not exists curator_note_versions_exhibition_status_idx
  on content.curator_note_versions (exhibition_id, status);
create index if not exists curator_note_versions_approved_at_idx
  on content.curator_note_versions (approved_at desc)
  where status = 'approved';

alter table content.curator_notes
  add constraint curator_notes_approved_version_fk
  foreign key (approved_version_id) references content.curator_note_versions(id) on delete set null
  deferrable initially deferred;

alter table content.curator_notes enable row level security;
alter table content.curator_note_versions enable row level security;
revoke all on content.curator_notes from public, anon, authenticated, service_role;
revoke all on content.curator_note_versions from public, anon, authenticated, service_role;

-- Visitors read approved notes only, and only while the exhibition is still in the public catalogue (FR-006, FR-017).
create or replace view public.curator_notes_published as
select
  version.exhibition_id,
  version.version_number,
  version.hook_ko,
  version.hook_en,
  version.insights_ko,
  version.insights_en,
  version.tip_ko,
  version.tip_en,
  version.reviewer_name_ko,
  version.reviewer_name_en,
  version.approved_at,
  catalog.closing_date
from content.curator_notes as note
join content.curator_note_versions as version on version.id = note.approved_version_id
join public.exhibition_catalog_v2 as catalog on catalog.id = note.exhibition_id
where version.status = 'approved';

comment on view public.curator_notes_published is
  'Approved curator notes of exhibitions in the public catalogue; the only read path for visitors (spec 090).';

revoke all on public.curator_notes_published from public, anon, authenticated, service_role;
grant select on public.curator_notes_published to anon, authenticated, service_role;

-- Aggregate analytics: the home carousel attributes impressions and opens to curator_note (FR-012).
alter table content.mobile_analytics_daily drop constraint if exists mobile_analytics_discovery_kind;
alter table content.mobile_analytics_daily add constraint mobile_analytics_discovery_kind check (discovery_kind in (
  'none', 'featured', 'organic', 'search', 'editor', 'event', 'gallery',
  'nearby', 'saved', 'notification', 'recommendation', 'route', 'curator_note'
));

commit;
