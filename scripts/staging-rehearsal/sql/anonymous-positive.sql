\set ON_ERROR_STOP on
\pset pager off
\pset null '(null)'

begin transaction isolation level repeatable read read only;
set local role anon;
\echo 'GALLR_ANON_ROLE_ASSUMED'

select count(*)::bigint as anonymous_catalog_v2_count
from public.exhibition_catalog_v2;

select *
from public.exhibition_catalog_v2_integrity(null, false);

reset role;
commit;
