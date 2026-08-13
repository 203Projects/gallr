-- Admin editor directory and reversible account management. Editor identities
-- remain durable because exhibitions, requests, and audit history reference
-- them; "remove" therefore deactivates access instead of deleting records.

alter table public.editors
  add column if not exists revision integer not null default 1
    check (revision > 0);

create or replace function content_private.editors_increment_revision()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $function$
begin
  new.revision := old.revision + 1;
  return new;
end;
$function$;

revoke all on function content_private.editors_increment_revision()
  from public, anon, authenticated, service_role;

drop trigger if exists editors_increment_revision on public.editors;
create trigger editors_increment_revision
  before update on public.editors
  for each row execute function content_private.editors_increment_revision();

create or replace function content_private.admin_editor_json(p_editor_id text)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select jsonb_build_object(
    'editor_id', editor.id,
    'email', invited.email,
    'name_ko', editor.name_ko,
    'name_en', editor.name_en,
    'title_ko', editor.title_ko,
    'title_en', editor.title_en,
    'bio_ko', editor.bio_ko,
    'bio_en', editor.bio_en,
    'curation_description_ko', editor.curation_description_ko,
    'curation_description_en', editor.curation_description_en,
    'is_active', editor.is_active,
    'active_from', to_char(editor.active_from, 'YYYY-MM-DD'),
    'active_to', case
      when editor.active_to is null then null
      else to_char(editor.active_to, 'YYYY-MM-DD')
    end,
    'revision', editor.revision,
    'has_access', membership.user_id is not null,
    'access_active', coalesce(membership.active, false)
  )
  from public.editors as editor
  left join content.editor_memberships as membership
    on membership.editor_id = editor.id
  left join auth.users as invited
    on invited.id = membership.user_id
  where editor.id = p_editor_id;
$function$;

create or replace function content_private.admin_list_editors_impl()
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
begin
  perform content_private.admin_assert_staff('admin'::content.staff_role);
  return query
  select content_private.admin_editor_json(editor.id)
  from public.editors as editor
  order by
    editor.is_active desc,
    editor.active_from desc,
    coalesce(nullif(editor.name_en, ''), editor.name_ko),
    editor.id;
end;
$function$;

create or replace function content_private.admin_update_editor_impl(
  p_editor_id text,
  p_expected_revision integer,
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
  v_actor_user_id uuid := content_private.admin_assert_staff('admin'::content.staff_role);
  v_editor public.editors%rowtype;
  v_name_ko text := btrim(coalesce(p_name_ko, ''));
  v_name_en text := btrim(coalesce(p_name_en, ''));
  v_title_ko text := btrim(coalesce(p_title_ko, ''));
  v_title_en text := btrim(coalesce(p_title_en, ''));
  v_bio_ko text := btrim(coalesce(p_bio_ko, ''));
  v_bio_en text := btrim(coalesce(p_bio_en, ''));
  v_curation_description_ko text := btrim(coalesce(p_curation_description_ko, ''));
  v_curation_description_en text := btrim(coalesce(p_curation_description_en, ''));
begin
  if p_expected_revision is null or p_expected_revision < 1 then
    raise exception using
      errcode = '22023', message = 'expected_revision_must_be_positive';
  end if;
  if v_name_ko = '' or length(v_name_ko) > 120
     or length(v_name_en) > 120
     or v_title_ko = '' or length(v_title_ko) > 160
     or length(v_title_en) > 160
     or v_bio_ko = '' or length(v_bio_ko) > 4000
     or length(v_bio_en) > 4000
     or v_curation_description_ko = ''
     or length(v_curation_description_ko) > 4000
     or length(v_curation_description_en) > 4000 then
    raise exception using errcode = '22023', message = 'editor_profile_invalid';
  end if;
  if p_is_active is null or p_active_from is null then
    raise exception using errcode = '22023', message = 'editor_schedule_required';
  end if;
  if p_active_to is not null and p_active_to < p_active_from then
    raise exception using errcode = '22023', message = 'editor_date_range_invalid';
  end if;

  select editor.*
  into v_editor
  from public.editors as editor
  where editor.id = p_editor_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'editor_not_found';
  end if;
  if v_editor.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_editor.revision::text;
  end if;

  update public.editors
  set
    name_ko = v_name_ko,
    name_en = v_name_en,
    title_ko = v_title_ko,
    title_en = v_title_en,
    bio_ko = v_bio_ko,
    bio_en = v_bio_en,
    curation_description_ko = v_curation_description_ko,
    curation_description_en = v_curation_description_en,
    is_active = p_is_active,
    active_from = p_active_from,
    active_to = p_active_to,
    revision = revision + 1,
    updated_at = now()
  where id = v_editor.id;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  )
  values (
    v_actor_user_id,
    'editor.updated',
    'editor',
    v_editor.id,
    jsonb_build_object(
      'previous_revision', v_editor.revision,
      'revision', v_editor.revision + 1,
      'is_active', p_is_active,
      'active_from', p_active_from,
      'active_to', p_active_to
    )
  );

  return content_private.admin_editor_json(v_editor.id);
end;
$function$;

create or replace function content_private.admin_set_editor_access_impl(
  p_editor_id text,
  p_expected_revision integer,
  p_active boolean
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_actor_user_id uuid := content_private.admin_assert_staff('admin'::content.staff_role);
  v_editor public.editors%rowtype;
  v_membership content.editor_memberships%rowtype;
begin
  if p_expected_revision is null or p_expected_revision < 1 then
    raise exception using
      errcode = '22023', message = 'expected_revision_must_be_positive';
  end if;
  if p_active is null then
    raise exception using errcode = '22023', message = 'active_state_required';
  end if;

  select editor.*
  into v_editor
  from public.editors as editor
  where editor.id = p_editor_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'editor_not_found';
  end if;
  if v_editor.revision <> p_expected_revision then
    raise exception using
      errcode = '40001',
      message = 'revision_conflict',
      detail = v_editor.revision::text;
  end if;

  select membership.*
  into v_membership
  from content.editor_memberships as membership
  where membership.editor_id = v_editor.id
  for update;

  if not found then
    raise exception using
      errcode = 'P0002', message = 'editor_membership_not_found';
  end if;

  update content.editor_memberships
  set
    active = p_active,
    updated_by = v_actor_user_id
  where user_id = v_membership.user_id;

  update public.editors
  set
    is_active = case when p_active then is_active else false end,
    revision = revision + 1,
    updated_at = now()
  where id = v_editor.id;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  )
  values (
    v_actor_user_id,
    case
      when p_active then 'editor.access_restored'
      else 'editor.access_deactivated'
    end,
    'editor',
    v_editor.id,
    jsonb_build_object(
      'previous_revision', v_editor.revision,
      'revision', v_editor.revision + 1,
      'access_active', p_active,
      'profile_active', case when p_active then v_editor.is_active else false end
    )
  );

  return content_private.admin_editor_json(v_editor.id);
end;
$function$;

revoke all on function content_private.admin_editor_json(text),
  content_private.editors_increment_revision(),
  content_private.admin_list_editors_impl(),
  content_private.admin_update_editor_impl(
    text, integer, text, text, text, text, text, text, text, text,
    boolean, date, date
  ),
  content_private.admin_set_editor_access_impl(text, integer, boolean)
  from public, anon, authenticated, service_role;

grant execute on function content_private.admin_list_editors_impl(),
  content_private.admin_update_editor_impl(
    text, integer, text, text, text, text, text, text, text, text,
    boolean, date, date
  ),
  content_private.admin_set_editor_access_impl(text, integer, boolean)
  to authenticated;

create or replace function public.admin_list_editors()
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from content_private.admin_list_editors_impl();
$function$;

create or replace function public.admin_update_editor(
  p_editor_id text,
  p_expected_revision integer,
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
  select content_private.admin_update_editor_impl(
    p_editor_id, p_expected_revision,
    p_name_ko, p_name_en, p_title_ko, p_title_en, p_bio_ko, p_bio_en,
    p_curation_description_ko, p_curation_description_en,
    p_is_active, p_active_from, p_active_to
  );
$function$;

create or replace function public.admin_set_editor_access(
  p_editor_id text,
  p_expected_revision integer,
  p_active boolean
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.admin_set_editor_access_impl(
    p_editor_id, p_expected_revision, p_active
  );
$function$;

revoke all on function public.admin_list_editors(),
  public.admin_update_editor(
    text, integer, text, text, text, text, text, text, text, text,
    boolean, date, date
  ),
  public.admin_set_editor_access(text, integer, boolean)
  from public, anon, authenticated, service_role;

grant execute on function public.admin_list_editors(),
  public.admin_update_editor(
    text, integer, text, text, text, text, text, text, text, text,
    boolean, date, date
  ),
  public.admin_set_editor_access(text, integer, boolean)
  to authenticated;
