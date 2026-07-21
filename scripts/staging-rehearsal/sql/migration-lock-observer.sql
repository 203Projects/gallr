\set ON_ERROR_STOP on
\pset pager off

-- Start in a second trusted direct-database session immediately before the
-- reviewed migration push. Stop with Ctrl-C only after the push finishes.
select
  pg_catalog.clock_timestamp() at time zone 'UTC' as observed_at_utc,
  activity.pid,
  activity.application_name,
  activity.state,
  activity.xact_start,
  activity.query_start,
  activity.wait_event_type,
  activity.wait_event,
  pg_catalog.pg_blocking_pids(activity.pid) as blocking_pids,
  lock.locktype,
  lock.mode,
  lock.granted,
  lock.waitstart,
  namespace.nspname as relation_schema,
  relation.relname as relation_name
from pg_catalog.pg_stat_activity as activity
left join pg_catalog.pg_locks as lock on lock.pid = activity.pid
left join pg_catalog.pg_database as lock_database
  on lock_database.oid = lock.database
left join pg_catalog.pg_class as relation
  on relation.oid = lock.relation
 and lock_database.datname = pg_catalog.current_database()
left join pg_catalog.pg_namespace as namespace
  on namespace.oid = relation.relnamespace
where activity.pid <> pg_catalog.pg_backend_pid()
  and activity.datname = pg_catalog.current_database()
  and (
    activity.wait_event_type = 'Lock'
    or (
      namespace.nspname in ('public', 'content', 'content_private')
      and relation.relname in (
        'exhibitions',
        'exhibition_versions',
        'curation_placements',
        'exhibition_version_media',
        'media_assets',
        'exhibition_catalog_v2'
      )
    )
  )
order by activity.pid, lock.granted, lock.mode, relation.relname;
\watch 0.25
