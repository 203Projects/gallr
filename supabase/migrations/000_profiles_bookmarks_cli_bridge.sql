-- CLI compatibility bridge for 005b_create_profiles_bookmarks.sql.
--
-- Supabase CLI 2.95.4 does not recognize the alphanumeric `005b` migration
-- version, so fresh local resets otherwise skip the profiles/bookmarks schema
-- and fail when later migrations reference it. Keep 005b as historical
-- documentation; this idempotent bridge reproduces its objects for CLI-driven
-- environments and is safe when those objects already exist remotely.
--
-- IMPORTANT: reconcile the linked project's migration history before pushing
-- this legacy bridge. Do not use --include-all without reviewing the plan.

create schema if not exists content_private;
revoke all on schema content_private from public, anon, authenticated;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '',
  avatar_url text,
  bio text default '',
  is_admin boolean default false,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

alter table public.profiles enable row level security;

do $bridge$
begin
  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'profiles'
      and policyname = 'Public read'
  ) then
    execute 'create policy "Public read" on public.profiles for select using (true)';
  end if;

  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'profiles'
      and policyname = 'Owner insert'
  ) then
    execute 'create policy "Owner insert" on public.profiles for insert with check (auth.uid() = id)';
  end if;

  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'profiles'
      and policyname = 'Owner write'
  ) then
    execute 'create policy "Owner write" on public.profiles for update using (auth.uid() = id)';
  end if;
end
$bridge$;

create or replace function content_private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, display_name, avatar_url)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'full_name', ''),
    coalesce(new.raw_user_meta_data ->> 'avatar_url', '')
  )
  on conflict (id) do nothing;

  return new;
end;
$$;

revoke all on function content_private.handle_new_user()
  from public, anon, authenticated;

do $bridge$
begin
  if not exists (
    select 1
    from pg_trigger
    where tgname = 'on_auth_user_created'
      and tgrelid = 'auth.users'::regclass
      and not tgisinternal
  ) then
    execute $trigger$
      create trigger on_auth_user_created
        after insert on auth.users
        for each row execute function content_private.handle_new_user()
    $trigger$;
  end if;
end
$bridge$;

create table if not exists public.bookmarks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid()
    references auth.users(id) on delete cascade,
  exhibition_id text not null,
  created_at timestamptz default now(),
  unique (user_id, exhibition_id)
);

alter table public.bookmarks enable row level security;

do $bridge$
begin
  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'bookmarks'
      and policyname = 'Owner read'
  ) then
    execute 'create policy "Owner read" on public.bookmarks for select using (auth.uid() = user_id)';
  end if;

  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'bookmarks'
      and policyname = 'Owner write'
  ) then
    execute 'create policy "Owner write" on public.bookmarks for insert with check (auth.uid() = user_id)';
  end if;

  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'bookmarks'
      and policyname = 'Owner delete'
  ) then
    execute 'create policy "Owner delete" on public.bookmarks for delete using (auth.uid() = user_id)';
  end if;
end
$bridge$;

-- New Supabase projects require explicit object grants. RLS remains the
-- row-level boundary for each API role.
grant select on public.profiles to anon, authenticated;
grant insert, update on public.profiles to authenticated;
grant select, insert, update, delete on public.bookmarks to authenticated;
