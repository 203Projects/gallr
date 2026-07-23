ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS ticket_url text;
ALTER TABLE exhibitions ADD COLUMN IF NOT EXISTS featured   boolean NOT NULL DEFAULT false;
