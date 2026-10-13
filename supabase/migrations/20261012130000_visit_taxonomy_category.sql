-- Spec 091 FR-022: editors can say how an exhibition can be visited (free admission, good with children and family)
-- through the reviewed vocabulary. A new enum value cannot be used in the transaction that adds it, so the value is
-- added here on its own and the terms follow in the next migration.
alter type content.art_taxonomy_category add value if not exists 'visit';
