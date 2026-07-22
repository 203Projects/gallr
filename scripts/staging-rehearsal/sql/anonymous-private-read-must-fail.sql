\set ON_ERROR_STOP on
\set VERBOSITY verbose
\pset pager off

begin transaction isolation level repeatable read read only;
set local role anon;
\echo 'GALLR_ANON_ROLE_ASSUMED'

-- Expected result: psql exits non-zero with SQLSTATE 42501.
select count(*) from content.exhibition_versions;

-- Reaching this marker means the access control failed open.
rollback;
\echo 'GALLR_EXPECTED_DENIAL_DID_NOT_OCCUR'
select 1 / 0 as anonymous_private_read_denial_did_not_occur;
