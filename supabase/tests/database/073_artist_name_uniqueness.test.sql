-- Spec 101 (US5): once duplicates are merged, an active artist name is unique after normalisation.
-- Every path that creates or renames an artist row gets artist_duplicate with the existing id.

begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(20);

select has_index(
  'content', 'artists', 'artists_active_name_ko_normalized_key',
  'active Korean names are unique after normalisation'
);
select has_index(
  'content', 'artists', 'artists_active_name_en_normalized_key',
  'active English names are unique after normalisation'
);
select has_trigger(
  'content', 'artists', 'artists_guard_duplicate_name',
  'a trigger turns a duplicate into artist_duplicate on every write path'
);
select has_function(
  'content_private', 'assert_no_duplicate_artists', array[]::text[],
  'the migration guard is a reusable function'
);
select ok(
  not exists (
    select 1
    from pg_catalog.pg_proc as proc
    join pg_catalog.pg_namespace as namespace on namespace.oid = proc.pronamespace
    where namespace.nspname in ('public', 'content_private')
      and proc.proname like 'owner%'
      and proc.prosrc ilike '%insert into content.artists%'
  ),
  'gallery owners have no artist-creating path (owner credits stay unresolved suggestions)'
);

create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
$$;
create function pg_temp.error_of(p_sql text) returns text language plpgsql as $$
declare
  v_state text;
  v_message text;
  v_detail text;
begin
  execute p_sql;
  return 'no error';
exception when others then
  get stacked diagnostics v_state = returned_sqlstate, v_message = message_text, v_detail = pg_exception_detail;
  return v_state || '|' || v_message || '|' || coalesce(v_detail, '');
end;
$$;
create function pg_temp.hint_of(p_sql text) returns text language plpgsql as $$
declare
  v_hint text;
begin
  execute p_sql;
  return 'no error';
exception when others then
  get stacked diagnostics v_hint = pg_exception_hint;
  return v_hint;
end;
$$;
grant execute on function pg_temp.act_as(uuid), pg_temp.error_of(text) to authenticated, anon;

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('73000000-0000-4000-8000-000000000001', 'unique-staff@example.invalid', now(), '{}');
insert into content.staff_members (user_id, role, active) values
  ('73000000-0000-4000-8000-000000000001', 'contributor', true);

insert into content.artists (id, name_ko, name_en) values
  ('73000000-0000-4000-8000-0000000000a1', '김유일', 'Kim Only');

-- == Admin create ==
set local role authenticated;
select pg_temp.act_as('73000000-0000-4000-8000-000000000001');
select is(
  pg_temp.error_of($$ select public.admin_create_artist('김 유일', 'Someone Else', '73000000-0000-4000-8000-000000000201') $$),
  'P0001|artist_duplicate|73000000-0000-4000-8000-0000000000a1',
  'Admin creation with a matching normalised Korean name returns the existing id'
);
select is(
  pg_temp.error_of($$ select public.admin_create_artist('다른 사람', 'KIM-ONLY', '73000000-0000-4000-8000-000000000202') $$),
  'P0001|artist_duplicate|73000000-0000-4000-8000-0000000000a1',
  'Admin creation with a matching normalised English name returns the existing id'
);
select throws_ok(
  $$ select public.admin_create_artist('ＫＩＭ유일', 'Kim Only', '73000000-0000-4000-8000-000000000203') $$,
  'P0001', 'artist_duplicate',
  'artist_duplicate is raised with errcode P0001'
);
select lives_ok(
  $$ select public.admin_create_artist('박새로', 'Park New', '73000000-0000-4000-8000-000000000204') $$,
  'a new name is still created'
);
select is(
  (public.admin_create_artist('박새로', 'Park New', '73000000-0000-4000-8000-000000000204') ->> 'name_en'),
  'Park New',
  'replaying the same request returns the stored response instead of a duplicate'
);
reset role;

select is(
  (select count(*)::integer from content.artists where content_private.normalize_artist_name(name_ko) = '김유일'),
  1,
  'the refused creations left no row'
);

-- == every other write path ==
select is(
  pg_temp.error_of($$ insert into content.artists (name_ko, name_en) values ('김유일', 'Kim Only Too') $$),
  'P0001|artist_duplicate|73000000-0000-4000-8000-0000000000a1',
  'a direct insert gets artist_duplicate, not a raw unique violation'
);
select is(
  pg_temp.error_of($$ update content.artists set name_en = 'kim  only' where name_ko = '박새로' $$),
  'P0001|artist_duplicate|73000000-0000-4000-8000-0000000000a1',
  'renaming into an active name gets artist_duplicate'
);

-- == archived artists do not block ==
update content.artists set archived_at = now() where id = '73000000-0000-4000-8000-0000000000a1';
set local role authenticated;
select pg_temp.act_as('73000000-0000-4000-8000-000000000001');
select lives_ok(
  $$ select public.admin_create_artist('김유일', 'Kim Only', '73000000-0000-4000-8000-000000000205') $$,
  'an archived artist does not block its name'
);
reset role;
select is(
  pg_temp.error_of($$ update content.artists set archived_at = null where id = '73000000-0000-4000-8000-0000000000a1' $$),
  'P0001|artist_duplicate|' || (
    select id::text from content.artists where name_ko = '김유일' and archived_at is null
  ),
  'restoring an archived artist whose name is now taken gets artist_duplicate'
);

-- == the index is the backstop ==
alter table content.artists disable trigger artists_guard_duplicate_name;
select throws_ok(
  $$ insert into content.artists (name_ko, name_en) values ('김 유일', 'Another') $$,
  '23505', null,
  'without the trigger the unique index still refuses an active duplicate'
);
alter table content.artists enable trigger artists_guard_duplicate_name;

-- == the migration guard ==
select lives_ok(
  $$ select content_private.assert_no_duplicate_artists() $$,
  'the guard passes when no active duplicates remain'
);
drop index content.artists_active_name_ko_normalized_key;
drop index content.artists_active_name_en_normalized_key;
alter table content.artists disable trigger artists_guard_duplicate_name;
insert into content.artists (name_ko, name_en) values
  ('박 새로', 'Park Renewed'),
  ('이중복', 'Lee Twice'),
  ('이 중복', 'LEE TWICE');
select throws_like(
  $$ select content_private.assert_no_duplicate_artists() $$,
  'artist_duplicates_remain: 2 groups%',
  'the guard aborts and counts the remaining groups'
);
select ok(
  (
    select position('ko:박새로 [' in report) > 0
      and position('ko:이중복 [' in report) > 0
      and position('박 새로 / Park Renewed' in report) > 0
      and position('이 중복 / LEE TWICE' in report) > 0
    from (select pg_temp.error_of($$ select content_private.assert_no_duplicate_artists() $$) as report) as guard
  ),
  'the guard lists every remaining group with its artists'
);

select ok(
  (
    select position('rename one' in hint) > 0 and position('do not merge' in hint) > 0
    from (select pg_temp.hint_of($$ select content_private.assert_no_duplicate_artists() $$) as hint) as guard
  ),
  'the guard tells the operator to rename, not merge, two different people who share a name'
);

select * from finish();
rollback;
