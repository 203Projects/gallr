import test from 'node:test';
import assert from 'node:assert/strict';
import {assertRetirementPreconditions} from './readiness.mjs';
import {LEGACY_SHA} from './archive.mjs';
import {readyFixture} from './test-fixtures.mjs';

test('retirement cannot start while the bridge, production cleanup or supported upgrades are incomplete',()=>{
  const p=readyFixture();assert.doesNotThrow(()=>assertRetirementPreconditions(p));
  for(const override of [{source_outbox_enabled:true},{reconcile_schedule_active:true},{legacy_receiver_enabled:true},{pending_events:1},{legacy_writes_blocked:false}])assert.throws(()=>assertRetirementPreconditions({...p,bridge:{...p.bridge,...override}}));
  assert.throws(()=>assertRetirementPreconditions({...p,catalogue:{...p.catalogue,legacy_url_count:1}}));
  assert.throws(()=>assertRetirementPreconditions({...p,catalogue:{...p.catalogue,verified_production_commit:null}}));
  assert.throws(()=>assertRetirementPreconditions({...p,catalogue:{...p.catalogue,seed_cleanup_develop_commit:null}}));
  assert.throws(()=>assertRetirementPreconditions({...p,stores:{...p.stores,available_android_code:23}}));
  assert.throws(()=>assertRetirementPreconditions({...p,stores:{...p.stores,available_ios_version:'1.7.6'}}));
});

test('readiness never accepts another target or malformed evidence',()=>{
  for(const p of [{},null,{...readyFixture(),draft:true},{...readyFixture(),excluded_primary_ref_sha256:LEGACY_SHA},{...readyFixture(),observed_at_utc:'invalid'}, {...readyFixture(),stores:{minimum_supported_android_code:24,available_android_code:34,available_ios_version:'1.9.2junk'}}])assert.throws(()=>assertRetirementPreconditions(p));
});
