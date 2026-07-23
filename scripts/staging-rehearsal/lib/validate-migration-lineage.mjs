#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstat, readdir, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const repositoryRoot = resolve(dirname(scriptPath), '../../..');
const defaultMigrationsDirectory = resolve(repositoryRoot, 'supabase/migrations');

const recoveredMigrationHashes = new Map([
  [
    '20260507150314_exhibitions_add_ticket_url_and_featured.sql',
    '93bea25bbc8979898793102a540de6bc27b12a4b94c53824859845178d2803d1',
  ],
  [
    '20260507150817_exhibitions_drop_unused_featured_column.sql',
    '3f6c62bc009cdef49ac055515bfc0dd9073e2fb699e9640e9f03d77fbd5f4982',
  ],
  [
    '20260511101318_add_is_homepage_featured.sql',
    '2acd81e0ceb09c0e6495ff51618f6426263ade658f62a28501c1321976f70f04',
  ],
  [
    '20260513110737_add_guest_editors.sql',
    'ac3133940174a12fa28dd5e44ce15b7689d6b98e795f6685474981254d165b74',
  ],
  [
    '20260513110749_unify_editors.sql',
    '05fa4deb043166ee73aac46a46ced112c766ab0002f7365c9935f090423db56e',
  ],
  [
    '20260513112154_add_v15_compat_shim.sql',
    'e224562fa31129028261d53bffecd5d58eeb662dfaaf8cd69693a0550774b605',
  ],
  [
    '20260513112327_fix_compat_shim_null_handling.sql',
    '224b28bc3729e62589313a892dcc40dce3bf6ebf840b35fa65043a45b757d909',
  ],
]);

const canonicalMigrationHashes = new Map([
  ['001_create_exhibitions.sql', '0bf8d7bc936186dfbc872d9cdb948984b537fab74deead60e4af3ff709bc613a'],
  ['002_bilingual_columns.sql', 'bf9a6c178f6ce5c61b22e877630713f1f7e1736f22062f3ac43f1a7ffd82143a'],
  ['003_create_exhibition_images_bucket.sql', '124ef48adfc5647d6a709cce3bf43ed13de07c385e3c68e1b7010239b9cf23bf'],
  ['004_add_hours_contact_reception.sql', '9e9f7ade44f518b1fc1a50e98d359b9638cf784bf829f624d24cf51861a4ef67'],
  ['005_add_opening_time.sql', 'fafee55ffd8589b3bec2359292d7087404b9bcf09df385b328075457fa5c64dc'],
  ['006_create_thoughts.sql', '041f0276af7fedf11fcd5d192c03525aeddb1bf42b989d755c7b0d1a59077e09'],
  ['007_fix_bookmarks_upsert_policy.sql', '98ed03a0663f4e9f23a1367c30629871cc2465756b20b097818600299c2ea0cf'],
  ['008_create_avatars_bucket.sql', '493b4e17cdbaa2b9aae9422ce039fcaf158e7efc4b11e9c7f72d43949d4e992b'],
  ['009_admin_thought_moderation.sql', '2e01580c5bb71312dfd40e03f77550366b716928ccd4e309d489a8671674b00c'],
  ['010_rename_opening_time_to_reception_time.sql', '793305c5d8b3a55b50c972a2f9e51c3ec685435b4a383b289a36abddc282c91d'],
  ['011_cleanup_duplicate_policies.sql', '1defa09eb9a94d3b24a7add057935eb58b4e01d74cca1127bc23e42d983b7c90'],
  ['012_revert_reception_time_to_opening_time.sql', 'e29393ca963df8890735cb79dc07fe3ca43974d09b95d2ee32ed55dc588a98a6'],
  ['013_create_events.sql', '5c80f81e00cc094f1b30f31413be188be4fb0f3245e23564c91bdac86eecdcc0'],
  ['014_add_event_cover_image_url.sql', '6c6f1ce3efd0290261cc65b5c583e4ea53c84f8ac9513b309e149c5f6a082a1c'],
  ['20260507150314_exhibitions_add_ticket_url_and_featured.sql', '441d6f96b4fe171d288a23e5602c8e84fdb0c7e93572c86fe0f67daea6918e6c'],
  ['20260507150817_exhibitions_drop_unused_featured_column.sql', 'a26039be7c647623aeb978a8c689c6989183f60a9570407e1daf59836553300a'],
  ['20260511101318_add_is_homepage_featured.sql', '3fcf5cca7ef8393c50ec3b69ae4b412df8c634df02d5792e96d311dcc4cb4f83'],
  ['20260513110737_add_guest_editors.sql', 'fcc001d9a71f1e5f96b1247dd16f01781f85bb67b4e341f6e15b97778499b469'],
  ['20260513110749_unify_editors.sql', '8d9f3669ac7648937bd10c222d4a27c31a56cbdbec0f7a5aa0ec081f78a05f29'],
  ['20260513112154_add_v15_compat_shim.sql', '329bafe3aae18510fff64886518a178b1e2b97caa00709dcca129a8aee4da6a1'],
  ['20260513112327_fix_compat_shim_null_handling.sql', 'd24ff845ed4dbc83b8177cb53ad62232f35b6b503ae91acb06de71e365c781c8'],
  ['20260603052153_add_event_short_label.sql', 'ffeecd90dbe2ae2cbbc033902c7086f22db65db86d0dff26e8f001a03dde197d'],
  ['20260721043214_cms_foundation.sql', '0aa104af1c675a9e37a264bccefc3043b9fb1aaf61af0eb3cf73da9dedb60efa'],
  ['20260721051120_admin_command_api.sql', '3585e682670203f5f92660fce5c352ee8aa71ded3384ce0f1e9d2da2d82128c5'],
  ['20260721060345_media_command_api.sql', 'a500eba05f29c54ead406e47facf62d100254ac4b590bdd001b6acdef3e9edd4'],
  ['20260721060349_command_idempotency_outbox_worker.sql', '4adbd3ae1766f2d91109a280c98f4434a383079eb5f7367fa7182187a87ff53d'],
  ['20260721075225_legacy_import_and_compatibility_preview.sql', '723747d8ef61436e0f991b2ecc94e0e3f53243cf827b649116176c54da80c5c4'],
  ['20260721103104_admin_exhibition_reference_fields.sql', '2108dee6876384c75f6f4f3907467ddc01a7791f080c1c6085f6cded7d9d8131'],
  ['20260721105000_reader_keyset_indexes.sql', 'e005e0cc66d6e91dc63cdfafde758663ad58b90874bffe9378316c33824f1f34'],
  ['20260721105100_legacy_reader_ticket_url.sql', '00fc7169cd51a8b2e2ce0977e2028e03401fe21f0568ff0a345f347e10a031c8'],
  ['20260721105200_reader_integrity_contract.sql', 'caeb2a58a66a85682829eefc22e3d3d09c94c3936deed6034002ef5d464af47e'],
  ['20260721120000_public_exhibition_catalog_v2.sql', 'dbb3603d13cfab2479565675a931e619a22cd52a7f98bf1ee0575478919c35d5'],
  ['20260722090000_required_map_location_on_publish.sql', '66f899550e2f2622b832739c3677e9ec9f68fe330ecadc1ccaf6c1cb0c0776e4'],
  ['20260722102309_geocode_rate_limit.sql', '71926b24b58bd2a1447c012cf9443b59d714577db3e48d2f9bd662f7ffe46b7a'],
]);

const eventShortLabelMigration = '20260603052153_add_event_short_label.sql';
const firstCatalogMigration = '20260721043214_cms_foundation.sql';
const latestCanonicalVersion = BigInt('20260722102309');
const minimumNodeMajor = 18;

export class MigrationLineageError extends Error {}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function versionOf(fileName) {
  const match = /^([0-9]{3}|[0-9]{14})_[a-z0-9][a-z0-9_]*\.sql$/.exec(
    fileName,
  );
  if (!match) {
    throw new MigrationLineageError(
      `invalid migration filename: ${fileName}`,
    );
  }
  return match[1];
}

async function requireExactFile(directory, fileName, expectedHash) {
  const path = resolve(directory, fileName);
  let bytes;
  try {
    const metadata = await lstat(path);
    if (!metadata.isFile() || metadata.isSymbolicLink()) {
      throw new MigrationLineageError(
        `historical migration must be a regular file: ${fileName}`,
      );
    }
    bytes = await readFile(path);
  } catch (error) {
    if (error?.code === 'ENOENT') {
      throw new MigrationLineageError(
        `required migration is missing: ${fileName}`,
      );
    }
    throw error;
  }

  const actualHash = sha256(bytes);
  if (actualHash !== expectedHash) {
    throw new MigrationLineageError(
      `historical migration bytes changed: ${fileName}`,
    );
  }
}

async function requireRecoveredStatementFile(
  directory,
  fileName,
  expectedNormalizedHash,
) {
  const path = resolve(directory, fileName);
  let bytes;
  try {
    const metadata = await lstat(path);
    if (!metadata.isFile() || metadata.isSymbolicLink()) {
      throw new MigrationLineageError(
        `recovered migration must be a regular file: ${fileName}`,
      );
    }
    bytes = await readFile(path);
  } catch (error) {
    if (error?.code === 'ENOENT') {
      throw new MigrationLineageError(
        `recovered migration is missing: ${fileName}`,
      );
    }
    throw error;
  }

  if (bytes.length === 0 || bytes.at(-1) !== 0x0a) {
    throw new MigrationLineageError(
      `recovered migration must end with one LF: ${fileName}`,
    );
  }

  const normalizedHash = sha256(bytes.subarray(0, bytes.length - 1));
  if (normalizedHash !== expectedNormalizedHash) {
    throw new MigrationLineageError(
      `recovered migration does not match recorded Supabase statements: ${fileName}`,
    );
  }
}

export async function validateMigrationLineage(
  migrationsDirectory = defaultMigrationsDirectory,
) {
  const nodeMajor = Number.parseInt(process.versions.node.split('.')[0], 10);
  if (!Number.isInteger(nodeMajor) || nodeMajor < minimumNodeMajor) {
    throw new MigrationLineageError(
      `Node.js ${minimumNodeMajor} or newer is required`,
    );
  }

  const directory = resolve(migrationsDirectory);
  const entries = await readdir(directory, { withFileTypes: true });
  const sqlEntries = entries.filter((entry) => entry.name.endsWith('.sql'));
  for (const entry of sqlEntries) {
    if (!entry.isFile() || entry.isSymbolicLink()) {
      throw new MigrationLineageError(
        `migration SQL entry must be a regular file: ${entry.name}`,
      );
    }
  }
  const migrationFiles = sqlEntries.map((entry) => entry.name).sort();

  const filesByVersion = new Map();
  for (const fileName of migrationFiles) {
    const version = versionOf(fileName);
    if (filesByVersion.has(version)) {
      throw new MigrationLineageError(
        `duplicate migration version ${version}: ${filesByVersion.get(version)}, ${fileName}`,
      );
    }
    if (!canonicalMigrationHashes.has(fileName)) {
      if (version.length === 3 || BigInt(version) <= latestCanonicalVersion) {
        throw new MigrationLineageError(
          `unrecognized or backdated migration is forbidden: ${fileName}`,
        );
      }
    }
    filesByVersion.set(version, fileName);
  }

  for (const [fileName, expectedHash] of recoveredMigrationHashes) {
    await requireRecoveredStatementFile(directory, fileName, expectedHash);
  }

  for (const [fileName, expectedHash] of canonicalMigrationHashes) {
    await requireExactFile(directory, fileName, expectedHash);
  }

  const lastRecoveredVersion = BigInt('20260513112327');
  const eventVersion = BigInt(versionOf(eventShortLabelMigration));
  const firstCatalogVersion = BigInt(versionOf(firstCatalogMigration));
  if (!(lastRecoveredVersion < eventVersion && eventVersion < firstCatalogVersion)) {
    throw new MigrationLineageError(
      'event short-label migration must sort after recovered history and before the catalog stack',
    );
  }

  return {
    migrationCount: migrationFiles.length,
    canonicalMigrationCount: canonicalMigrationHashes.size,
    recoveredMigrationCount: recoveredMigrationHashes.size,
    migrationsDirectory: directory,
  };
}

async function main() {
  const args = process.argv.slice(2);
  let migrationsDirectory = defaultMigrationsDirectory;
  if (args.length === 2 && args[0] === '--migrations-dir') {
    migrationsDirectory = args[1];
  } else if (args.length !== 0) {
    throw new MigrationLineageError(
      'usage: validate-migration-lineage.mjs [--migrations-dir <absolute-or-relative-path>]',
    );
  }

  const result = await validateMigrationLineage(migrationsDirectory);
  process.stdout.write(
    `PASS: canonical migration lineage (${result.migrationCount} files, ${result.recoveredMigrationCount} recovered versions)\n`,
  );
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main().catch((error) => {
    process.stderr.write(`ERROR: ${error.message}\n`);
    process.exitCode = 1;
  });
}
