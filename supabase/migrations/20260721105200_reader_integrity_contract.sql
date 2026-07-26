-- Give paginated public readers a single-snapshot integrity value.
--
-- Count alone cannot detect a concurrent delete plus insert. The checksum uses
-- an unambiguous byte-length-prefixed id stream in the same database ordering
-- as the readers' keyset cursor. Readers compare both values after draining all
-- pages and retry the complete read once if either differs.

create or replace function public.exhibition_reader_integrity(
  p_event_id text default null,
  p_featured_only boolean default false
)
returns table (
  row_count bigint,
  id_checksum_sha256 text
)
language sql
stable
security invoker
set search_path = ''
as $function$
  with scoped as (
    select exhibition.id
    from public.exhibitions as exhibition
    where (p_event_id is null or exhibition.event_id = p_event_id)
      and (
        not coalesce(p_featured_only, false)
        or exhibition.is_featured = true
      )
  )
  select
    count(*)::bigint,
    encode(
      extensions.digest(
        convert_to(
          coalesce(
            string_agg(
              octet_length(scoped.id)::text || ':' || scoped.id,
              ''
              order by scoped.id
            ),
            ''
          ),
          'UTF8'
        ),
        'sha256'
      ),
      'hex'
    )
  from scoped;
$function$;

comment on function public.exhibition_reader_integrity(text, boolean) is
  'Returns a single-snapshot row count and SHA-256 checksum for all, event-scoped, or featured public exhibitions.';

revoke all
  on function public.exhibition_reader_integrity(text, boolean)
  from public;
grant execute
  on function public.exhibition_reader_integrity(text, boolean)
  to anon, authenticated, service_role;
