-- Admin-only command used after the invite-editor Edge Function creates the
-- Auth user. The public wrapper stays SECURITY INVOKER; the private command
-- independently checks the authoritative staff membership before writing.

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
begin
  if v_actor_user_id is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if not exists (
    select 1
    from content.staff_members as staff
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
  if exists (
    select 1 from content.staff_members as staff where staff.user_id = p_user_id
  ) then
    raise exception using
      errcode = '23514', message = 'editor_account_conflicts_with_staff';
  end if;
  if v_editor_id !~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'
     or length(v_editor_id) < 3
     or length(v_editor_id) > 64 then
    raise exception using errcode = '22023', message = 'editor_slug_invalid';
  end if;
  if v_name_ko = '' or length(v_name_ko) > 120
     or length(v_name_en) > 120
     or v_title_ko = '' or length(v_title_ko) > 160
     or length(v_title_en) > 160
     or v_bio_ko = '' or length(v_bio_ko) > 4000
     or length(v_bio_en) > 4000 then
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
    is_active, active_from, active_to
  )
  values (
    v_editor_id, v_name_ko, v_name_en, v_title_ko, v_title_en,
    v_bio_ko, v_bio_en, p_is_active, p_active_from, p_active_to
  );

  insert into content.editor_memberships (
    user_id, editor_id, active, created_by, updated_by
  )
  values (p_user_id, v_editor_id, true, v_actor_user_id, v_actor_user_id);

  insert into content.audit_log (
    actor_user_id, action, entity_type, entity_id, metadata
  )
  values (
    v_actor_user_id,
    'editor.created',
    'editor',
    v_editor_id,
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

revoke all on function content_private.admin_create_editor_onboarding_impl(
  uuid, text, text, text, text, text, text, text, boolean, date, date
) from public, anon, authenticated, service_role;
grant execute on function content_private.admin_create_editor_onboarding_impl(
  uuid, text, text, text, text, text, text, text, boolean, date, date
) to authenticated;

create or replace function public.admin_create_editor_onboarding(
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
security invoker
set search_path = ''
as $function$
  select content_private.admin_create_editor_onboarding_impl(
    p_user_id, p_editor_id, p_name_ko, p_name_en, p_title_ko, p_title_en,
    p_bio_ko, p_bio_en, p_is_active, p_active_from, p_active_to
  );
$function$;

revoke all on function public.admin_create_editor_onboarding(
  uuid, text, text, text, text, text, text, text, boolean, date, date
) from public, anon, authenticated, service_role;
grant execute on function public.admin_create_editor_onboarding(
  uuid, text, text, text, text, text, text, text, boolean, date, date
) to authenticated;
