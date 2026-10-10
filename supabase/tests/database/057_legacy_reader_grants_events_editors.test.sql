-- The installed app reads public.events and public.editors through the REST endpoint with the
-- publishable key. A clean replay must grant the Data API roles SELECT on both tables; writes stay
-- with the service role and the definer-rights mirror functions.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(12);

select ok(has_table_privilege('anon', 'public.events', 'select'), 'anon can select events');
select ok(has_table_privilege('authenticated', 'public.events', 'select'), 'authenticated can select events');
select ok(has_table_privilege('anon', 'public.editors', 'select'), 'anon can select editors');
select ok(has_table_privilege('authenticated', 'public.editors', 'select'), 'authenticated can select editors');

select ok(not has_table_privilege('anon', 'public.events', 'insert'), 'anon cannot insert events');
select ok(not has_table_privilege('authenticated', 'public.events', 'insert, update, delete'), 'authenticated cannot write events');
select ok(not has_table_privilege('anon', 'public.editors', 'insert'), 'anon cannot insert editors');
select ok(not has_table_privilege('authenticated', 'public.editors', 'insert, update, delete'), 'authenticated cannot write editors');

select ok(has_table_privilege('service_role', 'public.events', 'select, insert, update, delete'), 'service role can maintain events');
select ok(has_table_privilege('service_role', 'public.editors', 'select, insert, update, delete'), 'service role can maintain editors');

-- Row-level security still gates what readers see.
select ok((select relrowsecurity from pg_class where oid = 'public.events'::regclass), 'events keeps row-level security');
select ok((select relrowsecurity from pg_class where oid = 'public.editors'::regclass), 'editors keeps row-level security');

select * from finish();
rollback;
