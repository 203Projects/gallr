import fs from 'node:fs';
import path from 'node:path';
import {LEGACY_SHA,PRIMARY_SHA,STAGING_SHA} from './archive.mjs';

export function assertRetirementPreconditions(p) {
  if(!p||p.schema!==1||p.operation!=='legacy_retirement_preconditions'||p.operator!=='Hanshin Lee'||
    p.legacy_project_ref_sha256!==LEGACY_SHA||p.excluded_primary_ref_sha256!==PRIMARY_SHA||p.excluded_staging_ref_sha256!==STAGING_SHA||
    !Number.isFinite(Date.parse(p.observed_at_utc)))throw Error('Wrong or malformed retirement readiness evidence');
  const bridge=p.bridge;
  if(!bridge||bridge.source_outbox_enabled!==false||bridge.reconcile_schedule_active!==false||bridge.legacy_receiver_enabled!==false||
    bridge.pending_events!==0||bridge.legacy_writes_blocked!==true)throw Error('Legacy bridge shutdown is incomplete');
  if(p.catalogue?.legacy_url_count!==0||!/^[a-f0-9]{40}$/.test(p.catalogue?.production_cleanup_commit??''))throw Error('Production legacy media cleanup is incomplete');
  const stores=p.stores,version=/^(\d+)\.(\d+)\.(\d+)$/.exec(stores?.available_ios_version??'');
  const supported=version&&(Number(version[1])>1||(Number(version[1])===1&&(Number(version[2])>7||(Number(version[2])===7&&Number(version[3])>=7))));
  if(!stores||stores.minimum_supported_android_code!==24||!Number.isSafeInteger(stores.available_android_code)||stores.available_android_code<24||!supported)throw Error('Supported store upgrades are not verified');
}

if(process.argv[1]?.endsWith('/readiness.mjs')) {
  try {
    const filename=process.argv[2];if(process.argv.length!==3||!path.isAbsolute(filename??''))throw Error('Invalid evidence path');
    const info=fs.lstatSync(filename);
    if(info.isSymbolicLink()||!info.isFile()||info.nlink!==1||info.uid!==process.getuid()||(info.mode&0o777)!==0o400)throw Error('Unsafe readiness evidence');
    assertRetirementPreconditions(JSON.parse(fs.readFileSync(filename)));
    console.log(JSON.stringify({retirement_preconditions_verified:true,remote_contact:false,remote_writes:false}));
  } catch {console.error('Retirement preconditions are incomplete; no remote operation was attempted.');process.exitCode=1;}
}
