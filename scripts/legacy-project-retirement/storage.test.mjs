import test from 'node:test';
import assert from 'node:assert/strict';
import { safeObjectPath, encodeObjectPath } from './storage.mjs';
test('object paths cannot traverse or change the download origin', () => {
  for (const name of ['../private','x/../private','/private','x//y','x\ny','']) assert.throws(()=>safeObjectPath(name));
  assert.equal(encodeObjectPath('folder/전시 #1.jpg'),'folder/%EC%A0%84%EC%8B%9C%20%231.jpg');
  assert.equal(encodeObjectPath('folder/file?x=y'),'folder/file%3Fx%3Dy');
});
