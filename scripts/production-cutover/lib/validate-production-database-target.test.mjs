#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const validator = fileURLToPath(
  new URL("./validate-production-database-target.mjs", import.meta.url)
);
const productionRef = "bbbbbbbbbbbbbbbbbbbb";
const tlsQuery =
  "sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem";

function validate(databaseUrl, projectRef = productionRef) {
  return spawnSync(process.execPath, [validator], {
    env: {
      PATH: process.env.PATH || "",
      GALLR_PRODUCTION_VALIDATION_PROJECT_REF: projectRef,
      GALLR_PRODUCTION_VALIDATION_DATABASE_URL: databaseUrl,
    },
    encoding: "utf8",
  });
}

for (const protocol of ["postgres", "postgresql"]) {
  const result = validate(
    `${protocol}://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}`
  );
  assert.equal(result.status, 0, `${protocol}/verify-full should be accepted`);
  assert.equal(result.stdout, "");
}

assert.equal(
  validate(
    `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem&sslmode=verify-full`
  ).status,
  0,
  "TLS query parameter order should not matter"
);

const rejectedUrls = [
  `postgresql://postgres:secret@db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres?${tlsQuery}`,
  `postgresql://postgres.${productionRef}:secret@aws-0-region.pooler.supabase.com:5432/postgres?${tlsQuery}`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:6543/postgres?${tlsQuery}`,
  `postgresql://postgres@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}`,
  `postgresql://other:secret@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/other?${tlsQuery}`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslmode=verify-full`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslmode=require&sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslmode=verify-ca&sslrootcert=%2Ftmp%2Fgallr-production-root-ca.pem`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslmode=disable`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}&host=evil.invalid`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}&sslmode=verify-full`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}&sslrootcert=%2Ftmp%2Fsecond.pem`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=relative%2Froot.pem`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Froot%0D.pem`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Froot%C2%85.pem`,
  `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}#ignored`,
  ` postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}`,
  `postgresql://postgres:${productionRef}@db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres?${tlsQuery}`,
];

for (const databaseUrl of rejectedUrls) {
  const result = validate(databaseUrl);
  assert.notEqual(result.status, 0, `should reject ${databaseUrl}`);
  assert.equal(result.stdout, "");
  assert.equal(result.stderr.includes(databaseUrl), false, "must not print URL");
}

assert.notEqual(
  validate(
    `postgresql://postgres:secret@db.${productionRef}.supabase.co:5432/postgres?${tlsQuery}`,
    "short-ref"
  ).status,
  0
);

console.log("PASS: direct production database URL validation is fail closed.");
