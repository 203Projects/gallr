import {LEGACY_SHA,PRIMARY_SHA,STAGING_SHA} from './archive.mjs';
export const readyFixture=()=>({schema:1,operation:'legacy_retirement_preconditions',operator:'Hanshin Lee',
  legacy_project_ref_sha256:LEGACY_SHA,excluded_primary_ref_sha256:PRIMARY_SHA,excluded_staging_ref_sha256:STAGING_SHA,
  bridge:{source_outbox_enabled:false,reconcile_schedule_active:false,legacy_receiver_enabled:false,pending_events:0,legacy_writes_blocked:true},
  catalogue:{legacy_url_count:0,production_cleanup_commit:'a'.repeat(40)},
  stores:{minimum_supported_android_code:24,available_android_code:34,available_ios_version:'1.9.2'},
  observed_at_utc:'2026-09-30T09:00:00Z'});
