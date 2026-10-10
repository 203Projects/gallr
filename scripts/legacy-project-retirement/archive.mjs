import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { validateDatabaseTarget, assertCertificateSourceUnchanged } from '../staging-rehearsal/lib/database-target.mjs';

export const LEGACY_SHA = 'ac8581ee2fc8c2fea20044724921692d2c9a070bf05733d68d0cb8e72ce8b105';
export const PRIMARY_SHA = '6192aba89fc90fb953c890cdfa4cdd4fc0e541be87f911ef83ef06e395225e2e';
export const STAGING_SHA = '895b9a952be25460006ac145f10ce7798157585aa97d0635508e3a5252a186d0';
export const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const MAGIC = Buffer.from('GALLRDB1');
const TOOL = '/opt/homebrew/Cellar/libpq/18.6/bin/pg_dump';

export function assertTargets(legacy, primary) {
  if (!/^[a-z0-9]{20}$/.test(legacy ?? '') || !/^[a-z0-9]{20}$/.test(primary ?? '') ||
      sha(legacy) !== LEGACY_SHA || sha(primary) !== PRIMARY_SHA || legacy === primary) {
    throw new Error('Retirement target mismatch');
  }
}

export function passfileLine(target) {
  const escape = value => {
    if (typeof value !== 'string' || /[\r\n\0]/.test(value)) throw new Error('Unsafe passfile value');
    return value.replaceAll('\\', '\\\\').replaceAll(':', '\\:');
  };
  return [target.host, target.port, target.database, target.user, target.password].map(escape).join(':') + '\n';
}

export function envelopeHeader(nonce) {
  if (!Buffer.isBuffer(nonce) || nonce.length !== 12) throw new Error('Invalid archive nonce');
  return Buffer.concat([MAGIC, nonce]);
}

export function decryptArchive(bytes, key) {
  if (bytes.length < 36 || !bytes.subarray(0, 8).equals(MAGIC) || key.length !== 32) throw new Error('Invalid archive');
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(8, 20));
  decipher.setAuthTag(bytes.subarray(-16));
  return Buffer.concat([decipher.update(bytes.subarray(20, -16)), decipher.final()]);
}

function secureDirectory(directory, repo) {
  const info = fs.lstatSync(directory);
  if (info.isSymbolicLink() || !info.isDirectory() || fs.realpathSync(directory) !== directory ||
      info.uid !== process.getuid() || (info.mode & 0o777) !== 0o700 ||
      directory === repo || directory.startsWith(repo + '/')) throw new Error('Unsafe evidence directory');
}

function reviewedSource(repo) {
  const environment = { PATH: '/usr/bin:/bin', LANG: 'C', GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' };
  const git = (...args) => execFileSync('/usr/bin/git', ['--no-replace-objects', '-C', repo, ...args], { env: environment });
  const commit = git('rev-parse', 'HEAD').toString().trim();
  for (const relative of ['scripts/legacy-project-retirement/archive.mjs', 'scripts/staging-rehearsal/lib/database-target.mjs']) {
    const current = fs.readFileSync(path.join(repo, relative));
    if (!current.equals(git('show', `${commit}:${relative}`))) throw new Error('Unreviewed archival source');
  }
  if (git('diff', '--name-only', 'HEAD').length !== 0) throw new Error('Dirty archival checkout');
  return commit;
}

function secureTool() {
  if (fs.realpathSync(TOOL) !== TOOL) throw new Error('Noncanonical pg_dump');
  for (let current = TOOL; current !== '/'; current = path.dirname(current)) {
    const info = fs.lstatSync(current);
    if (info.isSymbolicLink() || (info.mode & 0o022) !== 0 || ![0, process.getuid()].includes(info.uid)) throw new Error('Writable archival tool path');
  }
  return sha(fs.readFileSync(TOOL));
}

export async function archiveDatabase(environment, directory) {
  const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  assertTargets(environment.GALLR_RETIRE_LEGACY_REF, environment.GALLR_RETIRE_PRIMARY_REF);
  secureDirectory(directory, repo);
  const commit = reviewedSource(repo);
  const toolSha = secureTool();
  if (!/^[a-f0-9]{64}$/.test(environment.GALLR_RETIRE_ARCHIVE_KEY ?? '')) throw new Error('Archive key must come from the dedicated 1Password item');
  const key = Buffer.from(environment.GALLR_RETIRE_ARCHIVE_KEY, 'hex');
  const target = validateDatabaseTarget({ projectRef: environment.GALLR_RETIRE_LEGACY_REF,
    databaseUrl: environment.GALLR_RETIRE_DATABASE_URL, requireDirect: 'true' });
  const temporary = fs.mkdtempSync(path.join(fs.realpathSync('/tmp'), 'gallr-retirement-pass-'));
  fs.chmodSync(temporary, 0o700);
  const passfile = path.join(temporary, 'pgpass');
  const output = path.join(directory, 'legacy-database.dump.aesgcm');
  const nonce = crypto.randomBytes(12);
  let child;
  const stop = () => { child?.kill('SIGTERM'); };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  try {
    fs.writeFileSync(passfile, passfileLine(target), { flag: 'wx', mode: 0o600 });
    assertCertificateSourceUnchanged(target.certificate);
    if (sha(fs.readFileSync(TOOL)) !== toolSha) throw new Error('Archival tool changed');
    const fd = fs.openSync(output, 'wx', 0o600);
    fs.writeSync(fd, envelopeHeader(nonce));
    child = spawn(TOOL, ['--format=custom', '--no-owner'], {
      env: { PATH: '/usr/bin:/bin', LANG: 'C', LC_ALL: 'C', PGHOST: target.host, PGPORT: target.port,
        PGDATABASE: 'postgres', PGUSER: 'postgres', PGPASSFILE: passfile,
        PGSSLMODE: 'verify-full', PGSSLROOTCERT: target.certificate.sourcePath, PGGSSENCMODE: 'disable',
        PGSSLCERTMODE: 'disable', PGCONNECT_TIMEOUT: '15',
        PGOPTIONS: '-c default_transaction_read_only=on -c lock_timeout=5000' },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    // Provider diagnostics can contain targets or data; retain only a fixed error category.
    let diagnostics = ''; child.stderr.on('data', bytes => { diagnostics = (diagnostics + bytes.toString()).slice(-8192); });
    const exit = new Promise((resolve, reject) => { child.once('error', reject); child.once('close', code => resolve(code)); });
    const digest = crypto.createHash('sha256'); let plaintextBytes = 0;
    const measure = new Transform({ transform(bytes, _encoding, callback) { digest.update(bytes); plaintextBytes += bytes.length; callback(null, bytes); } });
    const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce);
    await pipeline(child.stdout, measure, cipher, fs.createWriteStream(output, { fd, autoClose: true }));
    if (await exit !== 0) throw new Error(/authentication failed/i.test(diagnostics) ? 'Legacy database credential rejected' : 'Read-only database archive failed');
    fs.appendFileSync(output, cipher.getAuthTag()); fs.chmodSync(output, 0o400);
    const bytes = fs.readFileSync(output);
    const decoded = decryptArchive(bytes, key);
    if (!decoded.subarray(0, 5).equals(Buffer.from('PGDMP')) || decoded.length !== plaintextBytes || sha(decoded) !== digest.digest('hex')) throw new Error('Archive integrity verification failed');
    const receipt = { schema: 1, operation: 'readonly_legacy_database_archive',
      legacy_project_ref_sha256: LEGACY_SHA, excluded_primary_ref_sha256: PRIMARY_SHA,
      operator: 'Hanshin Lee', reviewed_commit: commit, captured_at_utc: new Date().toISOString(),
      pg_dump_sha256: toolSha, encryption: 'AES-256-GCM', plaintext_bytes: plaintextBytes,
      encrypted_archive_sha256: sha(bytes), archive_integrity_verified: true,
      database_writes: false, restore_test_completed: false, storage_backup_completed: false };
    fs.writeFileSync(path.join(directory, 'database-archive-receipt.json'), JSON.stringify(receipt, null, 2) + '\n', { flag: 'wx', mode: 0o400 });
    return receipt;
  } finally {
    key.fill(0); stop(); process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop);
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 3) { console.error('Usage: node archive.mjs /absolute/secure/evidence-directory'); process.exit(64); }
  try { console.log(JSON.stringify(await archiveDatabase(process.env, process.argv[2]))); }
  catch (error) { console.error(['Legacy database credential rejected', 'Read-only database archive failed'].includes(error.message) ? error.message : 'Retirement archive stopped before a valid backup receipt was created'); process.exitCode = 1; }
}
