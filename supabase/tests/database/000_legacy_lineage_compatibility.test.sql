begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(14);

select has_table(
  'public',
  'profiles',
  'the version-005 clean replay creates profiles before dependent migrations'
);

select has_table(
  'public',
  'bookmarks',
  'the version-005 clean replay creates bookmarks before migration 007'
);

select ok(
  exists (
    select 1
    from pg_trigger
    where tgname = 'on_auth_user_created'
      and tgrelid = 'auth.users'::regclass
      and not tgisinternal
  ),
  'the clean replay installs the profile creation trigger'
);

select has_column(
  'public',
  'exhibitions',
  'ticket_url',
  'the recovered May lineage includes the ticket URL'
);

select ok(
  not exists (
    select 1
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'exhibitions'
      and column_name = 'featured'
  ),
  'the transient featured column is removed by the recovered lineage'
);

select has_column(
  'public',
  'exhibitions',
  'is_homepage_featured',
  'the recovered homepage curation field exists'
);

select ok(
  to_regclass('public.exhibitions_homepage_featured_idx') is not null,
  'the recovered homepage curation index exists'
);

select has_table(
  'public',
  'editors',
  'the recovered guest-editor migrations converge on editors'
);

select is(
  (select count(*)::integer from public.editors where id = 'gallr-editors'),
  1,
  'the recovered editor migration creates one house editor'
);

select is(
  (
    select is_generated
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'exhibitions'
      and column_name = 'is_editors_pick'
  ),
  'ALWAYS',
  'the v1.5 editor-pick compatibility field is generated'
);

select ok(
  (
    select generation_expression ilike '%coalesce%'
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'exhibitions'
      and column_name = 'is_editors_pick'
  ),
  'the editor-pick compatibility expression handles null editor IDs'
);

select is(
  (
    select is_generated
    from information_schema.columns
    where table_schema = 'public'
      and table_name = 'exhibitions'
      and column_name = 'guest_editor_id'
  ),
  'ALWAYS',
  'the v1.5 guest-editor compatibility field is generated'
);

select has_column(
  'public',
  'events',
  'short_label',
  'the chronologically versioned event short label exists'
);

select ok(
  to_regclass('public.guest_editors') is null,
  'the intermediate guest_editors table is not left behind'
);

select * from finish();
rollback;
