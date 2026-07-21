\set ON_ERROR_STOP on
\set VERBOSITY verbose
\pset pager off

begin;
set local role anon;
\echo 'GALLR_ANON_ROLE_ASSUMED'

-- Expected result: psql exits non-zero with SQLSTATE 42501. The open
-- transaction also guarantees rollback if this unexpectedly reaches the row.
insert into public.exhibition_catalog_v2 (id)
values ('gallr-staging-access-check-must-never-commit');

-- Reaching this marker means the access control failed open.
rollback;
\echo 'GALLR_EXPECTED_DENIAL_DID_NOT_OCCUR'
select 1 / 0 as anonymous_write_denial_did_not_occur;
