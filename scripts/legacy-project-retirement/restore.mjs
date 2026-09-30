import fs from 'node:fs';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { pipeline } from 'node:stream/promises';
import { decryptArchive, sha } from './archive.mjs';

export function assertRestoreContainer(value) {
  if (value.HostConfig?.NetworkMode !== 'none' || Object.keys(value.HostConfig?.PortBindings ?? {}).length ||
      value.Config?.Labels?.['com.gallr.retirement-restore'] !== '20260930' || !value.State?.Running ||
      value.Name !== '/gallr-retirement-restore-20260930') throw new Error('Restore target must be the owned isolated container');
}

export async function restore(directory, key) {
  const docker = '/Applications/Docker.app/Contents/Resources/bin/docker';
  const container = 'gallr-retirement-restore-20260930';
  const environment = { HOME: '/Users/hanshin', PATH: '/usr/bin:/bin:/opt/homebrew/bin', LANG: 'C' };
  const info = JSON.parse(execFileSync(docker, ['inspect', container], {env:environment}))[0];
  assertRestoreContainer(info);
  const bytes = fs.readFileSync(path.join(directory, 'legacy-database.dump.aesgcm'));
  const receipt = JSON.parse(fs.readFileSync(path.join(directory, 'database-archive-receipt.json')));
  if (sha(bytes) !== receipt.encrypted_archive_sha256) throw new Error('Backup receipt mismatch');
  const plaintext = decryptArchive(bytes, key);
  const generate = spawn('/opt/homebrew/Cellar/libpq/18.6/bin/pg_restore', ['--no-owner', '--clean', '--if-exists', '--file=-'], {env:environment,stdio:['pipe','pipe','pipe']});
  const apply = spawn(docker, ['exec', '-i', container, 'psql', '-X', '-U', 'postgres', '-d', 'legacy_retirement_restore', '-v', 'ON_ERROR_STOP=1'], {env:environment,stdio:['pipe','pipe','pipe']});
  // SQL/data diagnostics are private. Do not print restore statements or row values.
  let diagnostics='';
  for (const child of [generate,apply]) child.stderr.on('data', bytes => {diagnostics=(diagnostics+bytes.toString()).slice(-4096);});
  apply.stdout.resume();
  const complete = child => new Promise((resolve,reject) => {child.once('error',reject);child.once('close',code=>resolve(code));});
  const generated = complete(generate), applied = complete(apply);
  generate.stdin.on('error', () => {});
  generate.stdin.end(plaintext);
  let transportStopped=false;
  try {await pipeline(generate.stdout,apply.stdin);} catch {transportStopped=true;generate.kill();apply.kill();}
  plaintext.fill(0);
  const statuses=await Promise.all([generated,applied]);
  if (transportStopped || statuses.some(value=>value!==0)) {
    const role=/role "([a-z_]+)" does not exist/.exec(diagnostics);
    const config=/unrecognized configuration parameter "([a-z_]+)"/.exec(diagnostics);
    const extension=/extension "([a-z_]+)" is not available/.exec(diagnostics);
    throw new Error(role ? 'Local restore role missing: '+role[1] : config ? 'Local restore configuration unsupported: '+config[1] :
      extension ? 'Local restore extension missing: '+extension[1] : 'Local restore failed; private diagnostics were not printed');
  }
  const query="select json_build_object('auth_users',(select count(*) from auth.users),'profiles',(select count(*) from public.profiles),'bookmarks',(select count(*) from public.bookmarks),'exhibitions',(select count(*) from public.exhibitions),'storage_objects',(select count(*) from storage.objects))::text;";
  const counts=JSON.parse(execFileSync(docker,['exec',container,'psql','-X','-U','postgres','-d','legacy_retirement_restore','-A','-t','-v','ON_ERROR_STOP=1','-c',query],{env:environment}).toString());
  const result={schema:1,isolated_network:'none',host_ports:0,restore_completed:true,counts,
    archive_sha256:receipt.encrypted_archive_sha256,verified_at_utc:new Date().toISOString()};
  fs.writeFileSync(path.join(directory,'database-restore-receipt.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx',mode:0o400});
  return result;
}

if (process.argv[1]?.endsWith('/restore.mjs')) {
  try { console.log(JSON.stringify(await restore(process.argv[2],Buffer.from(process.env.GALLR_RETIRE_ARCHIVE_KEY ?? '', 'hex')))); }
  catch(error) {console.error(error.message.startsWith('Local restore ') ? error.message : 'Isolated restore failed before a valid restore receipt was created');process.exitCode=1;}
}
