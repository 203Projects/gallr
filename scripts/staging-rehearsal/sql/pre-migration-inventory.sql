\set ON_ERROR_STOP on
\pset pager off
\pset null '(null)'

-- Run with psql -X so user startup files cannot change this evidence session.
-- This script is read-only and is intentionally compatible with a clone that
-- does not yet have the canonical CMS migrations installed.
begin transaction isolation level repeatable read read only;
set local timezone = 'UTC';

select
  pg_catalog.clock_timestamp() at time zone 'UTC' as captured_at_utc,
  pg_catalog.current_database() as database_name,
  current_user as database_user,
  pg_catalog.current_setting('server_version') as server_version;

select
  pg_catalog.to_regclass('public.exhibitions') as legacy_table,
  pg_catalog.to_regclass('public.profiles') as profiles_table,
  pg_catalog.to_regclass('public.bookmarks') as bookmarks_table,
  pg_catalog.to_regclass('content.exhibitions') as canonical_table,
  pg_catalog.to_regclass('content.exhibition_versions') as version_table,
  pg_catalog.to_regclass('public.exhibition_catalog_v2') as catalog_v2_table,
  pg_catalog.to_regprocedure(
    'public.exhibition_reader_integrity(text,boolean)'
  ) as legacy_integrity_rpc,
  pg_catalog.to_regprocedure(
    'public.exhibition_catalog_v2_integrity(text,boolean)'
  ) as catalog_v2_integrity_rpc;

-- Version 005 is already recorded on the production lineage, while the
-- historical CLI-skipped 005b profiles/bookmarks objects were installed out of
-- band. A production-derived clone therefore must prove those objects and
-- access boundaries before the normalized clean-replay version 005 is skipped.
select
  coalesce(
    (
      select relation.relrowsecurity
      from pg_catalog.pg_class as relation
      join pg_catalog.pg_namespace as namespace
        on namespace.oid = relation.relnamespace
      where namespace.nspname = 'public'
        and relation.relname = 'profiles'
        and relation.relkind = 'r'
    ),
    false
  ) as profiles_rls_enabled,
  coalesce(
    (
      select relation.relrowsecurity
      from pg_catalog.pg_class as relation
      join pg_catalog.pg_namespace as namespace
        on namespace.oid = relation.relnamespace
      where namespace.nspname = 'public'
        and relation.relname = 'bookmarks'
        and relation.relkind = 'r'
    ),
    false
  ) as bookmarks_rls_enabled,
  (
    select namespace.nspname || '.' || procedure.proname
    from pg_catalog.pg_trigger as trigger
    join pg_catalog.pg_proc as procedure on procedure.oid = trigger.tgfoid
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where trigger.tgname = 'on_auth_user_created'
      and trigger.tgrelid = 'auth.users'::regclass
      and not trigger.tgisinternal
      and trigger.tgenabled <> 'D'
      and namespace.nspname in ('public', 'content_private')
    limit 1
  ) as signup_trigger_function,
  (
    select array_agg(policy.policyname order by policy.policyname)
    from pg_catalog.pg_policies as policy
    where policy.schemaname = 'public'
      and policy.tablename = 'profiles'
  ) as profiles_policies,
  (
    select array_agg(policy.policyname order by policy.policyname)
    from pg_catalog.pg_policies as policy
    where policy.schemaname = 'public'
      and policy.tablename = 'bookmarks'
  ) as bookmarks_policies,
  coalesce(
    pg_catalog.has_table_privilege(
      'anon',
      pg_catalog.to_regclass('public.profiles'),
      'SELECT'
    ),
    false
  ) as anon_profiles_select,
  coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.profiles'),
      'SELECT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.profiles'),
      'INSERT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.profiles'),
      'UPDATE'
    ),
    false
  ) as authenticated_profiles_access,
  coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.bookmarks'),
      'SELECT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.bookmarks'),
      'INSERT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.bookmarks'),
      'UPDATE'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.bookmarks'),
      'DELETE'
    ),
    false
  ) as authenticated_bookmarks_access;

select
  pg_catalog.to_regclass('public.profiles') is not null
  and pg_catalog.to_regclass('public.bookmarks') is not null
  and coalesce(
    (
      select bool_and(relation.relrowsecurity)
      from pg_catalog.pg_class as relation
      join pg_catalog.pg_namespace as namespace
        on namespace.oid = relation.relnamespace
      where namespace.nspname = 'public'
        and relation.relname in ('profiles', 'bookmarks')
        and relation.relkind = 'r'
    ),
    false
  )
  and (
    select count(*) = 2
    from pg_catalog.pg_class as relation
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = relation.relnamespace
    where namespace.nspname = 'public'
      and relation.relname in ('profiles', 'bookmarks')
      and relation.relkind = 'r'
  )
  and exists (
    select 1
    from pg_catalog.pg_trigger as trigger
    join pg_catalog.pg_proc as procedure on procedure.oid = trigger.tgfoid
    join pg_catalog.pg_namespace as namespace
      on namespace.oid = procedure.pronamespace
    where trigger.tgname = 'on_auth_user_created'
      and trigger.tgrelid = 'auth.users'::regclass
      and not trigger.tgisinternal
      and trigger.tgenabled <> 'D'
      and procedure.proname = 'handle_new_user'
      and namespace.nspname in ('public', 'content_private')
  )
  and not exists (
    select required.table_name, required.policy_name
    from (
      values
        ('profiles', 'Public read'),
        ('profiles', 'Owner insert'),
        ('profiles', 'Owner write'),
        ('bookmarks', 'Owner read'),
        ('bookmarks', 'Owner write'),
        ('bookmarks', 'Owner update'),
        ('bookmarks', 'Owner delete')
    ) as required(table_name, policy_name)
    where not exists (
      select 1
      from pg_catalog.pg_policies as policy
      where policy.schemaname = 'public'
        and policy.tablename = required.table_name
        and policy.policyname = required.policy_name
    )
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'anon',
      pg_catalog.to_regclass('public.profiles'),
      'SELECT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.profiles'),
      'SELECT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.profiles'),
      'INSERT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.profiles'),
      'UPDATE'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.bookmarks'),
      'SELECT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.bookmarks'),
      'INSERT'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.bookmarks'),
      'UPDATE'
    ),
    false
  )
  and coalesce(
    pg_catalog.has_table_privilege(
      'authenticated',
      pg_catalog.to_regclass('public.bookmarks'),
      'DELETE'
    ),
    false
  ) as version_five_prerequisites_exist
\gset

\if :version_five_prerequisites_exist
  \echo 'Version-005 out-of-band profiles/bookmarks prerequisites are present.'
\else
  \echo 'ERROR: version-005 out-of-band profiles/bookmarks prerequisites are missing or unsafe.'
  select 1 / 0 as staging_validation_failed;
\endif

-- The populated clone must contain the legacy production catalog.
select
  count(*)::bigint as legacy_row_count,
  count(*) filter (where exhibition.is_featured)::bigint
    as legacy_featured_count,
  count(*) filter (where exhibition.event_id is not null)::bigint
    as legacy_event_linked_count,
  count(*) filter (where exhibition.editor_id is not null)::bigint
    as legacy_editor_linked_count,
  count(*) filter (
    where exhibition.latitude is null and exhibition.longitude is null
  )::bigint as legacy_null_coordinate_count,
  min(exhibition.updated_at) as legacy_oldest_updated_at,
  max(exhibition.updated_at) as legacy_newest_updated_at,
  pg_catalog.md5(
    coalesce(
      string_agg(
        pg_catalog.octet_length(
          pg_catalog.convert_to(exhibition.id, 'UTF8')
        )::text || ':' || exhibition.id,
        '' order by exhibition.id
      ),
      ''
    )
  ) as legacy_id_membership_md5
from public.exhibitions as exhibition;

select pg_catalog.to_regprocedure('extensions.digest(bytea,text)') is not null
  as sha256_digest_exists
\gset

\if :sha256_digest_exists
  with legacy_payloads as (
    select
      exhibition.id,
      (to_jsonb(exhibition) - 'ticket_url')::text as payload
    from public.exhibitions as exhibition
  )
  select encode(
    extensions.digest(
      pg_catalog.convert_to(
        coalesce(
          string_agg(
            pg_catalog.octet_length(
              pg_catalog.convert_to(payload.id, 'UTF8')
            )::text || ':' || payload.id
              || pg_catalog.octet_length(
                pg_catalog.convert_to(payload.payload, 'UTF8')
              )::text || ':' || payload.payload,
            '' order by payload.id
          ),
          ''
        ),
        'UTF8'
      ),
      'sha256'
    ),
    'hex'
  ) as legacy_full_payload_sha256
  from legacy_payloads as payload
  \gset

  \echo legacy_full_payload_sha256=:legacy_full_payload_sha256
  select :'legacy_full_payload_sha256'::text as legacy_full_payload_sha256;
\else
  \echo 'ERROR: extensions.digest(bytea,text) is required for the full legacy payload fingerprint.'
  select 1 / 0 as staging_validation_failed;
\endif

select
  pg_catalog.to_regclass('content.exhibitions') is not null
    and pg_catalog.to_regclass('content.exhibition_versions') is not null
    as canonical_tables_exist
\gset

\if :canonical_tables_exist
  select
    count(*)::bigint as canonical_identity_count,
    count(*) filter (where exhibition.archived_at is not null)::bigint
      as canonical_archived_count,
    count(*) filter (where exhibition.published_version_id is null)::bigint
      as canonical_without_published_pointer_count
  from content.exhibitions as exhibition;

  select
    count(*)::bigint as canonical_version_count,
    count(*) filter (where version.status::text = 'draft')::bigint
      as canonical_draft_count,
    count(*) filter (where version.status::text = 'published')::bigint
      as canonical_published_version_count,
    count(*) filter (where version.status::text = 'superseded')::bigint
      as canonical_superseded_count
  from content.exhibition_versions as version;
\else
  \echo 'Canonical tables are not installed in the pre-migration snapshot.'
\endif

select
  pg_catalog.to_regprocedure(
    'public.exhibition_reader_integrity(text,boolean)'
  ) is not null as legacy_integrity_exists
\gset

\if :legacy_integrity_exists
  select *
  from public.exhibition_reader_integrity(null, false);
\else
  \echo 'Legacy integrity RPC is not installed; retain the full SHA-256 payload fingerprint above.'
\endif

select
  pg_catalog.to_regclass('supabase_migrations.schema_migrations') is not null
    as migration_history_exists
\gset

\if :migration_history_exists
  select version, name
  from supabase_migrations.schema_migrations
  order by version;
\else
  \echo 'Supabase migration history table is not present.'
\endif

commit;
