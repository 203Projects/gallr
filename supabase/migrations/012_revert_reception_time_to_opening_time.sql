-- Revert migration 010. The shipped app (1.2.0) reads opening_time from
-- Supabase; until a new app release cuts over to reception_time, keep
-- the column named opening_time. The rename remains a TODO for a future
-- coordinated release.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'exhibitions'
      AND column_name = 'reception_time'
  ) THEN
    ALTER TABLE exhibitions RENAME COLUMN reception_time TO opening_time;
  ELSE
    ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS opening_time TEXT;
  END IF;
END $$;
