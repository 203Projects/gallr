-- Spec 103 M3: the 1.15 in-app staff queue is retired on the server the day Admin's 감상 section goes live (owner
-- decision 8, FR-027). A staff account reaches other people's 감상 only through the M2 functions; every author keeps
-- the 1.15 flow on their own rows.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(22);

create function pg_temp.act_as(p_user uuid) returns void language sql as $$
  select set_config(
    'request.jwt.claims',
    json_build_object('sub', p_user, 'role', 'authenticated')::text,
    true
  );
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated;

create function pg_temp.act_as_anon() returns void language sql as $$
  select set_config('request.jwt.claims', '{"role":"anon"}', true);
$$;
grant execute on function pg_temp.act_as_anon() to anon;

create function pg_temp.act_as_service() returns void language sql as $$
  select set_config('request.jwt.claims', '', true);
$$;

create temp table touched (step text primary key, row_count integer not null);
grant all on touched to authenticated;

-- ── Policies ─────────────────────────────────────────────────────────────────────────────────────────────────────

select is(
  (
    select string_agg(policyname || ':' || cmd || ':' || array_to_string(roles, ','), ' | ' order by cmd, policyname)
    from pg_policies
    where schemaname = 'public' and tablename = 'thoughts'
  ),
  'owners can delete their thoughts:DELETE:authenticated | owners can create thoughts:INSERT:authenticated'
    || ' | approved thoughts are publicly readable:SELECT:anon'
    || ' | authenticated users can read approved or own thoughts:SELECT:authenticated'
    || ' | owners can update their thoughts:UPDATE:authenticated',
  'public.thoughts has exactly the author and reader policies'
);
select is(
  (
    select count(*)::integer
    from pg_policies
    where schemaname = 'public' and tablename = 'thoughts'
      and (coalesce(qual, '') like '%has_staff_role%' or coalesce(with_check, '') like '%has_staff_role%')
  ),
  0,
  'no policy on public.thoughts grants staff anything'
);
select ok(
  (
    select qual like '%auth.uid()%user_id%' and with_check like '%auth.uid()%user_id%'
    from pg_policies
    where schemaname = 'public' and tablename = 'thoughts' and policyname = 'owners can update their thoughts'
  ),
  'the update policy is the author''s own row, before and after'
);

-- ── Fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────────

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('78000000-0000-4000-8000-000000000001', 'retire-author@example.invalid', now(), '{}'),
  ('78000000-0000-4000-8000-000000000002', 'retire-admin@example.invalid', now(), '{}'),
  ('78000000-0000-4000-8000-000000000003', 'retire-publisher@example.invalid', now(), '{}'),
  ('78000000-0000-4000-8000-000000000004', 'retire-reader@example.invalid', now(), '{}');
insert into content.staff_members (user_id, role, active) values
  ('78000000-0000-4000-8000-000000000002', 'admin', true),
  ('78000000-0000-4000-8000-000000000003', 'publisher', true);

select pg_temp.act_as_service();
insert into public.thoughts (id, user_id, exhibition_id, content, moderation_status, approved_at) values
  ('78000000-0000-4000-8000-0000000000a1', '78000000-0000-4000-8000-000000000001', 'ex-078-a', 'Author pending',
   'pending', null),
  ('78000000-0000-4000-8000-0000000000a2', '78000000-0000-4000-8000-000000000001', 'ex-078-b', 'Author approved',
   'approved', '2026-11-01 10:00+00'),
  ('78000000-0000-4000-8000-0000000000c1', '78000000-0000-4000-8000-000000000002', 'ex-078-a', 'Admin own pending',
   'pending', null);

-- ── An active admin on app 1.15 ──────────────────────────────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('78000000-0000-4000-8000-000000000002');
select is(
  (select count(*)::integer from public.thoughts where not is_approved and user_id <> auth.uid()),
  0,
  'the 1.15 admin queue reads no one else''s pending 감상'
);
select is(
  (select count(*)::integer from public.thoughts where id = '78000000-0000-4000-8000-0000000000a2'),
  1,
  'an admin still reads approved 감상 like any reader'
);

with changed as (
  update public.thoughts set is_approved = true where id = '78000000-0000-4000-8000-0000000000a1' returning 1
)
insert into touched select 'admin approve', count(*) from changed;
with changed as (
  update public.thoughts set content = 'Admin rewrote it' where id = '78000000-0000-4000-8000-0000000000a2'
  returning 1
)
insert into touched select 'admin edit approved', count(*) from changed;
with removed as (
  delete from public.thoughts where id = '78000000-0000-4000-8000-0000000000a1' returning 1
)
insert into touched select 'admin delete pending', count(*) from removed;
with removed as (
  delete from public.thoughts where id = '78000000-0000-4000-8000-0000000000a2' returning 1
)
insert into touched select 'admin delete approved', count(*) from removed;

select is((select row_count from touched where step = 'admin approve'), 0,
  'the 1.15 admin approve touches no row');
select is((select row_count from touched where step = 'admin edit approved'), 0,
  'an admin cannot edit another person''s approved 감상');
select is((select row_count from touched where step = 'admin delete pending'), 0,
  'an admin cannot delete another person''s pending 감상');
select is((select row_count from touched where step = 'admin delete approved'), 0,
  'an admin cannot delete another person''s approved 감상');

-- The admin's own 감상 stays theirs.
select is(
  (select count(*)::integer from public.thoughts where id = '78000000-0000-4000-8000-0000000000c1'),
  1,
  'an admin reads their own pending 감상'
);
select lives_ok(
  $$delete from public.thoughts where id = '78000000-0000-4000-8000-0000000000c1'$$,
  'an admin deletes their own 감상'
);
reset role;

select pg_temp.act_as_service();
select is(
  (
    select string_agg(id::text || '/' || moderation_status || '/' || content, ',' order by id)
    from public.thoughts where user_id = '78000000-0000-4000-8000-000000000001'
  ),
  '78000000-0000-4000-8000-0000000000a1/pending/Author pending,'
    || '78000000-0000-4000-8000-0000000000a2/approved/Author approved',
  'the author''s 감상 are untouched'
);
select is(
  (select count(*)::integer from public.thoughts where id = '78000000-0000-4000-8000-0000000000c1'),
  0,
  'the admin''s own 감상 is gone'
);

-- ── Admin's 감상 section still moderates (M2 functions) ─────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('78000000-0000-4000-8000-000000000003');
select lives_ok(
  $$select public.decide_thought(
      '78000000-0000-4000-8000-0000000000a1', 'approve', null,
      (select (item ->> 'requested_at')::timestamptz
       from jsonb_array_elements(public.list_thought_review_queue()) as item
       where item ->> 'id' = '78000000-0000-4000-8000-0000000000a1'))$$,
  'a publisher still approves through the M2 function'
);
reset role;
select pg_temp.act_as_service();
select is(
  (select moderation_status from public.thoughts where id = '78000000-0000-4000-8000-0000000000a1'),
  'approved',
  'the M2 approval took effect'
);

-- ── The 1.15 author flow ─────────────────────────────────────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('78000000-0000-4000-8000-000000000004');
select lives_ok(
  $$insert into public.thoughts (user_id, exhibition_id, content)
    values ('78000000-0000-4000-8000-000000000004', 'ex-078-r', 'Reader note')$$,
  'a 1.15 author inserts a 감상'
);
select is(
  (select is_approved::text from public.thoughts
   where user_id = '78000000-0000-4000-8000-000000000004' and exhibition_id = 'ex-078-r'),
  'false',
  'the author reads their own pending 감상'
);
select lives_ok(
  $$update public.thoughts set content = 'Reader note, edited'
    where user_id = '78000000-0000-4000-8000-000000000004' and exhibition_id = 'ex-078-r'$$,
  'the author edits their own 감상'
);
select is(
  (select content from public.thoughts
   where user_id = '78000000-0000-4000-8000-000000000004' and exhibition_id = 'ex-078-r'),
  'Reader note, edited',
  'the edit is kept'
);
select throws_ok(
  $$insert into public.thoughts (user_id, exhibition_id, content)
    values ('78000000-0000-4000-8000-000000000001', 'ex-078-x', 'Posing as someone else')$$,
  '42501',
  null,
  'an author cannot insert a 감상 for another account'
);
select lives_ok(
  $$delete from public.thoughts
    where user_id = '78000000-0000-4000-8000-000000000004' and exhibition_id = 'ex-078-r'$$,
  'the author deletes their own 감상'
);
reset role;

set local role anon;
select pg_temp.act_as_anon();
select is(
  (select string_agg(id::text, ',' order by id) from public.thoughts where id::text like '78000000-%'),
  '78000000-0000-4000-8000-0000000000a1,78000000-0000-4000-8000-0000000000a2',
  'anon reads the approved 감상 only'
);
reset role;

select * from finish();
rollback;
