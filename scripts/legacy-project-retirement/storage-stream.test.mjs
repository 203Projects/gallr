import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createStorageWriter,verifyStorageFile} from './storage-stream.mjs';
import {sha} from './archive.mjs';

test('streaming storage archives authenticate all records and object bytes',async t=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'gallr-stream-test-'));
  t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const file=path.join(dir,'archive'),key=Buffer.alloc(32,7),bytes=Buffer.alloc(128*1024,42);
  const writer=await createStorageWriter(file,key);
  await writer.record({kind:'inventory',schema:2,buckets:[{id:'test'}]});
  await writer.record({kind:'object',bucket:'test',name:'folder/a',sha256:sha(bytes),bytes:bytes.toString('base64')});
  const digest=await writer.finish();
  assert.equal(digest,sha(fs.readFileSync(file)));
  assert.deepEqual(await verifyStorageFile(file,key),{object_count:1,total_bytes:bytes.length,bucket_count:1});
  const data=fs.readFileSync(file);data[data.length-1]^=1;
  fs.writeFileSync(file,data);
  await assert.rejects(()=>verifyStorageFile(file,key));
});

test('duplicate objects cannot pass a storage restore check',async t=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'gallr-stream-test-'));
  t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const file=path.join(dir,'archive'),key=Buffer.alloc(32,9),writer=await createStorageWriter(file,key);
  await writer.record({kind:'inventory',schema:2,buckets:[{id:'test'}]});
  const record={kind:'object',bucket:'test',name:'a',sha256:sha(Buffer.from('x')),bytes:Buffer.from('x').toString('base64')};
  await writer.record(record);await writer.record(record);await writer.finish();
  await assert.rejects(()=>verifyStorageFile(file,key));
});
