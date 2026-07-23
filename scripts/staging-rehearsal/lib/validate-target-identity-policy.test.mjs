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
const utcPatternForTest = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;

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

function soloManifestText(overrides = {}) {
  const firstConfirmation =
    `INTENT STAGING ${stagingRef} NOT PRODUCTION ${productionRef} ${commit} ` +
    "ACCEPT_NO_INDEPENDENT_REVIEW";
  return manifestText({
    manifest_schema: "2",
    generated_at_utc: utcAfter(-30 * 60 * 1000),
    governance_mode: "solo_operator",
    human_reviewer_count: "0",
    automation_is_independent_human_review: "false",
    residual_risk_accepted: "true",
    minimum_cooldown_seconds: "900",
    destructive_actions: "forbidden",
    first_confirmation_sha256: digest(firstConfirmation),
    executor: "hanshin-lee",
    reviewer: "hanshin-lee",
    ...overrides,
  });
}

function soloPolicyText(manifestBytes, overrides = {}) {
  const firstConfirmation =
    `INTENT STAGING ${stagingRef} NOT PRODUCTION ${productionRef} ${commit} ` +
    "ACCEPT_NO_INDEPENDENT_REVIEW";
  const fields = {
    policy_schema: "2",
    policy_kind: "gallr_disposable_clone_target",
    governance_mode: "solo_operator",
    issued_at_utc: utcAfter(-20 * 60 * 1000),
    valid_until_utc: utcAfter(60 * 60 * 1000),
    minimum_cooldown_seconds: "900",
    destructive_actions: "forbidden",
    staging_project_ref_sha256: digest(stagingRef),
    production_project_ref_sha256: digest(productionRef),
    repository_commit: commit,
    operator_manifest_sha256: digest(manifestBytes),
    change_record: "CHG-IDENTITY-001",
    operator_identity: "hanshin-lee",
    first_confirmation_sha256: digest(firstConfirmation),
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

  const manifest = options.rawManifest ?? manifestText(options.manifestOverrides);
  const manifestPath = path.join(evidenceRoot, "operator-manifest.txt");
  fs.writeFileSync(manifestPath, manifest, { mode: 0o444 });
  fs.chmodSync(manifestPath, options.manifestMode ?? 0o444);

  const policy = options.rawPolicy ?? policyText(Buffer.from(manifest), options.policyOverrides);
  const policyPath = options.policyInsideRepository
    ? path.join(repositoryRoot, "identity-policy.txt")
    : path.join(secureRoot, "identity-policy.txt");
  fs.writeFileSync(policyPath, policy, { mode: 0o400 });
  fs.chmodSync(policyPath, options.policyMode ?? 0o400);
  if (options.policyMtimeMs !== undefined) {
    const policyMtime = new Date(options.policyMtimeMs);
    fs.utimesSync(policyPath, policyMtime, policyMtime);
  }

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
      const expectedFieldCount = options.expectedFieldCount ?? 11;
      if (fields.length !== expectedFieldCount || fields[0] !== markerId) {
        throw new Error(`${name}: unexpected validator record`);
      }
      if (options.validateOutput) options.validateOutput(fields);
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

function runSoloCase(name, options = {}) {
  const manifest = soloManifestText(options.manifestOverrides);
  runCase(name, {
    ...options,
    rawManifest: manifest,
    rawPolicy: options.rawPolicy ?? soloPolicyText(Buffer.from(manifest), options.policyOverrides),
    expectedFieldCount: 15,
    policyMtimeMs: options.policyMtimeMs ?? Date.now() - 20 * 60 * 1000,
  });
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
runCase("calendar-invalid policy timestamp", {
  policyOverrides: { issued_at_utc: "2026-02-31T00:00:00Z" },
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

runSoloCase("valid solo-operator policy", {
  shouldPass: true,
  validateOutput(fields) {
    const expectedFirst = digest(
      `INTENT STAGING ${stagingRef} NOT PRODUCTION ${productionRef} ${commit} ` +
      "ACCEPT_NO_INDEPENDENT_REVIEW"
    );
    const expectedSecond = digest(
      `EXECUTE STAGING ${stagingRef} NOT PRODUCTION ${productionRef} ${commit} ` +
      "ACCEPT_NO_INDEPENDENT_REVIEW"
    );
    if (fields[8] !== "solo_operator" || fields[9] !== "hanshin-lee" ||
        fields[10] !== expectedFirst || fields[11] !== expectedSecond ||
        !utcPatternForTest.test(fields[12]) || fields[13] !== "900" ||
        !/^[0-9a-f]{64}$/.test(fields[14])) {
      throw new Error("valid solo-operator policy: unexpected schema-2 output fields");
    }
  },
});
runSoloCase("solo issued-at cooldown not elapsed", {
  policyOverrides: { issued_at_utc: utcAfter(-5 * 60 * 1000) },
});
runSoloCase("solo policy-file cooldown not elapsed", {
  policyMtimeMs: Date.now() - 5 * 60 * 1000,
});
runSoloCase("solo manifest identities must match", {
  manifestOverrides: { reviewer: "different-reviewer@example.test" },
});
runSoloCase("solo manifest identities reject aliases that differ only by case", {
  manifestOverrides: { reviewer: "HANSHIN-LEE" },
});
runSoloCase("solo policy operator must match manifest", {
  policyOverrides: { operator_identity: "different-operator@example.test" },
});
runSoloCase("solo policy governance mode is exact", {
  policyOverrides: { governance_mode: "separated_humans" },
});
runSoloCase("solo policy lifetime over one day", {
  policyOverrides: { valid_until_utc: utcAfter(25 * 60 * 60 * 1000) },
});
runSoloCase("solo automation cannot claim human independence", {
  manifestOverrides: { automation_is_independent_human_review: "true" },
});
runSoloCase("solo residual risk must be accepted", {
  manifestOverrides: { residual_risk_accepted: "false" },
});
runSoloCase("solo manifest records zero human reviewers", {
  manifestOverrides: { human_reviewer_count: "1" },
});
runSoloCase("solo manifest cooldown is fixed by reviewed code", {
  manifestOverrides: { minimum_cooldown_seconds: "899" },
});
runSoloCase("solo manifest cannot broaden destructive scope", {
  manifestOverrides: { destructive_actions: "allowed" },
});
runSoloCase("solo manifest first confirmation is exact", {
  manifestOverrides: { first_confirmation_sha256: "e".repeat(64) },
});
runSoloCase("solo manifest must predate its policy", {
  manifestOverrides: { generated_at_utc: utcAfter(-10 * 60 * 1000) },
});
runSoloCase("solo manifest timestamp must be a real calendar instant", {
  manifestOverrides: { generated_at_utc: "2026-02-31T00:00:00Z" },
});
runSoloCase("solo first confirmation must bind the exact targets", {
  policyOverrides: { first_confirmation_sha256: "f".repeat(64) },
});
runSoloCase("solo destructive scope cannot be broadened", {
  policyOverrides: { destructive_actions: "allowed" },
});
runSoloCase("solo cooldown is fixed by reviewed code", {
  policyOverrides: { minimum_cooldown_seconds: "60" },
});
runSoloCase("solo policy modification time cannot be in the future", {
  policyMtimeMs: Date.now() + 10 * 60 * 1000,
});
runSoloCase("solo policy and manifest cannot contain a raw target ref", {
  manifestOverrides: { change_record: `CHG-${stagingRef}` },
  policyOverrides: { change_record: `CHG-${stagingRef}` },
});
runSoloCase("solo policy requires a schema-2 manifest", {
  manifestOverrides: { manifest_schema: "1" },
});

const soloManifestWithSchemaOnePolicy = soloManifestText();
runCase("schema-1 policy rejects a schema-2 manifest", {
  rawManifest: soloManifestWithSchemaOnePolicy,
  rawPolicy: policyText(Buffer.from(soloManifestWithSchemaOnePolicy)),
});

const soloManifestForReorderedPolicy = soloManifestText();
const reorderedSoloLines = soloPolicyText(Buffer.from(soloManifestForReorderedPolicy))
  .trimEnd()
  .split("\n");
[reorderedSoloLines[2], reorderedSoloLines[3]] = [reorderedSoloLines[3], reorderedSoloLines[2]];
runSoloCase("reordered solo policy fields", {
  rawPolicy: `${reorderedSoloLines.join("\n")}\n`,
});

console.log("target identity policy tests passed");
