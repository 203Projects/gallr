-- Spec 091 FR-022: visit terms (free admission, good with children and family) join the reviewed vocabulary as
-- their own category, under the same guards as the art terms.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(10);

select is(
  (enum_range(null::content.art_taxonomy_category))[5]::text,
  'visit',
  'visit is appended after the four art categories, whose order is unchanged'
);

select results_eq(
  $$ select id, name_ko, name_en from content.art_taxonomy_terms where category = 'visit' order by sort_order $$,
  $$ values
       ('visit:free-admission'::text, '무료 관람'::text, 'Free admission'::text),
       ('visit:family', '아이·가족과 함께', 'Good with children and family') $$,
  'the two visit terms exist in their reviewed order'
);

select is(
  (select count(*)::integer from content.art_taxonomy_terms where category <> 'visit'),
  28,
  'the art vocabulary is untouched'
);

select lives_ok(
  $$ select content_private.validate_art_metadata_patch(
       '{"art_term_ids":["medium:painting","visit:free-admission","visit:family"]}'::jsonb
     ) $$,
  'a draft may carry visit terms beside art terms'
);

select throws_ok(
  $$ select content_private.validate_art_metadata_patch('{"art_term_ids":["visit:pets"]}'::jsonb) $$,
  '22023',
  'art_metadata_term_unknown',
  'a visit term that is not in the vocabulary is refused'
);

select throws_ok(
  $$ insert into content.art_taxonomy_terms (id, category, name_ko, name_en, sort_order)
     values ('audience:adults', 'visit', '성인', 'Adults', 9) $$,
  '23514',
  null,
  'an id outside the five category prefixes is refused'
);

select throws_ok(
  $$ insert into content.art_taxonomy_terms (id, category, name_ko, name_en, sort_order)
     values ('visit:quiet', 'mood', '조용함', 'Quiet', 9) $$,
  '23514',
  null,
  'a visit id cannot be filed under an art category'
);

select throws_ok(
  $$ update content.art_taxonomy_terms set name_en = 'Free entry' where id = 'visit:free-admission' $$,
  '23514',
  'art_taxonomy_term_semantics_immutable',
  'a visit term cannot be reinterpreted in place'
);

select ok(
  not has_table_privilege('anon', 'content.art_taxonomy_terms', 'select')
    and not has_table_privilege('authenticated', 'content.art_taxonomy_terms', 'select'),
  'the vocabulary table stays unreadable to visitors; they see terms only on published exhibitions'
);

select is(
  (
    select count(*)::integer
    from content.art_taxonomy_terms
    where category = 'visit' and active
  ),
  2,
  'both visit terms are active for editors to select'
);

select * from finish();
rollback;
