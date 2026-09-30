import test from 'node:test';
import assert from 'node:assert/strict';
import {buildBridgeShutdown} from './bridge-shutdown.mjs';

test('bridge shutdown is restricted to the reviewed primary and legacy identities',()=>{
  const primary={id:'oqrvbstopuppznxqoonp',name:'gallr-korea',region:'ap-northeast-2',status:'ACTIVE_HEALTHY'};
  const legacy={id:'yhuhjxswjbrtmbpbrciq',name:'gallr',region:'ap-southeast-1',status:'ACTIVE_HEALTHY'};
  const commit='a'.repeat(40);
  assert.match(buildBridgeShutdown('source',primary,commit),/source_outbox_enabled = false/);
  assert.match(buildBridgeShutdown('receiver',legacy,commit),/set enabled = false/);
  for(const [side,p] of [['source',legacy],['receiver',primary],['other',primary],['source',{...primary,id:'loesprvprsarosgzueuy'}],['receiver',{...legacy,region:'ap-northeast-2'}]])assert.throws(()=>buildBridgeShutdown(side,p,commit));
  assert.throws(()=>buildBridgeShutdown('source',primary,'bad'));
});

test('the shutdown statements preserve canonical projection, event history and all unrelated schedules',()=>{
  const source=buildBridgeShutdown('source',{id:'oqrvbstopuppznxqoonp',name:'gallr-korea',region:'ap-northeast-2',status:'ACTIVE_HEALTHY'},'b'.repeat(40));
  assert.match(source,/gallr-legacy-catalog-reconcile-5m/);
  assert.match(source,/cron\.alter_job\(v_jobid, null, null, null, null, false\)/);
  assert.match(source,/Legacy schedule changed during shutdown/);
  assert.doesNotMatch(source,/from cron\.job[^;]*for update/);
  assert.doesNotMatch(source,/\b(delete|drop|truncate)\b|update\s+content\.outbox_events|update\s+content_private\.exhibition_catalog_runtime/i);
  assert.match(source,/v_config\.enabled/);
});
