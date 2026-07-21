#!/usr/bin/env node

// Validate the independently prepared staging identity policy without making
// network calls or printing raw Supabase project references. On success this
// emits one strict TSV record containing only non-secret marker inputs.

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const POLICY_KEYS = [
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

const MANIFEST_KEYS = [
  "manifest_schema",
  "target",
  "change_record",
  "executor",
  "reviewer",
  "repository_commit",
  "staging_project_ref_sha256",
  "production_project_ref_sha256",
];

const sha256Pattern = /^[0-9a-f]{64}$/;
const projectRefPattern = /^[a-z0-9]{20}$/;
const commitPattern = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/;
const markerIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const strictTextPattern = /^[A-Za-z0-9][A-Za-z0-9 .,:_@/+\-]{2,159}$/;
const utcPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const maxPolicyLifetimeMs = 7 * 24 * 60 * 60 * 1000;
const maxClockSkewMs = 5 * 60 * 1000;

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
  for (const key of MANIFEST_KEYS) {
    if (!parsed.has(key) || parsed.get(key).length === 0) {
      fail("operator manifest is missing a required identity field");
    }
  }
  return parsed;
}

function assertOwnedRegularFile(filePath, exactMode, description) {
  let stat;
  let lstat;
  try {
    lstat = fs.lstatSync(filePath);
    stat = fs.statSync(filePath);
  } catch (_error) {
    fail(`${description} is unavailable`);
  }
  if (lstat.isSymbolicLink() || !stat.isFile()) {
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
assertOwnedRegularFile(policyPath, 0o400, "identity policy");
assertOwnedRegularFile(operatorManifestPath, 0o444, "operator manifest");

let policyBytes;
let manifestBytes;
try {
  policyBytes = fs.readFileSync(policyPath);
  manifestBytes = fs.readFileSync(operatorManifestPath);
} catch (_error) {
  fail("could not read identity artifacts");
}

const policyText = policyBytes.toString("utf8");
const manifestText = manifestBytes.toString("utf8");
if (Buffer.from(policyText, "utf8").compare(policyBytes) !== 0) {
  fail("identity policy must be valid UTF-8 text");
}

const policy = parseExactKeyValueFile(policyText, POLICY_KEYS, "identity policy");
const manifest = parseOperatorManifest(manifestText);

if (policy.get("policy_schema") !== "1") fail("unsupported policy schema");
if (policy.get("policy_kind") !== "gallr_disposable_clone_target") {
  fail("unexpected policy kind");
}
if (manifest.get("manifest_schema") !== "1" || manifest.get("target") !== "staging") {
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
for (const key of ["change_record", "approver_one", "approver_two"]) {
  if (!strictTextPattern.test(policy.get(key))) {
    fail(`identity policy has invalid ${key}`);
  }
}

const issuedAtText = policy.get("issued_at_utc");
const validUntilText = policy.get("valid_until_utc");
if (!utcPattern.test(issuedAtText) || !utcPattern.test(validUntilText)) {
  fail("policy timestamps must use exact UTC second precision");
}
const issuedAt = Date.parse(issuedAtText);
const validUntil = Date.parse(validUntilText);
const now = Date.now();
if (!Number.isFinite(issuedAt) || !Number.isFinite(validUntil)) {
  fail("policy timestamps are invalid");
}
if (issuedAt > now + maxClockSkewMs) fail("policy issue time is in the future");
if (validUntil <= now) fail("identity policy has expired");
if (validUntil <= issuedAt || validUntil - issuedAt > maxPolicyLifetimeMs) {
  fail("identity policy lifetime must be positive and no longer than seven days");
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

const canonicalIdentity = (value) => value.toLocaleLowerCase("en-US");
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

// A policy stores only fingerprints. Reject accidental inclusion of either raw
// ref even if it appears in an unrelated field.
if (policyText.includes(expectedStagingRef) || policyText.includes(productionRef)) {
  fail("identity policy must not contain raw project references");
}
if (manifestText.includes(expectedStagingRef) || manifestText.includes(productionRef)) {
  fail("operator manifest must not contain raw project references");
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
