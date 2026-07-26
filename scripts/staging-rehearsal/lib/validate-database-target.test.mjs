import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const validatorPath = fileURLToPath(
  new URL("./validate-database-target.mjs", import.meta.url)
);
const certificateFixturePath = fileURLToPath(
  new URL("../tests/fixtures/test-root-ca.pem", import.meta.url)
);
const stagingRef = "aaaaaaaaaaaaaaaaaaaa";
const temporaryRoot = fs.mkdtempSync(
  path.join(fs.realpathSync.native(os.tmpdir()), "gallr-validator-test-")
);
fs.chmodSync(temporaryRoot, 0o700);
const certificatePath = path.join(temporaryRoot, "root.crt");
fs.copyFileSync(certificateFixturePath, certificatePath);
fs.chmodSync(certificatePath, 0o400);
const encodedCertificatePath = encodeURIComponent(certificatePath);
const tlsQuery =
  `sslmode=verify-full&sslrootcert=${encodedCertificatePath}`;

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

try {
  for (const databaseUrl of [
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
    `postgres://postgres:secret@db.${stagingRef}.supabase.co/postgres?sslrootcert=${encodedCertificatePath}&sslmode=verify-full`,
    `postgres://postgres.${stagingRef}:secret@aws-0-region.pooler.supabase.com:5432/postgres?${tlsQuery}`,
    `postgresql://postgres.${stagingRef}:secret@aws-0-region.pooler.supabase.com:6543/postgres?${tlsQuery}`,
    `postgresql://postgres:p%40ss%3Aword%2525%2B@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
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
    `postgresql://postgres:@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
    `postgresql://postgres:bad%ZZ@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
    `postgresql://postgres:bad%0Avalue@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslrootcert=${encodedCertificatePath}`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=require&sslrootcert=${encodedCertificatePath}`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}&sslmode=verify-full`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}&sslrootcert=${encodedCertificatePath}`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?%73slmode=verify-full&sslrootcert=${encodedCertificatePath}`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full&%73slrootcert=${encodedCertificatePath}`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=relative%2Froot.pem`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${encodedCertificatePath}+suffix`,
    `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=${encodedCertificatePath}&host=evil.invalid`,
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

  const approvedCertificateBytes = fs.readFileSync(certificatePath);
  assert.equal(approvedCertificateBytes.at(-1), 0x0a);
  fs.chmodSync(certificatePath, 0o600);
  fs.writeFileSync(
    certificatePath,
    approvedCertificateBytes.subarray(0, -1)
  );
  fs.chmodSync(certificatePath, 0o400);
  assert.notEqual(
    validate(
      `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`
    ).status,
    0,
    "a semantically valid but byte-unapproved CA unexpectedly passed"
  );

  fs.chmodSync(certificatePath, 0o600);
  fs.writeFileSync(certificatePath, approvedCertificateBytes);
  fs.chmodSync(certificatePath, 0o644);
  assert.notEqual(
    validate(
      `postgresql://postgres:secret@db.${stagingRef}.supabase.co:5432/postgres?${tlsQuery}`
    ).status,
    0
  );

  console.log("database target validator tests passed");
} finally {
  fs.rmSync(temporaryRoot, { recursive: true, force: true });
}
