import test from 'node:test';
import assert from 'node:assert/strict';
import { assertRestoreContainer } from './restore.mjs';

test('rejects exposed, running application and unowned restore containers', () => {
  const good={Name:'/gallr-retirement-restore-20260930',State:{Running:true},HostConfig:{NetworkMode:'none',PortBindings:{}},Config:{Labels:{'com.gallr.retirement-restore':'20260930'}}};
  assert.doesNotThrow(()=>assertRestoreContainer(good));
  assert.throws(()=>assertRestoreContainer({...good,HostConfig:{NetworkMode:'bridge',PortBindings:{}}}));
  assert.throws(()=>assertRestoreContainer({...good,HostConfig:{NetworkMode:'none',PortBindings:{5432:[]}}}));
  assert.throws(()=>assertRestoreContainer({...good,Name:'/some-other-database'}));
  assert.throws(()=>assertRestoreContainer({...good,Config:{Labels:{}}}));
});
