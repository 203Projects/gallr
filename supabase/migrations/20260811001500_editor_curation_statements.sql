-- Separate the public collection statement from the editor's personal bio.
-- Existing copy is preserved by backfilling from bio; all new mutations stay
-- behind membership-derived editor and admin RPC boundaries.

alter table public.editors
  add column curation_description_ko text not null default '',
  add column curation_description_en text not null default '';

update public.editors
set
  curation_description_ko = bio_ko,
  curation_description_en = bio_en;

alter table public.editors
  add constraint editors_curation_description_length check (
    length(curation_description_ko) <= 4000
    and length(curation_description_en) <= 4000
  );

create or replace function content_private.editor_get_profile_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_editor_id text := content_private.editor_assert_membership();
  v_result jsonb;
begin
  select jsonb_build_object(
    'editor_id', editor.id,
    'name_ko', editor.name_ko,
    'name_en', editor.name_en,
    'bio_ko', editor.bio_ko,
    'bio_en', editor.bio_en,
    'curation_description_ko', editor.curation_description_ko,
    'curation_description_en', editor.curation_description_en,
    'pending_profile', exists (
      select 1 from content.editor_requests as request
      where request.editor_id = editor.id
        and request.kind = 'profile'
        and request.status = 'submitted'
    ),
    'pending_curation', exists (
      select 1 from content.editor_requests as request
      where request.editor_id = editor.id
        and request.kind = 'curation'
        and request.status = 'submitted'
    )
  )
  into v_result
  from public.editors as editor
  where editor.id = v_editor_id;
  return v_result;
end;
$function$;

create or replace function content_private.editor_submit_curation_with_statement_impl(
  p_changes jsonb,
  p_curation_description_ko text,
  p_curation_description_en text
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := (select auth.uid());
  v_editor_id text := content_private.editor_assert_membership();
  v_curation_description_ko text := btrim(coalesce(p_curation_description_ko, ''));
  v_curation_description_en text := btrim(coalesce(p_curation_description_en, ''));
  v_statement_changed boolean;
  v_request_id uuid;
  v_item jsonb;
  v_result jsonb;
  v_results jsonb := '[]'::jsonb;
  v_previous_editor_id text;
begin
  if p_changes is null or jsonb_typeof(p_changes) <> 'array'
     or jsonb_array_length(p_changes) > 100 then
    raise exception using errcode = '22023', message = 'curation_changes_invalid';
  end if;
  if v_curation_description_ko = ''
     or length(v_curation_description_ko) > 4000
     or length(v_curation_description_en) > 4000 then
    raise exception using errcode = '22023', message = 'curation_description_invalid';
  end if;
  select
    editor.curation_description_ko is distinct from v_curation_description_ko
    or editor.curation_description_en is distinct from v_curation_description_en
  into v_statement_changed
  from public.editors as editor
  where editor.id = v_editor_id;
  if jsonb_array_length(p_changes) = 0 and not v_statement_changed then
    raise exception using errcode = '22023', message = 'curation_request_has_no_changes';
  end if;
  if exists (
    select 1 from content.editor_requests
    where editor_id = v_editor_id and kind = 'curation' and status = 'submitted'
  ) then
    raise exception using errcode = '23505', message = 'editor_curation_request_pending';
  end if;
  for v_item in select value from jsonb_array_elements(p_changes)
  loop
    if jsonb_typeof(v_item) <> 'object'
       or not (v_item ?& array['exhibition_id', 'expected_version_id', 'expected_revision', 'selected'])
       or (select count(*) from jsonb_object_keys(v_item)) <> 4
       or jsonb_typeof(v_item -> 'selected') <> 'boolean'
       or jsonb_typeof(v_item -> 'expected_revision') <> 'number' then
      raise exception using errcode = '22023', message = 'curation_change_invalid';
    end if;
    select version.editor_id into v_previous_editor_id
    from content.exhibition_versions as version
    where version.id = (v_item ->> 'expected_version_id')::uuid
      and version.exhibition_id = v_item ->> 'exhibition_id';
    v_result := content_private.editor_set_pick_impl(
      v_item ->> 'exhibition_id',
      (v_item ->> 'expected_version_id')::uuid,
      (v_item ->> 'expected_revision')::integer,
      (v_item ->> 'selected')::boolean
    );
    v_results := v_results || jsonb_build_array(
      v_result || jsonb_build_object('previous_editor_id', v_previous_editor_id)
    );
  end loop;
  insert into content.editor_requests (editor_id, requested_by, kind, payload)
  values (
    v_editor_id,
    v_user_id,
    'curation',
    jsonb_build_object(
      'changes', v_results,
      'curation_description_ko', v_curation_description_ko,
      'curation_description_en', v_curation_description_en
    )
  )
  returning id into v_request_id;
  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    v_user_id,
    'editor.curation_submitted',
    'editor_request',
    v_request_id::text,
    jsonb_build_object(
      'editor_id', v_editor_id,
      'change_count', jsonb_array_length(v_results),
      'statement_changed', v_statement_changed
    )
  );
  return jsonb_build_object(
    'request_id', v_request_id,
    'status', 'submitted',
    'kind', 'curation',
    'candidates', v_results
  );
exception when invalid_text_representation then
  raise exception using errcode = '22023', message = 'curation_change_invalid';
end;
$function$;

-- Retain the original one-argument contract for already-shipped clients.
create or replace function content_private.editor_submit_curation_impl(p_changes jsonb)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_editor_id text := content_private.editor_assert_membership();
  v_editor public.editors%rowtype;
begin
  if p_changes is null or jsonb_typeof(p_changes) <> 'array'
     or jsonb_array_length(p_changes) < 1 then
    raise exception using errcode = '22023', message = 'curation_changes_invalid';
  end if;
  select editor.* into strict v_editor
  from public.editors as editor
  where editor.id = v_editor_id;
  return content_private.editor_submit_curation_with_statement_impl(
    p_changes,
    coalesce(nullif(v_editor.curation_description_ko, ''), v_editor.bio_ko),
    case
      when v_editor.curation_description_ko = '' then v_editor.bio_en
      else v_editor.curation_description_en
    end
  );
end;
$function$;

create or replace function content_private.admin_review_editor_request_impl(
  p_request_id uuid,
  p_approve boolean,
  p_review_notes text default ''
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_admin_id uuid := content_private.admin_assert_staff('admin'::content.staff_role);
  v_request content.editor_requests%rowtype;
  v_change jsonb;
  v_notes text := btrim(coalesce(p_review_notes, ''));
begin
  if p_approve is null then
    raise exception using errcode = '22023', message = 'review_decision_required';
  end if;
  if not p_approve and (v_notes = '' or length(v_notes) > 2000) then
    raise exception using errcode = '22023', message = 'review_notes_required';
  end if;
  select request.* into v_request
  from content.editor_requests as request
  where request.id = p_request_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'editor_request_not_found';
  end if;
  if v_request.status <> 'submitted' then
    raise exception using errcode = '22023', message = 'editor_request_not_reviewable';
  end if;

  if p_approve and v_request.kind = 'profile' then
    update public.editors
    set bio_ko = v_request.payload ->> 'bio_ko', bio_en = v_request.payload ->> 'bio_en'
    where id = v_request.editor_id;
  elsif v_request.kind = 'curation' then
    if p_approve and v_request.payload ? 'curation_description_ko' then
      update public.editors
      set
        curation_description_ko = v_request.payload ->> 'curation_description_ko',
        curation_description_en = coalesce(v_request.payload ->> 'curation_description_en', '')
      where id = v_request.editor_id;
    end if;
    for v_change in select value from jsonb_array_elements(v_request.payload -> 'changes')
    loop
      if p_approve then
        perform content_private.admin_publish_exhibition_impl(
          v_change ->> 'id',
          (v_change ->> 'working_version_id')::uuid,
          (v_change ->> 'revision')::integer
        );
      else
        update content.exhibition_versions as version
        set
          editor_id = v_change ->> 'previous_editor_id',
          revision = version.revision + 1,
          updated_by = v_admin_id
        where version.id = (v_change ->> 'working_version_id')::uuid
          and version.exhibition_id = v_change ->> 'id'
          and version.status = 'draft'::content.exhibition_version_status
          and version.revision = (v_change ->> 'revision')::integer;
        if not found then
          raise exception using errcode = '40001', message = 'revision_conflict';
        end if;
      end if;
    end loop;
  end if;

  update content.editor_requests
  set
    status = case when p_approve then 'accepted' else 'rejected' end,
    reviewed_by = v_admin_id,
    review_notes = nullif(v_notes, ''),
    reviewed_at = now()
  where id = p_request_id;
  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    v_admin_id,
    case when p_approve then 'editor.request_accepted' else 'editor.request_rejected' end,
    'editor_request', p_request_id::text,
    jsonb_build_object('editor_id', v_request.editor_id, 'kind', v_request.kind)
  );
  return content_private.editor_request_json(p_request_id);
end;
$function$;

create or replace function content_private.admin_create_editor_onboarding_with_statement_impl(
  p_user_id uuid,
  p_editor_id text,
  p_name_ko text,
  p_name_en text,
  p_title_ko text,
  p_title_en text,
  p_bio_ko text,
  p_bio_en text,
  p_curation_description_ko text,
  p_curation_description_en text,
  p_is_active boolean,
  p_active_from date,
  p_active_to date default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_actor_user_id uuid := (select auth.uid());
  v_editor_id text := btrim(coalesce(p_editor_id, ''));
  v_name_ko text := btrim(coalesce(p_name_ko, ''));
  v_name_en text := btrim(coalesce(p_name_en, ''));
  v_title_ko text := btrim(coalesce(p_title_ko, ''));
  v_title_en text := btrim(coalesce(p_title_en, ''));
  v_bio_ko text := btrim(coalesce(p_bio_ko, ''));
  v_bio_en text := btrim(coalesce(p_bio_en, ''));
  v_curation_description_ko text := btrim(coalesce(p_curation_description_ko, ''));
  v_curation_description_en text := btrim(coalesce(p_curation_description_en, ''));
begin
  if v_actor_user_id is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if not exists (
    select 1 from content.staff_members as staff
    where staff.user_id = v_actor_user_id
      and staff.active
      and staff.role = 'admin'::content.staff_role
  ) then
    raise exception using errcode = '42501', message = 'admin_role_required';
  end if;
  if p_user_id is null or not exists (
    select 1 from auth.users as invited where invited.id = p_user_id
  ) then
    raise exception using errcode = '22023', message = 'invited_user_required';
  end if;
  if exists (select 1 from content.staff_members as staff where staff.user_id = p_user_id) then
    raise exception using errcode = '23514', message = 'editor_account_conflicts_with_staff';
  end if;
  if v_editor_id !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
     or length(v_editor_id) < 3 or length(v_editor_id) > 64 then
    raise exception using errcode = '22023', message = 'editor_slug_invalid';
  end if;
  if v_name_ko = '' or length(v_name_ko) > 120
     or length(v_name_en) > 120
     or v_title_ko = '' or length(v_title_ko) > 160
     or length(v_title_en) > 160
     or v_bio_ko = '' or length(v_bio_ko) > 4000
     or length(v_bio_en) > 4000
     or v_curation_description_ko = '' or length(v_curation_description_ko) > 4000
     or length(v_curation_description_en) > 4000 then
    raise exception using errcode = '22023', message = 'editor_profile_invalid';
  end if;
  if p_is_active is null or p_active_from is null then
    raise exception using errcode = '22023', message = 'editor_schedule_required';
  end if;
  if p_active_to is not null and p_active_to < p_active_from then
    raise exception using errcode = '22023', message = 'editor_date_range_invalid';
  end if;

  insert into public.editors (
    id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
    curation_description_ko, curation_description_en,
    is_active, active_from, active_to
  )
  values (
    v_editor_id, v_name_ko, v_name_en, v_title_ko, v_title_en,
    v_bio_ko, v_bio_en, v_curation_description_ko, v_curation_description_en,
    p_is_active, p_active_from, p_active_to
  );
  insert into content.editor_memberships (
    user_id, editor_id, active, created_by, updated_by
  )
  values (p_user_id, v_editor_id, true, v_actor_user_id, v_actor_user_id);
  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  )
  values (
    v_actor_user_id, 'editor.created', 'editor', v_editor_id,
    jsonb_build_object(
      'editor_user_id', p_user_id,
      'is_active', p_is_active,
      'active_from', p_active_from,
      'active_to', p_active_to
    )
  );
  return jsonb_build_object(
    'editor_id', v_editor_id,
    'name_ko', v_name_ko,
    'name_en', v_name_en,
    'is_active', p_is_active
  );
end;
$function$;

-- Keep the old onboarding contract functional by using the existing bio as
-- the initial statement when an older caller has no distinct field.
create or replace function content_private.admin_create_editor_onboarding_impl(
  p_user_id uuid,
  p_editor_id text,
  p_name_ko text,
  p_name_en text,
  p_title_ko text,
  p_title_en text,
  p_bio_ko text,
  p_bio_en text,
  p_is_active boolean,
  p_active_from date,
  p_active_to date default null
)
returns jsonb
language sql
volatile
security definer
set search_path = ''
as $function$
  select content_private.admin_create_editor_onboarding_with_statement_impl(
    p_user_id, p_editor_id, p_name_ko, p_name_en, p_title_ko, p_title_en,
    p_bio_ko, p_bio_en, p_bio_ko, p_bio_en, p_is_active, p_active_from, p_active_to
  );
$function$;

create or replace function public.editor_submit_curation(
  p_changes jsonb,
  p_curation_description_ko text,
  p_curation_description_en text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.editor_submit_curation_with_statement_impl(
    p_changes, p_curation_description_ko, p_curation_description_en
  );
$function$;

create or replace function public.admin_create_editor_onboarding(
  p_user_id uuid,
  p_editor_id text,
  p_name_ko text,
  p_name_en text,
  p_title_ko text,
  p_title_en text,
  p_bio_ko text,
  p_bio_en text,
  p_curation_description_ko text,
  p_curation_description_en text,
  p_is_active boolean,
  p_active_from date,
  p_active_to date default null
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.admin_create_editor_onboarding_with_statement_impl(
    p_user_id, p_editor_id, p_name_ko, p_name_en, p_title_ko, p_title_en,
    p_bio_ko, p_bio_en, p_curation_description_ko, p_curation_description_en,
    p_is_active, p_active_from, p_active_to
  );
$function$;

revoke all on function content_private.editor_submit_curation_with_statement_impl(jsonb, text, text),
  content_private.admin_create_editor_onboarding_with_statement_impl(
    uuid, text, text, text, text, text, text, text, text, text, boolean, date, date
  )
  from public, anon, authenticated, service_role;
grant execute on function content_private.editor_submit_curation_with_statement_impl(jsonb, text, text),
  content_private.admin_create_editor_onboarding_with_statement_impl(
    uuid, text, text, text, text, text, text, text, text, text, boolean, date, date
  )
  to authenticated;

revoke all on function public.editor_submit_curation(jsonb, text, text),
  public.admin_create_editor_onboarding(
    uuid, text, text, text, text, text, text, text, text, text, boolean, date, date
  )
  from public, anon, authenticated, service_role;
grant execute on function public.editor_submit_curation(jsonb, text, text),
  public.admin_create_editor_onboarding(
    uuid, text, text, text, text, text, text, text, text, text, boolean, date, date
  )
  to authenticated;
