import assert from 'node:assert/strict';
import {
  cp,
  mkdtemp,
  rename,
  rm,
  symlink,
  unlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  MigrationLineageError,
  validateMigrationLineage,
} from './validate-migration-lineage.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const sourceMigrations = resolve(scriptDirectory, '../../../supabase/migrations');

async function withMigrationCopy(callback) {
  const parent = await mkdtemp(resolve(tmpdir(), 'gallr-lineage-test-'));
  const migrations = resolve(parent, 'migrations');
  await cp(sourceMigrations, migrations, { recursive: true });
  try {
    await callback(migrations);
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
}

async function rejectsWith(migrations, pattern) {
  await assert.rejects(
    validateMigrationLineage(migrations),
    (error) => error instanceof MigrationLineageError && pattern.test(error.message),
  );
}

test('accepts the canonical production-derived lineage', async () => {
  const result = await validateMigrationLineage(sourceMigrations);
  assert.equal(result.recoveredMigrationCount, 7);
  assert.ok(result.migrationCount > result.recoveredMigrationCount);
});

test('rejects a legacy numeric bridge version', async () => {
  await withMigrationCopy(async (migrations) => {
    await writeFile(resolve(migrations, '015_legacy_bridge.sql'), 'select 1;\n');
    await rejectsWith(migrations, /unrecognized or backdated migration/);
  });
});

test('rejects deletion of a recorded numeric migration', async () => {
  await withMigrationCopy(async (migrations) => {
    await unlink(resolve(migrations, '001_create_exhibitions.sql'));
    await rejectsWith(migrations, /required migration is missing/);
  });
});

test('rejects mutation of a recorded numeric migration', async () => {
  await withMigrationCopy(async (migrations) => {
    await writeFile(
      resolve(migrations, '014_add_event_cover_image_url.sql'),
      'alter table events add column changed text;\n',
    );
    await rejectsWith(migrations, /historical migration bytes changed/);
  });
});

test('rejects an unrecorded numeric migration version', async () => {
  await withMigrationCopy(async (migrations) => {
    await writeFile(resolve(migrations, '019_unrecorded_bridge.sql'), 'select 1;\n');
    await rejectsWith(migrations, /unrecognized or backdated migration/);
  });
});

test('rejects a backdated timestamp migration', async () => {
  await withMigrationCopy(async (migrations) => {
    await writeFile(
      resolve(migrations, '20260722000000_backdated.sql'),
      'select 1;\n',
    );
    await rejectsWith(migrations, /unrecognized or backdated migration/);
  });
});

test('accepts a unique future timestamp migration', async () => {
  await withMigrationCopy(async (migrations) => {
    await writeFile(
      resolve(migrations, '20260723000000_future_change.sql'),
      'select 1;\n',
    );
    const result = await validateMigrationLineage(migrations);
    assert.equal(result.migrationCount, result.canonicalMigrationCount + 1);
  });
});

test('rejects duplicate migration versions', async () => {
  await withMigrationCopy(async (migrations) => {
    await writeFile(resolve(migrations, '001_duplicate.sql'), 'select 1;\n');
    await rejectsWith(migrations, /duplicate migration version 001/);
  });
});

test('rejects changed recovered migration statements', async () => {
  await withMigrationCopy(async (migrations) => {
    const path = resolve(
      migrations,
      '20260511101318_add_is_homepage_featured.sql',
    );
    await writeFile(path, '-- changed historical migration\n');
    await rejectsWith(migrations, /does not match recorded Supabase statements/);
  });
});

test('rejects a missing recovered migration', async () => {
  await withMigrationCopy(async (migrations) => {
    await unlink(
      resolve(migrations, '20260513112327_fix_compat_shim_null_handling.sql'),
    );
    await rejectsWith(migrations, /recovered migration is missing/);
  });
});

test('rejects a recovered migration symlink', async () => {
  await withMigrationCopy(async (migrations) => {
    const fileName = '20260507150817_exhibitions_drop_unused_featured_column.sql';
    const path = resolve(migrations, fileName);
    const target = resolve(migrations, 'recovered-target.txt');
    await rename(path, target);
    await symlink(target, path);
    await rejectsWith(migrations, /migration SQL entry must be a regular file/);
  });
});

test('rejects an otherwise future SQL symlink', async () => {
  await withMigrationCopy(async (migrations) => {
    const target = resolve(migrations, 'future-target.txt');
    await writeFile(target, 'select 1;\n');
    await symlink(
      target,
      resolve(migrations, '20260723000001_symlinked.sql'),
    );
    await rejectsWith(migrations, /migration SQL entry must be a regular file/);
  });
});

test('rejects changes to the composite version 005 replay', async () => {
  await withMigrationCopy(async (migrations) => {
    await writeFile(
      resolve(migrations, '005_add_opening_time.sql'),
      'alter table exhibitions add column opening_time text;\n',
    );
    await rejectsWith(migrations, /historical migration bytes changed/);
  });
});

test('rejects a missing event short-label migration', async () => {
  await withMigrationCopy(async (migrations) => {
    await unlink(
      resolve(migrations, '20260603052153_add_event_short_label.sql'),
    );
    await rejectsWith(migrations, /required migration is missing/);
  });
});
