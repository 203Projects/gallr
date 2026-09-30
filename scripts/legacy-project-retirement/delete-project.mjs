import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {LEGACY_SHA,PRIMARY_SHA,STAGING_SHA,sha} from './archive.mjs';
import {assertMatureHold} from './hold.mjs';

export function assertProjectSet(projects) {
  if(!Array.isArray(projects))throw Error('Invalid project identity response');
  const find=(digest,name,region)=>{
    const matches=projects.filter(p=>typeof p.id==='string'&&sha(p.id)===digest);
    if(matches.length!==1||matches[0].name!==name||matches[0].region!==region)throw Error('Project identity changed');
    return matches[0];
  };
  const legacy=find(LEGACY_SHA,'gallr','ap-southeast-1');
  const primary=find(PRIMARY_SHA,'gallr-korea','ap-northeast-2');
  const staging=find(STAGING_SHA,'gallr-staging','ap-northeast-2');
  if(legacy.status!=='ACTIVE_HEALTHY'||primary.status!=='ACTIVE_HEALTHY')throw Error('Retirement targets are not healthy');
  return {legacy,primary,staging};
}

export function assertDeletionConfirmation(line,projects,commit) {
  const expected=`DELETE LEGACY ${projects.legacy.id} NOT SEOUL ${projects.primary.id} NOT STAGING ${projects.staging.id} ${commit} ACCEPT_NO_INDEPENDENT_REVIEW`;
  if(line!==expected)throw Error('Action-time retirement confirmation did not match');
}

function protectedPolicy(filename) {
  const info=fs.lstatSync(filename),parent=fs.lstatSync(path.dirname(filename));
  if(info.isSymbolicLink()||!info.isFile()||info.nlink!==1||info.uid!==process.getuid()||(info.mode&0o777)!==0o400||
    parent.isSymbolicLink()||!parent.isDirectory()||parent.uid!==process.getuid()||(parent.mode&0o777)!==0o700)throw Error('Unsafe retirement intent');
  return {policy:JSON.parse(fs.readFileSync(filename)),times:[info.mtimeMs,info.ctimeMs]};
}

function reviewedCommit(repo) {
  const env={PATH:'/usr/bin:/bin',LANG:'C',GIT_CONFIG_GLOBAL:'/dev/null',GIT_CONFIG_NOSYSTEM:'1'};
  const git=(...args)=>execFileSync('/usr/bin/git',['--no-replace-objects','-C',repo,...args],{env});
  const commit=git('rev-parse','HEAD').toString().trim();
  if(git('status','--porcelain').length)throw Error('Dirty retirement checkout');
  const files=git('ls-files','scripts/legacy-project-retirement','scripts/staging-rehearsal/lib/database-target.mjs').toString().trim().split('\n');
  for(const filename of files)if(!fs.readFileSync(path.join(repo,filename)).equals(git('show',commit+':'+filename)))throw Error('Substituted retirement source');
  return commit;
}

async function typedConfirmation(commit) {
  if(!process.stdin.isTTY||!process.stdout.isTTY)throw Error('A real operator terminal is required');
  process.stdout.write(`Permanent Singapore project retirement. Seoul and staging are retained.\nReviewed commit: ${commit}\nCheck the three dashboard identities and manually type this shape using their actual references:\nDELETE LEGACY <legacy-ref> NOT SEOUL <primary-ref> NOT STAGING <staging-ref> <full-reviewed-commit> ACCEPT_NO_INDEPENDENT_REVIEW\nAction-time confirmation (hidden): `);
  const wasRaw=process.stdin.isRaw;process.stdin.setRawMode(true);process.stdin.resume();
  try {return await new Promise((resolve,reject)=>{
    let line='';const read=bytes=>{
      for(const c of bytes.toString('utf8')){
        if(c==='\u0003'){process.stdin.off('data',read);reject(Error('Operator cancelled'));return;}
        if(c==='\r'||c==='\n'){process.stdin.off('data',read);resolve(line);return;}
        if(c==='\u007f'||c==='\b')line=line.slice(0,-1);
        else if(c>=' '&&c<='~')line+=c;
        if(line.length>512){process.stdin.off('data',read);reject(Error('Confirmation exceeded bound'));return;}
      }
    };process.stdin.on('data',read);
  });}finally{process.stdin.setRawMode(Boolean(wasRaw));process.stdin.pause();process.stdout.write('\n');}
}

export async function retireProject({policyPath,repo,token,fetcher=fetch,confirm=typedConfirmation}) {
  const commit=reviewedCommit(repo);
  let intent=protectedPolicy(policyPath);
  assertMatureHold(intent.policy,intent.times,commit);
  if(!token)throw Error('Management credential must come from 1Password');
  const request=async (suffix,options={})=>{
    const response=await fetcher('https://api.supabase.com/v1/'+suffix,{...options,redirect:'error',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},signal:AbortSignal.timeout(30000)});
    if(!response.ok)throw Error('Retirement provider request failed: HTTP '+response.status);
    return response;
  };
  const projects=assertProjectSet(await (await request('projects')).json());
  const state=await (await request('projects/'+projects.primary.id+'/database/query',{method:'POST',body:JSON.stringify({read_only:true,query:"select (select source_outbox_enabled from content_private.legacy_mobile_catalog_mirror_config where singleton) as enabled,(select count(*) from cron.job where jobname='gallr-legacy-catalog-reconcile-5m' and active) as active_jobs,(select count(*) from content.outbox_events where event_type='legacy_catalog.sync_requested' and status not in ('delivered','failed')) as pending;"})})).json();
  if(!Array.isArray(state)||state.length!==1||state[0].enabled!==false||Number(state[0].active_jobs)!==0||Number(state[0].pending)!==0)throw Error('Live source bridge is not shut down');
  const receiver=await (await request('projects/'+projects.legacy.id+'/database/query',{method:'POST',body:JSON.stringify({read_only:true,query:"select enabled,(select legacy_writes_blocked from content_private.exhibition_catalog_runtime where singleton) as blocked from content_private.legacy_mobile_catalog_mirror_config where singleton;"})})).json();
  if(!Array.isArray(receiver)||receiver.length!==1||receiver[0].enabled!==false||receiver[0].blocked!==true)throw Error('Live legacy receiver is not frozen');
  assertDeletionConfirmation(await confirm(commit),projects,commit);
  // Revalidate after human interaction so changed files or source cannot reuse
  // the earlier pass. The only DELETE endpoint is the verified Singapore ref.
  if(reviewedCommit(repo)!==commit)throw Error('Retirement commit changed');
  intent=protectedPolicy(policyPath);assertMatureHold(intent.policy,intent.times,commit);
  await request('projects/'+projects.legacy.id,{method:'DELETE'});
  const remaining=await (await request('projects')).json();
  if(!Array.isArray(remaining)||remaining.some(p=>sha(p.id??'')===LEGACY_SHA)||!remaining.some(p=>sha(p.id??'')===PRIMARY_SHA)||!remaining.some(p=>sha(p.id??'')===STAGING_SHA))throw Error('Deletion outcome requires inspection; do not retry the DELETE automatically');
  const receipt={schema:1,operation:'legacy_singapore_project_deleted',legacy_project_ref_sha256:LEGACY_SHA,primary_retained:true,staging_retained:true,
    reviewed_commit:commit,policy_sha256:sha(fs.readFileSync(policyPath)),verified_at_utc:new Date().toISOString()};
  fs.writeFileSync(path.join(intent.policy.backup_directory,'project-deletion-receipt.json'),JSON.stringify(receipt,null,2)+'\n',{flag:'wx',mode:0o400});
  return receipt;
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    if(process.argv.length!==3||!path.isAbsolute(process.argv[2]))throw Error('Absolute retirement intent path required');
    const repo=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
    console.log(JSON.stringify(await retireProject({policyPath:process.argv[2],repo,token:process.env.GALLR_RETIRE_MANAGEMENT_TOKEN})));
  }catch(error){const safe=['Full retirement hold has not elapsed','Action-time retirement confirmation did not match','A real operator terminal is required','Operator cancelled','Dirty retirement checkout','Retirement intent binding mismatch'];console.error(safe.includes(error.message)?error.message:'Retirement stopped; inspect protected evidence before any retry.');process.exitCode=1;}
}
