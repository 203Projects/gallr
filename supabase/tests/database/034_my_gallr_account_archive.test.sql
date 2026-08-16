begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(25);

select has_table('content_private', 'my_gallr_archives', 'account archive revision is private');
select has_table('content_private', 'my_gallr_visits', 'account visits are private');
select has_table(
  'content_private', 'my_gallr_followed_galleries', 'account followed galleries are private'
);
select has_table(
  'content_private', 'my_gallr_mutation_receipts', 'idempotency receipts are private'
);
select ok(
  (
    select bool_and(class.relrowsecurity)
    from pg_catalog.pg_class as class
    where class.oid in (
      'content_private.my_gallr_archives'::regclass,
      'content_private.my_gallr_visits'::regclass,
      'content_private.my_gallr_followed_galleries'::regclass,
      'content_private.my_gallr_mutation_receipts'::regclass
    )
  ),
  'all private archive tables retain RLS as defense in depth'
);
select ok(
  not has_table_privilege('anon', 'content_private.my_gallr_visits', 'SELECT, INSERT, UPDATE, DELETE')
  and not has_table_privilege(
    'authenticated',
    'content_private.my_gallr_followed_galleries',
    'SELECT, INSERT, UPDATE, DELETE'
  ),
  'client roles cannot read or write archive tables directly'
);
select ok(
  to_regprocedure('public.sync_my_gallr_archive(jsonb)') is not null,
  'the narrow account archive command exists'
);
select ok(
  (
    select procedure.prosecdef
      and procedure.proconfig = array['search_path=""']::text[]
    from pg_catalog.pg_proc as procedure
    where procedure.oid = to_regprocedure('public.sync_my_gallr_archive(jsonb)')
  ),
  'the public command is hardened SECURITY DEFINER code'
);
select ok(
  not has_function_privilege('anon', 'public.sync_my_gallr_archive(jsonb)', 'EXECUTE')
  and has_function_privilege('authenticated', 'public.sync_my_gallr_archive(jsonb)', 'EXECUTE'),
  'only authenticated clients can invoke account archive sync'
);
select ok(
  not has_function_privilege(
    'authenticated', 'content_private.sync_my_gallr_archive_impl(jsonb)', 'EXECUTE'
  ),
  'authenticated clients cannot bypass the public boundary'
);

insert into auth.users (id, email, raw_user_meta_data)
values
  ('b1000000-0000-0000-0000-000000000001', 'archive-one@example.invalid', '{}'::jsonb),
  ('b1000000-0000-0000-0000-000000000002', 'archive-two@example.invalid', '{}'::jsonb);

insert into content.galleries (id, name_ko, name_en, status)
values (
  'b2000000-0000-0000-0000-000000000001',
  '아카이브 갤러리',
  'Archive Gallery',
  'active'
);

set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"b1000000-0000-0000-0000-000000000001","role":"authenticated"}',
  true
);

select is(
  public.sync_my_gallr_archive() ->> 'revision',
  '0',
  'the first authenticated read creates an empty revision-zero archive'
);

create temporary table archive_result as
select public.sync_my_gallr_archive(
  jsonb_build_array(
    jsonb_build_object(
      'mutation_id', 'b3000000-0000-0000-0000-000000000001',
      'kind', 'add_visit',
      'record', jsonb_build_object(
        'client_record_id', 'device-record-1',
        'exhibition_id', 'exhibition-1',
        'snapshot', jsonb_build_object(
          'name_ko', '전시 하나',
          'name_en', 'Exhibition One',
          'venue_name_ko', '아카이브 갤러리',
          'venue_name_en', 'Archive Gallery',
          'opening_date', '2026-08-01',
          'closing_date', '2026-08-31',
          'cover_image_url', 'https://example.invalid/one.jpg'
        ),
        'created_at', '2026-08-14T00:00:00Z'
      )
    ),
    jsonb_build_object(
      'mutation_id', 'b3000000-0000-0000-0000-000000000002',
      'kind', 'follow_gallery',
      'record', jsonb_build_object(
        'gallery_key', '아카이브 갤러리\u001farchive gallery',
        'gallery_id', 'b2000000-0000-0000-0000-000000000001',
        'snapshot', jsonb_build_object(
          'name_ko', '아카이브 갤러리',
          'name_en', 'Archive Gallery',
          'city_ko', '서울',
          'city_en', 'Seoul',
          'region_ko', '삼청',
          'region_en', 'Samcheong'
        ),
        'known_exhibition_ids', jsonb_build_array('exhibition-1'),
        'followed_at', '2026-08-14T00:00:00Z'
      )
    )
  )
) as payload;

select is(payload ->> 'revision', '2', 'two accepted mutations advance the account revision twice')
from archive_result;
select is(
  payload #>> '{visits,0,exhibition_id}', 'exhibition-1', 'the visit is returned in the archive'
) from archive_result;
select is(
  payload #>> '{followed_galleries,0,gallery_id}',
  'b2000000-0000-0000-0000-000000000001',
  'the followed gallery uses its stable identity'
) from archive_result;
select ok(
  not ((payload #> '{followed_galleries,0}') ? 'new_exhibition_alerts_enabled'),
  'account restore never carries device notification consent'
) from archive_result;

select is(
  public.sync_my_gallr_archive(
    jsonb_build_array(
      jsonb_build_object(
        'mutation_id', 'b3000000-0000-0000-0000-000000000001',
        'kind', 'add_visit',
        'record', jsonb_build_object(
          'client_record_id', 'device-record-1',
          'exhibition_id', 'exhibition-1',
          'snapshot', jsonb_build_object(
            'name_ko', '전시 하나', 'name_en', 'Exhibition One',
            'venue_name_ko', '아카이브 갤러리', 'venue_name_en', 'Archive Gallery',
            'opening_date', '2026-08-01', 'closing_date', '2026-08-31',
            'cover_image_url', 'https://example.invalid/one.jpg'
          ),
          'created_at', '2026-08-14T00:00:00Z'
        )
      )
    )
  ) ->> 'revision',
  '2',
  'an ambiguous retry does not advance revision'
);
select is(
  jsonb_array_length(public.sync_my_gallr_archive() -> 'visits'),
  1,
  'retry retains exactly one visit'
);
reset role;
select is(
  (select count(*)::integer from content_private.my_gallr_mutation_receipts),
  2,
  'retry retains exactly one receipt per mutation'
);
set local role authenticated;
select set_config(
  'request.jwt.claims',
  '{"sub":"b1000000-0000-0000-0000-000000000001","role":"authenticated"}',
  true
);
select throws_ok(
  $$select public.sync_my_gallr_archive('[{
    "mutation_id":"b3000000-0000-0000-0000-000000000001",
    "kind":"remove_visit",
    "record":{"exhibition_id":"exhibition-1"}
  }]'::jsonb)$$,
  '22023',
  'mutation_id_conflict',
  'a mutation ID cannot be reused for different content'
);

select is(
  public.sync_my_gallr_archive('[{
    "mutation_id":"b3000000-0000-0000-0000-000000000003",
    "kind":"acknowledge_gallery",
    "record":{
      "gallery_key":"아카이브 갤러리\\u001farchive gallery",
      "known_exhibition_ids":["exhibition-1","exhibition-2"]
    }
  }]'::jsonb) #>> '{followed_galleries,0,known_exhibition_ids,1}',
  'exhibition-2',
  'gallery acknowledgement converges into the account record'
);
select is(
  public.sync_my_gallr_archive('[{
    "mutation_id":"b3000000-0000-0000-0000-000000000004",
    "kind":"remove_visit",
    "record":{"exhibition_id":"exhibition-1"}
  }]'::jsonb) ->> 'revision',
  '4',
  'a removal is ordered after earlier account changes'
);
select is(
  jsonb_array_length(public.sync_my_gallr_archive() -> 'visits'),
  0,
  'the removed visit stays absent on a later refresh'
);

select set_config(
  'request.jwt.claims',
  '{"sub":"b1000000-0000-0000-0000-000000000002","role":"authenticated"}',
  true
);
select is(
  jsonb_array_length(public.sync_my_gallr_archive() -> 'followed_galleries'),
  0,
  'a second account cannot read the first account archive'
);

select throws_ok(
  $$select public.sync_my_gallr_archive('[{
    "mutation_id":"b3000000-0000-0000-0000-000000000099",
    "kind":"add_visit",
    "record":{
      "client_record_id":"invalid",
      "exhibition_id":"invalid",
      "snapshot":{
        "name_ko":"잘못된 전시",
        "venue_name_ko":"잘못된 갤러리",
        "opening_date":"2026-08-01",
        "closing_date":"2026-08-31",
        "cover_image_url":"http://insecure.invalid/image.jpg"
      },
      "created_at":"2026-08-14T00:00:00Z"
    }
  }]'::jsonb)$$,
  '22023',
  'mutation_record_invalid',
  'invalid archive data fails through a stable sanitized error'
);

reset role;
select is(
  (
    select count(*)::integer
    from content_private.my_gallr_archives
    where user_id = 'b1000000-0000-0000-0000-000000000001'
  ),
  1,
  'one private archive row belongs to the first account'
);

select * from finish();
rollback;
