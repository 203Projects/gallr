import test from 'node:test';
import assert from 'node:assert/strict';
import {assertBackupEvidence,assertMatureHold,HOLD_SECONDS,STAGING_SHA} from './hold.mjs';
import {LEGACY_SHA,PRIMARY_SHA} from './archive.mjs';
test('the hold cannot start with an integrity check alone or wrong backup target',()=>{
  const db={legacy_project_ref_sha256:LEGACY_SHA,excluded_primary_ref_sha256:PRIMARY_SHA,archive_integrity_verified:true,database_writes:false,encrypted_archive_sha256:'x'};
  const restored={restore_completed:true,isolated_network:'none',host_ports:0,archive_sha256:'x'};
  const storage={legacy_project_ref_sha256:LEGACY_SHA,all_object_bytes_restore_verified:true,remote_writes:false};
  const config={configuration_archived:true,function_count:3};
  assert.doesNotThrow(()=>assertBackupEvidence(db,restored,storage,config));
  assert.throws(()=>assertBackupEvidence(db,{...restored,restore_completed:false},storage,config));
  assert.throws(()=>assertBackupEvidence(db,restored,{...storage,legacy_project_ref_sha256:PRIMARY_SHA},config));
  assert.throws(()=>assertBackupEvidence(db,{...restored,host_ports:5432},storage,config));
});
test('issue time and every file timestamp require a full 24-hour hold',()=>{
  const commit='a'.repeat(40),now=new Date('2026-10-01T12:00:00Z'),old=now.getTime()-HOLD_SECONDS*1000;
  const p={operation:'delete_legacy_singapore_project',legacy_project_ref_sha256:LEGACY_SHA,excluded_primary_ref_sha256:PRIMARY_SHA,excluded_staging_ref_sha256:STAGING_SHA,minimum_hold_seconds:HOLD_SECONDS,reviewed_commit:commit,issued_at_utc:new Date(old).toISOString(),backup_hashes:{}};
  assert.doesNotThrow(()=>assertMatureHold(p,[old,old],commit,now));
  assert.throws(()=>assertMatureHold(p,[old,old+1],commit,now));
  assert.throws(()=>assertMatureHold({...p,minimum_hold_seconds:900},[old],commit,now));
  assert.throws(()=>assertMatureHold(p,[old],'b'.repeat(40),now));
});
