-- Spec 091 FR-022: the visit terms. They ride the existing reviewed-metadata pipeline: staff and gallery owners
-- attach them in the same editor, published versions carry them in art_terms, and clients that do not know the
-- category drop them. They state a fact an editor confirmed; nothing infers them from an exhibition's text.
begin;

alter table content.art_taxonomy_terms
  drop constraint if exists art_taxonomy_terms_id_format;
alter table content.art_taxonomy_terms
  add constraint art_taxonomy_terms_id_format check (
    id ~ '^(medium|style|theme|mood|visit):[a-z0-9]+(-[a-z0-9]+)*$'
  );

insert into content.art_taxonomy_terms (
  id,
  category,
  name_ko,
  name_en,
  sort_order
)
values
  ('visit:free-admission', 'visit', '무료 관람', 'Free admission', 0),
  ('visit:family', 'visit', '아이·가족과 함께', 'Good with children and family', 1)
on conflict (id) do nothing;

comment on table content.art_taxonomy_terms is
  'Controlled, append-only vocabulary for reviewed exhibition metadata: art terms (medium, style, theme, mood) and visit terms (admission, audience). A semantic change requires a new stable identifier rather than reinterpreting an existing identifier.';

commit;
