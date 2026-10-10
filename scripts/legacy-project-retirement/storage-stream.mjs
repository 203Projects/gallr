import crypto from 'node:crypto';
import fs from 'node:fs';
import {envelopeHeader,sha} from './archive.mjs';

// Encrypt one record at a time; never build a JSON string for the entire bucket.
export async function createStorageWriter(filename,key) {
  if(key.length!==32)throw Error('Missing dedicated archive key');
  const file=await fs.promises.open(filename,'wx',0o600);
  const nonce=crypto.randomBytes(12),cipher=crypto.createCipheriv('aes-256-gcm',key,nonce),digest=crypto.createHash('sha256');
  const write=async bytes=>{
    digest.update(bytes);
    for(let offset=0;offset<bytes.length;){const {bytesWritten}=await file.write(bytes,offset,bytes.length-offset);if(!bytesWritten)throw Error('Archive write failed');offset+=bytesWritten;}
  };
  await write(envelopeHeader(nonce));
  return {
    async record(value){await write(cipher.update(JSON.stringify(value)+'\n','utf8'));},
    async finish(){await write(cipher.final());await write(cipher.getAuthTag());await file.sync();await file.close();return digest.digest('hex');},
    async close(){await file.close();}
  };
}

export async function verifyStorageFile(filename,key) {
  const file=await fs.promises.open(filename,'r');
  const size=(await file.stat()).size,header=Buffer.alloc(20),tag=Buffer.alloc(16);
  try {await file.read(header,0,20,0);await file.read(tag,0,16,size-16);}finally{await file.close();}
  if(size<36 || !header.subarray(0,8).equals(Buffer.from('GALLRDB1')) || key.length!==32)throw Error('Invalid storage envelope');
  const decipher=crypto.createDecipheriv('aes-256-gcm',key,header.subarray(8));decipher.setAuthTag(tag);
  const names=new Set();let buckets,objectCount=0,totalBytes=0,pieces=[],lineBytes=0;
  const consume=bytes=>{
    let offset=0;
    while(offset<bytes.length){
      const newline=bytes.indexOf(10,offset),end=newline<0?bytes.length:newline;
      pieces.push(bytes.subarray(offset,end));lineBytes+=end-offset;
      if(lineBytes>100*1024*1024)throw Error('Storage record exceeds verification bound');
      if(newline<0)break;
      const record=JSON.parse(Buffer.concat(pieces,lineBytes).toString('utf8'));pieces=[];lineBytes=0;
      if(!buckets){
        if(record.kind!=='inventory'||record.schema!==2||!Array.isArray(record.buckets))throw Error('Invalid storage inventory');
        buckets=record.buckets;
      } else {
        if(record.kind!=='object'||!buckets.some(bucket=>bucket.id===record.bucket)||typeof record.name!=='string'||typeof record.bytes!=='string')throw Error('Invalid storage record');
        const id=JSON.stringify([record.bucket,record.name]);if(names.has(id))throw Error('Duplicate storage object');names.add(id);
        const decoded=Buffer.from(record.bytes,'base64');if(sha(decoded)!==record.sha256)throw Error('Storage restore integrity failed');
        objectCount++;totalBytes+=decoded.length;decoded.fill(0);
      }
      offset=newline+1;
    }
  };
  for await(const bytes of fs.createReadStream(filename,{start:20,end:size-17}))consume(decipher.update(bytes));
  consume(decipher.final()); // No receipt can be emitted before the GCM tag verifies.
  if(!buckets||lineBytes||pieces.length)throw Error('Incomplete storage archive');
  return {object_count:objectCount,total_bytes:totalBytes,bucket_count:buckets.length};
}
