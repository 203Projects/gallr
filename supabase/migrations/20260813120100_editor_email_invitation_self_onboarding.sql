-- Split editor invitations from public profile creation. Admin sends only an
-- Auth invitation; the invited account completes its own unpublished profile
-- before receiving the scoped editor membership.

create table content.editor_invitations (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text not null check (length(email) between 3 and 254),
  invited_by uuid not null references auth.users(id) on delete restrict,
  invited_at timestamptz not null default now()
);

create unique index editor_invitations_email_idx
  on content.editor_invitations (lower(email));

comment on table content.editor_invitations is
  'Server-only pending Auth invitations awaiting editor-owned profile setup.';

alter table content.editor_invitations enable row level security;
revoke all on content.editor_invitations
  from public, anon, authenticated, service_role;

create or replace function content_private.admin_register_editor_invitation_impl(
  p_user_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $function$
declare
  v_actor_user_id uuid := content_private.admin_assert_staff(
    'admin'::content.staff_role
  );
  v_email text;
begin
  select lower(btrim(invited.email))
  into v_email
  from auth.users as invited
  where invited.id = p_user_id
    and invited.email is not null
    and btrim(invited.email) <> '';

  if not found then
    raise exception using
      errcode = '22023', message = 'invited_user_required';
  end if;
  if exists (
    select 1 from content.staff_members as staff
    where staff.user_id = p_user_id
  ) then
    raise exception using
      errcode = '23514', message = 'editor_account_conflicts_with_staff';
  end if;
  if exists (
    select 1 from content.editor_memberships as membership
    where membership.user_id = p_user_id
  ) then
    raise exception using
      errcode = '23505', message = 'editor_membership_exists';
  end if;

  insert into content.editor_invitations (user_id, email, invited_by)
  values (p_user_id, v_email, v_actor_user_id);

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  )
  values (
    v_actor_user_id,
    'editor.invited',
    'editor_invitation',
    p_user_id::text,
    jsonb_build_object('invited_user_id', p_user_id)
  );

  return jsonb_build_object('email', v_email, 'status', 'invited');
end;
$function$;

create or replace function content_private.editor_complete_onboarding_impl(
  p_editor_id text,
  p_name_ko text,
  p_name_en text,
  p_title_ko text,
  p_title_en text,
  p_bio_ko text,
  p_bio_en text,
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
  v_invitation content.editor_invitations%rowtype;
  v_editor_id text := btrim(coalesce(p_editor_id, ''));
  v_name_ko text := btrim(coalesce(p_name_ko, ''));
  v_name_en text := btrim(coalesce(p_name_en, ''));
  v_title_ko text := btrim(coalesce(p_title_ko, ''));
  v_title_en text := btrim(coalesce(p_title_en, ''));
  v_bio_ko text := btrim(coalesce(p_bio_ko, ''));
  v_bio_en text := btrim(coalesce(p_bio_en, ''));
  v_curation_description_ko text := btrim(
    coalesce(p_curation_description_ko, '')
  );
  v_curation_description_en text := btrim(
    coalesce(p_curation_description_en, '')
  );
begin
  if v_user_id is null then
    raise exception using
      errcode = '42501', message = 'authentication_required';
  end if;

  select invitation.*
  into v_invitation
  from content.editor_invitations as invitation
  where invitation.user_id = v_user_id
  for update;

  if not found then
    raise exception using
      errcode = '42501', message = 'editor_invitation_required';
  end if;
  if exists (
    select 1 from content.staff_members as staff
    where staff.user_id = v_user_id
  ) then
    raise exception using
      errcode = '23514', message = 'editor_account_conflicts_with_staff';
  end if;
  if v_editor_id !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
     or length(v_editor_id) < 3
     or length(v_editor_id) > 64 then
    raise exception using
      errcode = '22023', message = 'editor_slug_invalid';
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
    raise exception using
      errcode = '22023', message = 'editor_profile_invalid';
  end if;

  insert into public.editors (
    id, name_ko, name_en, title_ko, title_en, bio_ko, bio_en,
    curation_description_ko, curation_description_en,
    is_active, active_from, active_to
  )
  values (
    v_editor_id, v_name_ko, v_name_en, v_title_ko, v_title_en,
    v_bio_ko, v_bio_en,
    v_curation_description_ko, v_curation_description_en,
    false, current_date, null
  );

  insert into content.editor_memberships (
    user_id, editor_id, active, created_by, updated_by
  )
  values (
    v_user_id, v_editor_id, true, v_invitation.invited_by, v_user_id
  );

  delete from content.editor_invitations
  where user_id = v_user_id;

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  )
  values (
    v_user_id,
    'editor.onboarded',
    'editor',
    v_editor_id,
    jsonb_build_object('invited_by', v_invitation.invited_by)
  );

  return jsonb_build_object(
    'editor_id', v_editor_id,
    'name_ko', v_name_ko,
    'name_en', v_name_en,
    'is_active', false
  );
end;
$function$;

-- Retain staff precedence, then full editor membership, then the narrow
-- onboarding state. A pending invitation never satisfies editor APIs.
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
    raise exception using
      errcode = '42501', message = 'authentication_required';
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

  select jsonb_build_object(
    'user_id', invitation.user_id,
    'role', 'editor_onboarding',
    'active', true,
    'display_name', '',
    'avatar_url', null,
    'editor_id', null,
    'editor_name', null
  )
  into v_result
  from content.editor_invitations as invitation
  where invitation.user_id = v_user_id;

  if found then return v_result; end if;

  raise exception using
    errcode = '42501', message = 'active_staff_membership_required';
end;
$function$;

create or replace function public.admin_register_editor_invitation(
  p_user_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.admin_register_editor_invitation_impl(p_user_id);
$function$;

create or replace function public.editor_complete_onboarding(
  p_editor_id text,
  p_name_ko text,
  p_name_en text,
  p_title_ko text,
  p_title_en text,
  p_bio_ko text,
  p_bio_en text,
  p_curation_description_ko text,
  p_curation_description_en text
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $function$
  select content_private.editor_complete_onboarding_impl(
    p_editor_id, p_name_ko, p_name_en, p_title_ko, p_title_en,
    p_bio_ko, p_bio_en,
    p_curation_description_ko, p_curation_description_en
  );
$function$;

revoke all on function
  content_private.admin_register_editor_invitation_impl(uuid),
  content_private.editor_complete_onboarding_impl(
    text, text, text, text, text, text, text, text, text
  )
  from public, anon, authenticated, service_role;
grant execute on function
  content_private.admin_register_editor_invitation_impl(uuid),
  content_private.editor_complete_onboarding_impl(
    text, text, text, text, text, text, text, text, text
  )
  to authenticated;

revoke all on function
  public.admin_register_editor_invitation(uuid),
  public.editor_complete_onboarding(
    text, text, text, text, text, text, text, text, text
  )
  from public, anon, authenticated, service_role;
grant execute on function
  public.admin_register_editor_invitation(uuid),
  public.editor_complete_onboarding(
    text, text, text, text, text, text, text, text, text
  )
  to authenticated;
