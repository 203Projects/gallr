import test from 'node:test';
import assert from 'node:assert/strict';
import { safeObjectPath, encodeObjectPath, safeStorageDiagnostic } from './storage.mjs';
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
