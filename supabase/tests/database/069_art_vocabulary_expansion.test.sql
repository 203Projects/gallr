-- Spec 101 T042: the owner-decided vocabulary expansion adds twenty-two art terms after each category's last row
-- and changes nothing that existed.
begin;

create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;

select plan(9);

select results_eq(
  $$ select id, category::text, name_ko, name_en, sort_order::integer
     from content.art_taxonomy_terms
     where id in (
       'medium:ceramics', 'medium:textile', 'medium:sound', 'medium:media-art', 'medium:architecture-design',
       'style:dansaekhwa', 'style:minhwa', 'style:korean-painting', 'style:pop', 'style:surreal',
       'theme:body', 'theme:time', 'theme:ecology', 'theme:labor', 'theme:home', 'theme:gender', 'theme:archive',
       'theme:landscape',
       'mood:dark', 'mood:warm', 'mood:humorous', 'mood:melancholy'
     )
     order by category, sort_order $$,
  $$ values
       ('medium:ceramics'::text, 'medium'::text, '도예'::text, 'Ceramics'::text, 10),
       ('medium:textile', 'medium', '섬유', 'Textile', 11),
       ('medium:sound', 'medium', '사운드', 'Sound', 12),
       ('medium:media-art', 'medium', '미디어아트', 'Media art', 13),
       ('medium:architecture-design', 'medium', '건축·디자인', 'Architecture & design', 14),
       ('style:dansaekhwa', 'style', '단색화', 'Dansaekhwa', 6),
       ('style:minhwa', 'style', '민화', 'Minhwa', 7),
       ('style:korean-painting', 'style', '한국화', 'Korean painting', 8),
       ('style:pop', 'style', '팝', 'Pop', 9),
       ('style:surreal', 'style', '초현실', 'Surreal', 10),
       ('theme:body', 'theme', '신체', 'Body', 6),
       ('theme:time', 'theme', '시간', 'Time', 7),
       ('theme:ecology', 'theme', '생태', 'Ecology', 8),
       ('theme:labor', 'theme', '노동', 'Labor', 9),
       ('theme:home', 'theme', '집', 'Home', 10),
       ('theme:gender', 'theme', '젠더', 'Gender', 11),
       ('theme:archive', 'theme', '아카이브', 'Archive', 12),
       ('theme:landscape', 'theme', '풍경', 'Landscape', 13),
       ('mood:dark', 'mood', '어두운', 'Dark', 6),
       ('mood:warm', 'mood', '따뜻한', 'Warm', 7),
       ('mood:humorous', 'mood', '유머', 'Humorous', 8),
       ('mood:melancholy', 'mood', '쓸쓸한', 'Melancholy', 9) $$,
  'each new term exists once, in its category, with both labels and the next free sort order'
);

select results_eq(
  $$ select category::text, count(*)::integer
     from content.art_taxonomy_terms
     where category <> 'visit'
     group by category
     order by category $$,
  $$ values ('medium'::text, 15), ('style', 11), ('theme', 14), ('mood', 10) $$,
  'the art vocabulary now holds fifty terms: the original twenty-eight and twenty-two new ones'
);

select results_eq(
  $$ select id, name_ko, name_en, sort_order::integer
     from content.art_taxonomy_terms
     where id in ('medium:painting', 'medium:craft', 'style:experimental', 'theme:society', 'mood:monumental')
     order by id $$,
  $$ values
       ('medium:craft'::text, '공예'::text, 'Craft'::text, 9),
       ('medium:painting', '회화', 'Painting', 0),
       ('mood:monumental', '기념비적', 'Monumental', 5),
       ('style:experimental', '실험', 'Experimental', 5),
       ('theme:society', '사회', 'Society', 5) $$,
  'existing terms keep their ids, labels and sort orders, including the last row of each category'
);

select is(
  (
    select count(*)::integer
    from content.art_taxonomy_terms
    where category = 'visit'
  ),
  2,
  'the visit terms are untouched'
);

select is(
  (
    select count(*)::integer
    from (
      select category, sort_order
      from content.art_taxonomy_terms
      group by category, sort_order
      having count(*) > 1
    ) as duplicates
  ),
  0,
  'every sort order is unique within its category'
);

select is(
  (
    select count(*)::integer
    from content.art_taxonomy_terms
    where id like any (array['medium:%', 'style:%', 'theme:%', 'mood:%'])
      and not active
  ),
  0,
  'every art term is active for editors to select'
);

insert into content.art_taxonomy_terms (id, category, name_ko, name_en, sort_order)
values
  ('medium:ceramics', 'medium', '도예', 'Ceramics', 10),
  ('mood:melancholy', 'mood', '쓸쓸한', 'Melancholy', 9)
on conflict (id) do nothing;

select is(
  (select count(*)::integer from content.art_taxonomy_terms where category <> 'visit'),
  50,
  're-running the expansion insert adds nothing'
);

select lives_ok(
  $$ select content_private.validate_art_metadata_patch(
       '{"art_term_ids":["medium:craft","medium:ceramics","theme:home","mood:melancholy"]}'::jsonb
     ) $$,
  'a draft may carry a new term beside the broader existing one'
);

select ok(
  not has_table_privilege('anon', 'content.art_taxonomy_terms', 'select')
    and not has_table_privilege('authenticated', 'content.art_taxonomy_terms', 'select'),
  'the vocabulary table stays unreadable to visitors; they see terms only on published exhibitions'
);

select * from finish();
rollback;
