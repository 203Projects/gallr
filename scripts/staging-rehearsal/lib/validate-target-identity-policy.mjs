#!/usr/bin/env node

// Validate the independently prepared staging identity policy without making
// network calls or printing raw Supabase project references. On success this
// emits one strict TSV record containing only non-secret marker inputs.

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const MULTI_PERSON_POLICY_KEYS = [
  "policy_schema",
  "policy_kind",
  "issued_at_utc",
  "valid_until_utc",
  "staging_project_ref_sha256",
  "production_project_ref_sha256",
  "repository_commit",
  "operator_manifest_sha256",
  "change_record",
  "approver_one",
  "approver_two",
  "marker_id",
];

const SOLO_OPERATOR_POLICY_KEYS = [
  "policy_schema",
  "policy_kind",
  "governance_mode",
  "issued_at_utc",
  "valid_until_utc",
  "minimum_cooldown_seconds",
  "destructive_actions",
  "staging_project_ref_sha256",
  "production_project_ref_sha256",
  "repository_commit",
  "operator_manifest_sha256",
  "change_record",
  "operator_identity",
  "first_confirmation_sha256",
  "marker_id",
];

const MULTI_PERSON_MANIFEST_KEYS = [
  "manifest_schema",
  "target",
  "change_record",
  "executor",
  "reviewer",
  "repository_commit",
  "staging_project_ref_sha256",
  "production_project_ref_sha256",
];

const SOLO_OPERATOR_MANIFEST_KEYS = [
  "manifest_schema",
  "generated_at_utc",
  "target",
  "change_record",
  "executor",
  "reviewer",
  "repository_commit",
  "staging_project_ref_sha256",
  "production_project_ref_sha256",
  "governance_mode",
  "human_reviewer_count",
  "automation_is_independent_human_review",
  "residual_risk_accepted",
  "minimum_cooldown_seconds",
  "destructive_actions",
  "first_confirmation_sha256",
];

const sha256Pattern = /^[0-9a-f]{64}$/;
const projectRefPattern = /^[a-z0-9]{20}$/;
const commitPattern = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/;
const markerIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const strictTextPattern = /^[A-Za-z0-9][A-Za-z0-9 .,:_@/+\-]{2,159}$/;
const utcPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const maxPolicyLifetimeMs = 7 * 24 * 60 * 60 * 1000;
const maxSoloPolicyLifetimeMs = 24 * 60 * 60 * 1000;
const maxClockSkewMs = 5 * 60 * 1000;
const soloCooldownSeconds = 15 * 60;
const soloCooldownMs = soloCooldownSeconds * 1000;

function fail(message) {
  console.error(`target identity policy rejected: ${message}`);
  process.exit(1);
}

function env(name) {
  const value = String(process.env[name] || "");
  if (value.length === 0) fail(`${name} is required`);
  if (value.includes("\n") || value.includes("\r") || value.includes("\t")) {
    fail(`${name} must be a single-line value without tabs`);
  }
  return value;
}

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function parseExactUtc(value, description) {
  if (!utcPattern.test(value)) {
    fail(`${description} must use exact UTC second precision`);
  }
  const epoch = Date.parse(value);
  if (!Number.isFinite(epoch) ||
      new Date(epoch).toISOString().replace(".000Z", "Z") !== value) {
    fail(`${description} is invalid`);
  }
  return epoch;
}

function parseExactKeyValueFile(contents, expectedKeys, description) {
  if (contents.includes("\r")) fail(`${description} must use LF line endings`);
  if (!contents.endsWith("\n")) fail(`${description} must end with one newline`);
  if (contents.endsWith("\n\n")) fail(`${description} must not contain blank lines`);

  const lines = contents.slice(0, -1).split("\n");
  if (lines.length !== expectedKeys.length) {
    fail(`${description} has an unexpected field count`);
  }

  const parsed = new Map();
  for (const [index, line] of lines.entries()) {
    const delimiter = line.indexOf("=");
    if (delimiter <= 0) fail(`${description} contains a malformed field`);
    const key = line.slice(0, delimiter);
    const value = line.slice(delimiter + 1);
    if (!expectedKeys.includes(key)) fail(`${description} contains an unknown field`);
    if (key !== expectedKeys[index]) fail(`${description} fields are out of order`);
    if (parsed.has(key)) fail(`${description} contains a duplicate field`);
    if (value.length === 0) fail(`${description} contains an empty field`);
    parsed.set(key, value);
  }

  for (const key of expectedKeys) {
    if (!parsed.has(key)) fail(`${description} is missing a required field`);
  }
  return parsed;
}

function parseOperatorManifest(contents) {
  if (contents.includes("\r")) fail("operator manifest must use LF line endings");
  const preamble = contents.split("\n[")[0];
  const parsed = new Map();
  for (const line of preamble.split("\n")) {
    if (line.length === 0) continue;
    const delimiter = line.indexOf("=");
    if (delimiter <= 0) fail("operator manifest contains a malformed preamble field");
    const key = line.slice(0, delimiter);
    const value = line.slice(delimiter + 1);
    if (parsed.has(key)) fail("operator manifest contains a duplicate preamble field");
    parsed.set(key, value);
  }
  return parsed;
}

function assertManifestFields(manifest, requiredKeys) {
  for (const key of requiredKeys) {
    if (!manifest.has(key) || manifest.get(key).length === 0) {
      fail("operator manifest is missing a required identity field");
    }
  }
}

const stableStatFields = [
  "dev",
  "ino",
  "mode",
  "nlink",
  "uid",
  "gid",
  "size",
  "mtimeMs",
  "ctimeMs",
  "birthtimeMs",
];

function assertOwnedRegularFileStat(stat, exactMode, description) {
  if (!stat.isFile()) {
    fail(`${description} must be a regular non-symbolic-link file`);
  }
  if (stat.nlink !== 1) fail(`${description} must not be hard-linked`);
  if (typeof process.getuid === "function" && stat.uid !== process.getuid()) {
    fail(`${description} must be owned by the current user`);
  }
  if ((stat.mode & 0o777) !== exactMode) {
    fail(`${description} must have mode 0${exactMode.toString(8)}`);
  }
}

function assertStableFileDescriptor(before, after, description) {
  for (const field of stableStatFields) {
    if (!Object.is(before[field], after[field])) {
      fail(`${description} identity or metadata changed while it was read`);
    }
  }
}

function readOwnedRegularFileSnapshot(filePath, exactMode, description) {
  if (typeof fs.constants.O_NOFOLLOW !== "number") {
    fail("this platform cannot safely open identity artifacts");
  }

  const openFlags =
    fs.constants.O_RDONLY |
    fs.constants.O_NOFOLLOW |
    (typeof fs.constants.O_CLOEXEC === "number" ? fs.constants.O_CLOEXEC : 0);
  let descriptor;
  try {
    descriptor = fs.openSync(filePath, openFlags);
  } catch (_error) {
    fail(`${description} is unavailable`);
  }

  let before;
  let bytes;
  try {
    before = fs.fstatSync(descriptor);
    assertOwnedRegularFileStat(before, exactMode, description);

    bytes = fs.readFileSync(descriptor);

    const after = fs.fstatSync(descriptor);
    assertOwnedRegularFileStat(after, exactMode, description);
    assertStableFileDescriptor(before, after, description);
    if (bytes.length !== after.size) {
      fail(`${description} size changed while it was read`);
    }

    // Keep the public pathname bound to the descriptor snapshot for callers
    // that consume the validated artifact after this process returns. This
    // catches a rename-swap during validation without using the pathname to
    // obtain either the accepted bytes or their cooldown metadata.
    let pathStat;
    try {
      pathStat = fs.lstatSync(filePath);
    } catch (_error) {
      fail(`${description} pathname changed while it was read`);
    }
    if (pathStat.isSymbolicLink() || !pathStat.isFile() ||
        pathStat.dev !== after.dev || pathStat.ino !== after.ino) {
      fail(`${description} pathname changed while it was read`);
    }
  } catch (_error) {
    fail(`${description} could not be read safely`);
  } finally {
    try {
      fs.closeSync(descriptor);
    } catch (_error) {
      // The process fails closed below if the descriptor could not be closed.
      descriptor = undefined;
    }
  }
  if (descriptor === undefined) {
    fail(`${description} could not be closed safely`);
  }
  return { bytes, stat: before };
}

function assertSecureExternalPolicyPath(policyPath, repositoryRoot) {
  if (!path.isAbsolute(policyPath)) fail("policy path must be absolute");

  let resolvedRepository;
  let resolvedPolicy;
  try {
    resolvedRepository = fs.realpathSync(repositoryRoot);
    resolvedPolicy = fs.realpathSync(policyPath);
  } catch (_error) {
    fail("could not resolve policy and repository paths");
  }
  const relative = path.relative(resolvedRepository, resolvedPolicy);
  if (relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative))) {
    fail("policy file must be outside the repository");
  }

  const parent = path.dirname(resolvedPolicy);
  let parentLstat;
  let parentStat;
  try {
    parentLstat = fs.lstatSync(parent);
    parentStat = fs.statSync(parent);
  } catch (_error) {
    fail("could not inspect the policy parent directory");
  }
  if (parentLstat.isSymbolicLink() || !parentStat.isDirectory()) {
    fail("policy parent must be a real directory");
  }
  if (typeof process.getuid === "function" && parentStat.uid !== process.getuid()) {
    fail("policy parent must be owned by the current user");
  }
  if ((parentStat.mode & 0o777) !== 0o700) {
    fail("policy parent must have mode 0700");
  }
}

const policyPath = env("GALLR_IDENTITY_POLICY_PATH");
const repositoryRoot = env("GALLR_IDENTITY_REPO_ROOT");
const operatorManifestPath = env("GALLR_IDENTITY_OPERATOR_MANIFEST_PATH");
const expectedStagingRef = env("GALLR_IDENTITY_EXPECTED_STAGING_REF");
const productionRef = env("GALLR_IDENTITY_PRODUCTION_REF");
const currentCommit = env("GALLR_IDENTITY_CURRENT_COMMIT");

if (!projectRefPattern.test(expectedStagingRef) || !projectRefPattern.test(productionRef)) {
  fail("project references have an invalid format");
}
if (expectedStagingRef === productionRef) fail("project references must differ");
if (!commitPattern.test(currentCommit)) fail("current repository commit is invalid");

assertSecureExternalPolicyPath(policyPath, repositoryRoot);
const policySnapshot = readOwnedRegularFileSnapshot(
  policyPath,
  0o400,
  "identity policy"
);
const manifestSnapshot = readOwnedRegularFileSnapshot(
  operatorManifestPath,
  0o444,
  "operator manifest"
);
const policyBytes = policySnapshot.bytes;
const policyStat = policySnapshot.stat;
const manifestBytes = manifestSnapshot.bytes;

const policyText = policyBytes.toString("utf8");
const manifestText = manifestBytes.toString("utf8");
if (Buffer.from(policyText, "utf8").compare(policyBytes) !== 0) {
  fail("identity policy must be valid UTF-8 text");
}
if (Buffer.from(manifestText, "utf8").compare(manifestBytes) !== 0) {
  fail("operator manifest must be valid UTF-8 text");
}

const firstPolicyLine = policyText.split("\n", 1)[0];
let governanceMode;
let policyKeys;
if (firstPolicyLine === "policy_schema=1") {
  governanceMode = "separated_humans";
  policyKeys = MULTI_PERSON_POLICY_KEYS;
} else if (firstPolicyLine === "policy_schema=2") {
  governanceMode = "solo_operator";
  policyKeys = SOLO_OPERATOR_POLICY_KEYS;
} else {
  fail("unsupported policy schema");
}

const policy = parseExactKeyValueFile(policyText, policyKeys, "identity policy");
const manifest = parseOperatorManifest(manifestText);

if (policy.get("policy_kind") !== "gallr_disposable_clone_target") {
  fail("unexpected policy kind");
}
if (governanceMode === "separated_humans") {
  assertManifestFields(manifest, MULTI_PERSON_MANIFEST_KEYS);
  if (manifest.get("manifest_schema") !== "1") {
    fail("operator manifest schema does not match the identity policy");
  }
} else {
  assertManifestFields(manifest, SOLO_OPERATOR_MANIFEST_KEYS);
  if (manifest.get("manifest_schema") !== "2") {
    fail("operator manifest schema does not match the identity policy");
  }
}
if (manifest.get("target") !== "staging") {
  fail("operator manifest is not a staging manifest");
}

for (const key of ["change_record", "executor", "reviewer"]) {
  if (!strictTextPattern.test(manifest.get(key))) {
    fail(`operator manifest has invalid ${key}`);
  }
}
if (!commitPattern.test(manifest.get("repository_commit")) ||
    !sha256Pattern.test(manifest.get("staging_project_ref_sha256")) ||
    !sha256Pattern.test(manifest.get("production_project_ref_sha256"))) {
  fail("operator manifest has invalid identity fingerprints");
}

for (const key of [
  "staging_project_ref_sha256",
  "production_project_ref_sha256",
  "operator_manifest_sha256",
]) {
  if (!sha256Pattern.test(policy.get(key))) fail(`identity policy has invalid ${key}`);
}
if (!commitPattern.test(policy.get("repository_commit"))) {
  fail("identity policy has an invalid repository commit");
}
if (!markerIdPattern.test(policy.get("marker_id"))) {
  fail("identity policy has an invalid marker ID");
}
const policyIdentityKeys = governanceMode === "separated_humans"
  ? ["change_record", "approver_one", "approver_two"]
  : ["change_record", "operator_identity"];
for (const key of policyIdentityKeys) {
  if (!strictTextPattern.test(policy.get(key))) {
    fail(`identity policy has invalid ${key}`);
  }
}

const issuedAtText = policy.get("issued_at_utc");
const validUntilText = policy.get("valid_until_utc");
const issuedAt = parseExactUtc(issuedAtText, "policy issue timestamp");
const validUntil = parseExactUtc(validUntilText, "policy expiry timestamp");
const now = Date.now();
if (issuedAt > now + maxClockSkewMs) fail("policy issue time is in the future");
if (validUntil <= now) fail("identity policy has expired");
const maximumLifetime = governanceMode === "solo_operator"
  ? maxSoloPolicyLifetimeMs
  : maxPolicyLifetimeMs;
if (validUntil <= issuedAt || validUntil - issuedAt > maximumLifetime) {
  const maximumDescription = governanceMode === "solo_operator" ? "one day" : "seven days";
  fail(`identity policy lifetime must be positive and no longer than ${maximumDescription}`);
}

const stagingFingerprint = sha256(expectedStagingRef);
const productionFingerprint = sha256(productionRef);
const manifestFingerprint = sha256(manifestBytes);
if (policy.get("staging_project_ref_sha256") !== stagingFingerprint) {
  fail("staging label does not match the independently approved fingerprint");
}
if (policy.get("production_project_ref_sha256") !== productionFingerprint) {
  fail("production label does not match the independently approved fingerprint");
}
if (stagingFingerprint === productionFingerprint) fail("target fingerprints must differ");
if (policy.get("repository_commit") !== currentCommit) {
  fail("policy repository commit does not match the current commit");
}
if (policy.get("operator_manifest_sha256") !== manifestFingerprint) {
  fail("policy does not bind the exact operator manifest bytes");
}

const exactManifestMatches = [
  ["repository_commit", currentCommit],
  ["staging_project_ref_sha256", stagingFingerprint],
  ["production_project_ref_sha256", productionFingerprint],
  ["change_record", policy.get("change_record")],
];
for (const [key, expected] of exactManifestMatches) {
  if (manifest.get(key) !== expected) fail(`operator manifest disagrees on ${key}`);
}

// A policy stores only fingerprints. Reject accidental inclusion of either raw
// ref even if it appears in an unrelated field.
if (policyText.includes(expectedStagingRef) || policyText.includes(productionRef)) {
  fail("identity policy must not contain raw project references");
}
if (manifestText.includes(expectedStagingRef) || manifestText.includes(productionRef)) {
  fail("operator manifest must not contain raw project references");
}

const canonicalIdentity = (value) => value.toLocaleLowerCase("en-US");

if (governanceMode === "separated_humans") {
  const approverOne = canonicalIdentity(policy.get("approver_one"));
  const approverTwo = canonicalIdentity(policy.get("approver_two"));
  const executor = canonicalIdentity(manifest.get("executor"));
  const reviewer = canonicalIdentity(manifest.get("reviewer"));
  if (approverOne === approverTwo) fail("the two approvers must be distinct");
  if (approverOne === executor || approverTwo === executor) {
    fail("the executor cannot be an identity-policy approver");
  }
  if (reviewer !== approverOne && reviewer !== approverTwo) {
    fail("the operator-manifest reviewer must be one of the two approvers");
  }

  const output = [
    policy.get("marker_id"),
    issuedAtText,
    validUntilText,
    stagingFingerprint,
    productionFingerprint,
    currentCommit,
    manifestFingerprint,
    policy.get("change_record"),
    policy.get("approver_one"),
    policy.get("approver_two"),
    sha256(policyBytes),
  ];
  process.stdout.write(`${output.join("\t")}\n`);
} else {
  const expectedManifestValues = new Map([
    ["governance_mode", "solo_operator"],
    ["human_reviewer_count", "0"],
    ["automation_is_independent_human_review", "false"],
    ["residual_risk_accepted", "true"],
    ["minimum_cooldown_seconds", String(soloCooldownSeconds)],
    ["destructive_actions", "forbidden"],
  ]);
  for (const [key, expected] of expectedManifestValues) {
    if (manifest.get(key) !== expected) {
      fail(`solo-operator manifest has invalid ${key}`);
    }
  }
  if (policy.get("governance_mode") !== "solo_operator") {
    fail("solo-operator policy has an invalid governance mode");
  }
  if (policy.get("minimum_cooldown_seconds") !== String(soloCooldownSeconds)) {
    fail("solo-operator policy cooldown must be exactly 900 seconds");
  }
  if (policy.get("destructive_actions") !== "forbidden") {
    fail("solo-operator policy cannot authorize destructive actions");
  }
  if (!sha256Pattern.test(policy.get("first_confirmation_sha256")) ||
      !sha256Pattern.test(manifest.get("first_confirmation_sha256"))) {
    fail("solo-operator first confirmation fingerprint is invalid");
  }
  if (manifest.get("executor") !== manifest.get("reviewer")) {
    fail("solo-operator executor and reviewer must use the same exact stable identity");
  }
  if (policy.get("operator_identity") !== manifest.get("executor")) {
    fail("solo-operator policy identity must exactly match the manifest identity");
  }

  const generatedAtText = manifest.get("generated_at_utc");
  const generatedAt = parseExactUtc(generatedAtText, "solo-operator manifest timestamp");
  if (generatedAt > issuedAt || generatedAt > now + maxClockSkewMs) {
    fail("solo-operator manifest must be generated no later than the policy issue time");
  }

  if (now - issuedAt < soloCooldownMs) {
    fail("solo-operator policy issue cooldown has not elapsed");
  }
  const policySealTimes = [
    ["modification", policyStat.mtimeMs],
    ["metadata-change", policyStat.ctimeMs],
  ];
  if (Number.isFinite(policyStat.birthtimeMs) && policyStat.birthtimeMs > 0) {
    policySealTimes.push(["creation", policyStat.birthtimeMs]);
  }
  for (const [description, timestamp] of policySealTimes) {
    if (!Number.isFinite(timestamp)) {
      fail(`solo-operator policy ${description} time is unavailable`);
    }
    if (timestamp > now + maxClockSkewMs) {
      fail(`solo-operator policy file ${description} time is in the future`);
    }
    if (now - timestamp < soloCooldownMs) {
      fail(`solo-operator policy-file ${description} cooldown has not elapsed`);
    }
  }

  const expectedFirstLiteral =
    `INTENT STAGING ${expectedStagingRef} NOT PRODUCTION ${productionRef} ${currentCommit} ` +
    "ACCEPT_NO_INDEPENDENT_REVIEW";
  const expectedFirstFingerprint = sha256(expectedFirstLiteral);
  if (policy.get("first_confirmation_sha256") !== expectedFirstFingerprint ||
      manifest.get("first_confirmation_sha256") !== expectedFirstFingerprint) {
    fail("solo-operator first confirmation does not bind the exact targets and commit");
  }
  const expectedExecuteLiteral =
    `EXECUTE STAGING ${expectedStagingRef} NOT PRODUCTION ${productionRef} ${currentCommit} ` +
    "ACCEPT_NO_INDEPENDENT_REVIEW";
  const effectiveFirstAttestation = new Date(
    Math.ceil(Math.max(
      issuedAt,
      ...policySealTimes.map(([, timestamp]) => timestamp)
    ) / 1000) * 1000
  ).toISOString().replace(".000Z", "Z");

  const output = [
    policy.get("marker_id"),
    issuedAtText,
    validUntilText,
    stagingFingerprint,
    productionFingerprint,
    currentCommit,
    manifestFingerprint,
    policy.get("change_record"),
    "solo_operator",
    policy.get("operator_identity"),
    expectedFirstFingerprint,
    sha256(expectedExecuteLiteral),
    effectiveFirstAttestation,
    String(soloCooldownSeconds),
    sha256(policyBytes),
  ];
  process.stdout.write(`${output.join("\t")}\n`);
}
