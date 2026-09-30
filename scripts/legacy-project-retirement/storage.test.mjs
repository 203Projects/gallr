import test from 'node:test';
import assert from 'node:assert/strict';
import { safeObjectPath, encodeObjectPath, safeStorageDiagnostic,storageBudget } from './storage.mjs';
import {LEGACY_SHA,PRIMARY_SHA} from './archive.mjs';
test('a larger storage bound requires approval for the exact inventoried legacy scope',()=>{
  assert.equal(storageBudget().maxBytes,512*1024*1024);
  const approval={operation:'readonly_legacy_storage_archive',operator:'Hanshin Lee',legacy_project_ref_sha256:LEGACY_SHA,max_bytes:1024*1024*1024,expected_object_count:338,expected_total_bytes:839800404,owner_approved:true};
  assert.equal(storageBudget(approval).maxObjects,338);
  for(const override of [{owner_approved:false},{max_bytes:2*1024*1024*1024},{legacy_project_ref_sha256:PRIMARY_SHA},{expected_object_count:339}])assert.throws(()=>storageBudget({...approval,...override}));
});
test('storage diagnostics expose only controlled categories',()=>{
  assert.equal(safeStorageDiagnostic(new Error('Invalid bucket identifier')),'Invalid bucket identifier');
  assert.equal(safeStorageDiagnostic(new Error('Storage archival HTTP 404')),'Storage archival HTTP 404');
  assert.equal(safeStorageDiagnostic(new Error('private key or filename')),'Storage archive stopped before a valid complete receipt was created');
  assert.equal(safeStorageDiagnostic(Object.assign(new Error('fetch failed'),{cause:{code:'ENOTFOUND'}})),'Storage network error: ENOTFOUND');
});
test('object paths cannot traverse or change the download origin', () => {
  for (const name of ['../private','x/../private','/private','x//y','x\ny','']) assert.throws(()=>safeObjectPath(name));
  assert.equal(encodeObjectPath('folder/전시 #1.jpg'),'folder/%EC%A0%84%EC%8B%9C%20%231.jpg');
  assert.equal(encodeObjectPath('folder/file?x=y'),'folder/file%3Fx%3Dy');
});
