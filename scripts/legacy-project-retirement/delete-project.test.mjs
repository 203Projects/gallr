import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {assertProjectSet,assertDeletionConfirmation,retireProject} from './delete-project.mjs';
import {sealIntent,evidenceNames} from './hold.mjs';
import {sha,LEGACY_SHA,PRIMARY_SHA} from './archive.mjs';
import {readyFixture} from './test-fixtures.mjs';
const projects=[{id:'yhuhjxswjbrtmbpbrciq',name:'gallr',region:'ap-southeast-1',status:'ACTIVE_HEALTHY'},
  {id:'oqrvbstopuppznxqoonp',name:'gallr-korea',region:'ap-northeast-2',status:'ACTIVE_HEALTHY'},
  {id:'loesprvprsarosgzueuy',name:'gallr-staging',region:'ap-northeast-2',status:'ACTIVE_HEALTHY'}];

test('provider target proof excludes Seoul, staging, duplicates and renamed targets',()=>{
  assert.equal(assertProjectSet(projects).legacy.name,'gallr');
  for(const p of [[],projects.slice(1),[...projects,projects[0]],projects.map((p,i)=>i? p:{...p,name:'gallr-korea'}),projects.map((p,i)=>i? p:{...p,region:'ap-northeast-2'})])assert.throws(()=>assertProjectSet(p));
});

test('action-time confirmation is bound to all three references and the full source commit',()=>{
  const p=assertProjectSet(projects),commit='a'.repeat(40);
  const line=`DELETE LEGACY ${p.legacy.id} NOT SEOUL ${p.primary.id} NOT STAGING ${p.staging.id} ${commit} ACCEPT_NO_INDEPENDENT_REVIEW`;
  assert.doesNotThrow(()=>assertDeletionConfirmation(line,p,commit));
  for(const wrong of [line.replace(p.legacy.id,p.primary.id),line.replace(p.staging.id,p.legacy.id),line.replace(commit,'b'.repeat(40)),line.replace('ACCEPT_NO_INDEPENDENT_REVIEW',''),line+' '])assert.throws(()=>assertDeletionConfirmation(wrong,p,commit));
});

function cleanFixtureRepo(t) {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'gallr-retire-test-'));fs.chmodSync(dir,0o700);
  t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const git=(...args)=>execFileSync('/usr/bin/git',['-C',dir,...args],{stdio:'pipe'});
  git('init');git('config','user.name','Fixture');git('config','user.email','fixture@example.invalid');
  fs.mkdirSync(path.join(dir,'scripts/legacy-project-retirement'),{recursive:true});
  fs.writeFileSync(path.join(dir,'scripts/legacy-project-retirement/README.md'),'Synthetic source fixture.');
  fs.writeFileSync(path.join(dir,'.gitignore'),'private-evidence/');
  git('add','.');git('commit','-m','fixture');
  return {dir,commit:git('rev-parse','HEAD').toString().trim()};
}

test('missing intent in a clean checkout stops before network contact or human confirmation',async t=>{
  const {dir}=cleanFixtureRepo(t);
  let contacted=0,confirmed=0;
  await assert.rejects(()=>retireProject({policyPath:path.join(dir,'missing.json'),repo:dir,token:'synthetic',fetcher:async()=>{contacted++;throw Error('unexpected');},confirm:async()=>{confirmed++;throw Error('unexpected');}}),/ENOENT/);
  assert.equal(contacted,0);assert.equal(confirmed,0);
});

test('backdating the issue time cannot bypass fresh filesystem timestamps or contact the provider',async t=>{
  const {dir,commit}=cleanFixtureRepo(t),evidence=path.join(dir,'private-evidence');fs.mkdirSync(evidence,{mode:0o700});
  const write=(name,value)=>fs.writeFileSync(path.join(evidence,name),typeof value==='string'?value:JSON.stringify(value),{mode:0o400});
  const digest=sha(Buffer.from('synthetic encrypted archive'));
  for(const name of evidenceNames.filter(name=>name.endsWith('.aesgcm')))write(name,'synthetic encrypted archive');
  write('database-archive-receipt.json',{legacy_project_ref_sha256:LEGACY_SHA,excluded_primary_ref_sha256:PRIMARY_SHA,archive_integrity_verified:true,database_writes:false,encrypted_archive_sha256:digest});
  write('database-restore-receipt.json',{restore_completed:true,isolated_network:'none',host_ports:0,cron_jobs_disabled:true,archive_sha256:digest});
  write('storage-archive-receipt.json',{legacy_project_ref_sha256:LEGACY_SHA,all_object_bytes_restore_verified:true,remote_writes:false,encrypted_archive_sha256:digest});
  write('configuration-archive-receipt.json',{configuration_archived:true,function_count:3,encrypted_archive_sha256:digest});
  write('retirement-preconditions.json',readyFixture());
  const policy=path.join(evidence,'intent.json');sealIntent(evidence,policy,commit,new Date(Date.now()-86400*1000));
  let contacted=0,confirmed=0;
  await assert.rejects(()=>retireProject({policyPath:policy,repo:dir,token:'synthetic',fetcher:async()=>{contacted++;throw Error('unexpected');},confirm:async()=>{confirmed++;throw Error('unexpected');}}),/Full retirement hold has not elapsed/);
  assert.equal(contacted,0);assert.equal(confirmed,0);
});
