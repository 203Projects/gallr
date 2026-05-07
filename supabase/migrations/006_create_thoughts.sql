-- Phase 2: Exhibition thoughts (Letterboxd-style reviews)

CREATE TABLE IF NOT EXISTS thoughts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    exhibition_id TEXT NOT NULL,
    content TEXT NOT NULL CHECK (char_length(content) <= 280),
    is_approved BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, exhibition_id)
);

ALTER TABLE thoughts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Approved public read" ON thoughts
    FOR SELECT USING (is_approved = TRUE OR auth.uid() = user_id);

CREATE POLICY "Owner write" ON thoughts
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Owner update" ON thoughts
    FOR UPDATE USING (auth.uid() = user_id);

CREATE POLICY "Owner delete" ON thoughts
    FOR DELETE USING (auth.uid() = user_id);
