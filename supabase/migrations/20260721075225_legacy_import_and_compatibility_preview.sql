-- Legacy exhibition import, reconciliation, and compatibility preview.
--
-- This migration is additive and local-first. It does not read from or write
-- to public.exhibitions, does not expose canonical content to public clients,
-- and does not archive records that are absent from an import snapshot.
-- Operators first stage an immutable bundle, review its report, and then apply
-- the validated batch through a service-role-only command.

alter table content.exhibition_versions
  add column if not exists legacy_source_updated_at timestamptz;

create table content.legacy_import_batches (
  id uuid primary key default gen_random_uuid(),
  source_system text not null,
  source_file_name text not null,
  source_snapshot_at timestamptz not null,
  source_sha256 text not null,
  normalized_sha256 text not null,
  status text not null default 'staging',
  row_count integer not null,
  error_count integer not null default 0,
  warning_count integer not null default 0,
  report jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  validated_at timestamptz,
  applied_at timestamptz,
  baseline_batch_id uuid
    references content.legacy_import_batches(id) on delete restrict,
  constraint legacy_import_batches_source_system_not_blank check (
    length(btrim(source_system)) > 0
  ),
  constraint legacy_import_batches_source_file_name_not_blank check (
    length(btrim(source_file_name)) > 0
  ),
  constraint legacy_import_batches_source_sha256_format check (
    source_sha256 ~ '^[0-9a-f]{64}$'
  ),
  constraint legacy_import_batches_normalized_sha256_format check (
    normalized_sha256 ~ '^[0-9a-f]{64}$'
  ),
  constraint legacy_import_batches_status check (
    status in ('staging', 'validated', 'applied')
  ),
  constraint legacy_import_batches_row_count_positive check (
    row_count > 0 and row_count <= 5000
  ),
  constraint legacy_import_batches_issue_counts_nonnegative check (
    error_count >= 0 and warning_count >= 0
  ),
  constraint legacy_import_batches_report_object check (
    jsonb_typeof(report) = 'object'
  ),
  constraint legacy_import_batches_baseline_not_self check (
    baseline_batch_id is null or baseline_batch_id <> id
  ),
  unique (source_system, source_sha256)
);

create table content.legacy_import_rows (
  batch_id uuid not null
    references content.legacy_import_batches(id) on delete cascade,
  row_ordinal integer not null,
  source_row_number integer,
  source_id text,
  raw_payload jsonb not null,
  normalized_payload jsonb not null,
  row_sha256 text not null,
  issues jsonb not null default '[]'::jsonb,
  action text,
  applied_version_id uuid
    references content.exhibition_versions(id) on delete restrict,
  created_at timestamptz not null default now(),
  primary key (batch_id, row_ordinal),
  constraint legacy_import_rows_ordinal_positive check (row_ordinal > 0),
  constraint legacy_import_rows_source_row_positive check (
    source_row_number is null or source_row_number > 0
  ),
  constraint legacy_import_rows_raw_object check (
    jsonb_typeof(raw_payload) = 'object'
  ),
  constraint legacy_import_rows_normalized_object check (
    jsonb_typeof(normalized_payload) = 'object'
  ),
  constraint legacy_import_rows_sha256_format check (
    row_sha256 ~ '^[0-9a-f]{64}$'
  ),
  constraint legacy_import_rows_issues_array check (
    jsonb_typeof(issues) = 'array'
  ),
  constraint legacy_import_rows_action check (
    action is null or action in ('blocked', 'insert', 'revise', 'unchanged')
  )
);

create table content.legacy_import_links (
  source_system text not null,
  source_id text not null,
  exhibition_id text not null
    references content.exhibitions(id) on delete restrict,
  last_imported_version_id uuid not null,
  last_row_sha256 text not null,
  first_batch_id uuid not null
    references content.legacy_import_batches(id) on delete restrict,
  last_batch_id uuid not null
    references content.legacy_import_batches(id) on delete restrict,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (source_system, source_id),
  constraint legacy_import_links_version_fk
    foreign key (exhibition_id, last_imported_version_id)
    references content.exhibition_versions(exhibition_id, id)
    on delete restrict,
  constraint legacy_import_links_identity_match check (
    source_id = exhibition_id
  ),
  constraint legacy_import_links_sha256_format check (
    last_row_sha256 ~ '^[0-9a-f]{64}$'
  )
);

create index legacy_import_rows_source_id_idx
  on content.legacy_import_rows (source_id, batch_id);
create index legacy_import_batches_normalized_sha_idx
  on content.legacy_import_batches (source_system, normalized_sha256);
create index legacy_import_batches_applied_snapshot_idx
  on content.legacy_import_batches (
    source_system,
    source_snapshot_at desc,
    applied_at desc
  )
  where status = 'applied';
create index legacy_import_batches_baseline_idx
  on content.legacy_import_batches (baseline_batch_id)
  where baseline_batch_id is not null;
create index legacy_import_rows_applied_version_idx
  on content.legacy_import_rows (applied_version_id)
  where applied_version_id is not null;
create index legacy_import_links_exhibition_idx
  on content.legacy_import_links (exhibition_id);
create index legacy_import_links_version_idx
  on content.legacy_import_links (last_imported_version_id);
create index legacy_import_links_first_batch_idx
  on content.legacy_import_links (first_batch_id);
create index legacy_import_links_last_batch_idx
  on content.legacy_import_links (last_batch_id);

alter table content.legacy_import_batches enable row level security;
alter table content.legacy_import_rows enable row level security;
alter table content.legacy_import_links enable row level security;

revoke all on content.legacy_import_batches
  from public, anon, authenticated;
revoke all on content.legacy_import_rows
  from public, anon, authenticated;
revoke all on content.legacy_import_links
  from public, anon, authenticated;
grant all on content.legacy_import_batches to service_role;
grant all on content.legacy_import_rows to service_role;
grant all on content.legacy_import_links to service_role;

create trigger legacy_import_links_set_updated_at
  before update on content.legacy_import_links
  for each row execute function content_private.set_updated_at();

create or replace function content_private.legacy_try_date(p_value text)
returns date
language plpgsql
stable
set search_path = ''
as $$
begin
  if p_value is null or btrim(p_value) = '' then
    return null;
  end if;
  return replace(btrim(p_value), '.', '-')::date;
exception
  when datetime_field_overflow or invalid_datetime_format then
    return null;
end;
$$;

create or replace function content_private.legacy_try_timestamptz(p_value text)
returns timestamptz
language plpgsql
stable
set search_path = ''
as $$
begin
  if p_value is null or btrim(p_value) = '' then
    return null;
  end if;
  return btrim(p_value)::timestamptz;
exception
  when datetime_field_overflow or invalid_datetime_format then
    return null;
end;
$$;

create or replace function content_private.legacy_try_double(p_value text)
returns double precision
language plpgsql
immutable
set search_path = ''
as $$
begin
  if p_value is null or btrim(p_value) = '' then
    return null;
  end if;
  return btrim(p_value)::double precision;
exception
  when invalid_text_representation or numeric_value_out_of_range then
    return null;
end;
$$;

create or replace function content_private.legacy_try_boolean(p_value jsonb)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_value text;
begin
  if p_value is null or p_value = 'null'::jsonb then
    return null;
  end if;
  if jsonb_typeof(p_value) = 'boolean' then
    return (p_value #>> '{}')::boolean;
  end if;
  v_value := lower(btrim(p_value #>> '{}'));
  if v_value in ('true', '1', 'yes') then
    return true;
  end if;
  if v_value in ('false', '0', 'no') then
    return false;
  end if;
  return null;
end;
$$;

create or replace function content_private.legacy_sha256_json(p_value jsonb)
returns text
language sql
immutable
set search_path = ''
as $$
  select encode(
    extensions.digest(convert_to(coalesce(p_value, 'null'::jsonb)::text, 'UTF8'), 'sha256'),
    'hex'
  );
$$;

create or replace function content_private.legacy_normalize_exhibition_row(
  p_row jsonb
)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object(
    'id', btrim(coalesce(p_row ->> 'id', '')),
    'name_ko', btrim(coalesce(p_row ->> 'name_ko', '')),
    'name_en', btrim(coalesce(p_row ->> 'name_en', '')),
    'venue_name_ko', btrim(coalesce(p_row ->> 'venue_name_ko', '')),
    'venue_name_en', btrim(coalesce(p_row ->> 'venue_name_en', '')),
    'city_ko', btrim(coalesce(p_row ->> 'city_ko', '')),
    'city_en', btrim(coalesce(p_row ->> 'city_en', '')),
    'region_ko', btrim(coalesce(p_row ->> 'region_ko', '')),
    'region_en', btrim(coalesce(p_row ->> 'region_en', '')),
    'opening_date', replace(btrim(coalesce(p_row ->> 'opening_date', '')), '.', '-'),
    'closing_date', replace(btrim(coalesce(p_row ->> 'closing_date', '')), '.', '-'),
    'is_featured', content_private.legacy_try_boolean(p_row -> 'is_featured'),
    'latitude', nullif(btrim(coalesce(p_row ->> 'latitude', '')), ''),
    'longitude', nullif(btrim(coalesce(p_row ->> 'longitude', '')), ''),
    'description_ko', btrim(coalesce(p_row ->> 'description_ko', '')),
    'description_en', btrim(coalesce(p_row ->> 'description_en', '')),
    'address_ko', btrim(coalesce(p_row ->> 'address_ko', '')),
    'address_en', btrim(coalesce(p_row ->> 'address_en', '')),
    'cover_image_url', nullif(btrim(coalesce(p_row ->> 'cover_image_url', '')), ''),
    'hours', nullif(btrim(coalesce(p_row ->> 'hours', '')), ''),
    'contact', nullif(btrim(coalesce(p_row ->> 'contact', '')), ''),
    'reception_date', nullif(btrim(coalesce(p_row ->> 'reception_date', '')), ''),
    'opening_time', nullif(btrim(coalesce(p_row ->> 'opening_time', '')), ''),
    'event_id', nullif(btrim(coalesce(p_row ->> 'event_id', '')), ''),
    'editor_id', nullif(btrim(coalesce(p_row ->> 'editor_id', '')), ''),
    'is_homepage_featured',
      content_private.legacy_try_boolean(p_row -> 'is_homepage_featured'),
    'ticket_url', nullif(btrim(coalesce(p_row ->> 'ticket_url', '')), ''),
    'updated_at', nullif(btrim(coalesce(p_row ->> 'updated_at', '')), '')
  );
$$;

create or replace function content_private.legacy_issue(
  p_severity text,
  p_code text,
  p_field text,
  p_message text
)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select jsonb_build_object(
    'severity', p_severity,
    'code', p_code,
    'field', p_field,
    'message', p_message
  );
$$;

create or replace function content_private.legacy_import_row_issues(
  p_batch_id uuid,
  p_row_ordinal integer
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_row content.legacy_import_rows%rowtype;
  v_batch content.legacy_import_batches%rowtype;
  v_payload jsonb;
  v_raw jsonb;
  v_issues jsonb := '[]'::jsonb;
  v_field text;
  v_id text;
  v_opening date;
  v_closing date;
  v_lat_text text;
  v_lon_text text;
  v_lat double precision;
  v_lon double precision;
  v_url text;
  v_link content.legacy_import_links%rowtype;
begin
  select * into strict v_row
  from content.legacy_import_rows
  where batch_id = p_batch_id and row_ordinal = p_row_ordinal;

  select * into strict v_batch
  from content.legacy_import_batches
  where id = p_batch_id;

  v_payload := v_row.normalized_payload;
  v_raw := v_row.raw_payload;
  v_id := v_payload ->> 'id';

  if exists (
    select 1
    from content.legacy_import_batches as applied
    where applied.source_system = v_batch.source_system
      and applied.status = 'applied'
      and applied.id <> v_batch.id
      and applied.source_snapshot_at >= v_batch.source_snapshot_at
  ) then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'source_snapshot_not_newer', 'source_snapshot_at',
      'A newer or equal source snapshot has already been applied.'
    ));
  end if;

  if coalesce(v_raw ->> 'id', '') is distinct from v_id then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'id_is_not_trimmed', 'id',
      'The authoritative database ID contains outer whitespace and cannot be rewritten.'
    ));
  end if;
  if v_id = '' or length(v_id) > 128 then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'invalid_id', 'id', 'ID must contain 1 to 128 trimmed characters.'
    ));
  end if;

  if (
    select count(*)
    from content.legacy_import_rows as duplicate
    where duplicate.batch_id = p_batch_id
      and duplicate.source_id = v_id
      and v_id <> ''
  ) > 1 then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'duplicate_id', 'id', 'The source snapshot contains this ID more than once.'
    ));
  end if;

  foreach v_field in array array[
    'name_ko', 'venue_name_ko', 'city_ko', 'region_ko'
  ]
  loop
    if btrim(coalesce(v_payload ->> v_field, '')) = '' then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'error', 'missing_required_field', v_field, 'A published exhibition requires this field.'
      ));
    end if;
  end loop;

  v_opening := content_private.legacy_try_date(v_payload ->> 'opening_date');
  v_closing := content_private.legacy_try_date(v_payload ->> 'closing_date');
  if coalesce(v_payload ->> 'opening_date', '') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
     or v_opening is null then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'invalid_opening_date', 'opening_date', 'Use a real Gregorian YYYY-MM-DD date.'
    ));
  end if;
  if coalesce(v_payload ->> 'closing_date', '') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
     or v_closing is null then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'invalid_closing_date', 'closing_date', 'Use a real Gregorian YYYY-MM-DD date.'
    ));
  end if;
  if v_opening is not null and v_closing is not null and v_closing < v_opening then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'reversed_date_range', 'closing_date', 'Closing date is earlier than opening date.'
    ));
  end if;

  if content_private.legacy_try_boolean(v_raw -> 'is_featured') is null then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'invalid_boolean', 'is_featured', 'Use true or false.'
    ));
  end if;
  if content_private.legacy_try_boolean(v_raw -> 'is_homepage_featured') is null then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'invalid_boolean', 'is_homepage_featured', 'Use true or false.'
    ));
  end if;

  v_lat_text := nullif(btrim(coalesce(v_payload ->> 'latitude', '')), '');
  v_lon_text := nullif(btrim(coalesce(v_payload ->> 'longitude', '')), '');
  if (v_lat_text is null) <> (v_lon_text is null) then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'incomplete_coordinate_pair', 'latitude,longitude',
      'Latitude and longitude must both be present or both be empty.'
    ));
  end if;
  if v_lat_text is not null then
    v_lat := content_private.legacy_try_double(v_lat_text);
    if v_lat is null or v_lat < -90 or v_lat > 90 then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'error', 'invalid_latitude', 'latitude', 'Latitude must be numeric and between -90 and 90.'
      ));
    end if;
  end if;
  if v_lon_text is not null then
    v_lon := content_private.legacy_try_double(v_lon_text);
    if v_lon is null or v_lon < -180 or v_lon > 180 then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'error', 'invalid_longitude', 'longitude', 'Longitude must be numeric and between -180 and 180.'
      ));
    end if;
  end if;

  if (v_payload ->> 'reception_date') is not null
     and content_private.legacy_try_timestamptz(v_payload ->> 'reception_date') is null then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'invalid_reception_date', 'reception_date', 'Reception date must be an ISO timestamp.'
    ));
  end if;
  if content_private.legacy_try_timestamptz(v_payload ->> 'updated_at') is null then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'invalid_updated_at', 'updated_at', 'The database export must include a valid updated_at timestamp.'
    ));
  end if;

  foreach v_field in array array['cover_image_url', 'ticket_url']
  loop
    v_url := v_payload ->> v_field;
    if v_url is not null and v_url !~* '^https?://[^[:space:]]+$' then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'error', 'invalid_url', v_field, 'Only absolute HTTP or HTTPS URLs are accepted.'
      ));
    elsif v_url ~* '^http://' then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'warning', 'insecure_http_url', v_field, 'Review and migrate this URL to HTTPS when possible.'
      ));
    end if;
  end loop;

  if (v_payload ->> 'cover_image_url') is null then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'warning', 'missing_cover_image', 'cover_image_url', 'The exhibition will publish without a cover image.'
    ));
  end if;

  if (v_payload ->> 'event_id') is not null and not exists (
    select 1 from public.events where id = v_payload ->> 'event_id'
  ) then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'orphan_event_id', 'event_id', 'The referenced event does not exist.'
    ));
  end if;
  if (v_payload ->> 'editor_id') is not null and not exists (
    select 1 from public.editors where id = v_payload ->> 'editor_id'
  ) then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'orphan_editor_id', 'editor_id', 'The referenced editor does not exist.'
    ));
  end if;

  select * into v_link
  from content.legacy_import_links
  where source_system = v_batch.source_system
    and source_id = v_id;

  if exists (select 1 from content.exhibitions where id = v_id) then
    if v_link.source_id is null then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'error', 'canonical_id_collision', 'id',
        'A canonical exhibition with this ID was not created by the legacy importer.'
      ));
    elsif exists (
      select 1
      from content.exhibitions as exhibition
      where exhibition.id = v_id
        and exhibition.published_version_id is distinct from v_link.last_imported_version_id
    ) then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'error', 'canonical_changed_since_import', 'id',
        'The canonical published pointer changed after the previous import.'
      ));
    end if;
    if exists (
      select 1
      from content.exhibitions as exhibition
      where exhibition.id = v_id
        and exhibition.archived_at is not null
    ) then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'error', 'canonical_archived_since_import', 'id',
        'The canonical exhibition is archived and cannot be changed by a legacy snapshot.'
      ));
    end if;
    if exists (
      select 1
      from content.exhibition_versions as version
      where version.exhibition_id = v_id
        and version.status = 'draft'::content.exhibition_version_status
    ) then
      v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
        'error', 'canonical_draft_exists', 'id',
        'Resolve the active admin draft before importing a changed snapshot.'
      ));
    end if;
  elsif v_link.source_id is not null then
    v_issues := v_issues || jsonb_build_array(content_private.legacy_issue(
      'error', 'broken_import_link', 'id', 'Import provenance points to a missing canonical identity.'
    ));
  end if;

  return v_issues;
end;
$$;

create or replace function content_private.stage_legacy_exhibitions_impl(
  p_bundle jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_rows jsonb;
  v_row jsonb;
  v_normalized jsonb;
  v_source_system text;
  v_source_file_name text;
  v_source_sha256 text;
  v_snapshot_at timestamptz;
  v_normalized_sha256 text;
  v_batch_id uuid;
  v_baseline_batch_id uuid;
  v_existing content.legacy_import_batches%rowtype;
  v_ordinal bigint;
  v_source_row_number integer;
  v_issues jsonb;
  v_action text;
  v_error_count integer;
  v_warning_count integer;
  v_blocked_rows integer;
  v_report jsonb;
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('gallr:legacy-exhibition-import', 0)
  );

  if p_bundle is null or jsonb_typeof(p_bundle) <> 'object' then
    raise exception using errcode = '22023', message = 'legacy_bundle_must_be_an_object';
  end if;
  if p_bundle ->> 'schema_version' is distinct from '1' then
    raise exception using errcode = '22023', message = 'unsupported_legacy_bundle_schema';
  end if;

  v_source_system := btrim(coalesce(p_bundle ->> 'source_system', ''));
  if v_source_system <> 'legacy_public_exhibitions' then
    raise exception using errcode = '22023', message = 'unsupported_legacy_source_system';
  end if;
  v_source_file_name := btrim(coalesce(p_bundle ->> 'source_file_name', ''));
  if v_source_file_name = '' or length(v_source_file_name) > 255 then
    raise exception using errcode = '22023', message = 'invalid_legacy_source_file_name';
  end if;
  v_source_sha256 := lower(btrim(coalesce(p_bundle ->> 'source_sha256', '')));
  if v_source_sha256 !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'invalid_legacy_source_sha256';
  end if;
  v_snapshot_at := content_private.legacy_try_timestamptz(
    p_bundle ->> 'source_snapshot_at'
  );
  if v_snapshot_at is null then
    raise exception using errcode = '22023', message = 'invalid_legacy_source_snapshot_at';
  end if;

  v_rows := p_bundle -> 'rows';
  if v_rows is null or jsonb_typeof(v_rows) <> 'array' then
    raise exception using errcode = '22023', message = 'legacy_bundle_rows_must_be_an_array';
  end if;
  if jsonb_array_length(v_rows) < 1 or jsonb_array_length(v_rows) > 5000 then
    raise exception using errcode = '22023', message = 'legacy_bundle_row_count_out_of_range';
  end if;
  if coalesce(p_bundle ->> 'row_count', '') !~ '^[0-9]+$'
     or (p_bundle ->> 'row_count')::integer <> jsonb_array_length(v_rows) then
    raise exception using errcode = '22023', message = 'legacy_bundle_row_count_mismatch';
  end if;
  if exists (
    select 1 from jsonb_array_elements(v_rows) as source(row)
    where jsonb_typeof(source.row) <> 'object'
  ) then
    raise exception using errcode = '22023', message = 'legacy_bundle_rows_must_be_objects';
  end if;

  select content_private.legacy_sha256_json(
    jsonb_agg(normalized.row order by normalized.row ->> 'id', normalized.row::text)
  )
  into v_normalized_sha256
  from jsonb_array_elements(v_rows) as source(row)
  cross join lateral (
    select content_private.legacy_normalize_exhibition_row(source.row) as row
  ) as normalized;

  select * into v_existing
  from content.legacy_import_batches
  where source_system = v_source_system
    and source_sha256 = v_source_sha256;

  if found then
    if v_existing.normalized_sha256 <> v_normalized_sha256 then
      raise exception using
        errcode = '23514',
        message = 'legacy_source_sha256_payload_mismatch';
    end if;
    return v_existing.report || jsonb_build_object(
      'batch_id', v_existing.id,
      'idempotent_replay', true,
      'status', v_existing.status
    );
  end if;

  select id into v_baseline_batch_id
  from content.legacy_import_batches
  where source_system = v_source_system
    and status = 'applied'
  order by source_snapshot_at desc, applied_at desc, id desc
  limit 1;

  insert into content.legacy_import_batches (
    source_system,
    source_file_name,
    source_snapshot_at,
    source_sha256,
    normalized_sha256,
    row_count,
    baseline_batch_id
  ) values (
    v_source_system,
    v_source_file_name,
    v_snapshot_at,
    v_source_sha256,
    v_normalized_sha256,
    jsonb_array_length(v_rows),
    v_baseline_batch_id
  )
  returning id into v_batch_id;

  for v_row, v_ordinal in
    select source.row, source.ordinality
    from jsonb_array_elements(v_rows) with ordinality as source(row, ordinality)
  loop
    v_normalized := content_private.legacy_normalize_exhibition_row(v_row);
    v_source_row_number := case
      when coalesce(v_row ->> 'source_row_number', '') ~ '^[0-9]+$'
        and (v_row ->> 'source_row_number')::integer > 0
      then (v_row ->> 'source_row_number')::integer
      else null
    end;

    insert into content.legacy_import_rows (
      batch_id,
      row_ordinal,
      source_row_number,
      source_id,
      raw_payload,
      normalized_payload,
      row_sha256
    ) values (
      v_batch_id,
      v_ordinal::integer,
      v_source_row_number,
      nullif(v_normalized ->> 'id', ''),
      v_row,
      v_normalized,
      content_private.legacy_sha256_json(v_normalized)
    );
  end loop;

  for v_ordinal in
    select row_ordinal
    from content.legacy_import_rows
    where batch_id = v_batch_id
    order by row_ordinal
  loop
    v_issues := content_private.legacy_import_row_issues(
      v_batch_id,
      v_ordinal::integer
    );

    if exists (
      select 1
      from jsonb_array_elements(v_issues) as issue(value)
      where issue.value ->> 'severity' = 'error'
    ) then
      v_action := 'blocked';
    elsif not exists (
      select 1
      from content.exhibitions
      where id = (
        select source_id
        from content.legacy_import_rows
        where batch_id = v_batch_id and row_ordinal = v_ordinal
      )
    ) then
      v_action := 'insert';
    elsif exists (
      select 1
      from content.legacy_import_rows as staged
      join content.legacy_import_links as link
        on link.source_system = v_source_system
       and link.source_id = staged.source_id
       and link.last_row_sha256 = staged.row_sha256
      where staged.batch_id = v_batch_id
        and staged.row_ordinal = v_ordinal
    ) then
      v_action := 'unchanged';
    else
      v_action := 'revise';
    end if;

    update content.legacy_import_rows
    set issues = v_issues, action = v_action
    where batch_id = v_batch_id and row_ordinal = v_ordinal;
  end loop;

  select count(*)::integer into v_error_count
  from content.legacy_import_rows as staged
  cross join lateral jsonb_array_elements(staged.issues) as issue(value)
  where staged.batch_id = v_batch_id and issue.value ->> 'severity' = 'error';

  select count(*)::integer into v_warning_count
  from content.legacy_import_rows as staged
  cross join lateral jsonb_array_elements(staged.issues) as issue(value)
  where staged.batch_id = v_batch_id and issue.value ->> 'severity' = 'warning';

  select count(*)::integer into v_blocked_rows
  from content.legacy_import_rows
  where batch_id = v_batch_id and action = 'blocked';

  v_report := jsonb_build_object(
    'schema_version', 1,
    'batch_id', v_batch_id,
    'status', 'validated',
    'source_system', v_source_system,
    'source_file_name', v_source_file_name,
    'source_snapshot_at', v_snapshot_at,
    'source_sha256', v_source_sha256,
    'normalized_sha256', v_normalized_sha256,
    'baseline_batch_id', v_baseline_batch_id,
    'row_count', jsonb_array_length(v_rows),
    'blocked_rows', v_blocked_rows,
    'error_count', v_error_count,
    'warning_count', v_warning_count,
    'planned_actions', jsonb_build_object(
      'insert', (select count(*) from content.legacy_import_rows where batch_id = v_batch_id and action = 'insert'),
      'revise', (select count(*) from content.legacy_import_rows where batch_id = v_batch_id and action = 'revise'),
      'unchanged', (select count(*) from content.legacy_import_rows where batch_id = v_batch_id and action = 'unchanged')
    ),
    'row_results', coalesce((
      select jsonb_agg(jsonb_build_object(
        'row_ordinal', staged.row_ordinal,
        'source_row_number', staged.source_row_number,
        'id', staged.source_id,
        'action', staged.action,
        'row_sha256', staged.row_sha256,
        'issues', staged.issues
      ) order by staged.row_ordinal)
      from content.legacy_import_rows as staged
      where staged.batch_id = v_batch_id
    ), '[]'::jsonb),
    'missing_previously_imported_ids', coalesce((
      select jsonb_agg(link.source_id order by link.source_id)
      from content.legacy_import_links as link
      where link.source_system = v_source_system
        and not exists (
          select 1
          from content.legacy_import_rows as staged
          where staged.batch_id = v_batch_id
            and staged.source_id = link.source_id
        )
    ), '[]'::jsonb),
    'idempotent_replay', false
  );

  update content.legacy_import_batches
  set
    status = 'validated',
    error_count = v_error_count,
    warning_count = v_warning_count,
    report = v_report,
    validated_at = now()
  where id = v_batch_id;

  return v_report;
end;
$$;

create or replace function content_private.apply_legacy_exhibitions_impl(
  p_batch_id uuid
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_batch content.legacy_import_batches%rowtype;
  v_staged content.legacy_import_rows%rowtype;
  v_exhibition content.exhibitions%rowtype;
  v_link content.legacy_import_links%rowtype;
  v_payload jsonb;
  v_version_id uuid;
  v_version_number integer;
  v_source_updated_at timestamptz;
  v_action text;
  v_report jsonb;
  v_error_count integer;
  v_error_detail jsonb;
  v_latest_applied content.legacy_import_batches%rowtype;
begin
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('gallr:legacy-exhibition-import', 0)
  );

  select * into v_batch
  from content.legacy_import_batches
  where id = p_batch_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'legacy_import_batch_not_found';
  end if;
  if v_batch.status = 'applied' then
    return v_batch.report || jsonb_build_object(
      'batch_id', v_batch.id,
      'idempotent_replay', true,
      'status', 'applied'
    );
  end if;
  if v_batch.status <> 'validated' then
    raise exception using errcode = '22023', message = 'legacy_import_batch_not_validated';
  end if;

  select * into v_latest_applied
  from content.legacy_import_batches
  where source_system = v_batch.source_system
    and status = 'applied'
    and id <> v_batch.id
  order by source_snapshot_at desc, applied_at desc, id desc
  limit 1;

  if found and v_batch.source_snapshot_at <= v_latest_applied.source_snapshot_at then
    raise exception using
      errcode = '40001',
      message = 'legacy_import_source_snapshot_not_newer',
      detail = v_latest_applied.id::text;
  end if;
  if v_batch.baseline_batch_id is distinct from v_latest_applied.id then
    raise exception using
      errcode = '40001',
      message = 'legacy_import_baseline_changed_since_stage',
      detail = jsonb_build_object(
        'staged_baseline_batch_id', v_batch.baseline_batch_id,
        'current_baseline_batch_id', v_latest_applied.id
      )::text;
  end if;

  -- Re-evaluate stateful conflicts after acquiring the importer lock. This
  -- prevents a previously valid report from overwriting a newer admin change.
  for v_staged in
    select *
    from content.legacy_import_rows
    where batch_id = p_batch_id
    order by source_id, row_ordinal
  loop
    update content.legacy_import_rows
    set issues = content_private.legacy_import_row_issues(
      p_batch_id,
      v_staged.row_ordinal
    )
    where batch_id = p_batch_id and row_ordinal = v_staged.row_ordinal;
  end loop;

  select count(*)::integer into v_error_count
  from content.legacy_import_rows as staged
  cross join lateral jsonb_array_elements(staged.issues) as issue(value)
  where staged.batch_id = p_batch_id and issue.value ->> 'severity' = 'error';

  if v_error_count > 0 then
    with error_rows as (
      select
        staged.row_ordinal,
        staged.source_row_number,
        staged.source_id,
        (
          select coalesce(jsonb_agg(issue.value order by issue.ordinality), '[]'::jsonb)
          from jsonb_array_elements(staged.issues) with ordinality as issue(value, ordinality)
          where issue.value ->> 'severity' = 'error'
        ) as errors
      from content.legacy_import_rows as staged
      where staged.batch_id = p_batch_id
        and exists (
          select 1
          from jsonb_array_elements(staged.issues) as issue(value)
          where issue.value ->> 'severity' = 'error'
        )
      order by staged.row_ordinal
    ),
    reported_rows as (
      select * from error_rows limit 100
    )
    select jsonb_build_object(
      'error_count', v_error_count,
      'error_row_count', (select count(*) from error_rows),
      'reported_row_count', (select count(*) from reported_rows),
      'truncated', (select count(*) from error_rows) > 100,
      'row_errors', coalesce(
        (select jsonb_agg(to_jsonb(reported_rows) order by row_ordinal)
         from reported_rows),
        '[]'::jsonb
      )
    )
    into v_error_detail;

    raise exception using
      errcode = '23514',
      message = 'legacy_import_batch_has_errors',
      detail = v_error_detail::text;
  end if;

  for v_staged in
    select *
    from content.legacy_import_rows
    where batch_id = p_batch_id
    order by source_id, row_ordinal
  loop
    v_payload := v_staged.normalized_payload;
    v_source_updated_at := content_private.legacy_try_timestamptz(
      v_payload ->> 'updated_at'
    );

    select * into v_link
    from content.legacy_import_links
    where source_system = v_batch.source_system
      and source_id = v_staged.source_id
    for update;

    if not found then
      v_action := 'insert';
      v_version_id := gen_random_uuid();
      v_version_number := 1;

      insert into content.exhibitions (
        id,
        created_at,
        updated_at
      ) values (
        v_staged.source_id,
        v_source_updated_at,
        v_source_updated_at
      );
    else
      -- Lock and re-check canonical state even for an unchanged row. Admin
      -- publish/archive/draft commands lock the same identity, so this closes
      -- the race between the batch-wide validation pass and per-row apply.
      select * into strict v_exhibition
      from content.exhibitions
      where id = v_staged.source_id
      for update;

      if v_exhibition.published_version_id is distinct from v_link.last_imported_version_id then
        raise exception using
          errcode = '40001',
          message = 'canonical_changed_since_import',
          detail = v_staged.source_id;
      end if;
      if v_exhibition.archived_at is not null then
        raise exception using
          errcode = '40001',
          message = 'canonical_archived_since_import',
          detail = v_staged.source_id;
      end if;
      if exists (
        select 1
        from content.exhibition_versions
        where exhibition_id = v_staged.source_id
          and status = 'draft'::content.exhibition_version_status
      ) then
        raise exception using
          errcode = '40001',
          message = 'canonical_draft_exists',
          detail = v_staged.source_id;
      end if;

      if v_link.last_row_sha256 = v_staged.row_sha256 then
        v_action := 'unchanged';
        v_version_id := v_link.last_imported_version_id;
      else
        v_action := 'revise';

        update content.exhibition_versions
        set status = 'superseded'::content.exhibition_version_status
        where id = v_link.last_imported_version_id
          and exhibition_id = v_staged.source_id
          and status = 'published'::content.exhibition_version_status;

        if not found then
          raise exception using
            errcode = '40001',
            message = 'last_imported_version_is_not_published',
            detail = v_staged.source_id;
        end if;

        select coalesce(max(version_number), 0) + 1
        into v_version_number
        from content.exhibition_versions
        where exhibition_id = v_staged.source_id;

        v_version_id := gen_random_uuid();
      end if;
    end if;

    if v_action in ('insert', 'revise') then
      insert into content.exhibition_versions (
        id,
        exhibition_id,
        version_number,
        revision,
        status,
        event_id,
        editor_id,
        name_ko,
        name_en,
        venue_name_ko,
        venue_name_en,
        city_ko,
        city_en,
        region_ko,
        region_en,
        address_ko,
        address_en,
        opening_date,
        closing_date,
        latitude,
        longitude,
        description_ko,
        description_en,
        hours,
        contact,
        reception_date,
        opening_time,
        ticket_url,
        legacy_cover_image_url,
        is_featured,
        is_homepage_featured,
        published_at,
        created_at,
        updated_at,
        legacy_source_updated_at
      ) values (
        v_version_id,
        v_staged.source_id,
        v_version_number,
        1,
        'published'::content.exhibition_version_status,
        v_payload ->> 'event_id',
        v_payload ->> 'editor_id',
        v_payload ->> 'name_ko',
        v_payload ->> 'name_en',
        v_payload ->> 'venue_name_ko',
        v_payload ->> 'venue_name_en',
        v_payload ->> 'city_ko',
        v_payload ->> 'city_en',
        v_payload ->> 'region_ko',
        v_payload ->> 'region_en',
        v_payload ->> 'address_ko',
        v_payload ->> 'address_en',
        content_private.legacy_try_date(v_payload ->> 'opening_date'),
        content_private.legacy_try_date(v_payload ->> 'closing_date'),
        content_private.legacy_try_double(v_payload ->> 'latitude'),
        content_private.legacy_try_double(v_payload ->> 'longitude'),
        v_payload ->> 'description_ko',
        v_payload ->> 'description_en',
        v_payload ->> 'hours',
        v_payload ->> 'contact',
        content_private.legacy_try_timestamptz(v_payload ->> 'reception_date'),
        v_payload ->> 'opening_time',
        v_payload ->> 'ticket_url',
        v_payload ->> 'cover_image_url',
        (v_payload ->> 'is_featured')::boolean,
        (v_payload ->> 'is_homepage_featured')::boolean,
        v_source_updated_at,
        v_source_updated_at,
        v_source_updated_at,
        v_source_updated_at
      );

      update content.exhibitions
      set published_version_id = v_version_id
      where id = v_staged.source_id;

      insert into content.curation_placements (
        surface,
        exhibition_id,
        position,
        enabled
      ) values
        (
          'app_featured'::content.curation_surface,
          v_staged.source_id,
          0,
          (v_payload ->> 'is_featured')::boolean
        ),
        (
          'homepage'::content.curation_surface,
          v_staged.source_id,
          0,
          (v_payload ->> 'is_homepage_featured')::boolean
        )
      on conflict (surface, exhibition_id) do update
      set enabled = excluded.enabled;

      insert into content.audit_log (
        actor_user_id,
        action,
        entity_type,
        entity_id,
        request_id,
        metadata
      ) values (
        null,
        'legacy_import.' || v_action,
        'exhibition',
        v_staged.source_id,
        p_batch_id,
        jsonb_build_object(
          'source_system', v_batch.source_system,
          'source_sha256', v_batch.source_sha256,
          'row_sha256', v_staged.row_sha256,
          'version_id', v_version_id,
          'source_row_number', v_staged.source_row_number
        )
      );
    end if;

    insert into content.legacy_import_links (
      source_system,
      source_id,
      exhibition_id,
      last_imported_version_id,
      last_row_sha256,
      first_batch_id,
      last_batch_id
    ) values (
      v_batch.source_system,
      v_staged.source_id,
      v_staged.source_id,
      v_version_id,
      v_staged.row_sha256,
      p_batch_id,
      p_batch_id
    )
    on conflict (source_system, source_id) do update
    set
      last_imported_version_id = excluded.last_imported_version_id,
      last_row_sha256 = excluded.last_row_sha256,
      last_batch_id = excluded.last_batch_id;

    update content.legacy_import_rows
    set action = v_action, applied_version_id = v_version_id
    where batch_id = p_batch_id and row_ordinal = v_staged.row_ordinal;
  end loop;

  v_report := v_batch.report || jsonb_build_object(
    'status', 'applied',
    'applied_at', now(),
    'applied_actions', jsonb_build_object(
      'insert', (select count(*) from content.legacy_import_rows where batch_id = p_batch_id and action = 'insert'),
      'revise', (select count(*) from content.legacy_import_rows where batch_id = p_batch_id and action = 'revise'),
      'unchanged', (select count(*) from content.legacy_import_rows where batch_id = p_batch_id and action = 'unchanged')
    ),
    'idempotent_replay', false
  );

  update content.legacy_import_batches
  set status = 'applied', applied_at = now(), report = v_report
  where id = p_batch_id;

  return v_report;
end;
$$;

-- The preview is intentionally service-only in Phase 4. It is a
-- security-invoker view, so a later anonymous cutover must explicitly add a
-- published-only RLS/grant design rather than accidentally bypassing RLS.
create or replace view public.exhibitions_v2_preview
with (security_invoker = true)
as
select
  exhibition.id,
  version.name_ko,
  version.name_en,
  version.venue_name_ko,
  version.venue_name_en,
  version.city_ko,
  version.city_en,
  version.region_ko,
  version.region_en,
  version.opening_date,
  version.closing_date,
  coalesce(app_featured.enabled, version.is_featured) as is_featured,
  version.latitude,
  version.longitude,
  version.description_ko,
  version.description_en,
  version.address_ko,
  version.address_en,
  coalesce(cover.public_url, version.legacy_cover_image_url) as cover_image_url,
  version.hours,
  version.contact,
  version.reception_date,
  version.opening_time,
  version.event_id,
  version.editor_id,
  coalesce(homepage.enabled, version.is_homepage_featured) as is_homepage_featured,
  version.ticket_url,
  coalesce(
    version.legacy_source_updated_at,
    greatest(exhibition.updated_at, version.updated_at)
  ) as updated_at,
  coalesce(version.editor_id = 'gallr-editors', false) as is_editors_pick,
  case
    when version.editor_id is distinct from 'gallr-editors' then version.editor_id
    else null
  end as guest_editor_id
from content.exhibitions as exhibition
join content.exhibition_versions as version
  on version.exhibition_id = exhibition.id
 and version.id = exhibition.published_version_id
 and version.status = 'published'::content.exhibition_version_status
left join content.curation_placements as app_featured
  on app_featured.exhibition_id = exhibition.id
 and app_featured.surface = 'app_featured'::content.curation_surface
left join content.curation_placements as homepage
  on homepage.exhibition_id = exhibition.id
 and homepage.surface = 'homepage'::content.curation_surface
left join lateral (
  select asset.public_url
  from content.exhibition_version_media as attachment
  join content.media_assets as asset on asset.id = attachment.media_id
  where attachment.version_id = version.id
    and attachment.role = 'cover'::content.media_role
    and asset.status = 'published'::content.media_asset_status
    and asset.purged_at is null
  order by attachment.sort_order, attachment.created_at, attachment.media_id
  limit 1
) as cover on true
where exhibition.archived_at is null;

revoke all on public.exhibitions_v2_preview
  from public, anon, authenticated;
grant select on public.exhibitions_v2_preview to service_role;

create or replace view public.guest_editors_v2_preview
with (security_invoker = true)
as
select
  id,
  name_ko,
  name_en,
  title_ko,
  title_en,
  bio_ko,
  bio_en,
  is_active,
  active_from,
  active_to,
  created_at,
  updated_at
from public.editors;

revoke all on public.guest_editors_v2_preview
  from public, anon, authenticated;
grant select on public.guest_editors_v2_preview to service_role;

create or replace function content_private.legacy_public_json(
  p_payload jsonb
)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select jsonb_build_object(
    'id', p_payload ->> 'id',
    'name_ko', p_payload ->> 'name_ko',
    'name_en', p_payload ->> 'name_en',
    'venue_name_ko', p_payload ->> 'venue_name_ko',
    'venue_name_en', p_payload ->> 'venue_name_en',
    'city_ko', p_payload ->> 'city_ko',
    'city_en', p_payload ->> 'city_en',
    'region_ko', p_payload ->> 'region_ko',
    'region_en', p_payload ->> 'region_en',
    'opening_date', content_private.legacy_try_date(p_payload ->> 'opening_date'),
    'closing_date', content_private.legacy_try_date(p_payload ->> 'closing_date'),
    'is_featured', content_private.legacy_try_boolean(p_payload -> 'is_featured'),
    'latitude', content_private.legacy_try_double(p_payload ->> 'latitude'),
    'longitude', content_private.legacy_try_double(p_payload ->> 'longitude'),
    'description_ko', p_payload ->> 'description_ko',
    'description_en', p_payload ->> 'description_en',
    'address_ko', p_payload ->> 'address_ko',
    'address_en', p_payload ->> 'address_en',
    'cover_image_url', p_payload ->> 'cover_image_url',
    'hours', p_payload ->> 'hours',
    'contact', p_payload ->> 'contact',
    'reception_date', content_private.legacy_try_timestamptz(p_payload ->> 'reception_date'),
    'opening_time', p_payload ->> 'opening_time',
    'event_id', p_payload ->> 'event_id',
    'editor_id', p_payload ->> 'editor_id',
    'is_homepage_featured',
      content_private.legacy_try_boolean(p_payload -> 'is_homepage_featured'),
    'ticket_url', p_payload ->> 'ticket_url',
    'updated_at', content_private.legacy_try_timestamptz(p_payload ->> 'updated_at')
  );
$$;

create or replace function content_private.reconcile_legacy_exhibitions_impl(
  p_batch_id uuid
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_batch content.legacy_import_batches%rowtype;
  v_report jsonb;
begin
  select * into v_batch
  from content.legacy_import_batches
  where id = p_batch_id;
  if not found then
    raise exception using errcode = 'P0002', message = 'legacy_import_batch_not_found';
  end if;

  with source_rows as (
    select
      staged.source_id as id,
      content_private.legacy_public_json(staged.normalized_payload) as payload
    from content.legacy_import_rows as staged
    where staged.batch_id = p_batch_id
      and staged.action <> 'blocked'
  ),
  preview_rows as (
    select
      preview.id,
      jsonb_build_object(
        'id', preview.id,
        'name_ko', preview.name_ko,
        'name_en', preview.name_en,
        'venue_name_ko', preview.venue_name_ko,
        'venue_name_en', preview.venue_name_en,
        'city_ko', preview.city_ko,
        'city_en', preview.city_en,
        'region_ko', preview.region_ko,
        'region_en', preview.region_en,
        'opening_date', preview.opening_date,
        'closing_date', preview.closing_date,
        'is_featured', preview.is_featured,
        'latitude', preview.latitude,
        'longitude', preview.longitude,
        'description_ko', preview.description_ko,
        'description_en', preview.description_en,
        'address_ko', preview.address_ko,
        'address_en', preview.address_en,
        'cover_image_url', preview.cover_image_url,
        'hours', preview.hours,
        'contact', preview.contact,
        'reception_date', preview.reception_date,
        'opening_time', preview.opening_time,
        'event_id', preview.event_id,
        'editor_id', preview.editor_id,
        'is_homepage_featured', preview.is_homepage_featured,
        'ticket_url', preview.ticket_url,
        'updated_at', preview.updated_at
      ) as payload
    from public.exhibitions_v2_preview as preview
  ),
  comparison as (
    select
      coalesce(source.id, preview.id) as id,
      source.payload as source_payload,
      preview.payload as preview_payload,
      case
        when source.id is null then 'only_in_preview'
        when preview.id is null then 'only_in_source'
        when source.payload = preview.payload then 'match'
        else 'field_mismatch'
      end as status
    from source_rows as source
    full join preview_rows as preview using (id)
  ),
  differences as (
    select
      comparison.id,
      comparison.status,
      case when comparison.source_payload is null then null
        else content_private.legacy_sha256_json(comparison.source_payload) end
        as source_sha256,
      case when comparison.preview_payload is null then null
        else content_private.legacy_sha256_json(comparison.preview_payload) end
        as preview_sha256,
      case
        when comparison.status <> 'field_mismatch' then '[]'::jsonb
        else coalesce((
          select jsonb_agg(field.key order by field.key)
          from (
            select coalesce(source_field.key, preview_field.key) as key
            from jsonb_each(comparison.source_payload) as source_field
            full join jsonb_each(comparison.preview_payload) as preview_field using (key)
            where source_field.value is distinct from preview_field.value
          ) as field
        ), '[]'::jsonb)
      end as differing_fields
    from comparison
    where comparison.status <> 'match'
  )
  select jsonb_build_object(
    'schema_version', 1,
    'batch_id', p_batch_id,
    'batch_status', v_batch.status,
    'source_count', (select count(*) from source_rows),
    'preview_count', (select count(*) from preview_rows),
    'matching_count', (select count(*) from comparison where status = 'match'),
    'difference_count', (select count(*) from comparison where status <> 'match'),
    'differences', coalesce((
      select jsonb_agg(to_jsonb(differences) order by differences.id)
      from differences
    ), '[]'::jsonb)
  )
  into v_report;

  return v_report;
end;
$$;

create or replace function public.migration_stage_legacy_exhibitions(
  p_bundle jsonb
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.stage_legacy_exhibitions_impl(p_bundle);
$$;

create or replace function public.migration_apply_legacy_exhibitions(
  p_batch_id uuid
)
returns jsonb
language sql
volatile
security invoker
set search_path = ''
as $$
  select content_private.apply_legacy_exhibitions_impl(p_batch_id);
$$;

create or replace function public.migration_reconcile_legacy_exhibitions(
  p_batch_id uuid
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select content_private.reconcile_legacy_exhibitions_impl(p_batch_id);
$$;

revoke all on function content_private.legacy_try_date(text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.legacy_try_timestamptz(text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.legacy_try_double(text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.legacy_try_boolean(jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.legacy_sha256_json(jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.legacy_normalize_exhibition_row(jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.legacy_issue(text, text, text, text)
  from public, anon, authenticated, service_role;
revoke all on function content_private.legacy_import_row_issues(uuid, integer)
  from public, anon, authenticated, service_role;
revoke all on function content_private.stage_legacy_exhibitions_impl(jsonb)
  from public, anon, authenticated;
revoke all on function content_private.apply_legacy_exhibitions_impl(uuid)
  from public, anon, authenticated;
revoke all on function content_private.legacy_public_json(jsonb)
  from public, anon, authenticated, service_role;
revoke all on function content_private.reconcile_legacy_exhibitions_impl(uuid)
  from public, anon, authenticated;

grant execute on function content_private.stage_legacy_exhibitions_impl(jsonb)
  to service_role;
grant execute on function content_private.apply_legacy_exhibitions_impl(uuid)
  to service_role;
grant execute on function content_private.reconcile_legacy_exhibitions_impl(uuid)
  to service_role;

revoke all on function public.migration_stage_legacy_exhibitions(jsonb)
  from public, anon, authenticated;
revoke all on function public.migration_apply_legacy_exhibitions(uuid)
  from public, anon, authenticated;
revoke all on function public.migration_reconcile_legacy_exhibitions(uuid)
  from public, anon, authenticated;
grant execute on function public.migration_stage_legacy_exhibitions(jsonb)
  to service_role;
grant execute on function public.migration_apply_legacy_exhibitions(uuid)
  to service_role;
grant execute on function public.migration_reconcile_legacy_exhibitions(uuid)
  to service_role;
