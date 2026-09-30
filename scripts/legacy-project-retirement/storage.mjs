import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { assertTargets, LEGACY_SHA, sha } from './archive.mjs';
import {createStorageWriter,verifyStorageFile} from './storage-stream.mjs';

export function safeObjectPath(value) {
  if (typeof value !== 'string' || !value || value.startsWith('/') || /[\0\r\n]/.test(value) ||
      value.split('/').some(segment => !segment || segment === '.' || segment === '..')) throw new Error('Unsafe storage object name');
  return value;
}

export function encodeObjectPath(value) { return safeObjectPath(value).split('/').map(encodeURIComponent).join('/'); }

export function storageBudget(approval) {
  if(!approval)return {maxBytes:512*1024*1024,maxObjects:10000};
  if(approval.operation!=='readonly_legacy_storage_archive' || approval.operator!=='Hanshin Lee' ||
    approval.legacy_project_ref_sha256!==LEGACY_SHA || approval.max_bytes!==1024*1024*1024 ||
    approval.expected_object_count!==338 || approval.expected_total_bytes!==839800404 || approval.owner_approved!==true)throw Error('Invalid storage volume approval');
  return {maxBytes:approval.max_bytes,maxObjects:approval.expected_object_count,expectedBytes:approval.expected_total_bytes};
}

export function safeStorageDiagnostic(error) {
  const messages=['Unsafe storage object name','Unsafe storage archive directory','Storage key does not match the verified legacy key','Wrong legacy service key','A legacy-project server key is required','Invalid bucket inventory','Invalid bucket identifier','Invalid storage page','Archive size requires operator review','Missing dedicated archive key','Storage restore integrity failed','Invalid storage volume approval','Insufficient free disk for storage archive','Storage inventory changed from approved scope'];
  if(messages.includes(error.message) || /^Storage archival HTTP [0-9]{3}$/.test(error.message))return error.message;
  const code=error.cause?.code;
  if(['ENOTFOUND','ECONNRESET','ECONNREFUSED','ETIMEDOUT','CERT_HAS_EXPIRED','UNABLE_TO_VERIFY_LEAF_SIGNATURE'].includes(code))return 'Storage network error: '+code;
  return 'Storage archive stopped before a valid complete receipt was created';
}

export async function archiveStorage(environment, directory) {
  const legacy=environment.GALLR_RETIRE_LEGACY_REF, primary=environment.GALLR_RETIRE_PRIMARY_REF;
  assertTargets(legacy,primary);
  const info=fs.lstatSync(directory);
  if (info.isSymbolicLink() || !info.isDirectory() || fs.realpathSync(directory)!==directory || info.uid!==process.getuid() || (info.mode&0o777)!==0o700) throw new Error('Unsafe storage archive directory');
  const credential=environment.GALLR_RETIRE_SERVICE_KEY;
  if (!credential || sha(credential)!==environment.GALLR_RETIRE_CONFIRMED_KEY_SHA256) throw new Error('Storage key does not match the verified legacy key');
  const headers={apikey:credential};
  if (credential.startsWith('eyJ')) {
    const claims=JSON.parse(Buffer.from(credential.split('.')[1],'base64url'));
    if (claims.ref!==legacy || claims.role!=='service_role') throw new Error('Wrong legacy service key');
    headers.Authorization='Bearer '+credential;
  } else if (!credential.startsWith('sb_secret_')) throw new Error('A legacy-project server key is required');
  const key=Buffer.from(environment.GALLR_RETIRE_ARCHIVE_KEY??'','hex');
  if(key.length!==32)throw Error('Missing dedicated archive key');
  let approval;
  if(environment.GALLR_RETIRE_STORAGE_APPROVAL_FILE){
    const file=environment.GALLR_RETIRE_STORAGE_APPROVAL_FILE,stat=fs.lstatSync(file);
    if(stat.isSymbolicLink()||!stat.isFile()||stat.nlink!==1||stat.uid!==process.getuid()||(stat.mode&0o777)!==0o400)throw Error('Invalid storage volume approval');
    approval=JSON.parse(fs.readFileSync(file));
  }
  const budget=storageBudget(approval),space=fs.statfsSync(directory);
  if(space.bavail*space.bsize<Math.ceil(budget.maxBytes*4/3)+2*1024*1024*1024)throw Error('Insufficient free disk for storage archive');
  const origin=`https://${legacy}.supabase.co`;
  const request=async (url,body) => {
    const response=await fetch(origin+url,{method:body?'POST':'GET',headers:{...headers,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(30000)});
    if (!response.ok) throw new Error('Storage archival HTTP '+response.status);
    return response;
  };
  const buckets=await (await request('/storage/v1/bucket')).json();
  if (!Array.isArray(buckets)) throw new Error('Invalid bucket inventory');
  const temporary=path.join(directory,'legacy-storage.partial-'+crypto.randomBytes(8).toString('hex'));
  const writer=await createStorageWriter(temporary,key);
  let totalBytes=0,objectCount=0,published=false;
  try {
  await writer.record({kind:'inventory',schema:2,buckets});
  for (const bucket of buckets) {
    if (!/^[a-zA-Z0-9_-]{1,100}$/.test(bucket.id)) throw new Error('Invalid bucket identifier');
    const prefixes=[''];
    while (prefixes.length) {
      const prefix=prefixes.shift();
      for (let offset=0;;offset+=100) {
        const rows=await (await request('/storage/v1/object/list/'+encodeURIComponent(bucket.id),{prefix,limit:100,offset,sortBy:{column:'name',order:'asc'}})).json();
        if (!Array.isArray(rows)) throw new Error('Invalid storage page');
        for (const row of rows) {
          const objectName=safeObjectPath(prefix?prefix+'/'+row.name:row.name);
          if (row.id===null) {prefixes.push(objectName);continue;}
          const response=await request('/storage/v1/object/authenticated/'+encodeURIComponent(bucket.id)+'/'+encodeObjectPath(objectName));
          const chunks=[];let objectBytes=0;
          for await(const chunk of response.body){
            objectBytes+=chunk.length;totalBytes+=chunk.length;
            if(totalBytes>budget.maxBytes||objectBytes>64*1024*1024)throw Error('Archive size requires operator review');
            chunks.push(chunk);
          }
          if(objectCount>=budget.maxObjects)throw Error('Archive size requires operator review');
          const bytes=Buffer.concat(chunks,objectBytes);
          await writer.record({kind:'object',bucket:bucket.id,name:objectName,metadata:row.metadata,sha256:sha(bytes),bytes:bytes.toString('base64')});
          objectCount++;bytes.fill(0);
          if(objectCount%50===0)console.log(JSON.stringify({storage_objects_archived:objectCount,total_bytes:totalBytes}));
        }
        if (rows.length<100) break;
      }
    }
  }
  if(budget.expectedBytes!==undefined&&(totalBytes!==budget.expectedBytes||objectCount!==budget.maxObjects))throw Error('Storage inventory changed from approved scope');
  const digest=await writer.finish(),restored=await verifyStorageFile(temporary,key);
  if(restored.object_count!==objectCount||restored.total_bytes!==totalBytes||restored.bucket_count!==buckets.length)throw Error('Storage restore integrity failed');
  fs.chmodSync(temporary,0o400);
  const output=path.join(directory,'legacy-storage.ndjson.aesgcm');
  fs.linkSync(temporary,output);fs.unlinkSync(temporary);published=true;
  const receipt={schema:2,format:'encrypted_ndjson',legacy_project_ref_sha256:sha(legacy),bucket_count:buckets.length,object_count:objectCount,total_bytes:totalBytes,
    encrypted_archive_sha256:digest,all_object_bytes_restore_verified:true,remote_writes:false,verified_at_utc:new Date().toISOString()};
  fs.writeFileSync(path.join(directory,'storage-archive-receipt.json'),JSON.stringify(receipt,null,2)+'\n',{flag:'wx',mode:0o400});
  return receipt;
  } finally {key.fill(0);await writer.close();if(!published&&fs.existsSync(temporary))fs.unlinkSync(temporary);}
}

if (process.argv[1]?.endsWith('/storage.mjs')) {
  try {console.log(JSON.stringify(await archiveStorage(process.env,process.argv[2])));}
  catch(error) {console.error(safeStorageDiagnostic(error));process.exitCode=1;}
}
