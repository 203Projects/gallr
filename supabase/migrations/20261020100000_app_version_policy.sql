-- Spec 098: the minimum and recommended app version per platform, read by the app without an account and
-- written by staff. Seeded at the current store version so the release carrying the check gates nobody.

create table if not exists content.app_version_policy (
  platform text primary key,
  minimum_version text not null,
  recommended_version text not null,
  latest_released_version text,
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users(id) on delete set null,
  constraint app_version_policy_platform_check check (platform in ('android', 'ios')),
  constraint app_version_policy_minimum_format check (minimum_version ~ '^\d+\.\d+\.\d+$'),
  constraint app_version_policy_recommended_format check (recommended_version ~ '^\d+\.\d+\.\d+$'),
  constraint app_version_policy_latest_format check (
    latest_released_version is null or latest_released_version ~ '^\d+\.\d+\.\d+$'
  )
);

comment on table content.app_version_policy is
  'Per-platform minimum and recommended app versions (spec 098). Read through get_app_version_policy; written through admin_set_app_version_policy.';

alter table content.app_version_policy enable row level security;
revoke all on table content.app_version_policy from public, anon, authenticated;

insert into content.app_version_policy (platform, minimum_version, recommended_version)
values ('android', '1.13.1', '1.13.1'), ('ios', '1.13.1', '1.13.1')
on conflict (platform) do nothing;

-- Three numbers compare as numbers, so 1.14.10 > 1.14.9.
create or replace function content_private.app_version_key(p_version text)
returns integer[]
language sql
immutable
set search_path = ''
as $$
  select array(select part::integer from unnest(string_to_array(p_version, '.')) as part);
$$;

revoke all on function content_private.app_version_key(text) from public, anon, authenticated;

create or replace function public.get_app_version_policy(p_platform text)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'minimum_version', policy.minimum_version,
    'recommended_version', policy.recommended_version
  )
  from content.app_version_policy as policy
  where policy.platform = p_platform;
$$;

revoke all on function public.get_app_version_policy(text) from public;
grant execute on function public.get_app_version_policy(text) to anon, authenticated;

create or replace function public.admin_set_app_version_policy(
  p_platform text,
  p_minimum text,
  p_recommended text,
  p_latest_released text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_staff uuid;
  v_row content.app_version_policy;
begin
  v_staff := content_private.admin_assert_staff('admin'::content.staff_role);

  if p_platform is null or p_platform not in ('android', 'ios') then
    raise exception using errcode = '22023', message = 'app_version_policy_unknown_platform';
  end if;
  if p_minimum !~ '^\d+\.\d+\.\d+$' or p_recommended !~ '^\d+\.\d+\.\d+$'
     or (p_latest_released is not null and p_latest_released !~ '^\d+\.\d+\.\d+$') then
    raise exception using errcode = '22023', message = 'app_version_policy_invalid_version';
  end if;
  if content_private.app_version_key(p_recommended) < content_private.app_version_key(p_minimum) then
    raise exception using errcode = '22023', message = 'app_version_policy_recommended_below_minimum';
  end if;

  insert into content.app_version_policy as policy
    (platform, minimum_version, recommended_version, latest_released_version, updated_at, updated_by)
  values (p_platform, p_minimum, p_recommended, p_latest_released, now(), v_staff)
  on conflict (platform) do update set
    minimum_version = excluded.minimum_version,
    recommended_version = excluded.recommended_version,
    latest_released_version = excluded.latest_released_version,
    updated_at = excluded.updated_at,
    updated_by = excluded.updated_by
  returning * into v_row;

  return jsonb_build_object(
    'platform', v_row.platform,
    'minimum_version', v_row.minimum_version,
    'recommended_version', v_row.recommended_version,
    'latest_released_version', v_row.latest_released_version,
    'updated_at', v_row.updated_at,
    'updated_by', v_row.updated_by
  );
end;
$$;

revoke all on function public.admin_set_app_version_policy(text, text, text, text) from public, anon;
grant execute on function public.admin_set_app_version_policy(text, text, text, text) to authenticated;
