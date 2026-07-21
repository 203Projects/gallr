import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const validatorPath = fileURLToPath(
  new URL("./validate-database-target.mjs", import.meta.url)
);
const stagingRef = "aaaaaaaaaaaaaaaaaaaa";
const tlsQuery =
  "sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem";

function validate(databaseUrl, projectRef = stagingRef, requireDirect = "false") {
  return spawnSync(process.execPath, [validatorPath], {
    encoding: "utf8",
    env: {
      GALLR_VALIDATION_PROJECT_REF: projectRef,
      GALLR_VALIDATION_DATABASE_URL: databaseUrl,
      GALLR_VALIDATION_REQUIRE_DIRECT: requireDirect,
    },
  });
}

for (const databaseUrl of [
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
  `postgres://postgres:secret@db.${stagingRef}.supabase.co/postgres?sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem&sslmode=verify-full`,
  `postgres://postgres.${stagingRef}:secret@aws-0-region.pooler.supabase.com:5432/postgres?${tlsQuery}`,
  `postgresql://postgres.${stagingRef}:secret@aws-0-region.pooler.supabase.com:6543/postgres?${tlsQuery}`,
]) {
  const result = validate(databaseUrl);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, "");
  assert.equal(result.stderr, "");
}

for (const databaseUrl of [
  `postgresql://postgres:${stagingRef}@db.bbbbbbbbbbbbbbbbbbbb.supabase.co:5432/postgres?${tlsQuery}`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co.evil:5432/postgres?${tlsQuery}`,
  `postgresql://postgres.${stagingRef}:secret@evil-pooler.example.com:6543/postgres?${tlsQuery}`,
  `postgresql://postgres.bbbbbbbbbbbbbbbbbbbb:secret@aws-0-region.pooler.supabase.com:6543/postgres?${tlsQuery}`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/not-postgres?${tlsQuery}`,
  `https://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=require&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-ca&sslrootcert=%2Ftmp%2Fgallr-staging-root-ca.pem`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=disable`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}&sslmode=verify-full`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}&sslrootcert=%2Ftmp%2Fsecond.pem`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=relative%2Froot.pem`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Froot%0A.pem`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Froot%E2%80%A8.pem`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?host=db.bbbbbbbbbbbbbbbbbbbb.supabase.co`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?hostaddr=127.0.0.1`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?user=postgres.bbbbbbbbbbbbbbbbbbbb`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?dbname=other`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?port=6543`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?options=-csearch_path%3Dpublic`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}&host=evil.invalid`,
  ` postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
  `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}\n`,
]) {
  const result = validate(databaseUrl);
  assert.notEqual(result.status, 0, "unsafe URL unexpectedly passed");
  assert.equal(result.stdout, "");
  assert.ok(!result.stderr.includes(databaseUrl));
  assert.ok(!result.stderr.includes(stagingRef));
  assert.ok(!result.stderr.includes("secret"));
}

assert.notEqual(
  validate(
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
    "short-ref"
  ).status,
  0
);

assert.equal(
  validate(
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
    stagingRef,
    "true"
  ).status,
  0
);
assert.notEqual(
  validate(
    `postgresql://postgres.${stagingRef}:secret@aws-0-region.pooler.supabase.com:6543/postgres?${tlsQuery}`,
    stagingRef,
    "true"
  ).status,
  0
);

console.log("database target validator tests passed");
