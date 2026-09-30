import test from 'node:test';
import assert from 'node:assert/strict';
import { assertRestoreContainer, assertRestoreSettings } from './restore.mjs';

test('local restore requires the correct cron database and disabled jobs',()=>{
  const libraries='pg_cron,pg_net,pgsodium,supabase_vault';
  assert.doesNotThrow(()=>assertRestoreSettings(['legacy_retirement_restore','off',libraries]));
  for(const settings of [['postgres','off',libraries],['legacy_retirement_restore','on',libraries],['legacy_retirement_restore','off','pg_cron'],[null,null],[]])assert.throws(()=>assertRestoreSettings(settings));
});

test('rejects exposed, running application and unowned restore containers', () => {
  const good={Name:'/gallr-retirement-restore-20260930',State:{Running:true},HostConfig:{NetworkMode:'none',PortBindings:{}},Config:{Labels:{'com.gallr.retirement-restore':'20260930'}}};
  assert.doesNotThrow(()=>assertRestoreContainer(good));
  assert.throws(()=>assertRestoreContainer({...good,HostConfig:{NetworkMode:'bridge',PortBindings:{}}}));
  assert.throws(()=>assertRestoreContainer({...good,HostConfig:{NetworkMode:'none',PortBindings:{5432:[]}}}));
  assert.throws(()=>assertRestoreContainer({...good,Name:'/some-other-database'}));
  assert.throws(()=>assertRestoreContainer({...good,Config:{Labels:{}}}));
});
