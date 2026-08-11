-- Reviewable editor profile and curation workflow plus authenticated missing-
-- exhibition intake. Editor identity is always derived from membership.

alter table content.exhibition_submissions
  drop constraint if exists exhibition_submissions_source_check;
alter table content.exhibition_submissions
  add constraint exhibition_submissions_source_check
  check (source in ('public_form', 'owner_workspace', 'editor_workspace'));
alter table content.exhibition_submissions
  drop constraint if exists exhibition_submissions_owner_source_pair;
alter table content.exhibition_submissions
  add constraint exhibition_submissions_owner_source_pair
  check (
    (source = 'owner_workspace' and owner_exhibition_id is not null)
    or (source in ('public_form', 'editor_workspace') and owner_exhibition_id is null)
  );

create table content.editor_requests (
  id uuid primary key default gen_random_uuid(),
  editor_id text not null references public.editors(id) on delete restrict,
  requested_by uuid not null references auth.users(id) on delete restrict,
  kind text not null check (kind in ('profile', 'curation')),
  status text not null default 'submitted'
    check (status in ('submitted', 'accepted', 'rejected')),
  payload jsonb not null check (jsonb_typeof(payload) = 'object'),
  reviewed_by uuid references auth.users(id) on delete set null,
  review_notes text,
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint editor_requests_review_state check (
    (status = 'submitted' and reviewed_at is null and reviewed_by is null)
    or (status in ('accepted', 'rejected') and reviewed_at is not null and reviewed_by is not null)
  ),
  constraint editor_requests_review_notes_length check (
    review_notes is null or length(review_notes) <= 2000
  )
);

alter table content.editor_requests enable row level security;
revoke all on content.editor_requests from public, anon, authenticated, service_role;
create unique index editor_requests_one_open_kind_idx
  on content.editor_requests (editor_id, kind)
  where status = 'submitted';
create index editor_requests_review_queue_idx
  on content.editor_requests (status, created_at desc);
create trigger editor_requests_set_updated_at
  before update on content.editor_requests
  for each row execute function content_private.set_updated_at();

create or replace function content_private.editor_request_json(p_request_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select jsonb_build_object(
    'id', request.id,
    'editor_id', request.editor_id,
    'editor_name', coalesce(nullif(editor.name_en, ''), editor.name_ko),
    'kind', request.kind,
    'status', request.status,
    'payload', request.payload,
    'review_notes', coalesce(request.review_notes, ''),
    'created_at', request.created_at
  )
  from content.editor_requests as request
  join public.editors as editor on editor.id = request.editor_id
  where request.id = p_request_id;
$function$;

revoke all on function content_private.editor_request_json(uuid)
  from public, anon, authenticated, service_role;

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
    'pending_profile', exists (
      select 1 from content.editor_requests as request
      where request.editor_id = editor.id
        and request.kind = 'profile'
        and request.status = 'submitted'
    )
  )
  into v_result
  from public.editors as editor
  where editor.id = v_editor_id;
  return v_result;
end;
$function$;

create or replace function content_private.editor_submit_profile_impl(
  p_bio_ko text,
  p_bio_en text
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
  v_bio_ko text := btrim(coalesce(p_bio_ko, ''));
  v_bio_en text := btrim(coalesce(p_bio_en, ''));
  v_request_id uuid;
begin
  if v_bio_ko = '' or length(v_bio_ko) > 4000 or length(v_bio_en) > 4000 then
    raise exception using errcode = '22023', message = 'editor_bio_invalid';
  end if;
  if exists (
    select 1 from content.editor_requests
    where editor_id = v_editor_id and kind = 'profile' and status = 'submitted'
  ) then
    raise exception using errcode = '23505', message = 'editor_profile_request_pending';
  end if;
  insert into content.editor_requests (editor_id, requested_by, kind, payload)
  values (
    v_editor_id, v_user_id, 'profile',
    jsonb_build_object('bio_ko', v_bio_ko, 'bio_en', v_bio_en)
  )
  returning id into v_request_id;
  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    v_user_id, 'editor.profile_submitted', 'editor_request', v_request_id::text,
    jsonb_build_object('editor_id', v_editor_id)
  );
  return jsonb_build_object('request_id', v_request_id, 'status', 'submitted');
end;
$function$;

-- Ongoing means opened on or before today and closing on or after today.
create or replace function content_private.editor_list_pick_candidates_impl(
  p_search text default ''
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_editor_id text := content_private.editor_assert_membership();
  v_search text := lower(btrim(coalesce(p_search, '')));
begin
  return query
  select content_private.editor_pick_json(exhibition.id, chosen.id, v_editor_id)
  from content.exhibitions as exhibition
  join content.exhibition_versions as published
    on published.exhibition_id = exhibition.id
   and published.id = exhibition.published_version_id
  join lateral (
    select candidate.*
    from content.exhibition_versions as candidate
    where candidate.exhibition_id = exhibition.id
      and (candidate.status = 'draft'::content.exhibition_version_status
        or candidate.id = exhibition.published_version_id)
    order by (candidate.status = 'draft'::content.exhibition_version_status) desc,
      candidate.version_number desc
    limit 1
  ) as chosen on true
  where exhibition.published_version_id is not null
    and exhibition.archived_at is null
    and published.opening_date <= current_date
    and published.closing_date >= current_date
    and (chosen.editor_id is null or chosen.editor_id = v_editor_id)
    and (published.editor_id is null or published.editor_id = v_editor_id)
    and (
      v_search = '' or position(v_search in lower(concat_ws(
        ' ', exhibition.id, chosen.name_ko, chosen.name_en,
        chosen.venue_name_ko, chosen.venue_name_en
      ))) > 0
    )
  order by (chosen.editor_id = v_editor_id) desc,
    chosen.closing_date, chosen.name_ko, exhibition.id;
end;
$function$;

create or replace function content_private.editor_submit_curation_impl(p_changes jsonb)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := (select auth.uid());
  v_editor_id text := content_private.editor_assert_membership();
  v_request_id uuid;
  v_item jsonb;
  v_result jsonb;
  v_results jsonb := '[]'::jsonb;
  v_previous_editor_id text;
begin
  if p_changes is null or jsonb_typeof(p_changes) <> 'array'
     or jsonb_array_length(p_changes) < 1
     or jsonb_array_length(p_changes) > 100 then
    raise exception using errcode = '22023', message = 'curation_changes_invalid';
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
  values (v_editor_id, v_user_id, 'curation', jsonb_build_object('changes', v_results))
  returning id into v_request_id;
  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    v_user_id, 'editor.curation_submitted', 'editor_request', v_request_id::text,
    jsonb_build_object('editor_id', v_editor_id, 'change_count', jsonb_array_length(v_results))
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

create or replace function content_private.editor_submit_exhibition_impl(p_payload jsonb)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := (select auth.uid());
  v_editor_id text := content_private.editor_assert_membership();
  v_email text;
  v_submission_id uuid := gen_random_uuid();
  v_payload jsonb;
begin
  if p_payload is null or jsonb_typeof(p_payload) <> 'object' then
    raise exception using errcode = '22023', message = 'submission_payload_invalid';
  end if;
  if nullif(btrim(p_payload ->> 'name_ko'), '') is null
     or nullif(btrim(p_payload ->> 'venue_name_ko'), '') is null
     or nullif(btrim(p_payload ->> 'opening_date'), '') is null
     or nullif(btrim(p_payload ->> 'closing_date'), '') is null
     or nullif(btrim(p_payload ->> 'address_ko'), '') is null
     or nullif(btrim(p_payload ->> 'hours'), '') is null then
    raise exception using errcode = '22023', message = 'submission_required_field_invalid';
  end if;
  if (p_payload ->> 'opening_date')::date > (p_payload ->> 'closing_date')::date then
    raise exception using errcode = '22023', message = 'submission_date_order_invalid';
  end if;
  v_payload := jsonb_build_object(
    'name_ko', left(btrim(p_payload ->> 'name_ko'), 300),
    'name_en', left(btrim(coalesce(p_payload ->> 'name_en', '')), 300),
    'venue_name_ko', left(btrim(p_payload ->> 'venue_name_ko'), 300),
    'venue_name_en', left(btrim(coalesce(p_payload ->> 'venue_name_en', '')), 300),
    'opening_date', p_payload ->> 'opening_date',
    'closing_date', p_payload ->> 'closing_date',
    'address_ko', left(btrim(p_payload ->> 'address_ko'), 500),
    'address_en', left(btrim(coalesce(p_payload ->> 'address_en', '')), 500),
    'hours', left(btrim(p_payload ->> 'hours'), 1000),
    'description_ko', left(btrim(coalesce(p_payload ->> 'description_ko', '')), 20000),
    'description_en', left(btrim(coalesce(p_payload ->> 'description_en', '')), 20000),
    'reception_date', '', 'reception_end', '', 'editor_id', v_editor_id
  );
  select lower(email) into v_email from auth.users where id = v_user_id;
  insert into content.exhibition_submissions (
    id, status, submitter_email, payload, source, submitted_at
  ) values (
    v_submission_id, 'submitted', v_email, v_payload, 'editor_workspace', now()
  );
  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    v_user_id, 'editor.exhibition_submitted', 'exhibition_submission', v_submission_id::text,
    jsonb_build_object('editor_id', v_editor_id)
  );
  return jsonb_build_object('submission_id', v_submission_id, 'status', 'submitted');
exception when invalid_datetime_format or datetime_field_overflow then
  raise exception using errcode = '22023', message = 'submission_dates_invalid';
end;
$function$;

create or replace function content_private.admin_list_editor_requests_impl(
  p_status text default 'submitted'
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  perform content_private.admin_assert_staff('admin'::content.staff_role);
  if p_status not in ('submitted', 'accepted', 'rejected') then
    raise exception using errcode = '22023', message = 'editor_request_status_invalid';
  end if;
  return query
  select content_private.editor_request_json(request.id)
  from content.editor_requests as request
  where request.status = p_status
  order by request.created_at desc, request.id;
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

-- Preserve the existing public/owner acceptance implementation behind a new
-- dispatcher that adds editor-attribution handling.
alter function content_private.admin_accept_submission_impl(uuid)
  rename to admin_accept_submission_pre_editor_impl;

create or replace function content_private.admin_accept_submission_impl(
  p_submission_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_admin_id uuid := content_private.admin_assert_staff('publisher'::content.staff_role);
  v_submission content.exhibition_submissions%rowtype;
  v_response jsonb;
  v_exhibition_id text;
  v_version_id uuid;
begin
  select submission.* into v_submission
  from content.exhibition_submissions as submission
  where submission.id = p_submission_id;
  if not found then
    raise exception using errcode = 'P0002', message = 'submission_not_found';
  end if;
  if v_submission.source <> 'editor_workspace' then
    return content_private.admin_accept_submission_pre_editor_impl(p_submission_id);
  end if;
  if not exists (
    select 1 from public.editors where id = v_submission.payload ->> 'editor_id'
  ) then
    raise exception using errcode = '22023', message = 'submission_editor_invalid';
  end if;
  v_response := content_private.admin_accept_public_submission_impl(p_submission_id);
  v_exhibition_id := v_response -> 'exhibition' ->> 'id';
  v_version_id := (v_response -> 'exhibition' ->> 'working_version_id')::uuid;
  update content.exhibition_versions
  set editor_id = v_submission.payload ->> 'editor_id', revision = revision + 1,
      updated_by = v_admin_id
  where id = v_version_id and exhibition_id = v_exhibition_id
    and status = 'draft'::content.exhibition_version_status;
  insert into content.audit_log (actor_user_id, action, entity_type, entity_id, metadata)
  values (
    v_admin_id, 'editor.exhibition_attributed', 'exhibition', v_exhibition_id,
    jsonb_build_object('editor_id', v_submission.payload ->> 'editor_id', 'submission_id', p_submission_id)
  );
  return jsonb_build_object(
    'submission', content_private.admin_submission_json(p_submission_id),
    'exhibition', content_private.admin_exhibition_json(v_exhibition_id, v_version_id)
  );
end;
$function$;

create or replace function content_private.admin_accept_submission_idempotent_impl(
  p_submission_id uuid,
  p_request_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := content_private.admin_assert_staff('publisher'::content.staff_role);
  v_fingerprint text;
  v_is_replay boolean;
  v_stored_response jsonb;
  v_response jsonb;
begin
  v_fingerprint := content_private.command_request_fingerprint(
    jsonb_build_object('submission_id', p_submission_id)
  );
  select request.is_replay, request.stored_response
  into v_is_replay, v_stored_response
  from content_private.begin_command_request(
    v_user_id, p_request_id, 'admin_accept_exhibition_submission', v_fingerprint
  ) as request;
  if v_is_replay then return v_stored_response; end if;
  perform set_config('app.command_request_id', p_request_id::text, true);
  perform set_config('app.command_actor_id', v_user_id::text, true);
  v_response := content_private.admin_accept_submission_impl(p_submission_id);
  perform set_config('app.command_request_id', '', true);
  perform set_config('app.command_actor_id', '', true);
  return content_private.complete_command_request(
    v_user_id, p_request_id, 'admin_accept_exhibition_submission',
    v_fingerprint, v_response
  );
end;
$function$;

revoke all on function content_private.editor_get_profile_impl()
  from public, anon, authenticated, service_role;
revoke all on function content_private.editor_submit_profile_impl(text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.editor_submit_curation_impl(jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.editor_submit_exhibition_impl(jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_list_editor_requests_impl(text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_review_editor_request_impl(uuid, boolean, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_accept_submission_pre_editor_impl(uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_accept_submission_impl(uuid)
  from public, anon, authenticated, service_role;
revoke all on function content_private.admin_accept_submission_idempotent_impl(uuid, uuid)
  from public, anon, authenticated, service_role;

grant execute on function content_private.editor_get_profile_impl(),
  content_private.editor_submit_profile_impl(text, text),
  content_private.editor_submit_curation_impl(jsonb),
  content_private.editor_submit_exhibition_impl(jsonb),
  content_private.admin_list_editor_requests_impl(text),
  content_private.admin_review_editor_request_impl(uuid, boolean, text),
  content_private.admin_accept_submission_pre_editor_impl(uuid),
  content_private.admin_accept_submission_impl(uuid),
  content_private.admin_accept_submission_idempotent_impl(uuid, uuid)
  to authenticated;

create or replace function public.editor_get_profile()
returns jsonb language sql stable security invoker set search_path = ''
as $function$ select content_private.editor_get_profile_impl(); $function$;
create or replace function public.editor_submit_profile(p_bio_ko text, p_bio_en text)
returns jsonb language sql volatile security invoker set search_path = ''
as $function$ select content_private.editor_submit_profile_impl(p_bio_ko, p_bio_en); $function$;
create or replace function public.editor_submit_curation(p_changes jsonb)
returns jsonb language sql volatile security invoker set search_path = ''
as $function$ select content_private.editor_submit_curation_impl(p_changes); $function$;
create or replace function public.editor_submit_exhibition(p_payload jsonb)
returns jsonb language sql volatile security invoker set search_path = ''
as $function$ select content_private.editor_submit_exhibition_impl(p_payload); $function$;
create or replace function public.admin_list_editor_requests(p_status text default 'submitted')
returns setof jsonb language sql stable security invoker set search_path = ''
as $function$ select * from content_private.admin_list_editor_requests_impl(p_status); $function$;
create or replace function public.admin_review_editor_request(
  p_request_id uuid, p_approve boolean, p_review_notes text default ''
)
returns jsonb language sql volatile security invoker set search_path = ''
as $function$
  select content_private.admin_review_editor_request_impl(p_request_id, p_approve, p_review_notes);
$function$;

revoke all on function public.editor_get_profile(),
  public.editor_submit_profile(text, text),
  public.editor_submit_curation(jsonb),
  public.editor_submit_exhibition(jsonb),
  public.admin_list_editor_requests(text),
  public.admin_review_editor_request(uuid, boolean, text)
  from public, anon, authenticated, service_role;
grant execute on function public.editor_get_profile(),
  public.editor_submit_profile(text, text),
  public.editor_submit_curation(jsonb),
  public.editor_submit_exhibition(jsonb),
  public.admin_list_editor_requests(text),
  public.admin_review_editor_request(uuid, boolean, text)
  to authenticated;
