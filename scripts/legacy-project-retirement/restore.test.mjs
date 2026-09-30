import test from 'node:test';
import assert from 'node:assert/strict';
import { assertRestoreContainer, assertRestoreSettings } from './restore.mjs';

test('local restore requires the correct cron database and disabled jobs',()=>{
  assert.doesNotThrow(()=>assertRestoreSettings(['legacy_retirement_restore','off']));
  for(const settings of [['postgres','off'],['legacy_retirement_restore','on'],[null,null],[]])assert.throws(()=>assertRestoreSettings(settings));
});

test('rejects exposed, running application and unowned restore containers', () => {
  const good={Name:'/gallr-retirement-restore-20260930',State:{Running:true},HostConfig:{NetworkMode:'none',PortBindings:{}},Config:{Labels:{'com.gallr.retirement-restore':'20260930'}}};
  assert.doesNotThrow(()=>assertRestoreContainer(good));
  assert.throws(()=>assertRestoreContainer({...good,HostConfig:{NetworkMode:'bridge',PortBindings:{}}}));
  assert.throws(()=>assertRestoreContainer({...good,HostConfig:{NetworkMode:'none',PortBindings:{5432:[]}}}));
  assert.throws(()=>assertRestoreContainer({...good,Name:'/some-other-database'}));
  assert.throws(()=>assertRestoreContainer({...good,Config:{Labels:{}}}));
});
