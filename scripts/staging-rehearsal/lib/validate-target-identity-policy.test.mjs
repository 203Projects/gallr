#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const validatorPath = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "validate-target-identity-policy.mjs"
);
const stagingRef = "s".repeat(20);
const productionRef = "p".repeat(20);
const commit = "a".repeat(40);
const markerId = "123e4567-e89b-42d3-a456-426614174000";

function digest(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function utcAfter(milliseconds) {
  return new Date(Date.now() + milliseconds).toISOString().replace(/\.\d{3}Z$/, "Z");
}

function manifestText(overrides = {}) {
  const fields = {
    manifest_schema: "1",
    run_id: "identity-policy-test",
    generated_at_utc: utcAfter(-60_000),
    target: "staging",
    change_record: "CHG-IDENTITY-001",
    executor: "executor@example.test",
    reviewer: "reviewer@example.test",
    repository_commit: commit,
    staging_project_ref_sha256: digest(stagingRef),
    production_project_ref_sha256: digest(productionRef),
    ...overrides,
  };
  return `${Object.entries(fields).map(([key, value]) => `${key}=${value}`).join("\n")}\n\n[migration_sha256]\n${"b".repeat(64)}  supabase/migrations/example.sql\n`;
}

function policyText(manifestBytes, overrides = {}) {
  const fields = {
    policy_schema: "1",
    policy_kind: "gallr_disposable_clone_target",
    issued_at_utc: utcAfter(-60_000),
    valid_until_utc: utcAfter(60 * 60 * 1000),
    staging_project_ref_sha256: digest(stagingRef),
    production_project_ref_sha256: digest(productionRef),
    repository_commit: commit,
    operator_manifest_sha256: digest(manifestBytes),
    change_record: "CHG-IDENTITY-001",
    approver_one: "reviewer@example.test",
    approver_two: "identity@example.test",
    marker_id: markerId,
    ...overrides,
  };
  return `${Object.entries(fields).map(([key, value]) => `${key}=${value}`).join("\n")}\n`;
}

function runCase(name, options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gallr-identity-policy-"));
  const repositoryRoot = path.join(root, "repository");
  const secureRoot = path.join(root, "secure");
  const evidenceRoot = path.join(root, "evidence");
  fs.mkdirSync(repositoryRoot, { mode: 0o700 });
  fs.mkdirSync(secureRoot, { mode: options.parentMode ?? 0o700 });
  fs.mkdirSync(evidenceRoot, { mode: 0o700 });

  const manifest = manifestText(options.manifestOverrides);
  const manifestPath = path.join(evidenceRoot, "operator-manifest.txt");
  fs.writeFileSync(manifestPath, manifest, { mode: 0o444 });
  fs.chmodSync(manifestPath, options.manifestMode ?? 0o444);

  const policy = options.rawPolicy ?? policyText(Buffer.from(manifest), options.policyOverrides);
  const policyPath = options.policyInsideRepository
    ? path.join(repositoryRoot, "identity-policy.txt")
    : path.join(secureRoot, "identity-policy.txt");
  fs.writeFileSync(policyPath, policy, { mode: 0o400 });
  fs.chmodSync(policyPath, options.policyMode ?? 0o400);

  const result = spawnSync(process.execPath, [validatorPath], {
    encoding: "utf8",
    env: {
      ...process.env,
      GALLR_IDENTITY_POLICY_PATH: policyPath,
      GALLR_IDENTITY_REPO_ROOT: repositoryRoot,
      GALLR_IDENTITY_OPERATOR_MANIFEST_PATH: manifestPath,
      GALLR_IDENTITY_EXPECTED_STAGING_REF: options.stagingRef ?? stagingRef,
      GALLR_IDENTITY_PRODUCTION_REF: options.productionRef ?? productionRef,
      GALLR_IDENTITY_CURRENT_COMMIT: options.currentCommit ?? commit,
    },
  });

  try {
    if (options.shouldPass) {
      if (result.status !== 0) {
        throw new Error(`${name}: expected success, got ${result.stderr}`);
      }
      const fields = result.stdout.trimEnd().split("\t");
      if (fields.length !== 11 || fields[0] !== markerId) {
        throw new Error(`${name}: unexpected validator record`);
      }
    } else if (result.status === 0) {
      throw new Error(`${name}: expected fail-closed rejection`);
    }
    if (`${result.stdout}${result.stderr}`.includes(stagingRef) ||
        `${result.stdout}${result.stderr}`.includes(productionRef)) {
      throw new Error(`${name}: validator disclosed a raw project ref`);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

runCase("valid policy", { shouldPass: true });
runCase("swapped labels", { stagingRef: productionRef, productionRef: stagingRef });
runCase("writable policy", { policyMode: 0o600 });
runCase("insecure policy parent", { parentMode: 0o755 });
runCase("policy stored in repository", { policyInsideRepository: true });
runCase("operator manifest wrong mode", { manifestMode: 0o400 });
runCase("same approver twice", {
  policyOverrides: { approver_two: "REVIEWER@example.test" },
});
runCase("executor is an approver", {
  policyOverrides: { approver_two: "executor@example.test" },
});
runCase("manifest reviewer did not approve", {
  policyOverrides: {
    approver_one: "identity-one@example.test",
    approver_two: "identity-two@example.test",
  },
});
runCase("expired policy", {
  policyOverrides: {
    issued_at_utc: utcAfter(-2 * 60 * 60 * 1000),
    valid_until_utc: utcAfter(-60 * 60 * 1000),
  },
});
runCase("policy lifetime over seven days", {
  policyOverrides: {
    issued_at_utc: utcAfter(-60_000),
    valid_until_utc: utcAfter(8 * 24 * 60 * 60 * 1000),
  },
});
runCase("wrong reviewed commit", { currentCommit: "c".repeat(40) });
runCase("manifest changed after approval", {
  policyOverrides: { operator_manifest_sha256: "d".repeat(64) },
});
runCase("raw staging ref embedded", {
  policyOverrides: { change_record: `CHG-${stagingRef}` },
  manifestOverrides: { change_record: `CHG-${stagingRef}` },
});

const duplicateManifest = manifestText();
const duplicatePolicy = policyText(Buffer.from(duplicateManifest)).replace(
  "marker_id=123e4567-e89b-42d3-a456-426614174000",
  "approver_two=another@example.test"
);
runCase("duplicate policy key", { rawPolicy: duplicatePolicy });

const reorderedLines = policyText(Buffer.from(duplicateManifest)).trimEnd().split("\n");
[reorderedLines[0], reorderedLines[1]] = [reorderedLines[1], reorderedLines[0]];
runCase("reordered policy fields", { rawPolicy: `${reorderedLines.join("\n")}\n` });

console.log("target identity policy tests passed");
