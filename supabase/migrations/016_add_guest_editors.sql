-- Migration 016 — Guest Editor feature
-- Adds the guest_editors table (admin-managed via Supabase Studio) and
-- a nullable guest_editor_id FK column on exhibitions.

create table guest_editors (
  id          text primary key,           -- slug, e.g. 'minjung-kim'
  name_ko     text not null,
  name_en     text not null default '',
  title_ko    text not null,
  title_en    text not null default '',
  bio_ko      text not null,
  bio_en      text not null default '',
  is_active   boolean not null default false,
  active_from date    not null default current_date,
  active_to   date,
  created_at  timestamptz default now(),
  updated_at  timestamptz default now()
);

alter table guest_editors enable row level security;

create policy "guest_editors are readable by anyone"
  on guest_editors for select using (true);

-- No insert/update policies — admin writes via the service role through
-- Supabase Studio. Without service-role auth, writes fail by default.

create index guest_editors_active_idx
  on guest_editors (is_active, active_from desc);

alter table exhibitions
  add column guest_editor_id text references guest_editors(id) on delete set null;

create index exhibitions_guest_editor_idx
  on exhibitions (guest_editor_id) where guest_editor_id is not null;
