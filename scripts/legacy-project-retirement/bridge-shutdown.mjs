import {LEGACY_SHA,PRIMARY_SHA,sha} from './archive.mjs';

// Pure SQL preparation. Execution must use the same freshly verified provider
// project identity and the exact clean committed source that produced the SQL.
export function buildBridgeShutdown(side,metadata,commit) {
  const expected=side==='source'?{sha:PRIMARY_SHA,name:'gallr-korea',region:'ap-northeast-2'}:
    side==='receiver'?{sha:LEGACY_SHA,name:'gallr',region:'ap-southeast-1'}:null;
  if(!expected||!metadata||sha(metadata.id??'')!==expected.sha||metadata.name!==expected.name||metadata.region!==expected.region||
    metadata.status!=='ACTIVE_HEALTHY'||!/^[a-f0-9]{40}$/.test(commit))throw Error('Unverified bridge shutdown target or commit');
  const reason=`Hanshin Lee; approved legacy retirement; ${commit}`;
  const body=side==='source'?`
  if v_config.enabled then raise exception 'Primary unexpectedly acts as a legacy receiver'; end if;
  select jobid, command into strict v_jobid, v_command from cron.job
    where jobname = 'gallr-legacy-catalog-reconcile-5m' for update;
  if v_command is distinct from 'select content_private.invoke_legacy_catalog_mirror()' then
    raise exception 'Unexpected legacy bridge schedule command';
  end if;
  update content_private.legacy_mobile_catalog_mirror_config
    set source_outbox_enabled = false, reason = '${reason}' where singleton;
  perform cron.alter_job(v_jobid, null, null, null, null, false);
  `:`
  if pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(v_config.expected_source_project_ref,'UTF8')),'hex')
      is distinct from '${PRIMARY_SHA}' then raise exception 'Unexpected receiver source identity'; end if;
  if not exists(select 1 from content_private.exhibition_catalog_runtime
    where singleton and legacy_writes_blocked and not legacy_mirror_enabled) then
    raise exception 'Legacy catalogue writer is not frozen';
  end if;
  update content_private.legacy_mobile_catalog_mirror_config
    set enabled = false, reason = '${reason}' where singleton;
  `;
  return `begin;
set local lock_timeout = '5s';
set local statement_timeout = '20s';
select pg_catalog.pg_advisory_xact_lock(73241,1);
do $retire_bridge$
declare
  v_config content_private.legacy_mobile_catalog_mirror_config%rowtype;
  v_jobid bigint;
  v_command text;
begin
  select * into strict v_config from content_private.legacy_mobile_catalog_mirror_config where singleton for update;
${body}
end;
$retire_bridge$;
commit;
select jsonb_build_object('source_outbox_enabled',source_outbox_enabled,'legacy_receiver_enabled',enabled)
  as bridge_configuration from content_private.legacy_mobile_catalog_mirror_config where singleton;
`;
}
