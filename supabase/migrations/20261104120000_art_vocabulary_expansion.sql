-- Spec 101 T046: the owner-decided vocabulary expansion (specs/101-taste-listening/vocabulary.md, decided
-- 2026-11-04). Twenty-two art terms join the four existing categories after each category's last row. The table is
-- append-only: no existing id, label or sort order changes, and re-running this file inserts nothing.
begin;

insert into content.art_taxonomy_terms (
  id,
  category,
  name_ko,
  name_en,
  sort_order
)
values
  ('medium:ceramics', 'medium', '도예', 'Ceramics', 10),
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
  ('mood:melancholy', 'mood', '쓸쓸한', 'Melancholy', 9)
on conflict (id) do nothing;

commit;
