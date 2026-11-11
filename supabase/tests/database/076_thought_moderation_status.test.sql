-- Spec 103 M1: a moderation status on public.thoughts kept in step with is_approved, and the trigger that returns
-- any edited text to review. Closes the live defect where an author could change the text of an approved 감상 and
-- it stayed public (research §1; FR-019, FR-030, SC-005 1.15 path). The 1.15 app keeps working: it inserts,
-- edits `content`, deletes, reads `is_approved`, and its staff queue approves by setting `is_approved = true`.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(67);

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

-- No JWT: the service role or a migration (auth.uid() is null).
create function pg_temp.act_as_service() returns void language sql as $$
  select set_config('request.jwt.claims', '', true);
$$;

create function pg_temp.moderation_flag(p_on boolean) returns void language sql as $$
  select set_config('gallr.thought_moderation', case when p_on then 'on' else 'off' end, true);
$$;
grant execute on function pg_temp.moderation_flag(boolean) to authenticated;

-- The moderation state of one row as booleans, so assertions do not depend on clock values. Read as postgres.
create function pg_temp.state(p_id uuid) returns jsonb language sql as $$
  select jsonb_build_object(
    'status', thought.moderation_status,
    'is_approved', thought.is_approved,
    'reason', thought.rejection_reason,
    'requested_now', thought.requested_at = now(),
    'approved_now', coalesce(thought.approved_at = now(), false),
    'decided_by', thought.decided_by,
    'decided_now', coalesce(thought.decided_at = now(), false),
    'seen', thought.approval_seen_at is not null
  )
  from public.thoughts as thought
  where thought.id = p_id;
$$;

create function pg_temp.expected(
  p_status text,
  p_requested_now boolean,
  p_approved_now boolean default false,
  p_decided_by uuid default null,
  p_decided_now boolean default false,
  p_seen boolean default false,
  p_reason text default null
) returns jsonb language sql as $$
  select jsonb_build_object(
    'status', p_status,
    'is_approved', p_status = 'approved',
    'reason', p_reason,
    'requested_now', p_requested_now,
    'approved_now', p_approved_now,
    'decided_by', p_decided_by,
    'decided_now', p_decided_now,
    'seen', p_seen
  );
$$;

-- Moves every moderation timestamp of a row into the past so a later reset to now() is observable.
create function pg_temp.age(p_id uuid) returns void language sql as $$
  update public.thoughts
  set requested_at = timestamptz '2026-01-01 00:00:00+00',
      decided_at = case when decided_at is null then null else timestamptz '2026-01-02 00:00:00+00' end,
      approved_at = case when approved_at is null then null else timestamptz '2026-01-02 00:00:00+00' end
  where id = p_id;
$$;

-- Stands in for an M2 staff function: a definer that sets the flag, makes one write as the calling account (its JWT
-- still names the caller) and clears the flag at once.
create function pg_temp.as_moderator(p_sql text) returns void language plpgsql security definer as $$
begin
  perform set_config('gallr.thought_moderation', 'on', true);
  execute p_sql;
  perform set_config('gallr.thought_moderation', '', true);
end;
$$;
grant execute on function pg_temp.as_moderator(text) to authenticated;

-- ── Schema shape ─────────────────────────────────────────────────────────────────────────────────────────────────

select is(
  (
    select count(*)::integer
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'thoughts'
      and column_name in (
        'moderation_status', 'rejection_reason', 'requested_at', 'decided_at', 'decided_by', 'approved_at',
        'approval_seen_at'
      )
  ),
  7,
  'public.thoughts has the seven moderation columns'
);
select col_not_null('public', 'thoughts', 'is_approved', 'is_approved is not null');
select col_default_is('public', 'thoughts', 'is_approved', 'false', 'is_approved defaults to false');
select col_not_null('public', 'thoughts', 'moderation_status', 'moderation_status is not null');
select col_default_is('public', 'thoughts', 'moderation_status', 'pending'::text, 'moderation_status defaults to pending');
select col_not_null('public', 'thoughts', 'requested_at', 'requested_at is not null');
select is(
  (
    select count(*)::integer
    from pg_constraint
    where conrelid = 'public.thoughts'::regclass
      and contype = 'c'
      and conname in (
        'thoughts_status_matches_is_approved',
        'thoughts_moderation_status_valid',
        'thoughts_rejection_reason_valid'
      )
  ),
  3,
  'the three moderation check constraints exist'
);
select ok(
  (
    select pg_get_indexdef(indexrelid) like '%(requested_at)%'
      and pg_get_expr(indpred, indrelid) like '%moderation_status = ''pending''%'
    from pg_index
    where indexrelid = 'public.thoughts_pending_queue_idx'::regclass
  ),
  'the pending review queue has a partial index on requested_at'
);
select ok(
  (
    select pg_get_indexdef(indexrelid) like '%(exhibition_id, created_at DESC)%'
      and pg_get_expr(indpred, indrelid) like '%moderation_status = ''approved''%'
    from pg_index
    where indexrelid = 'public.thoughts_public_by_exhibition_idx'::regclass
  ),
  'public 감상 by exhibition have a partial index'
);
select is(
  (
    select confdeltype::text
    from pg_constraint
    where conrelid = 'public.thoughts'::regclass
      and contype = 'f'
      and conkey = array[(
        select attnum from pg_attribute
        where attrelid = 'public.thoughts'::regclass and attname = 'decided_by'
      )]
  ),
  'n',
  'decided_by references auth.users and is set null when the staff account goes'
);
select ok(
  (
    select prosecdef and proconfig @> array['search_path=""']
    from pg_proc
    where oid = 'content_private.enforce_thought_moderation()'::regprocedure
  ),
  'the moderation trigger is security definer with an empty search_path'
);
select ok(
  not has_function_privilege('anon', 'content_private.enforce_thought_moderation()', 'EXECUTE')
    and not has_function_privilege('authenticated', 'content_private.enforce_thought_moderation()', 'EXECUTE'),
  'nobody can call the moderation trigger function directly'
);

-- Backfill: rows that existed before this suite (a production-derived database) are consistent. In a clean replay
-- the set is empty; production held 4 approved rows on 2026-11-11.
select is(
  (
    select count(*)::integer
    from public.thoughts
    where not (
      requested_at is not null
      and (
        (is_approved and moderation_status = 'approved' and approved_at is not null)
        or (not is_approved and moderation_status in ('pending', 'rejected'))
      )
    )
  ),
  0,
  'backfilled rows carry a status, approved_at when approved, and requested_at'
);

-- ── Grants unchanged ─────────────────────────────────────────────────────────────────────────────────────────────
-- M1 grants nothing: the table-level grants from before stay as they are (RLS does the denying; anon writes are
-- refused by policy below), and no new column carries a column-level grant of its own.

select is(
  (
    select count(*)::integer
    from pg_attribute
    where attrelid = 'public.thoughts'::regclass
      and attname in (
        'moderation_status', 'rejection_reason', 'requested_at', 'decided_at', 'decided_by', 'approved_at',
        'approval_seen_at'
      )
      and attacl is not null
  ),
  0,
  'the moderation columns have no column-level grants'
);
select ok(
  has_table_privilege('anon', 'public.thoughts', 'SELECT')
    and has_table_privilege('authenticated', 'public.thoughts', 'SELECT')
    and has_table_privilege('authenticated', 'public.thoughts', 'INSERT')
    and has_table_privilege('authenticated', 'public.thoughts', 'UPDATE')
    and has_table_privilege('authenticated', 'public.thoughts', 'DELETE'),
  'authenticated keeps select, insert, update and delete on public.thoughts'
);

-- ── Fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────────

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
  ('76000000-0000-4000-8000-000000000001', 'thought-author-a@example.invalid', now(), '{}'),
  ('76000000-0000-4000-8000-000000000002', 'thought-author-b@example.invalid', now(), '{}'),
  ('76000000-0000-4000-8000-000000000003', 'thought-staff-admin@example.invalid', now(), '{}'),
  ('76000000-0000-4000-8000-000000000004', 'thought-staff-publisher@example.invalid', now(), '{}'),
  ('76000000-0000-4000-8000-000000000005', 'thought-reader@example.invalid', now(), '{}');
insert into content.staff_members (user_id, role, active) values
  ('76000000-0000-4000-8000-000000000003', 'admin', true),
  ('76000000-0000-4000-8000-000000000004', 'publisher', true);

-- ── Author inserts always land pending ───────────────────────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000001');
insert into public.thoughts (id, user_id, exhibition_id, content, is_approved)
values (
  '76000000-0000-4000-8000-0000000000a1', '76000000-0000-4000-8000-000000000001', 'ex-076-a',
  'A first note', true
);

select pg_temp.act_as('76000000-0000-4000-8000-000000000002');
insert into public.thoughts (
  id, user_id, exhibition_id, content, is_approved, moderation_status, approved_at, decided_at, decided_by,
  approval_seen_at
) values (
  '76000000-0000-4000-8000-0000000000b1', '76000000-0000-4000-8000-000000000002', 'ex-076-b',
  'B first note', true, 'approved', now(), now(), '76000000-0000-4000-8000-000000000002', now()
);

select pg_temp.act_as('76000000-0000-4000-8000-000000000003');
insert into public.thoughts (id, user_id, exhibition_id, content, is_approved)
values (
  '76000000-0000-4000-8000-0000000000c1', '76000000-0000-4000-8000-000000000003', 'ex-076-s',
  'Staff own note', true
);

reset role;
select pg_temp.act_as_service();

select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000a1'),
  pg_temp.expected('pending', true),
  'an author insert with is_approved true lands pending'
);
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected('pending', true),
  'an author insert asking for approved, with decision fields, lands pending with none of them'
);
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000c1'),
  pg_temp.expected('pending', true),
  'a staff admin inserting their own 감상 is an author and lands pending'
);

-- ── 1.15 staff queue: an admin approves another person's pending row through the table ───────────────────────────

select pg_temp.age('76000000-0000-4000-8000-0000000000a1');
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000003');
update public.thoughts set is_approved = true where id = '76000000-0000-4000-8000-0000000000a1';
reset role;
select pg_temp.act_as_service();

select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000a1'),
  pg_temp.expected(
    'approved', false, p_approved_now => true, p_decided_by => '76000000-0000-4000-8000-000000000003',
    p_decided_now => true
  ),
  'the 1.15 admin queue approves a pending row and records who decided'
);

set local role anon;
select pg_temp.act_as_anon();
select is(
  (select count(*)::integer from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  1,
  'anon reads the approved 감상'
);
reset role;

-- Any other legacy change keeps the moderation state.
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000003');
update public.thoughts set is_approved = false where id = '76000000-0000-4000-8000-0000000000a1';
reset role;
select pg_temp.act_as_service();
select is(
  (select moderation_status || '/' || is_approved::text from public.thoughts
   where id = '76000000-0000-4000-8000-0000000000a1'),
  'approved/true',
  'an admin cannot unapprove through the legacy table path'
);

-- ── approval_seen_at: null → now() only on an approved row ───────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000001');
update public.thoughts
set approval_seen_at = timestamptz '2020-01-01 00:00:00+00'
where id = '76000000-0000-4000-8000-0000000000a1';
reset role;
select pg_temp.act_as_service();
select is(
  (select approval_seen_at from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  now(),
  'the author acknowledges an approval; the server stamps now()'
);
select is(
  (select moderation_status || '/' || is_approved::text from public.thoughts
   where id = '76000000-0000-4000-8000-0000000000a1'),
  'approved/true',
  'acknowledging an approval keeps the row approved'
);

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000001');
update public.thoughts set approval_seen_at = null where id = '76000000-0000-4000-8000-0000000000a1';
reset role;
select pg_temp.act_as_service();
select ok(
  (select approval_seen_at is not null from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  'an acknowledgement cannot be cleared by the author'
);

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000002');
update public.thoughts set approval_seen_at = now() where id = '76000000-0000-4000-8000-0000000000b1';
reset role;
select pg_temp.act_as_service();
select ok(
  (select approval_seen_at is null from public.thoughts where id = '76000000-0000-4000-8000-0000000000b1'),
  'a pending row cannot be acknowledged'
);

-- ── Authors cannot move their own row's moderation state ─────────────────────────────────────────────────────────

select pg_temp.age('76000000-0000-4000-8000-0000000000b1');
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000002');
update public.thoughts
set moderation_status = 'approved'
where id = '76000000-0000-4000-8000-0000000000b1';
update public.thoughts
set is_approved = true, approved_at = now(), decided_at = now(),
    decided_by = '76000000-0000-4000-8000-000000000002'
where id = '76000000-0000-4000-8000-0000000000b1';
update public.thoughts
set moderation_status = 'rejected', rejection_reason = 'other'
where id = '76000000-0000-4000-8000-0000000000b1';
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected('pending', false),
  'an author update that only asks for approved, rejected or decision fields changes nothing'
);

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000002');
update public.thoughts
set user_id = '76000000-0000-4000-8000-000000000001', exhibition_id = 'ex-076-moved'
where id = '76000000-0000-4000-8000-0000000000b1';
reset role;
select pg_temp.act_as_service();
select is(
  (select user_id::text || '/' || exhibition_id from public.thoughts
   where id = '76000000-0000-4000-8000-0000000000b1'),
  '76000000-0000-4000-8000-000000000002/ex-076-b',
  'an author cannot change the owner or exhibition of a 감상'
);

-- A plain author who sets the moderation flag gets no staff power.
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000002');
select pg_temp.moderation_flag(true);
update public.thoughts
set moderation_status = 'approved', approved_at = now()
where id = '76000000-0000-4000-8000-0000000000b1';
select pg_temp.moderation_flag(false);
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected('pending', false),
  'the moderation flag without a publisher role changes nothing'
);

-- ── A publisher has no legacy table path ─────────────────────────────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000004');
update public.thoughts set is_approved = true where id = '76000000-0000-4000-8000-0000000000b1';
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected('pending', false),
  'a publisher cannot approve another person''s 감상 through the table'
);

-- ── A staff admin editing their own row is an author ─────────────────────────────────────────────────────────────

select pg_temp.age('76000000-0000-4000-8000-0000000000c1');
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000003');
update public.thoughts set is_approved = true where id = '76000000-0000-4000-8000-0000000000c1';
update public.thoughts set moderation_status = 'approved' where id = '76000000-0000-4000-8000-0000000000c1';
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000c1'),
  pg_temp.expected('pending', false),
  'a staff admin cannot approve their own 감상 through the table'
);

-- ── The live defect: editing approved text returns it to review (SC-005, 1.15 path) ──────────────────────────────

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000005');
select is(
  (select count(*)::integer from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  1,
  'another signed-in reader sees the approved 감상 before the edit'
);
reset role;

select pg_temp.age('76000000-0000-4000-8000-0000000000a1');
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000001');
update public.thoughts
set content = 'A edited after approval'
where id = '76000000-0000-4000-8000-0000000000a1';
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000a1'),
  pg_temp.expected('pending', true),
  'an author edit of approved text returns it to pending and clears the approval'
);
select is(
  (select content from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  'A edited after approval',
  'the edited text is kept'
);

set local role anon;
select pg_temp.act_as_anon();
select is(
  (select count(*)::integer from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  0,
  'the edited 감상 leaves the anon read at once'
);
reset role;

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000005');
select is(
  (select count(*)::integer from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  0,
  'the edited 감상 leaves other signed-in readers'
);
select pg_temp.act_as('76000000-0000-4000-8000-000000000001');
select is(
  (select count(*)::integer from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  1,
  'the author still reads their pending 감상'
);
reset role;

-- An admin changing someone else's text is not the legacy approve path: the text goes back to review.
select pg_temp.act_as_service();
update public.thoughts
set moderation_status = 'approved', approved_at = now(), decided_at = now()
where id = '76000000-0000-4000-8000-0000000000a1';
select pg_temp.age('76000000-0000-4000-8000-0000000000a1');
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000003');
update public.thoughts
set content = 'Staff rewrote this', is_approved = true
where id = '76000000-0000-4000-8000-0000000000a1';
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000a1'),
  pg_temp.expected('pending', true),
  'a staff edit of another person''s text returns it to pending'
);

-- ── Rejected rows ────────────────────────────────────────────────────────────────────────────────────────────────

update public.thoughts
set moderation_status = 'rejected', rejection_reason = 'promotional', decided_at = now(),
    decided_by = '76000000-0000-4000-8000-000000000003'
where id = '76000000-0000-4000-8000-0000000000b1';
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected(
    'rejected', false, p_decided_by => '76000000-0000-4000-8000-000000000003', p_decided_now => true,
    p_reason => 'promotional'
  ),
  'a service-role write is kept and is_approved follows the status'
);

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000003');
update public.thoughts set is_approved = true where id = '76000000-0000-4000-8000-0000000000b1';
reset role;
select pg_temp.act_as_service();
select is(
  (select moderation_status || '/' || rejection_reason from public.thoughts
   where id = '76000000-0000-4000-8000-0000000000b1'),
  'rejected/promotional',
  'the 1.15 admin queue cannot approve a rejected row'
);

select pg_temp.age('76000000-0000-4000-8000-0000000000b1');
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000002');
update public.thoughts set moderation_status = 'pending' where id = '76000000-0000-4000-8000-0000000000b1';
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected('pending', true),
  'an author asking again moves a rejected row back to pending without its reason'
);

update public.thoughts
set moderation_status = 'rejected', rejection_reason = 'other'
where id = '76000000-0000-4000-8000-0000000000b1';
select pg_temp.age('76000000-0000-4000-8000-0000000000b1');
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000002');
update public.thoughts set content = 'B rewrote it' where id = '76000000-0000-4000-8000-0000000000b1';
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected('pending', true),
  'an author edit of rejected text returns it to pending'
);

-- ── The moderating path (used by the M2 staff functions) ─────────────────────────────────────────────────────────

-- A publisher decides another person's pending 감상. The decision also tries to change what was reviewed (text,
-- owner, exhibition, review request and creation time) and to name someone else as the decider: only the decision
-- is kept, and the decider is the caller.
select pg_temp.age('76000000-0000-4000-8000-0000000000b1');
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000004');
select pg_temp.as_moderator($$
  update public.thoughts
  set moderation_status = 'approved', approved_at = now(), decided_at = now(), approval_seen_at = null,
      decided_by = '76000000-0000-4000-8000-000000000005',
      content = 'Swapped after review', user_id = '76000000-0000-4000-8000-000000000004',
      exhibition_id = 'ex-076-moved', requested_at = now(), created_at = timestamptz '2000-01-01 00:00:00+00'
  where id = '76000000-0000-4000-8000-0000000000b1'
$$);
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected(
    'approved', false, p_approved_now => true, p_decided_by => '76000000-0000-4000-8000-000000000004',
    p_decided_now => true
  ),
  'a publisher''s decision on another person''s 감상 is kept, attributed to the caller'
);
select is(
  (select content || '/' || user_id::text || '/' || exhibition_id || '/' || (created_at > '2001-01-01')::text
   from public.thoughts where id = '76000000-0000-4000-8000-0000000000b1'),
  'B rewrote it/76000000-0000-4000-8000-000000000002/ex-076-b/true',
  'a decision cannot change the text, owner, exhibition or creation time it decided on'
);

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000004');
select pg_temp.as_moderator($$
  update public.thoughts
  set moderation_status = 'rejected', rejection_reason = 'promotional', decided_at = now(),
      decided_by = '76000000-0000-4000-8000-000000000004'
  where id = '76000000-0000-4000-8000-0000000000b1'
$$);
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000b1'),
  pg_temp.expected(
    'rejected', false, p_approved_now => true, p_decided_by => '76000000-0000-4000-8000-000000000004',
    p_decided_now => true, p_reason => 'promotional'
  ),
  'a publisher''s take-down of another person''s 감상 is kept with its reason'
);

-- Without the flag a staff admin's status change on someone else's row is not a decision.
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000003');
update public.thoughts
set moderation_status = 'approved', rejection_reason = null
where id = '76000000-0000-4000-8000-0000000000b1';
reset role;
select pg_temp.act_as_service();
select is(
  (select moderation_status || '/' || rejection_reason from public.thoughts
   where id = '76000000-0000-4000-8000-0000000000b1'),
  'rejected/promotional',
  'without the flag a staff status change through the table is ignored'
);

-- No self-approval: a staff member's own 감상 goes through the author path, flag or not.
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000004');
insert into public.thoughts (id, user_id, exhibition_id, content)
values (
  '76000000-0000-4000-8000-0000000000d1', '76000000-0000-4000-8000-000000000004', 'ex-076-p',
  'Publisher own note'
);
select pg_temp.as_moderator($$
  update public.thoughts
  set moderation_status = 'approved', approved_at = now(), decided_at = now(),
      decided_by = '76000000-0000-4000-8000-000000000004'
  where id = '76000000-0000-4000-8000-0000000000d1'
$$);
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000d1'),
  pg_temp.expected('pending', true),
  'a publisher cannot approve their own 감상 with the moderation flag'
);

-- INSERT is never a decision, with the flag or without.
set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000004');
select pg_temp.as_moderator($$
  insert into public.thoughts (
    id, user_id, exhibition_id, content, is_approved, moderation_status, approved_at, decided_at, decided_by
  ) values (
    '76000000-0000-4000-8000-0000000000d2', '76000000-0000-4000-8000-000000000004', 'ex-076-p2',
    'Publisher second note', true, 'approved', now(), now(), '76000000-0000-4000-8000-000000000004'
  )
$$);
select pg_temp.as_moderator($$
  insert into public.thoughts (
    id, user_id, exhibition_id, content, is_approved, moderation_status, approved_at, decided_at, decided_by
  ) values (
    '76000000-0000-4000-8000-0000000000f1', '76000000-0000-4000-8000-000000000002', 'ex-076-f',
    'Inserted for someone else', true, 'approved', now(), now(), '76000000-0000-4000-8000-000000000004'
  )
$$);
reset role;
select pg_temp.act_as_service();
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000d2'),
  pg_temp.expected('pending', true),
  'a publisher''s own insert with the moderation flag lands pending'
);
select is(
  pg_temp.state('76000000-0000-4000-8000-0000000000f1'),
  pg_temp.expected('pending', true),
  'an insert for another account with the moderation flag lands pending'
);

-- ── The 1.15 author flow: insert, read own pending, delete own ───────────────────────────────────────────────────

set local role authenticated;
select pg_temp.act_as('76000000-0000-4000-8000-000000000005');
select lives_ok(
  $$insert into public.thoughts (user_id, exhibition_id, content)
    values ('76000000-0000-4000-8000-000000000005', 'ex-076-r', 'Reader note')$$,
  'a 1.15 author can insert a 감상'
);
select is(
  (select is_approved from public.thoughts
   where user_id = '76000000-0000-4000-8000-000000000005' and exhibition_id = 'ex-076-r'),
  false,
  'the 1.15 author reads their own pending 감상 with is_approved false'
);
select lives_ok(
  $$update public.thoughts set content = 'Reader note, edited'
    where user_id = '76000000-0000-4000-8000-000000000005' and exhibition_id = 'ex-076-r'$$,
  'a 1.15 author can edit their pending 감상'
);
select lives_ok(
  $$delete from public.thoughts
    where user_id = '76000000-0000-4000-8000-000000000005' and exhibition_id = 'ex-076-r'$$,
  'a 1.15 author can delete their own 감상'
);
reset role;
select pg_temp.act_as_service();
select is(
  (select count(*)::integer from public.thoughts
   where user_id = '76000000-0000-4000-8000-000000000005' and exhibition_id = 'ex-076-r'),
  0,
  'the deleted 감상 is gone'
);

set local role anon;
select pg_temp.act_as_anon();
select throws_ok(
  $$insert into public.thoughts (user_id, exhibition_id, content)
    values ('76000000-0000-4000-8000-000000000005', 'ex-076-anon', 'Anon note')$$,
  '42501',
  null,
  'anon cannot insert a 감상'
);
select is(
  (select count(*)::integer from public.thoughts where id::text like '76000000-%' and not is_approved),
  0,
  'anon never reads a pending or rejected 감상'
);
reset role;

-- ── Service role ─────────────────────────────────────────────────────────────────────────────────────────────────

select pg_temp.act_as_service();
insert into public.thoughts (id, user_id, exhibition_id, content, is_approved)
values (
  '76000000-0000-4000-8000-0000000000e1', '76000000-0000-4000-8000-000000000005', 'ex-076-svc',
  'Service note', true
);
select is(
  (select moderation_status || '/' || is_approved::text from public.thoughts
   where id = '76000000-0000-4000-8000-0000000000e1'),
  'pending/false',
  'a service-role insert without a status lands pending; is_approved follows the status'
);
update public.thoughts
set moderation_status = 'approved', approved_at = now()
where id = '76000000-0000-4000-8000-0000000000e1';
select is(
  (select moderation_status || '/' || is_approved::text from public.thoughts
   where id = '76000000-0000-4000-8000-0000000000e1'),
  'approved/true',
  'a service-role approval sets is_approved'
);

-- ── Check constraints ────────────────────────────────────────────────────────────────────────────────────────────

select throws_ok(
  $$update public.thoughts set moderation_status = 'hidden'
    where id = '76000000-0000-4000-8000-0000000000e1'$$,
  '23514',
  null,
  'an unknown status is refused'
);
select throws_ok(
  $$update public.thoughts set moderation_status = 'rejected', rejection_reason = null
    where id = '76000000-0000-4000-8000-0000000000e1'$$,
  '23514',
  null,
  'a rejection without a reason is refused'
);
select throws_ok(
  $$update public.thoughts set moderation_status = 'rejected', rejection_reason = 'spam'
    where id = '76000000-0000-4000-8000-0000000000e1'$$,
  '23514',
  null,
  'a rejection reason outside the list is refused'
);
select throws_ok(
  $$update public.thoughts set rejection_reason = 'other'
    where id = '76000000-0000-4000-8000-0000000000e1'$$,
  '23514',
  null,
  'a reason on a row that is not rejected is refused'
);
select lives_ok(
  $$update public.thoughts set moderation_status = 'rejected', rejection_reason = 'inappropriate_language'
    where id = '76000000-0000-4000-8000-0000000000e1'$$,
  'a rejection with a listed reason is accepted'
);

alter table public.thoughts disable trigger thoughts_enforce_moderation;
select throws_ok(
  $$update public.thoughts set is_approved = true
    where id = '76000000-0000-4000-8000-0000000000e1'$$,
  '23514',
  null,
  'is_approved cannot disagree with the status even when the trigger is bypassed'
);
select throws_ok(
  $$update public.thoughts set is_approved = null
    where id = '76000000-0000-4000-8000-0000000000e1'$$,
  '23502',
  null,
  'is_approved cannot be null'
);
alter table public.thoughts enable trigger thoughts_enforce_moderation;

select is(
  (
    select count(*)::integer
    from public.thoughts
    where id::text like '76000000-%'
      and not (
        is_approved = (moderation_status = 'approved')
        and (moderation_status <> 'approved' or approved_at is not null)
        and requested_at is not null
      )
  ),
  0,
  'every fixture row is consistent after all cases'
);

-- ── Account removal ──────────────────────────────────────────────────────────────────────────────────────────────
-- Active staff accounts are protected from deletion (account_deletion_requires_support), so the decider here is a
-- plain account written by the service role.

delete from auth.users where id = '76000000-0000-4000-8000-000000000001';
select ok(
  not exists (select 1 from public.thoughts where id = '76000000-0000-4000-8000-0000000000a1'),
  'deleting an author cascades their 감상'
);
update public.thoughts
set moderation_status = 'approved', rejection_reason = null, approved_at = now(), decided_at = now(),
    decided_by = '76000000-0000-4000-8000-000000000005'
where id = '76000000-0000-4000-8000-0000000000b1';
delete from auth.users where id = '76000000-0000-4000-8000-000000000005';
select is(
  (select moderation_status || '/' || coalesce(decided_by::text, 'null') from public.thoughts
   where id = '76000000-0000-4000-8000-0000000000b1'),
  'approved/null',
  'deleting the deciding account keeps the decision and clears decided_by'
);

select * from finish();
rollback;
