import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { assertTargets, envelopeHeader, decryptArchive, passfileLine } from './archive.mjs';

test('rejects primary, staging, malformed and swapped retirement targets', () => {
  for (const legacy of ['oqrvbstopuppznxqoonp', 'loesprvprsarosgzueuy', 'short', '']) {
    assert.throws(() => assertTargets(legacy, 'oqrvbstopuppznxqoonp'));
  }
  assert.throws(() => assertTargets('yhuhjxswjbrtmbpbrciq', 'loesprvprsarosgzueuy'));
  assert.doesNotThrow(() => assertTargets('yhuhjxswjbrtmbpbrciq', 'oqrvbstopuppznxqoonp'));
});

test('archive authenticates every byte and refuses a different key', () => {
  const key = crypto.randomBytes(32), nonce = crypto.randomBytes(12);
  const original = Buffer.from('PGDMP synthetic local fixture');
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce);
  const archive = Buffer.concat([envelopeHeader(nonce), cipher.update(original), cipher.final(), cipher.getAuthTag()]);
  assert.deepEqual(decryptArchive(archive, key), original);
  const changed = Buffer.from(archive); changed[22] ^= 1;
  assert.throws(() => decryptArchive(changed, key));
  assert.throws(() => decryptArchive(archive, crypto.randomBytes(32)));
  assert.throws(() => decryptArchive(archive.subarray(0, -1), key));
});

test('passfile escaping cannot inject another credential record', () => {
  assert.equal(passfileLine({host:'db.fixture',port:'5432',database:'postgres',user:'postgres',password:'a:b\\c'}), 'db.fixture:5432:postgres:postgres:a\\:b\\\\c\n');
  for (const password of ['a\nb', 'a\rb', 'a\0b']) assert.throws(() => passfileLine({host:'db.fixture',port:'5432',database:'postgres',user:'postgres',password}));
});
