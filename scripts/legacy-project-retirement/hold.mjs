import fs from 'node:fs';
import path from 'node:path';
import { LEGACY_SHA, PRIMARY_SHA, STAGING_SHA, sha } from './archive.mjs';
import {assertRetirementPreconditions} from './readiness.mjs';

export {STAGING_SHA};
export const HOLD_SECONDS=86400;
const required=[['database-archive-receipt.json','legacy-database.dump.aesgcm','encrypted_archive_sha256'],
  ['storage-archive-receipt.json','legacy-storage.ndjson.aesgcm','encrypted_archive_sha256'],
  ['configuration-archive-receipt.json','legacy-configuration.json.aesgcm','encrypted_archive_sha256']];
export const evidenceNames=[...required.flatMap(([receipt,archive])=>[receipt,archive]),'database-restore-receipt.json','retirement-preconditions.json'];
const objectScope=['legacy database and Auth','legacy Storage objects','legacy Edge Functions and configuration','legacy project credentials and provider backups'];
const retainedScope=['Seoul project and its legacy key compatibility','staging project','independent encrypted archives and 1Password archive key'];

export function assertBackupEvidence(database,restore,storage,configuration) {
  if(database.legacy_project_ref_sha256!==LEGACY_SHA || database.excluded_primary_ref_sha256!==PRIMARY_SHA ||
     database.archive_integrity_verified!==true || database.database_writes!==false ||
     restore.restore_completed!==true || restore.isolated_network!=='none' || restore.host_ports!==0 || restore.cron_jobs_disabled!==true ||
     restore.archive_sha256!==database.encrypted_archive_sha256 || storage.legacy_project_ref_sha256!==LEGACY_SHA ||
     storage.all_object_bytes_restore_verified!==true || storage.remote_writes!==false ||
     configuration.configuration_archived!==true || !Number.isInteger(configuration.function_count)) throw Error('Incomplete or wrong retirement backup evidence');
}

function secureFile(filename) {
  const info=fs.lstatSync(filename);
  if(info.isSymbolicLink() || !info.isFile() || info.nlink!==1 || info.uid!==process.getuid() || (info.mode&0o777)!==0o400) throw Error('Unsafe retirement evidence file');
  return fs.readFileSync(filename);
}

export function sealIntent(directory,policyPath,commit,now=new Date()) {
  if(!/^[a-f0-9]{40}$/.test(commit))throw Error('Full reviewed retirement commit required');
  const receipts=required.map(([name])=>JSON.parse(secureFile(path.join(directory,name))));
  const restore=JSON.parse(secureFile(path.join(directory,'database-restore-receipt.json')));
  assertBackupEvidence(receipts[0],restore,receipts[1],receipts[2]);
  assertRetirementPreconditions(JSON.parse(secureFile(path.join(directory,'retirement-preconditions.json'))));
  const hashes={};
  for(const [receiptName,archiveName,key] of required) {
    const archive=secureFile(path.join(directory,archiveName));
    if(sha(archive)!==JSON.parse(secureFile(path.join(directory,receiptName)))[key])throw Error('Archive bytes differ from verified receipt');
    hashes[archiveName]=sha(archive);hashes[receiptName]=sha(secureFile(path.join(directory,receiptName)));
  }
  hashes['database-restore-receipt.json']=sha(secureFile(path.join(directory,'database-restore-receipt.json')));
  hashes['retirement-preconditions.json']=sha(secureFile(path.join(directory,'retirement-preconditions.json')));
  const parent=fs.lstatSync(path.dirname(policyPath));
  if(parent.isSymbolicLink() || !parent.isDirectory() || parent.uid!==process.getuid() || (parent.mode&0o777)!==0o700)throw Error('Retirement policy parent must be private');
  const policy={schema:1,operation:'delete_legacy_singapore_project',project_name:'gallr',operator:'Hanshin Lee',
    governance:'solo_operator',human_reviewer_count:0,approval_record:'Owner explicitly approved legacy project retirement on 2026-09-30.',
    legacy_project_ref_sha256:LEGACY_SHA,excluded_primary_ref_sha256:PRIMARY_SHA,excluded_staging_ref_sha256:STAGING_SHA,
    reviewed_commit:commit,issued_at_utc:now.toISOString(),minimum_hold_seconds:HOLD_SECONDS,
    object_scope:objectScope,
    retained_scope:retainedScope,
    backup_directory:directory,backup_hashes:hashes};
  fs.writeFileSync(policyPath,JSON.stringify(policy,null,2)+'\n',{flag:'wx',mode:0o400});
  return policy;
}

export function assertMatureHold(policy,fileTimes,currentCommit,now=new Date()) {
  if(policy.schema!==1 || policy.project_name!=='gallr' || policy.operator!=='Hanshin Lee' ||
    policy.governance!=='solo_operator' || policy.human_reviewer_count!==0 ||
    JSON.stringify(policy.object_scope)!==JSON.stringify(objectScope) || JSON.stringify(policy.retained_scope)!==JSON.stringify(retainedScope) ||
    policy.operation!=='delete_legacy_singapore_project' || policy.legacy_project_ref_sha256!==LEGACY_SHA ||
    policy.excluded_primary_ref_sha256!==PRIMARY_SHA || policy.excluded_staging_ref_sha256!==STAGING_SHA ||
    policy.minimum_hold_seconds!==HOLD_SECONDS || !/^[a-f0-9]{40}$/.test(currentCommit) || policy.reviewed_commit!==currentCommit)throw Error('Retirement intent binding mismatch');
  const names=Object.keys(policy.backup_hashes ?? {});
  if(names.length!==evidenceNames.length || !evidenceNames.every(name=>names.includes(name) && /^[a-f0-9]{64}$/.test(policy.backup_hashes[name])) ||
    !Array.isArray(fileTimes) || fileTimes.length<2 || !path.isAbsolute(policy.backup_directory ?? ''))throw Error('Incomplete retirement intent evidence');
  const issued=Date.parse(policy.issued_at_utc);
  const times=[issued,...fileTimes];
  if(times.some(t=>!Number.isFinite(t)||now.getTime()-t<HOLD_SECONDS*1000))throw Error('Full retirement hold has not elapsed');
  for(const [name,digest] of Object.entries(policy.backup_hashes))if(sha(secureFile(path.join(policy.backup_directory,name)))!==digest)throw Error('Retirement evidence changed; restart the hold');
}

if(process.argv[1]?.endsWith('/hold.mjs')) {
  try {
    if(process.argv[2]!=='seal'||process.argv.length!==6)throw Error('Usage: hold.mjs seal EVIDENCE POLICY FULL_COMMIT');
    const policy=sealIntent(process.argv[3],process.argv[4],process.argv[5]);
    console.log(JSON.stringify({retirement_hold_sealed:true,earliest_issue_time_execution_utc:new Date(Date.parse(policy.issued_at_utc)+HOLD_SECONDS*1000).toISOString(),file_timestamp_hold_also_required:true}));
  } catch {console.error('Retirement intent was not sealed: verified complete backup/restore evidence is required.');process.exitCode=1;}
}
