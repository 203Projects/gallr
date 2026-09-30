begin;
create extension if not exists pgtap with schema extensions;
set local search_path = extensions, public;
select plan(29);
select has_function('public','submit_individual_exhibition',array['jsonb','uuid'],'verified individual intake exists');
insert into auth.users(id,email,email_confirmed_at,is_anonymous,raw_user_meta_data) values
 ('00000000-0000-4000-8000-000000008701','individual@example.invalid',now(),false,'{}'),
 ('00000000-0000-4000-8000-000000008702','unverified@example.invalid',null,false,'{}'),
 ('00000000-0000-4000-8000-000000008704','anonymous@example.invalid',now(),true,'{}'),
 ('00000000-0000-4000-8000-000000008703','staff@example.invalid',now(),false,'{}');
insert into content.staff_members(user_id,role,active) values ('00000000-0000-4000-8000-000000008703','admin',true);
create temporary table individual_receipts(receipt jsonb);
grant all on individual_receipts to authenticated;
select ok(not has_function_privilege('anon','public.submit_individual_exhibition(jsonb,uuid)','EXECUTE'),'anon cannot submit');
select ok(not has_function_privilege('service_role','public.submit_individual_exhibition(jsonb,uuid)','EXECUTE'),'service role cannot bypass identity');
select ok((select not prosecdef and proconfig @> array['search_path=""']::text[] from pg_proc where oid='public.submit_individual_exhibition(jsonb,uuid)'::regprocedure),'public wrapper is invoker with fixed search path');
select ok((select prosecdef and proconfig @> array['search_path=""']::text[] from pg_proc where oid='content_private.submit_individual_exhibition_impl(jsonb,uuid)'::regprocedure),'private helper pins empty search path');
create temporary table image_fixtures(request_id uuid primary key,receipt jsonb);
grant all on image_fixtures to authenticated;
create function pg_temp.with_image(p_payload jsonb,p_request_id uuid) returns jsonb language plpgsql as $f$
declare v_receipt jsonb;
begin
  select receipt into v_receipt from image_fixtures where request_id=p_request_id;
  if not found then
    v_receipt:=public.reserve_individual_exhibition_image(md5(p_request_id::text||':image')::uuid,'image/png',68,'poster.png');
    insert into storage.objects(bucket_id,name,metadata) values('exhibition-media',v_receipt->>'object_path','{"mimetype":"image/png","size":68}');
    insert into image_fixtures values(p_request_id,v_receipt);
  end if;
  return p_payload||jsonb_build_object('image_asset_id',v_receipt->>'asset_id');
end;
$f$;
grant execute on function pg_temp.with_image(jsonb,uuid) to authenticated;
set local role authenticated;
select set_config('request.jwt.claim.sub','',true);
select throws_ok($$select public.submit_individual_exhibition('{}','00000000-0000-4000-8000-000000008711')$$,'42501','verified_email_required','missing identity denied');
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000008704',true);
select throws_ok($$select public.submit_individual_exhibition('{}','00000000-0000-4000-8000-000000008711')$$,'42501','verified_email_required','anonymous Auth account denied');
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000008702',true);
select throws_ok($$select public.submit_individual_exhibition('{}','00000000-0000-4000-8000-000000008711')$$,'42501','verified_email_required','unverified email denied');
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000008701',true);
select throws_ok($$select public.submit_individual_exhibition('{"submitter_email":"spoof@example.invalid"}','00000000-0000-4000-8000-000000008711')$$,'22023','individual_submission_payload_invalid','caller cannot supply identity');
insert into individual_receipts select public.submit_individual_exhibition(pg_temp.with_image('{"name_ko":"전시","venue_name_ko":"공간","address_ko":"서울","hours":"10–18","opening_date":"2026-09-30","closing_date":"2026-10-31"}','00000000-0000-4000-8000-000000008711'),'00000000-0000-4000-8000-000000008711');
select is((select receipt->>'status' from individual_receipts),'submitted','ordinary verified user can submit');
select is(public.submit_individual_exhibition(pg_temp.with_image('{"name_ko":"전시","venue_name_ko":"공간","address_ko":"서울","hours":"10–18","opening_date":"2026-09-30","closing_date":"2026-10-31"}','00000000-0000-4000-8000-000000008711'),'00000000-0000-4000-8000-000000008711'),(select receipt from individual_receipts),'retry returns identical receipt');
select throws_ok($$select public.submit_individual_exhibition(pg_temp.with_image('{"name_ko":"다른 전시","venue_name_ko":"공간","address_ko":"서울","hours":"10–18","opening_date":"2026-09-30","closing_date":"2026-10-31"}','00000000-0000-4000-8000-000000008711'),'00000000-0000-4000-8000-000000008711')$$,'22023','idempotency_key_reused_with_different_request','changed payload cannot reuse receipt');
reset role;
select is((select count(*)::integer from content.exhibition_submissions where submitter_user_id='00000000-0000-4000-8000-000000008701'),1,'one queue row after retry');
select is((select submitter_email from content.exhibition_submissions where submitter_user_id='00000000-0000-4000-8000-000000008701'),'individual@example.invalid','email comes from verified auth identity');
select is((select source from content.exhibition_submissions where submitter_user_id='00000000-0000-4000-8000-000000008701'),'public_form','existing public review path reused');
select is((select count(*)::integer from content.staff_members where user_id='00000000-0000-4000-8000-000000008701'),0,'submission grants no staff membership');
select is((select count(*)::integer from content.gallery_memberships where user_id='00000000-0000-4000-8000-000000008701'),0,'submission grants no gallery membership');
select is((select count(*)::integer from content.editor_memberships where user_id='00000000-0000-4000-8000-000000008701'),0,'submission grants no editor membership');
select ok((select accepted_exhibition_id is null from content.exhibition_submissions where submitter_user_id='00000000-0000-4000-8000-000000008701'),'intake does not create a published exhibition');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000008703',true);
select lives_ok($$select public.admin_accept_exhibition_submission((select (receipt->>'submission_id')::uuid from individual_receipts),'00000000-0000-4000-8000-000000008731')$$,'staff can accept through existing review');
reset role;
select ok((select accepted_exhibition_id is not null from content.exhibition_submissions where submitter_user_id='00000000-0000-4000-8000-000000008701'),'review creates canonical draft');
select is((select count(*)::integer from content.exhibition_version_media where media_id=(select (receipt->>'asset_id')::uuid from image_fixtures where request_id='00000000-0000-4000-8000-000000008711')),1,'accepted draft retains submitted image');
select ok((select published_version_id is null from content.exhibitions where id=(select accepted_exhibition_id from content.exhibition_submissions where submitter_user_id='00000000-0000-4000-8000-000000008701')),'accepted submission remains unpublished');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000008701',true);
select throws_ok($$select public.submit_individual_exhibition(pg_temp.with_image('{"name_ko":"전시","venue_name_ko":"공간","address_ko":"서울","hours":"10–18","opening_date":"2026-02-30","closing_date":"2026-10-31"}','00000000-0000-4000-8000-000000008712'),'00000000-0000-4000-8000-000000008712')$$,'22008',null,'impossible calendar date rejected');
select throws_ok($$select public.submit_individual_exhibition(pg_temp.with_image('{"name_ko":42,"venue_name_ko":"공간","address_ko":"서울","hours":"10–18","opening_date":"2026-09-30","closing_date":"2026-10-31"}','00000000-0000-4000-8000-000000008712'),'00000000-0000-4000-8000-000000008712')$$,'22023','individual_submission_payload_invalid','non-string payload rejected');
select lives_ok($$select public.submit_individual_exhibition(pg_temp.with_image('{"name_ko":"두 번째","venue_name_ko":"공간","address_ko":"서울","hours":"10–18","opening_date":"2026-09-30","closing_date":"2026-10-31"}','00000000-0000-4000-8000-000000008712'),'00000000-0000-4000-8000-000000008712')$$,'second submission succeeds');
select lives_ok($$select public.submit_individual_exhibition(pg_temp.with_image('{"name_ko":"세 번째","venue_name_ko":"공간","address_ko":"서울","hours":"10–18","opening_date":"2026-09-30","closing_date":"2026-10-31"}','00000000-0000-4000-8000-000000008713'),'00000000-0000-4000-8000-000000008713')$$,'third submission succeeds');
select throws_ok($$select public.submit_individual_exhibition(pg_temp.with_image('{"name_ko":"네 번째","venue_name_ko":"공간","address_ko":"서울","hours":"10–18","opening_date":"2026-09-30","closing_date":"2026-10-31"}','00000000-0000-4000-8000-000000008714'),'00000000-0000-4000-8000-000000008714')$$,'P0001','submission_rate_limited','fourth submission is rate limited');
reset role;
select is((select count(*)::integer from content.audit_log where actor_user_id='00000000-0000-4000-8000-000000008701' and action='individual.exhibition_submitted'),3,'each successful intake emits one audit event');
select * from finish();
rollback;
