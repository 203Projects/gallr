import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { assertTargets, envelopeHeader, decryptArchive, sha } from './archive.mjs';

export function safeObjectPath(value) {
  if (typeof value !== 'string' || !value || value.startsWith('/') || /[\0\r\n]/.test(value) ||
      value.split('/').some(segment => !segment || segment === '.' || segment === '..')) throw new Error('Unsafe storage object name');
  return value;
}

export function encodeObjectPath(value) { return safeObjectPath(value).split('/').map(encodeURIComponent).join('/'); }

export function safeStorageDiagnostic(error) {
  const messages=['Unsafe storage object name','Unsafe storage archive directory','Storage key does not match the verified legacy key','Wrong legacy service key','A legacy-project server key is required','Invalid bucket inventory','Invalid bucket identifier','Invalid storage page','Archive size requires operator review','Missing dedicated archive key','Storage restore integrity failed'];
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
  const origin=`https://${legacy}.supabase.co`;
  const request=async (url,body) => {
    const response=await fetch(origin+url,{method:body?'POST':'GET',headers:{...headers,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(30000)});
    if (!response.ok) throw new Error('Storage archival HTTP '+response.status);
    return response;
  };
  const buckets=await (await request('/storage/v1/bucket')).json();
  if (!Array.isArray(buckets)) throw new Error('Invalid bucket inventory');
  const objects=[]; let totalBytes=0;
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
          const bytes=Buffer.from(await response.arrayBuffer()); totalBytes+=bytes.length;
          if (totalBytes>512*1024*1024 || objects.length>=10000) throw new Error('Archive size requires operator review');
          objects.push({bucket:bucket.id,name:objectName,metadata:row.metadata,sha256:sha(bytes),bytes:bytes.toString('base64')});
        }
        if (rows.length<100) break;
      }
    }
  }
  const payload=Buffer.from(JSON.stringify({schema:1,buckets,objects}));
  const key=Buffer.from(environment.GALLR_RETIRE_ARCHIVE_KEY??'','hex');
  if (key.length!==32) throw new Error('Missing dedicated archive key');
  const nonce=crypto.randomBytes(12),cipher=crypto.createCipheriv('aes-256-gcm',key,nonce);
  const encrypted=Buffer.concat([envelopeHeader(nonce),cipher.update(payload),cipher.final(),cipher.getAuthTag()]);
  const output=path.join(directory,'legacy-storage.json.aesgcm');
  fs.writeFileSync(output,encrypted,{flag:'wx',mode:0o400});
  const restored=JSON.parse(decryptArchive(fs.readFileSync(output),key));
  for (const object of restored.objects) if (sha(Buffer.from(object.bytes,'base64'))!==object.sha256) throw new Error('Storage restore integrity failed');
  key.fill(0);payload.fill(0);
  const receipt={schema:1,legacy_project_ref_sha256:sha(legacy),bucket_count:buckets.length,object_count:objects.length,total_bytes:totalBytes,
    encrypted_archive_sha256:sha(encrypted),all_object_bytes_restore_verified:true,remote_writes:false,verified_at_utc:new Date().toISOString()};
  fs.writeFileSync(path.join(directory,'storage-archive-receipt.json'),JSON.stringify(receipt,null,2)+'\n',{flag:'wx',mode:0o400});
  return receipt;
}

if (process.argv[1]?.endsWith('/storage.mjs')) {
  try {console.log(JSON.stringify(await archiveStorage(process.env,process.argv[2])));}
  catch(error) {console.error(safeStorageDiagnostic(error));process.exitCode=1;}
}
