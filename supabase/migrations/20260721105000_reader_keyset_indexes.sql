-- Support the public readers' eager keyset pagination paths.
--
-- The unfiltered catalog already uses exhibitions_pkey (id). Event-scoped
-- reads add an equality predicate before the id cursor, while featured reads
-- always use the same boolean predicate. Match those query shapes directly so
-- later pages do not degrade into repeated scans as the catalog grows.

create index if not exists exhibitions_event_id_id_idx
  on public.exhibitions (event_id, id)
  where event_id is not null;

create index if not exists exhibitions_featured_id_idx
  on public.exhibitions (id)
  where is_featured = true;
