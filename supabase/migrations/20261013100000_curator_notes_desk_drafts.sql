-- Spec 090 follow-up: desk drafting. A Claude session at a staff member's desk writes the note and the
-- operator stores it through store_curator_note_draft under the database owner role (the Supabase CLI's
-- --linked login), so the function can no longer trust anything the caller sends about provenance:
--
--   * source_fields are derived from the live packet; the caller's list is ignored;
--   * the draft must name the live published version (source_version_id), which refuses a statement
--     prepared against another catalogue (staging vs production) even when the packet text is identical;
--   * requested_by, when supplied, must be an active publisher or admin (the tier Admin requires);
--   * a draft for an exhibition an editor declined (auto_draft_blocked) or whose approved note already
--     covers the current packet is refused unless the payload carries override = true together with a
--     requester; an override lifts the decline and is audited as curator_note_unblocked;
--   * a desk draft (producer claude-desk) always names its requester; producer is constrained.
--
-- content_private.curator_note_desk_contract() lets the desk tool refuse a target that lacks these
-- rules. The daily API schedule is removed: notes are drafted at the desk (owner decision 2026-10-13).
-- schedule_curator_note_drafts() stays for an operator who re-enables the API path by hand.
begin;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'curator_note_versions_producer' and conrelid = 'content.curator_note_versions'::regclass
  ) then
    alter table content.curator_note_versions
      add constraint curator_note_versions_producer check (producer in ('claude', 'claude-desk'));
  end if;
end;
$$;

create or replace function content_private.curator_note_desk_contract()
returns integer
language sql
immutable
security definer
set search_path = ''
as $$ select 1; $$;
revoke all on function content_private.curator_note_desk_contract() from public, anon, authenticated, service_role;
comment on function content_private.curator_note_desk_contract() is
  'Version of the desk drafting rules in store_curator_note_draft; the desk tool refuses a target whose value it does not know.';

create or replace function content_private.store_curator_note_draft_impl(p_payload jsonb)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_exhibition_id text := nullif(btrim(coalesce(p_payload ->> 'exhibition_id', '')), '');
  v_status text := p_payload ->> 'status';
  v_producer text := coalesce(p_payload ->> 'producer', 'claude');
  v_source_fields text[];
  v_packet record;
  v_note content.curator_notes%rowtype;
  v_version_id uuid;
  v_version_number integer;
  v_requested_by uuid;
  v_supplied_version uuid;
  v_generated_at timestamptz;
  v_override boolean;
  v_held boolean := false;
begin
  if v_exhibition_id is null or v_status not in ('draft', 'unusable') then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  select * into v_packet from content_private.curator_note_packet(v_exhibition_id);
  if v_packet.packet_hash is null then
    raise exception using errcode = 'P0002', message = 'curator_note_not_found';
  end if;
  if coalesce(p_payload ->> 'source_hash', '') <> v_packet.packet_hash then
    raise sqlstate 'PT409' using message = 'curator_note_source_changed';
  end if;
  begin
    v_supplied_version := nullif(p_payload ->> 'source_version_id', '')::uuid;
    v_requested_by := nullif(p_payload ->> 'requested_by', '')::uuid;
    v_override := coalesce((p_payload ->> 'override')::boolean, false);
    v_generated_at := coalesce((p_payload ->> 'generated_at')::timestamptz, clock_timestamp());
  exception when others then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end;
  if v_packet.source_version_id is not null and v_supplied_version is distinct from v_packet.source_version_id then
    raise sqlstate 'PT409' using message = 'curator_note_source_changed';
  end if;
  if v_requested_by is not null and not exists (
    select 1 from content.staff_members as staff
    where staff.user_id = v_requested_by and staff.active and staff.role in ('publisher', 'admin')
  ) then
    raise exception using errcode = '42501', message = 'curator_note_requester_not_staff';
  end if;
  if (v_producer = 'claude-desk' or v_override) and v_requested_by is null then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  select coalesce(array_agg(key order by key), '{}'::text[]) into v_source_fields
  from jsonb_object_keys(v_packet.packet) as key;

  if v_status = 'draft' then
    if not content_private.curator_note_insights_valid(p_payload -> 'insights_ko', v_source_fields, false)
       or not content_private.curator_note_insights_valid(coalesce(p_payload -> 'insights_en', '[]'::jsonb), v_source_fields, true)
       or nullif(btrim(coalesce(p_payload ->> 'hook_ko', '')), '') is null
       or nullif(btrim(coalesce(p_payload ->> 'tip_ko', '')), '') is null then
      raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
    end if;
  end if;

  insert into content.curator_notes (exhibition_id) values (v_exhibition_id)
  on conflict (exhibition_id) do nothing;
  select * into v_note from content.curator_notes where exhibition_id = v_exhibition_id for update;

  if v_status = 'draft' then
    if exists (
      select 1 from content.curator_note_versions as open_draft
      where open_draft.exhibition_id = v_exhibition_id and open_draft.status = 'draft'
    ) then
      raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
    end if;
    if v_note.auto_draft_blocked then
      if not v_override then
        raise sqlstate 'PT409' using message = 'curator_note_blocked';
      end if;
      v_held := true;
    end if;
    if exists (
      select 1 from content.curator_note_versions as approved
      where approved.id = v_note.approved_version_id and approved.source_hash = v_packet.packet_hash
    ) and not v_override then
      raise sqlstate 'PT409' using message = 'curator_note_already_approved';
    end if;
  end if;

  select coalesce(max(version_number), 0) + 1 into v_version_number
  from content.curator_note_versions where exhibition_id = v_exhibition_id;

  insert into content.curator_note_versions (
    exhibition_id, version_number, status,
    hook_ko, hook_en, insights_ko, insights_en, tip_ko, tip_en,
    producer, model, prompt_version, source_fields, source_hash, source_version_id,
    request_kind, requested_by, generated_at, rejection_code
  ) values (
    v_exhibition_id, v_version_number, v_status::content.curator_note_status,
    coalesce(p_payload ->> 'hook_ko', ''), coalesce(p_payload ->> 'hook_en', ''),
    coalesce(p_payload -> 'insights_ko', '[]'::jsonb), coalesce(p_payload -> 'insights_en', '[]'::jsonb),
    coalesce(p_payload ->> 'tip_ko', ''), coalesce(p_payload ->> 'tip_en', ''),
    v_producer, p_payload ->> 'model', p_payload ->> 'prompt_version',
    v_source_fields, v_packet.packet_hash, v_packet.source_version_id,
    p_payload ->> 'request_kind', v_requested_by, v_generated_at,
    case when v_status = 'unusable' then p_payload ->> 'rejection_code' end
  )
  returning id into v_version_id;

  update content.curator_notes
  set last_source_hash = v_packet.packet_hash,
      auto_draft_blocked = case when v_held then false else auto_draft_blocked end,
      updated_at = clock_timestamp()
  where exhibition_id = v_exhibition_id;

  if v_held then
    perform content_private.record_curator_note_audit(
      v_requested_by, 'curator_note_unblocked', v_exhibition_id, v_version_id, jsonb_build_object('override', true)
    );
  end if;
  perform content_private.record_curator_note_audit(
    v_requested_by, 'curator_note_drafted', v_exhibition_id, v_version_id,
    jsonb_build_object(
      'status', v_status, 'request_kind', p_payload ->> 'request_kind', 'producer', v_producer, 'override', v_override,
      'model', p_payload ->> 'model', 'prompt_version', p_payload ->> 'prompt_version',
      'rejection_code', case when v_status = 'unusable' then p_payload ->> 'rejection_code' end
    )
  );
  return content_private.curator_note_version_json(v_version_id);
end;
$$;

comment on function public.service_store_curator_note_draft(jsonb) is
  'Service-only: stores a drafted (or unusable) curator note version with provenance derived from the live packet; refuses a changed packet or version, a requester below the publisher tier, and a draft over a decline or an approved note unless override is set by a requester.';

select cron.unschedule('curator_notes_daily')
where exists (select 1 from cron.job where jobname = 'curator_notes_daily');

commit;
