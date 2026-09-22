begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(2);

insert into auth.users(id, email, email_confirmed_at, raw_user_meta_data)
values ('08400000-0000-4000-8000-000000000001', 'original@example.invalid', now(), '{}');
insert into content.galleries(id, name_ko, name_en, status)
values ('08400000-0000-4000-8000-000000000002', '백필 갤러리', 'Backfill Gallery', 'active');
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"08400000-0000-4000-8000-000000000001","role":"authenticated"}', true);
select public.owner_claim_existing_gallery(
  '08400000-0000-4000-8000-000000000002', null, null, 'A claim note',
  '08400000-0000-4000-8000-000000000003'
);
reset role;
-- Reproduce a pre-rollout pending membership with an existing intake snapshot.
update content.gallery_memberships set claimant_email = null, claim_email_captured_at = null
where user_id = '08400000-0000-4000-8000-000000000001';
update auth.users set email = 'changed@example.invalid'
where id = '08400000-0000-4000-8000-000000000001';
\ir ../../migrations/20260922023714_complete_workflow_email_delivery.sql
select is((select claimant_email from content.gallery_memberships where user_id = '08400000-0000-4000-8000-000000000001'),
  'original@example.invalid', 'backfill recovers the captured intake address before considering current Auth');
update auth.users set email = 'changed-again@example.invalid'
where id = '08400000-0000-4000-8000-000000000001';
\ir ../../migrations/20260922023714_complete_workflow_email_delivery.sql
select is((select claimant_email from content.gallery_memberships where user_id = '08400000-0000-4000-8000-000000000001'),
  'original@example.invalid', 'reapplying migration does not refresh the captured address');
select * from finish();
rollback;
