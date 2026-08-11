-- Least-privilege editor collection access for the gallr admin portal.
-- Editor memberships are deliberately separate from staff_members: editor
-- accounts may propose only their own attribution changes and never satisfy a
-- contributor/publisher/admin authorization check.

create table content.editor_memberships (
  user_id uuid primary key references auth.users(id) on delete cascade,
  editor_id text not null unique references public.editors(id) on delete restrict,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users(id) on delete set null,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null
);

comment on table content.editor_memberships is
  'Invite-only account-to-editor authorization for the scoped My picks portal.';

alter table content.editor_memberships enable row level security;
revoke all on content.editor_memberships from public, anon, authenticated, service_role;

create trigger editor_memberships_set_updated_at
  before update on content.editor_memberships
  for each row execute function content_private.set_updated_at();

create or replace function content_private.editor_assert_membership()
returns text
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := (select auth.uid());
  v_editor_id text;
begin
  if v_user_id is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;

  select membership.editor_id
  into v_editor_id
  from content.editor_memberships as membership
  where membership.user_id = v_user_id
    and membership.active;

  if not found then
    raise exception using
      errcode = '42501',
      message = 'active_editor_membership_required';
  end if;

  return v_editor_id;
end;
$function$;

revoke all on function content_private.editor_assert_membership()
  from public, anon, authenticated, service_role;

-- Keep the existing portal access RPC and staff precedence. A dual-authorized
-- account enters the staff workspace; editor access never broadens staff APIs.
create or replace function content_private.admin_current_staff_impl()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := (select auth.uid());
  v_result jsonb;
begin
  if v_user_id is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;

  select jsonb_build_object(
    'user_id', staff.user_id,
    'role', staff.role::text,
    'active', staff.active,
    'display_name', coalesce(profile.display_name, ''),
    'avatar_url', profile.avatar_url,
    'editor_id', null,
    'editor_name', null
  )
  into v_result
  from content.staff_members as staff
  left join public.profiles as profile on profile.id = staff.user_id
  where staff.user_id = v_user_id
    and staff.active;

  if found then return v_result; end if;

  select jsonb_build_object(
    'user_id', membership.user_id,
    'role', 'editor',
    'active', membership.active,
    'display_name', coalesce(profile.display_name, ''),
    'avatar_url', profile.avatar_url,
    'editor_id', editor.id,
    'editor_name', coalesce(nullif(editor.name_en, ''), editor.name_ko)
  )
  into v_result
  from content.editor_memberships as membership
  join public.editors as editor on editor.id = membership.editor_id
  left join public.profiles as profile on profile.id = membership.user_id
  where membership.user_id = v_user_id
    and membership.active;

  if found then return v_result; end if;

  raise exception using
    errcode = '42501',
    message = 'active_staff_membership_required';
end;
$function$;

revoke all on function content_private.admin_current_staff_impl()
  from public, anon, authenticated, service_role;
grant execute on function content_private.admin_current_staff_impl()
  to authenticated;

create or replace function content_private.editor_pick_json(
  p_exhibition_id text,
  p_version_id uuid,
  p_editor_id text
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $function$
  select jsonb_build_object(
    'id', exhibition.id,
    'working_version_id', version.id,
    'published_version_id', exhibition.published_version_id,
    'revision', version.revision,
    'name_ko', version.name_ko,
    'name_en', version.name_en,
    'venue_name_ko', version.venue_name_ko,
    'venue_name_en', version.venue_name_en,
    'opening_date', coalesce(to_char(version.opening_date, 'YYYY-MM-DD'), ''),
    'closing_date', coalesce(to_char(version.closing_date, 'YYYY-MM-DD'), ''),
    'selected', coalesce(version.editor_id = p_editor_id, false),
    'live', coalesce(published.editor_id = p_editor_id, false)
  )
  from content.exhibitions as exhibition
  join content.exhibition_versions as version
    on version.exhibition_id = exhibition.id
   and version.id = p_version_id
  join content.exhibition_versions as published
    on published.exhibition_id = exhibition.id
   and published.id = exhibition.published_version_id
  where exhibition.id = p_exhibition_id;
$function$;

revoke all on function content_private.editor_pick_json(text, uuid, text)
  from public, anon, authenticated, service_role;

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
      and (
        candidate.status = 'draft'::content.exhibition_version_status
        or candidate.id = exhibition.published_version_id
      )
    order by
      (candidate.status = 'draft'::content.exhibition_version_status) desc,
      candidate.version_number desc
    limit 1
  ) as chosen on true
  where exhibition.published_version_id is not null
    and exhibition.archived_at is null
    and (chosen.editor_id is null or chosen.editor_id = v_editor_id)
    and (published.editor_id is null or published.editor_id = v_editor_id)
    and (
      v_search = ''
      or position(
        v_search in lower(concat_ws(
          ' ', exhibition.id, chosen.name_ko, chosen.name_en,
          chosen.venue_name_ko, chosen.venue_name_en
        ))
      ) > 0
    )
  order by
    (chosen.editor_id = v_editor_id) desc,
    chosen.closing_date desc nulls last,
    chosen.name_ko,
    exhibition.id;
end;
$function$;

create or replace function content_private.editor_set_pick_impl(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_selected boolean
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
  v_exhibition content.exhibitions%rowtype;
  v_source content.exhibition_versions%rowtype;
  v_draft content.exhibition_versions%rowtype;
  v_previous_editor_id text;
  v_published_editor_id text;
  v_desired_editor_id text := case when p_selected then v_editor_id else null end;
  v_next_version_number integer;
begin
  if p_expected_version_id is null then
    raise exception using errcode = '22023', message = 'expected_version_id_is_required';
  end if;
  if p_expected_revision is null or p_expected_revision < 1 then
    raise exception using errcode = '22023', message = 'expected_revision_must_be_positive';
  end if;
  if p_selected is null then
    raise exception using errcode = '22023', message = 'selected_state_is_required';
  end if;

  select exhibition.*
  into v_exhibition
  from content.exhibitions as exhibition
  where exhibition.id = p_exhibition_id
  for update;

  if not found
     or v_exhibition.published_version_id is null
     or v_exhibition.archived_at is not null then
    raise exception using errcode = '42501', message = 'editor_pick_not_available';
  end if;

  select candidate.*
  into v_source
  from content.exhibition_versions as candidate
  where candidate.exhibition_id = p_exhibition_id
    and (
      candidate.status = 'draft'::content.exhibition_version_status
      or candidate.id = v_exhibition.published_version_id
    )
  order by
    (candidate.status = 'draft'::content.exhibition_version_status) desc,
    candidate.version_number desc
  limit 1
  for update;

  if v_source.id is distinct from p_expected_version_id then
    raise exception using errcode = '40001', message = 'working_version_changed';
  end if;
  if v_source.revision <> p_expected_revision then
    raise exception using
      errcode = '40001', message = 'revision_conflict', detail = v_source.revision::text;
  end if;

  select published.editor_id
  into v_published_editor_id
  from content.exhibition_versions as published
  where published.id = v_exhibition.published_version_id
    and published.exhibition_id = p_exhibition_id;

  if (v_source.editor_id is not null and v_source.editor_id <> v_editor_id)
     or (
       v_published_editor_id is not null
       and v_published_editor_id <> v_editor_id
     ) then
    raise exception using errcode = '42501', message = 'editor_pick_not_available';
  end if;

  if v_source.editor_id is not distinct from v_desired_editor_id then
    return content_private.editor_pick_json(
      p_exhibition_id, v_source.id, v_editor_id
    );
  end if;

  v_previous_editor_id := v_source.editor_id;

  if v_source.status = 'published'::content.exhibition_version_status then
    select coalesce(max(version.version_number), 0) + 1
    into v_next_version_number
    from content.exhibition_versions as version
    where version.exhibition_id = p_exhibition_id;

    insert into content.exhibition_versions (
      exhibition_id, version_number, revision, status,
      venue_id, event_id, editor_id,
      name_ko, name_en, venue_name_ko, venue_name_en,
      city_ko, city_en, region_ko, region_en, address_ko, address_en,
      opening_date, closing_date, latitude, longitude,
      description_ko, description_en, credits_ko, credits_en,
      hours, contact, reception_date, opening_time, ticket_url,
      legacy_cover_image_url, legacy_source_updated_at,
      is_featured, is_homepage_featured, created_by, updated_by
    )
    values (
      p_exhibition_id, v_next_version_number, v_source.revision,
      'draft'::content.exhibition_version_status,
      v_source.venue_id, v_source.event_id, v_source.editor_id,
      v_source.name_ko, v_source.name_en,
      v_source.venue_name_ko, v_source.venue_name_en,
      v_source.city_ko, v_source.city_en, v_source.region_ko, v_source.region_en,
      v_source.address_ko, v_source.address_en,
      v_source.opening_date, v_source.closing_date,
      v_source.latitude, v_source.longitude,
      v_source.description_ko, v_source.description_en,
      v_source.credits_ko, v_source.credits_en,
      v_source.hours, v_source.contact, v_source.reception_date,
      v_source.opening_time, v_source.ticket_url,
      v_source.legacy_cover_image_url, v_source.legacy_source_updated_at,
      v_source.is_featured, v_source.is_homepage_featured,
      v_user_id, v_user_id
    )
    returning * into v_draft;

    insert into content.exhibition_version_media (
      version_id, media_id, role, sort_order, focal_x, focal_y, created_by
    )
    select
      v_draft.id, attachment.media_id, attachment.role,
      attachment.sort_order, attachment.focal_x, attachment.focal_y, v_user_id
    from content.exhibition_version_media as attachment
    where attachment.version_id = v_source.id;
  elsif v_source.status = 'draft'::content.exhibition_version_status then
    v_draft := v_source;
  else
    raise exception using errcode = '42501', message = 'editor_pick_not_available';
  end if;

  update content.exhibition_versions as version
  set
    editor_id = v_desired_editor_id,
    revision = version.revision + 1,
    updated_by = v_user_id
  where version.id = v_draft.id
    and version.exhibition_id = p_exhibition_id
    and version.status = 'draft'::content.exhibition_version_status
    and version.revision = p_expected_revision
  returning version.* into v_draft;

  if not found then
    raise exception using errcode = '40001', message = 'revision_conflict';
  end if;

  update content.exhibitions
  set updated_by = v_user_id
  where id = p_exhibition_id;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  )
  values (
    v_user_id,
    case when p_selected then 'editor.pick_added' else 'editor.pick_removed' end,
    'exhibition',
    p_exhibition_id,
    jsonb_build_object(
      'editor_id', v_editor_id,
      'previous_editor_id', v_previous_editor_id,
      'selected', p_selected,
      'working_version_id', v_draft.id,
      'revision', v_draft.revision
    )
  );

  return content_private.editor_pick_json(
    p_exhibition_id, v_draft.id, v_editor_id
  );
end;
$function$;

revoke all on function content_private.editor_list_pick_candidates_impl(text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.editor_set_pick_impl(text, uuid, integer, boolean)
  from public, anon, authenticated, service_role;
grant execute on function content_private.editor_list_pick_candidates_impl(text)
  to authenticated;
grant execute on function content_private.editor_set_pick_impl(text, uuid, integer, boolean)
  to authenticated;

create or replace function public.editor_list_pick_candidates(
  p_search text default ''
)
returns setof jsonb
language sql
stable
security invoker
set search_path = ''
as $function$
  select * from content_private.editor_list_pick_candidates_impl(p_search);
$function$;

create or replace function public.editor_set_pick(
  p_exhibition_id text,
  p_expected_version_id uuid,
  p_expected_revision integer,
  p_selected boolean
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.editor_set_pick_impl(
    p_exhibition_id,
    p_expected_version_id,
    p_expected_revision,
    p_selected
  );
$function$;

revoke all on function public.editor_list_pick_candidates(text)
  from public, anon, authenticated, service_role;
revoke all on function public.editor_set_pick(text, uuid, integer, boolean)
  from public, anon, authenticated, service_role;
grant execute on function public.editor_list_pick_candidates(text)
  to authenticated;
grant execute on function public.editor_set_pick(text, uuid, integer, boolean)
  to authenticated;
