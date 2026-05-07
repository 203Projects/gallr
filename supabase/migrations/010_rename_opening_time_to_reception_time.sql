-- Intended as a RENAME from opening_time (migration 005). The remote
-- column was missing when this ran, so this acts as either a rename
-- (when opening_time exists) or an add (when it doesn't).
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'exhibitions'
      AND column_name = 'opening_time'
  ) THEN
    ALTER TABLE exhibitions RENAME COLUMN opening_time TO reception_time;
  ELSE
    ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS reception_time TEXT;
  END IF;
END $$;
