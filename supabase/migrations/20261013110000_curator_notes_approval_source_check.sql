-- Spec 090 follow-up: approval re-checks the source. A draft is written from the exhibition's packet at that
-- moment (source_hash); if the published text changes before a publisher approves it, the note would publish facts
-- written from text nobody can see any more. Approval now refuses such a draft (curator_note_source_changed), and
-- the review queue and every version carry a `stale` flag so Admin can show the state and disable Approve.
-- An exhibition that left the catalogue has no packet, so its drafts are stale too.
begin;

-- True when the version's packet is not the exhibition's current one.
create or replace function content_private.curator_note_version_is_stale(p_version_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    version.source_hash is distinct from (
      select packet.packet_hash from content_private.curator_note_packet(version.exhibition_id) as packet
    ),
    true
  )
  from content.curator_note_versions as version
  where version.id = p_version_id;
$$;
revoke all on function content_private.curator_note_version_is_stale(uuid) from public, anon, authenticated, service_role;

create or replace function content_private.curator_note_version_json(p_version_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'id', version.id,
    'exhibition_id', version.exhibition_id,
    'version_number', version.version_number,
    'status', version.status,
    'hook_ko', version.hook_ko,
    'hook_en', version.hook_en,
    'insights_ko', version.insights_ko,
    'insights_en', version.insights_en,
    'tip_ko', version.tip_ko,
    'tip_en', version.tip_en,
    'unsupported_refs', to_jsonb(version.unsupported_refs),
    'edited', version.edited,
    'producer', version.producer,
    'model', version.model,
    'prompt_version', version.prompt_version,
    'source_fields', to_jsonb(version.source_fields),
    'source_hash', version.source_hash,
    'source_version_id', version.source_version_id,
    'request_kind', version.request_kind,
    'generated_at', version.generated_at,
    'rejection_code', version.rejection_code,
    'reviewer_name_ko', version.reviewer_name_ko,
    'reviewer_name_en', version.reviewer_name_en,
    'decided_at', version.decided_at,
    'decline_reason', version.decline_reason,
    'decline_note', version.decline_note,
    'approved_at', version.approved_at,
    'created_at', version.created_at,
    'revision', version.updated_at,
    'stale', content_private.curator_note_version_is_stale(version.id)
  )
  from content.curator_note_versions as version
  where version.id = p_version_id;
$$;

create or replace function content_private.list_curator_note_queue_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform content_private.require_curator_note_staff();
  return (
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'version_id', version.id,
          'exhibition_id', version.exhibition_id,
          'name_ko', coalesce(catalog.name_ko, ''),
          'name_en', coalesce(catalog.name_en, ''),
          'is_featured', coalesce(catalog.is_featured, false),
          'closing_date', catalog.closing_date,
          'version_number', version.version_number,
          'producer', version.producer,
          'prompt_version', version.prompt_version,
          'generated_at', version.generated_at,
          'request_kind', version.request_kind,
          'unsupported_count', coalesce(array_length(version.unsupported_refs, 1), 0),
          'edited', version.edited,
          'stale', content_private.curator_note_version_is_stale(version.id),
          'revision', version.updated_at
        )
        order by coalesce(catalog.is_featured, false) desc, version.generated_at asc, version.id
      ),
      '[]'::jsonb
    )
    from content.curator_note_versions as version
    left join public.exhibition_catalog_v2 as catalog on catalog.id = version.exhibition_id
    where version.status = 'draft'
  );
end;
$$;

create or replace function content_private.approve_curator_note_impl(
  p_version_id uuid,
  p_expected_revision timestamptz
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_version content.curator_note_versions%rowtype
    := content_private.staff_curator_note_version_for_update(p_version_id, p_expected_revision);
  v_actor uuid := auth.uid();
  v_now timestamptz := clock_timestamp();
  v_name record;
  v_previous uuid;
begin
  if v_version.status <> 'draft' then
    raise sqlstate 'PT409' using message = 'curator_note_invalid_transition';
  end if;
  if coalesce(array_length(v_version.unsupported_refs, 1), 0) > 0 then
    raise sqlstate 'PT409' using message = 'curator_note_unsupported';
  end if;
  if btrim(v_version.hook_ko) = '' or btrim(v_version.tip_ko) = ''
     or jsonb_array_length(v_version.insights_ko) not between 2 and 4 then
    raise exception using errcode = '22023', message = 'curator_note_invalid_fields';
  end if;
  if content_private.curator_note_version_is_stale(p_version_id) then
    raise sqlstate 'PT409' using message = 'curator_note_source_changed';
  end if;
  select * into v_name from content_private.curator_note_reviewer_name(v_actor);
  select approved_version_id into v_previous from content.curator_notes
  where exhibition_id = v_version.exhibition_id for update;
  if v_previous is not null then
    update content.curator_note_versions
    set status = 'superseded', updated_at = v_now
    where id = v_previous and status = 'approved';
  end if;
  update content.curator_note_versions
  set status = 'approved', reviewed_by = v_actor, reviewer_name_ko = v_name.name_ko, reviewer_name_en = v_name.name_en,
      decided_at = v_now, approved_at = v_now, updated_at = v_now
  where id = p_version_id;
  update content.curator_notes
  set approved_version_id = p_version_id, auto_draft_blocked = false, updated_at = v_now
  where exhibition_id = v_version.exhibition_id;
  perform content_private.record_curator_note_audit(
    v_actor, 'curator_note_approved', v_version.exhibition_id, p_version_id,
    jsonb_build_object('superseded_version_id', v_previous, 'edited', v_version.edited, 'revision', p_expected_revision)
  );
  return content_private.curator_note_version_json(p_version_id);
end;
$$;

comment on function public.approve_curator_note(uuid, timestamptz) is
  'Publisher tier: publishes a draft under the reviewer''s name; refused while any sentence is marked unsupported or when the exhibition''s text changed since the draft was written.';

commit;
