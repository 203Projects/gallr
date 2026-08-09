// Focused, network-free tests for the opt-in real PostgREST evidence harness.
// Run: node tests/fetch-exhibitions.integration-harness.test.js

const assert = require("assert").strict;
const childProcess = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const {
  checksumExhibitionContent,
  checksumExhibitionIds,
} = require("../scripts/fetch-exhibitions.js");
const {
  CANONICAL_V2_EXHIBITION_READER_SOURCE,
} = require("../scripts/lib/exhibition-reader-source.js");
const {
  MUTATION_ATTESTATION,
  assertIntegrationEvidence,
  execFileTracked,
  executeValidatedMutation,
  mutationPsqlArguments,
  readIntegrationConfig,
  runIntegration,
} = require("./fetch-exhibitions.integration.test.js");

function checksum(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

async function waitForFile(filePath, timeoutMs = 5_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fs.existsSync(filePath) && fs.statSync(filePath).size > 0) return;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  assert.fail(`timed out waiting for ${filePath}`);
}

async function waitForProcessGone(pid, timeoutMs = 5_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      process.kill(pid, 0);
    } catch (error) {
      if (error && error.code === "ESRCH") return;
      throw error;
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  assert.fail(`timed out waiting for process ${pid} to exit`);
}

function waitForChild(child) {
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("close", (code, signal) => resolve({ code, signal }));
  });
}

function response(
  body,
  { total = Array.isArray(body) ? body.length : 1, start = 0, url = "" } = {}
) {
  const snapshot = JSON.parse(JSON.stringify(body));
  const contentRange = Array.isArray(snapshot) && snapshot.length > 0
    ? `${start}-${start + snapshot.length - 1}/${total}`
    : `*/${total}`;
  return {
    ok: true,
    status: 200,
    url,
    headers: {
      get(name) {
        return name.toLowerCase() === "content-range" ? contentRange : null;
      },
    },
    json: async () => JSON.parse(JSON.stringify(snapshot)),
    clone() {
      return response(snapshot, { total, start, url });
    },
  };
}

function integrationEnv(overrides = {}) {
  const stagingRef = "aaaaaaaaaaaaaaaaaaaa";
  return {
    GALLR_POSTGREST_INTEGRATION: "1",
    GALLR_POSTGREST_TARGET: "staging",
    GALLR_EXHIBITION_SOURCE: "canonical-v2",
    SUPABASE_URL: `https://${stagingRef}.supabase.co`,
    SUPABASE_PUBLISHABLE_KEY: "publishable-test-key",
    GALLR_EXPECTED_EXHIBITION_COUNT: "0",
    GALLR_EXPECTED_MIN_EXHIBITIONS: "0",
    GALLR_EXPECTED_STAGING_PROJECT_REF: stagingRef,
    GALLR_PRODUCTION_PROJECT_REF: "bbbbbbbbbbbbbbbbbbbb",
    GALLR_STAGING_REHEARSAL_CONFIRM: stagingRef,
    ...overrides,
  };
}

const EVIDENCE_ROOT = "/tmp/gallr-fixture-evidence";
const MANIFEST_PATH =
  `${EVIDENCE_ROOT}/fixtures-catalog-test01/manifest.json`;
const OPERATOR_MANIFEST_PATH = `${EVIDENCE_ROOT}/operator-manifest.txt`;
const IDENTITY_POLICY_PATH = "/tmp/gallr-identity/policy.txt";
const REVIEWED_NODE_PATH = "/tmp/gallr-reviewed-tools/node";
const REVIEWED_PSQL_PATH = "/tmp/gallr-reviewed-tools/psql";
const REVIEWED_NODE_BYTES = Buffer.from("reviewed-node-test-bytes", "utf8");
const REVIEWED_PSQL_BYTES = Buffer.from("reviewed-psql-test-bytes", "utf8");
const REPOSITORY_COMMIT = "c".repeat(40);
const CHANGE_RECORD = "CHG-POSTGREST-RETRY-001";
const OPERATOR_IDENTITY = "solo.dev@example.test";
const FIRST_CONFIRMATION_SHA256 = checksum("first-confirmation");
const MARKER_ID = "123e4567-e89b-42d3-a456-426614174000";
const ALTERNATE_MARKER_ID = "123e4567-e89b-42d3-a456-426614174001";
const POLICY_ISSUED_AT_UTC = new Date(
  Math.floor((Date.now() - 20 * 60 * 1000) / 1000) * 1000
).toISOString().replace(".000Z", "Z");
const POLICY_VALID_UNTIL_UTC = new Date(
  Math.floor((Date.now() + 60 * 60 * 1000) / 1000) * 1000
).toISOString().replace(".000Z", "Z");
const FIXTURE_RUN_ID = "catalog-test01";
const FIXTURE_PREFIX = `gallr-rehearsal-${FIXTURE_RUN_ID}-`;
const FIXTURE_EVENT_ID = `${FIXTURE_PREFIX}event.catalog.v2,(load):한글`;
const FIXTURE_CURSOR_ID = `${FIXTURE_PREFIX}catalog-0500.cursor,(reserved):한글`;
const FIXTURE_MUTATION_ID =
  `${FIXTURE_PREFIX}catalog-0750.mutate,(same-id):한글`;
const FIXTURE_MUTATION_VERSION_ID = "00000000-0000-4000-8000-000000000750";
const TARGET_GUARD_PASS =
  "PASS: independent policy and disposable-clone marker identify staging";
const MUTATION_COMPLETE_TOKEN = "GALLR_POSTGREST_MUTATION_COMPLETE";

function fixtureExhibitionIds() {
  const ids = Array.from(
    { length: 1205 },
    (_unused, index) =>
      `${FIXTURE_PREFIX}catalog-${String(index + 1).padStart(4, "0")}`
  );
  ids[499] = FIXTURE_CURSOR_ID;
  ids[749] = FIXTURE_MUTATION_ID;
  return ids;
}

function fixtureVersionIds() {
  return Array.from(
    { length: 1205 },
    (_unused, index) =>
      `00000000-0000-4000-8000-${String(index + 1).padStart(12, "0")}`
  );
}

function fixtureManifest(overrides = {}) {
  return {
    schema_version: 1,
    state: "provisioned",
    run_id: FIXTURE_RUN_ID,
    fixture_prefix: FIXTURE_PREFIX,
    fixture_count: 1205,
    mutation_target_id: FIXTURE_MUTATION_ID,
    staging_ref_sha256: checksum("aaaaaaaaaaaaaaaaaaaa"),
    production_ref_sha256: checksum("bbbbbbbbbbbbbbbbbbbb"),
    database_evidence: {
      load_event_id: FIXTURE_EVENT_ID,
      boundary_cursor_id: FIXTURE_CURSOR_ID,
      mutation_target_id: FIXTURE_MUTATION_ID,
      fixture_exhibition_ids: fixtureExhibitionIds(),
      fixture_version_ids: fixtureVersionIds(),
    },
    ...overrides,
  };
}

function operatorManifest(overrides = {}) {
  const fields = {
    manifest_schema: "2",
    generated_at_utc: POLICY_ISSUED_AT_UTC,
    target: "staging",
    change_record: CHANGE_RECORD,
    executor: OPERATOR_IDENTITY,
    reviewer: OPERATOR_IDENTITY,
    repository_commit: REPOSITORY_COMMIT,
    staging_project_ref_sha256: checksum("aaaaaaaaaaaaaaaaaaaa"),
    production_project_ref_sha256: checksum("bbbbbbbbbbbbbbbbbbbb"),
    governance_mode: "solo_operator",
    human_reviewer_count: "0",
    automation_is_independent_human_review: "false",
    residual_risk_accepted: "true",
    minimum_cooldown_seconds: "900",
    destructive_actions: "forbidden",
    first_confirmation_sha256: FIRST_CONFIRMATION_SHA256,
    reviewed_node_path: REVIEWED_NODE_PATH,
    reviewed_node_sha256: checksum(REVIEWED_NODE_BYTES),
    reviewed_psql_path: REVIEWED_PSQL_PATH,
    reviewed_psql_sha256: checksum(REVIEWED_PSQL_BYTES),
    ...overrides,
  };
  return Buffer.from(
    `${Object.entries(fields).map(([key, value]) => `${key}=${value}`).join("\n")}\n`,
    "utf8"
  );
}

function identityPolicy(
  overrides = {},
  operatorManifestBytes = operatorManifest()
) {
  const fields = {
    policy_schema: "2",
    policy_kind: "gallr_disposable_clone_target",
    governance_mode: "solo_operator",
    issued_at_utc: POLICY_ISSUED_AT_UTC,
    valid_until_utc: POLICY_VALID_UNTIL_UTC,
    minimum_cooldown_seconds: "900",
    destructive_actions: "forbidden",
    staging_project_ref_sha256: checksum("aaaaaaaaaaaaaaaaaaaa"),
    production_project_ref_sha256: checksum("bbbbbbbbbbbbbbbbbbbb"),
    repository_commit: REPOSITORY_COMMIT,
    operator_manifest_sha256: checksum(operatorManifestBytes),
    change_record: CHANGE_RECORD,
    operator_identity: OPERATOR_IDENTITY,
    first_confirmation_sha256: FIRST_CONFIRMATION_SHA256,
    marker_id: MARKER_ID,
    ...overrides,
  };
  return Buffer.from(
    `${Object.entries(fields).map(([key, value]) => `${key}=${value}`).join("\n")}\n`,
    "utf8"
  );
}

function secureMutationFilesystem(options = {}) {
  const {
    manifest = fixtureManifest(),
    operatorManifestBytes = operatorManifest(),
    identityPolicyBytes = identityPolicy({}, operatorManifestBytes),
    manifestSymlink = false,
    manifestParentMode = 0o40700,
    finalPathInodeOverrides = {},
    fstatAfterOverrides = {},
    onOpen = () => {},
  } = options;
  const manifestBytes = Buffer.from(JSON.stringify(manifest), "utf8");
  const fileContents = new Map([
    [MANIFEST_PATH, manifestBytes],
    [OPERATOR_MANIFEST_PATH, operatorManifestBytes],
    [IDENTITY_POLICY_PATH, identityPolicyBytes],
  ]);
  const fileModes = new Map([
    [MANIFEST_PATH, 0o100400],
    [OPERATOR_MANIFEST_PATH, 0o100444],
    [IDENTITY_POLICY_PATH, 0o100400],
  ]);
  const fileInodes = new Map([
    [MANIFEST_PATH, 101],
    [OPERATOR_MANIFEST_PATH, 102],
    [IDENTITY_POLICY_PATH, 103],
  ]);
  const fileStat = (filePath, {
    inode = fileInodes.get(filePath),
    mode = fileModes.get(filePath),
    symbolicLink = filePath === MANIFEST_PATH && manifestSymlink,
  } = {}) => ({
    dev: 7,
    ino: inode,
    uid: 501,
    gid: 20,
    mode,
    nlink: 1,
    size: fileContents.get(filePath).length,
    mtimeMs: 1_000,
    ctimeMs: 1_000,
    birthtimeMs: 1_000,
    isDirectory: () => false,
    isFile: () => !symbolicLink,
    isSymbolicLink: () => symbolicLink,
  });
  const directoryStat = (mode) => ({
    dev: 7,
    ino: 200,
    uid: 501,
    gid: 20,
    mode,
    nlink: 1,
    size: 0,
    mtimeMs: 1_000,
    ctimeMs: 1_000,
    birthtimeMs: 1_000,
    isDirectory: () => true,
    isFile: () => false,
    isSymbolicLink: () => false,
  });
  let nextDescriptor = 40;
  const descriptorPaths = new Map();
  const fstatCalls = new Map();
  return {
    constants: fs.constants,
    getuid: () => 501,
    lstatSync: (filePath) => {
      if (fileContents.has(filePath)) {
        return fileStat(filePath, {
          inode: Object.prototype.hasOwnProperty.call(
            finalPathInodeOverrides,
            filePath
          )
            ? finalPathInodeOverrides[filePath]
            : fileInodes.get(filePath),
        });
      }
      if (filePath === EVIDENCE_ROOT) return directoryStat(0o40700);
      if (filePath === path.dirname(IDENTITY_POLICY_PATH)) {
        return directoryStat(0o40700);
      }
      throw new Error(`unexpected lstat path: ${filePath}`);
    },
    statSync: (filePath) => {
      if (filePath === path.dirname(MANIFEST_PATH)) {
        return directoryStat(manifestParentMode);
      }
      if (filePath === path.dirname(IDENTITY_POLICY_PATH)) {
        return directoryStat(0o40700);
      }
      throw new Error(`unexpected stat path: ${filePath}`);
    },
    realpathSync: (filePath) => filePath,
    openSync: (filePath, flags) => {
      onOpen(filePath, flags);
      assert.notEqual(
        flags & fs.constants.O_NOFOLLOW,
        0,
        "validation artifact opens must use O_NOFOLLOW"
      );
      if (!fileContents.has(filePath)) {
        throw new Error(`unexpected open path: ${filePath}`);
      }
      if (filePath === MANIFEST_PATH && manifestSymlink) {
        const error = new Error("symbolic link");
        error.code = "ELOOP";
        throw error;
      }
      const descriptor = nextDescriptor;
      nextDescriptor += 1;
      descriptorPaths.set(descriptor, filePath);
      return descriptor;
    },
    fstatSync: (descriptor) => {
      const filePath = descriptorPaths.get(descriptor);
      if (!filePath) throw new Error(`unexpected descriptor: ${descriptor}`);
      const callCount = (fstatCalls.get(descriptor) || 0) + 1;
      fstatCalls.set(descriptor, callCount);
      const overrides = callCount > 1 && fstatAfterOverrides[filePath]
        ? fstatAfterOverrides[filePath]
        : {};
      return fileStat(filePath, overrides);
    },
    readFileSync: (fileOrDescriptor) => {
      if (Number.isInteger(fileOrDescriptor)) {
        const filePath = descriptorPaths.get(fileOrDescriptor);
        if (!filePath) {
          throw new Error(`unexpected descriptor read: ${fileOrDescriptor}`);
        }
        return fileContents.get(filePath);
      }
      const filePath = fileOrDescriptor;
      if (filePath === MANIFEST_PATH) return manifestBytes;
      if (filePath === OPERATOR_MANIFEST_PATH) return operatorManifestBytes;
      if (filePath === IDENTITY_POLICY_PATH) return identityPolicyBytes;
      if (filePath === REVIEWED_NODE_PATH) return REVIEWED_NODE_BYTES;
      if (filePath === REVIEWED_PSQL_PATH) return REVIEWED_PSQL_BYTES;
      throw new Error(`unexpected read path: ${filePath}`);
    },
    closeSync: (descriptor) => {
      if (!descriptorPaths.delete(descriptor)) {
        throw new Error(`unexpected descriptor close: ${descriptor}`);
      }
    },
  };
}

function mutationIntegrationEnv(overrides = {}) {
  return integrationEnv({
    GALLR_EXPECTED_EXHIBITION_COUNT: "1205",
    GALLR_EXPECTED_MIN_EXHIBITIONS: "1001",
    GALLR_EXPECTED_MIN_PAGE_REQUESTS: "4",
    GALLR_TEST_EVENT_ID: FIXTURE_EVENT_ID,
    GALLR_EXPECTED_CURSOR: FIXTURE_CURSOR_ID,
    GALLR_POSTGREST_FIXTURE_MANIFEST: MANIFEST_PATH,
    GALLR_STAGING_DATABASE_URL:
      "postgresql://postgres:test@db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres" +
      "?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-test-supabase-ca.crt",
    GALLR_STAGING_EVIDENCE_DIR: EVIDENCE_ROOT,
    GALLR_STAGING_IDENTITY_POLICY_PATH: IDENTITY_POLICY_PATH,
    GALLR_POSTGREST_MUTATION_ATTESTATION: MUTATION_ATTESTATION,
    GALLR_POSTGREST_MUTATION_TARGET_ID: FIXTURE_MUTATION_ID,
    GALLR_EXPECTED_FETCH_ATTEMPTS: "2",
    GALLR_EXPECTED_INTEGRITY_CALLS: "2",
    ...overrides,
  });
}

function readMutationConfig(
  envOverrides = {},
  filesystemOptions = {}
) {
  return readIntegrationConfig(
    mutationIntegrationEnv(envOverrides),
    secureMutationFilesystem(filesystemOptions)
  );
}

function executionReadFile(mutation, overrides = {}) {
  return (filePath) => {
    if (Object.prototype.hasOwnProperty.call(overrides, filePath)) {
      return overrides[filePath];
    }
    if (filePath === OPERATOR_MANIFEST_PATH) return operatorManifest();
    if (filePath === IDENTITY_POLICY_PATH) return identityPolicy();
    if (filePath === REVIEWED_NODE_PATH) return REVIEWED_NODE_BYTES;
    if (filePath === REVIEWED_PSQL_PATH) return REVIEWED_PSQL_BYTES;
    if (
      filePath === mutation.targetGuardPath ||
      filePath === mutation.psqlRunnerPath ||
      filePath === mutation.mutationSqlPath
    ) {
      return fs.readFileSync(filePath);
    }
    throw new Error(`unexpected execution read: ${filePath}`);
  };
}

(async () => {
  // Mutation coordination is inert unless the integration test itself is enabled.
  {
    let filesystemChecks = 0;
    const config = readIntegrationConfig({
      GALLR_POSTGREST_MUTATION_HOOK: "/should/not-run",
      GALLR_POSTGREST_MUTATION_ATTESTATION: MUTATION_ATTESTATION,
    }, {
      lstatSync: () => { filesystemChecks += 1; },
      statSync: () => { filesystemChecks += 1; },
    });
    assert.deepEqual(config, { enabled: false });
    assert.equal(filesystemChecks, 0);
  }

  // A stable run retains one-attempt defaults and has no mutation transport.
  {
    const config = readIntegrationConfig(integrationEnv());
    assert.equal(config.expectedAttempts, 1);
    assert.equal(config.expectedIntegrityCalls, 1);
    assert.equal(config.mutation, null);
  }

  // Every request rejects redirects and the observer rejects a changed final
  // origin even if a custom fetch implementation ignores that policy.
  {
    const config = readIntegrationConfig(integrationEnv());
    let observedOptions;
    await assert.rejects(
      runIntegration(config, {
        fetchImpl: async (requestedUrl, options) => {
          observedOptions = options;
          const redirected = new URL(requestedUrl);
          redirected.hostname = "bbbbbbbbbbbbbbbbbbbb.supabase.co";
          return response([], { total: 0, url: redirected.href });
        },
      }),
      /changed origin/
    );
    assert.equal(observedOptions.redirect, "error");
    assert.equal(observedOptions.headers.apikey, "publishable-test-key");
  }

  // Local mode remains available for stable, network-isolated proofs but can
  // never enable the mutation transaction.
  {
    const localConfig = readIntegrationConfig(integrationEnv({
      GALLR_POSTGREST_TARGET: "local",
      SUPABASE_URL: "http://127.0.0.1:55321",
      GALLR_EXPECTED_STAGING_PROJECT_REF: "",
      GALLR_PRODUCTION_PROJECT_REF: "",
      GALLR_STAGING_REHEARSAL_CONFIRM: "",
    }));
    assert.equal(localConfig.targetEnvironment, "local");
    assert.throws(
      () => readIntegrationConfig(integrationEnv({
        GALLR_POSTGREST_TARGET: "local",
        SUPABASE_URL: "https://example.com",
      })),
      /loopback/
    );
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv({
        GALLR_POSTGREST_TARGET: "local",
        SUPABASE_URL: "http://127.0.0.1:55321",
        GALLR_EXPECTED_STAGING_PROJECT_REF: "",
        GALLR_PRODUCTION_PROJECT_REF: "",
        GALLR_STAGING_REHEARSAL_CONFIRM: "",
      }), secureMutationFilesystem()),
      /forbidden against the local target mode/
    );
  }

  // No operator-provided executable or guard path survives in the contract.
  {
    for (const [name, value] of [
      ["GALLR_POSTGREST_MUTATION_HOOK", "/tmp/operator-hook"],
      ["GALLR_POSTGREST_MUTATION_HOOK_SHA256", "0".repeat(64)],
      ["GALLR_POSTGREST_MUTATION_HOOK_TIMEOUT_MS", "1000"],
      ["GALLR_POSTGREST_TARGET_GUARD", "/tmp/operator-guard"],
      ["GALLR_POSTGREST_TARGET_GUARD_SHA256", "0".repeat(64)],
    ]) {
      assert.throws(
        () => readIntegrationConfig(integrationEnv({ [name]: value })),
        /operator-provided mutation hook and target-guard paths are forbidden/
      );
    }
    const config = readMutationConfig();
    assert.equal(Object.hasOwn(config.mutation, "hookPath"), false);
    assert.equal(
      config.mutation.targetGuardPath,
      path.resolve(
        __dirname,
        "../../scripts/staging-rehearsal/assert-disposable-clone-target.sh"
      )
    );
    assert.equal(
      config.mutation.publishedVersionId,
      FIXTURE_MUTATION_VERSION_ID
    );
    assert.equal(config.mutation.fixtureExhibitionIds[499], FIXTURE_CURSOR_ID);
    assert.equal(config.mutation.fixtureExhibitionIds[749], FIXTURE_MUTATION_ID);
    assert.equal(
      config.mutation.fixtureExhibitionIdChecksum,
      checksumExhibitionIds(fixtureExhibitionIds())
    );
  }

  // Accepted artifact bytes come from O_NOFOLLOW descriptors whose identity
  // is stable across both fstat calls and still names the final pathname.
  {
    const openedPaths = [];
    const { mutation } = readMutationConfig({}, {
      onOpen: (filePath) => openedPaths.push(filePath),
    });
    assert.deepEqual(openedPaths, [
      MANIFEST_PATH,
      OPERATOR_MANIFEST_PATH,
      IDENTITY_POLICY_PATH,
    ]);
    assert.equal(mutation.markerId, MARKER_ID);
    assert.equal(mutation.identityPolicySha256, checksum(identityPolicy()));

    assert.throws(
      () => readMutationConfig({}, {
        fstatAfterOverrides: {
          [OPERATOR_MANIFEST_PATH]: { inode: 999 },
        },
      }),
      /operator manifest identity or metadata changed while it was read/
    );
    assert.throws(
      () => readMutationConfig({}, {
        finalPathInodeOverrides: {
          [IDENTITY_POLICY_PATH]: 999,
        },
      }),
      /identity policy pathname inode changed/
    );
  }

  // The checked-in SQL keeps the exact marker and published version locked
  // through commit, binds the target-row checksum, changes only description_en
  // in its sole durable DML statement, and emits one post-commit token.
  {
    const { mutation } = readMutationConfig();
    const sql = fs.readFileSync(mutation.mutationSqlPath, "utf8");
    assert.match(sql, /^\\set ON_ERROR_STOP on$/m);
    assert.match(sql, /^set local search_path = pg_catalog;$/m);
    assert.match(
      sql,
      /from gallr_rehearsal_private\.disposable_clone_marker[\s\S]*for update;/
    );
    assert.equal(
      (
        sql.match(
          /marker\.operator_manifest_sha256 = config\.operator_manifest_sha256/g
        ) || []
      ).length,
      2,
      "marker bindings must be checked both after lock and before commit"
    );
    for (const [column, configField] of [
      ["marker_id", "marker_id"],
      ["governance_mode", "governance_mode"],
      ["policy_issued_at", "policy_issued_at"],
      ["valid_until", "valid_until"],
      ["policy_sha256", "policy_sha256"],
    ]) {
      assert.equal(
        (
          sql.match(
            new RegExp(
              `marker\\.${column} = config\\.${configField}`,
              "g"
            )
          ) || []
        ).length,
        2,
        `exact ${column} binding must reject a different marker at both checks`
      );
    }
    assert.equal(
      (
        sql.match(
          /marker\.valid_until > clock_timestamp\(\) \+ interval '31 seconds'/g
        ) || []
      ).length,
      2,
      "marker must outlive the transaction idle timeout at both checks"
    );
    assert.match(
      sql,
      /from content\.exhibition_versions as version[\s\S]*for update;/
    );
    const projectorLockOrder = [
      "pg_advisory_xact_lock_shared(73241, 1)",
      "from public.exhibitions as legacy",
      "pg_advisory_xact_lock(\n    73242",
      "from public.exhibition_catalog_v2 as catalog",
    ].map((needle) => sql.indexOf(needle));
    assert.ok(
      projectorLockOrder.every((offset) => offset >= 0) &&
        projectorLockOrder.every(
          (offset, index) =>
            index === 0 || offset > projectorLockOrder[index - 1]
        ),
      "mutation SQL must follow the projector lock order"
    );
    assert.match(
      sql,
      /checksum_before is distinct from config\.content_checksum_sha256/
    );
    const dollarQuotedBodies = [...sql.matchAll(/do \$[a-z_]+\$([\s\S]*?)\$[a-z_]+\$;/g)]
      .map((match) => match[1]);
    assert.ok(dollarQuotedBodies.length >= 2);
    assert.equal(
      dollarQuotedBodies.some((body) => body.includes(":'expected_")),
      false,
      "psql variables must be materialized before dollar-quoted PL/pgSQL"
    );
    const businessUpdate = sql.match(
      /update content\.exhibition_versions as version[\s\S]*?get diagnostics updated_rows/
    );
    assert.ok(businessUpdate, "fixed business-field UPDATE is missing");
    assert.match(businessUpdate[0], /set description_en =/);
    assert.equal(/\bset\s+(?!description_en\b)/i.test(businessUpdate[0]), false);
    const dmlTargets = [
      ...sql.matchAll(
        /^\s*(insert\s+into|update|delete\s+from|merge\s+into|truncate(?:\s+table)?)\s+([a-z_][a-z0-9_.]*)/gim
      ),
    ].map((match) => [
      match[1].replace(/\s+/g, " ").toLowerCase(),
      match[2].toLowerCase(),
    ]);
    assert.deepEqual(
      dmlTargets,
      [
        ["insert into", "pg_temp.mutation_config"],
        ["update", "content.exhibition_versions"],
      ],
      "fixed SQL must contain one temp-config INSERT and exactly one durable business UPDATE"
    );
    assert.match(
      sql,
      /commit;\s+select 'GALLR_POSTGREST_MUTATION_COMPLETE';\s*$/i
    );
  }

  // Partial, unattested, wrong-target, and mismatched manifest bindings fail
  // before any PostgREST request can leave the process.
  {
    assert.throws(
      () => readIntegrationConfig(integrationEnv({
        GALLR_POSTGREST_MUTATION_TARGET_ID: FIXTURE_MUTATION_ID,
      })),
      /attestation/
    );
    assert.throws(
      () => readMutationConfig({
        GALLR_POSTGREST_MUTATION_TARGET_ID: `${FIXTURE_PREFIX}different`,
      }),
      /deterministic fixture mutation ID/
    );
    assert.throws(
      () => readMutationConfig(
        {},
        {
          manifest: fixtureManifest({
            staging_ref_sha256: "0".repeat(64),
          }),
        }
      ),
      /staging fingerprint does not match/
    );
    assert.throws(
      () => readMutationConfig(
        {},
        {
          operatorManifestBytes: operatorManifest({
            staging_project_ref_sha256: "0".repeat(64),
          }),
        }
      ),
      /operator manifest staging fingerprint does not match/
    );
    assert.throws(
      () => readMutationConfig(
        {},
        {
          identityPolicyBytes: identityPolicy({
            staging_project_ref_sha256: "0".repeat(64),
          }),
        }
      ),
      /identity policy staging fingerprint does not match/
    );
    assert.throws(
      () => readMutationConfig(
        {},
        {
          identityPolicyBytes: identityPolicy({
            operator_manifest_sha256: "0".repeat(64),
          }),
        }
      ),
      /identity policy does not bind the exact operator manifest bytes/
    );
    assert.throws(
      () => readMutationConfig({
        GALLR_EXPECTED_FETCH_ATTEMPTS: "",
        GALLR_EXPECTED_INTEGRITY_CALLS: "",
      }),
      /explicit expected fetch-attempt and integrity-call counts/
    );

    const baselineMutation = readMutationConfig().mutation;
    const alternateMarkerMutation = readMutationConfig({}, {
      identityPolicyBytes: identityPolicy({
        marker_id: ALTERNATE_MARKER_ID,
      }),
    }).mutation;
    for (const field of [
      "stagingRefSha256",
      "productionRefSha256",
      "repositoryCommit",
      "operatorManifestSha256",
      "governanceMode",
      "policyIssuedAtUtc",
      "validUntilUtc",
    ]) {
      assert.equal(
        alternateMarkerMutation[field],
        baselineMutation[field],
        `alternate-marker fixture must retain common ${field}`
      );
    }
    assert.notEqual(
      alternateMarkerMutation.markerId,
      baselineMutation.markerId
    );
    const alternateMarkerArguments = mutationPsqlArguments(
      alternateMarkerMutation,
      checksum("target-row-before")
    );
    assert.ok(
      alternateMarkerArguments.includes(
        `--set=expected_marker_id=${ALTERNATE_MARKER_ID}`
      )
    );
    assert.ok(
      alternateMarkerArguments.includes(
        `--set=expected_policy_sha256=${checksum(identityPolicy({
          marker_id: ALTERNATE_MARKER_ID,
        }))}`
      )
    );
  }

  // The coordinator runs the fixed target guard first, then invokes the fixed
  // launcher under the exact reviewed Node path with exact argv and clean env.
  {
    const { mutation } = readMutationConfig();
    const calls = [];
    const expectedChecksum = checksum("target-row-before");
    await executeValidatedMutation(mutation, {
      attempt: 1,
      pageRequest: 2,
      readerSource: "canonical-v2",
      baseUrl: "https://aaaaaaaaaaaaaaaaaaaa.supabase.co",
      expectedContentChecksum: expectedChecksum,
    }, {
      readFileImpl: executionReadFile(mutation),
      execGuardImpl: async (file, args, options) => {
        calls.push({ kind: "guard", file, args, options });
        return { stdout: `${TARGET_GUARD_PASS}\n`, stderr: "" };
      },
      execFileImpl: async (file, args, options) => {
        calls.push({ kind: "mutation", file, args, options });
        return {
          stdout: `${MUTATION_COMPLETE_TOKEN}\n`,
          stderr: "",
        };
      },
    });

    assert.deepEqual(calls.map((call) => call.kind), ["guard", "mutation"]);
    const [guardCall, mutationCall] = calls;
    assert.equal(guardCall.file, mutation.targetGuardPath);
    assert.deepEqual(guardCall.args, []);
    assert.equal(guardCall.options.cwd, mutation.repositoryRoot);
    assert.equal(guardCall.options.shell, false);
    assert.deepEqual(guardCall.options.env, {
      HOME: "/nonexistent",
      LANG: "C",
      LC_ALL: "C",
      PATH: "/usr/bin:/bin:/usr/sbin:/sbin",
      TMPDIR: "/tmp",
      NODE_OPTIONS: "",
      NODE_PATH: "",
      ...mutation.targetGuardEnvironment,
      BASH_ENV: "/dev/null",
      ENV: "/dev/null",
      GIT_CONFIG_GLOBAL: "/dev/null",
      GIT_CONFIG_NOSYSTEM: "1",
      GIT_OPTIONAL_LOCKS: "0",
    });

    assert.equal(mutationCall.file, REVIEWED_NODE_PATH);
    assert.deepEqual(mutationCall.args, [
      mutation.psqlRunnerPath,
      "--",
      "-Atq",
      "--set=ON_ERROR_STOP=1",
      `--set=expected_staging_ref_sha256=${checksum("aaaaaaaaaaaaaaaaaaaa")}`,
      `--set=expected_production_ref_sha256=${checksum("bbbbbbbbbbbbbbbbbbbb")}`,
      `--set=expected_repository_commit=${REPOSITORY_COMMIT}`,
      `--set=expected_operator_manifest_sha256=${checksum(operatorManifest())}`,
      `--set=expected_marker_id=${MARKER_ID}`,
      "--set=expected_governance_mode=solo_operator",
      `--set=expected_policy_issued_at_utc=${POLICY_ISSUED_AT_UTC}`,
      `--set=expected_valid_until_utc=${POLICY_VALID_UNTIL_UTC}`,
      `--set=expected_policy_sha256=${checksum(identityPolicy())}`,
      `--set=expected_fixture_prefix=${FIXTURE_PREFIX}`,
      `--set=expected_event_id=${FIXTURE_EVENT_ID}`,
      `--set=expected_target_id=${FIXTURE_MUTATION_ID}`,
      `--set=expected_published_version_id=${FIXTURE_MUTATION_VERSION_ID}`,
      `--set=expected_content_checksum_sha256=${expectedChecksum}`,
      `--file=${mutation.mutationSqlPath}`,
    ]);
    assert.deepEqual(mutationCall.options.env, {
      HOME: "/nonexistent",
      LANG: "C",
      LC_ALL: "C",
      PATH: "/usr/bin:/bin:/usr/sbin:/sbin",
      TMPDIR: "/tmp",
      NODE_OPTIONS: "",
      NODE_PATH: "",
      GALLR_VALIDATION_PROJECT_REF: "aaaaaaaaaaaaaaaaaaaa",
      GALLR_VALIDATION_DATABASE_URL:
        mutation.targetGuardEnvironment.GALLR_STAGING_DATABASE_URL,
      GALLR_VALIDATION_REQUIRE_DIRECT: "true",
      GALLR_PSQL_APPNAME: "gallr-postgrest-mutation-evidence",
      GALLR_PSQL_CONNECT_TIMEOUT: "15",
      GALLR_PSQL_OPTIONS:
        "-c statement_timeout=30s -c lock_timeout=5s",
      GALLR_VALIDATED_PSQL_PATH: REVIEWED_PSQL_PATH,
      GALLR_VALIDATED_PSQL_SHA256: checksum(REVIEWED_PSQL_BYTES),
    });
    assert.equal(mutationCall.options.timeout, 45_000);
    assert.equal(mutationCall.options.shell, false);
    for (const secretName of [
      "SUPABASE_PUBLISHABLE_KEY",
      "SUPABASE_ANON_KEY",
      "SUPABASE_SERVICE_ROLE_KEY",
      "SUPABASE_SECRET_KEY",
      "DATABASE_URL",
    ]) {
      assert.equal(mutationCall.options.env[secretName], undefined);
    }
  }

  // A malformed target-row checksum, changed reviewed bytes, guard output
  // drift, any mutation stdout/stderr drift, and a non-zero launcher exit all
  // fail closed.
  {
    const { mutation } = readMutationConfig();
    const context = {
      attempt: 1,
      pageRequest: 2,
      readerSource: "canonical-v2",
      baseUrl: "https://aaaaaaaaaaaaaaaaaaaa.supabase.co",
      expectedContentChecksum: checksum("before"),
    };
    const exactGuard = async () => ({
      stdout: `${TARGET_GUARD_PASS}\n`,
      stderr: "",
    });

    let guardCalled = false;
    await assert.rejects(
      executeValidatedMutation(mutation, {
        ...context,
        expectedContentChecksum: "wrong-checksum",
      }, {
        readFileImpl: executionReadFile(mutation),
        execGuardImpl: async () => {
          guardCalled = true;
          return { stdout: `${TARGET_GUARD_PASS}\n`, stderr: "" };
        },
      }),
      /validated exhibition mutation failed/
    );
    assert.equal(guardCalled, false);

    await assert.rejects(
      executeValidatedMutation(mutation, {
        ...context,
        pageRequest: 3,
      }, {
        readFileImpl: executionReadFile(mutation),
        execGuardImpl: async () => {
          guardCalled = true;
          return { stdout: `${TARGET_GUARD_PASS}\n`, stderr: "" };
        },
      }),
      /validated exhibition mutation failed/
    );
    assert.equal(guardCalled, false);

    await assert.rejects(
      executeValidatedMutation(mutation, context, {
        readFileImpl: executionReadFile(mutation, {
          [REVIEWED_NODE_PATH]: Buffer.from("changed", "utf8"),
        }),
        execGuardImpl: exactGuard,
      }),
      /validated exhibition mutation failed/
    );
    await assert.rejects(
      executeValidatedMutation(mutation, context, {
        readFileImpl: executionReadFile(mutation, {
          [IDENTITY_POLICY_PATH]: Buffer.from("changed-policy\n", "utf8"),
        }),
        execGuardImpl: exactGuard,
      }),
      /validated exhibition mutation failed/
    );

    let mutationCalled = false;
    await assert.rejects(
      executeValidatedMutation(mutation, context, {
        readFileImpl: executionReadFile(mutation),
        execGuardImpl: async () => ({
          stdout: `${TARGET_GUARD_PASS}\nextra\n`,
          stderr: "",
        }),
        execFileImpl: async () => {
          mutationCalled = true;
          return { stdout: `${MUTATION_COMPLETE_TOKEN}\n`, stderr: "" };
        },
      }),
      /validated exhibition mutation failed/
    );
    assert.equal(mutationCalled, false);

    for (const result of [
      { stdout: MUTATION_COMPLETE_TOKEN, stderr: "" },
      { stdout: `${MUTATION_COMPLETE_TOKEN}\nextra\n`, stderr: "" },
      { stdout: `${MUTATION_COMPLETE_TOKEN}\n`, stderr: "unexpected\n" },
    ]) {
      await assert.rejects(
        executeValidatedMutation(mutation, context, {
          readFileImpl: executionReadFile(mutation),
          execGuardImpl: exactGuard,
          execFileImpl: async () => result,
        }),
        /validated exhibition mutation failed/
      );
    }

    await assert.rejects(
      executeValidatedMutation(mutation, context, {
        readFileImpl: executionReadFile(mutation),
        execGuardImpl: exactGuard,
        execFileImpl: async () => {
          const error = new Error("psql rejected wrong target or checksum");
          error.code = 3;
          error.stderr = "sensitive database detail";
          throw error;
        },
      }),
      (error) =>
        error.message === "validated exhibition mutation failed with code 3" &&
        !error.message.includes("sensitive")
    );
  }

  // A same-ID content mutation receives the checksum from the first-attempt
  // page that contained the target, invalidates attempt one, and is observed
  // exactly once on the verified retry.
  {
    const targetId = "id-0750";
    const originalRows = Array.from({ length: 1205 }, (_unused, index) => {
      const id = `id-${String(index + 1).padStart(4, "0")}`;
      return {
        id,
        content_checksum_sha256: checksum(`before:${id}`),
      };
    });
    assert.equal(originalRows[749].id, targetId);
    let databaseRows = originalRows.map((row) => ({ ...row }));
    let mutationCalls = 0;
    const fetchImpl = async (url) => {
      const parsed = new URL(url);
      if (parsed.pathname.endsWith("/rpc/exhibition_catalog_v2_integrity")) {
        return response([{
          row_count: databaseRows.length,
          id_checksum_sha256: checksumExhibitionIds(databaseRows),
          catalog_checksum_sha256: checksumExhibitionContent(databaseRows),
        }], { url });
      }
      assert.ok(parsed.pathname.endsWith("/exhibition_catalog_v2"));
      const cursor = parsed.searchParams.get("id");
      const afterId = cursor === null ? null : cursor.slice(3);
      const start = afterId === null
        ? 0
        : databaseRows.findIndex((row) => row.id > afterId);
      const safeStart = start === -1 ? databaseRows.length : start;
      const page = databaseRows.slice(safeStart, safeStart + 500);
      return response(page, { total: databaseRows.length, start: safeStart, url });
    };
    const config = {
      enabled: true,
      baseUrl: "http://127.0.0.1:55321",
      key: "publishable-test-key",
      exact: 1205,
      minimum: 1205,
      minimumPageRequests: 4,
      expectedAttempts: 2,
      expectedIntegrityCalls: 2,
      eventId: "",
      expectedCursor: "",
      featuredOnly: false,
      readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
      targetEnvironment: "staging",
      filters: [],
      mutation: {
        fixtureExhibitionIds: originalRows.map((row) => row.id),
        fixtureExhibitionIdChecksum: checksumExhibitionIds(originalRows),
        fixtureManifestSha256: checksum("fixture-manifest"),
        fixturePrefix: "fixture-prefix-",
        mutationSqlSha256: checksum("fixed-sql"),
        operatorManifestSha256: checksum("operator-manifest"),
        publishedVersionId: FIXTURE_MUTATION_VERSION_ID,
        targetId,
      },
    };
    const { evidence, evidenceSummary, rows } = await runIntegration(config, {
      fetchImpl,
      executeMutation: async (_mutation, context) => {
        mutationCalls += 1;
        assert.equal(context.attempt, 1);
        assert.equal(context.pageRequest, 2);
        assert.equal(
          context.expectedContentChecksum,
          checksum(`before:${targetId}`)
        );
        databaseRows = databaseRows.map((row) => row.id === targetId
          ? { ...row, content_checksum_sha256: checksum(`after:${row.id}`) }
          : row);
      },
    });

    assert.equal(mutationCalls, 1);
    assert.equal(evidence.attempts.length, 2);
    assert.equal(evidence.integrityCalls.length, 2);
    assert.equal(evidence.mismatches.length, 1);
    assert.deepEqual(
      evidence.attempts.map((attempt) => attempt.pageLengths),
      [[500, 500, 205, 0], [500, 500, 205, 0]]
    );
    assert.equal(
      rows.find((row) => row.id === targetId).content_checksum_sha256,
      checksum(`after:${targetId}`)
    );
    assert.deepEqual(evidenceSummary.final_snapshot, {
      row_count: 1205,
      id_checksum_sha256: checksumExhibitionIds(rows),
      catalog_checksum_sha256: checksumExhibitionContent(rows),
    });
    assert.equal(evidenceSummary.integrity_calls.length, 2);
    assert.equal(
      evidenceSummary.discarded_attempts[0].field,
      "catalog_checksum_sha256"
    );
    assert.deepEqual(
      evidenceSummary.page_requests.map((request) => request.rowCount),
      [500, 500, 205, 0, 500, 500, 205, 0]
    );
    assert.equal(evidence.mutationInvocations[0].attempt, 1);
    assert.equal(evidence.mutationInvocations[0].pageRequest, 2);
    assert.equal(
      evidenceSummary.mutation.checksum_before,
      checksum(`before:${targetId}`)
    );
    assert.equal(evidenceSummary.mutation.target_id, targetId);
    assert.equal(
      evidenceSummary.mutation.published_version_id,
      FIXTURE_MUTATION_VERSION_ID
    );
    assert.notEqual(
      evidenceSummary.mutation.checksum_before,
      evidenceSummary.mutation.checksum_after
    );
    assert.throws(
      () => assertIntegrationEvidence(
        config,
        [
          { ...rows[0], id: "same-count-unsealed-id" },
          ...rows.slice(1),
        ],
        evidence
      ),
      /final fetched IDs must exactly match the sealed/
    );
  }

  // A maxBuffer failure kills the complete detached group, including a
  // long-lived descendant that inherited no output descriptors.
  {
    const overflowRoot = fs.mkdtempSync(
      path.join(os.tmpdir(), "gallr-postgrest-overflow-test-")
    );
    fs.chmodSync(overflowRoot, 0o700);
    const overflowProgram = path.join(overflowRoot, "overflow-program.js");
    const grandchildPidPath = path.join(overflowRoot, "grandchild.pid");
    fs.writeFileSync(
      overflowProgram,
      [
        '"use strict";',
        'const childProcess = require("child_process");',
        'const fs = require("fs");',
        'const child = childProcess.spawn("/bin/sleep", ["30"], { stdio: "ignore" });',
        'fs.writeFileSync(process.env.GALLR_GRANDCHILD_PID_PATH, `${child.pid}\\n`);',
        'process.stdout.write("x".repeat(128 * 1024));',
        'setInterval(() => {}, 1000);',
        "",
      ].join("\n"),
      { mode: 0o600 }
    );
    try {
      await assert.rejects(
        execFileTracked(process.execPath, [overflowProgram], {
          env: {
            HOME: "/nonexistent",
            LANG: "C",
            LC_ALL: "C",
            PATH: "/usr/bin:/bin",
            GALLR_GRANDCHILD_PID_PATH: grandchildPidPath,
          },
          timeout: 30_000,
          maxBuffer: 1024,
          shell: false,
          windowsHide: true,
        }),
        (error) => error && error.code === "ERR_CHILD_PROCESS_STDIO_MAXBUFFER"
      );
      const grandchildPid = Number(
        fs.readFileSync(grandchildPidPath, "utf8").trim()
      );
      assert.throws(
        () => process.kill(grandchildPid, 0),
        (error) => error && error.code === "ESRCH"
      );
    } finally {
      fs.rmSync(overflowRoot, { recursive: true, force: true });
    }
  }

  // A descendant that explicitly ignores TERM is escalated to KILL, and the
  // final process-group drain remains bounded.
  {
    const resistantRoot = fs.mkdtempSync(
      path.join(os.tmpdir(), "gallr-postgrest-term-resistant-test-")
    );
    fs.chmodSync(resistantRoot, 0o700);
    const resistantProgram = path.join(resistantRoot, "program.js");
    const resistantPidPath = path.join(resistantRoot, "descendant.pid");
    fs.writeFileSync(
      resistantProgram,
      [
        '"use strict";',
        'const childProcess = require("child_process");',
        'const fs = require("fs");',
        'const child = childProcess.spawn("/bin/bash", ["-c",',
        "  \"trap '' TERM; while :; do /bin/sleep 1; done\"], {",
        '  env: { HOME: "/nonexistent", LANG: "C", LC_ALL: "C", PATH: "/usr/bin:/bin" },',
        '  stdio: "ignore",',
        "});",
        'fs.writeFileSync(process.env.GALLR_RESISTANT_PID_PATH, `${child.pid}\\n`);',
        'process.on("SIGTERM", () => process.exit(0));',
        "setInterval(() => {}, 1000);",
        "",
      ].join("\n"),
      { mode: 0o600 }
    );
    let resistantPid = null;
    const startedAt = Date.now();
    try {
      await assert.rejects(
        execFileTracked(process.execPath, [resistantProgram], {
          env: {
            HOME: "/nonexistent",
            LANG: "C",
            LC_ALL: "C",
            PATH: "/usr/bin:/bin",
            GALLR_RESISTANT_PID_PATH: resistantPidPath,
          },
          timeout: 500,
          maxBuffer: 1024,
          shell: false,
          windowsHide: true,
        }),
        (error) => error && error.killed === true
      );
      assert.ok(
        Date.now() - startedAt < 10_000,
        "TERM-resistant process-group drain exceeded its bounded deadline"
      );
      resistantPid = Number(
        fs.readFileSync(resistantPidPath, "utf8").trim()
      );
      assert.ok(Number.isInteger(resistantPid) && resistantPid > 1);
      await waitForProcessGone(resistantPid);
    } finally {
      if (Number.isInteger(resistantPid)) {
        try {
          process.kill(resistantPid, "SIGKILL");
        } catch (_error) {
          // Already reaped.
        }
      }
      fs.rmSync(resistantRoot, { recursive: true, force: true });
    }
  }

  // The real mutation topology has two process groups: execFileTracked owns the
  // validated launcher, while that launcher owns a separately detached psql
  // group and the private pgpass directory. The outer supervisor must leave
  // enough TERM grace for the inner owner to escalate, reap, and remove secrets
  // before the original signal is re-raised.
  {
    const nestedRoot = fs.mkdtempSync(
      path.join(os.tmpdir(), "gallr-postgrest-nested-cancel-test-")
    );
    fs.chmodSync(nestedRoot, 0o700);
    const launcherPath = path.join(nestedRoot, "validated-launcher.js");
    const probePath = path.join(nestedRoot, "outer-probe.js");
    const readyPath = path.join(nestedRoot, "launcher.ready");
    const descendantPidPath = path.join(nestedRoot, "psql.pid");
    const cleanupMarkerPath = path.join(nestedRoot, "cleanup.complete");
    const transportDirectory = path.join(nestedRoot, "private-transport");
    const harnessPath = path.resolve(
      __dirname,
      "fetch-exhibitions.integration.test.js"
    );

    fs.writeFileSync(
      launcherPath,
      [
        '"use strict";',
        'const childProcess = require("child_process");',
        'const fs = require("fs");',
        'const path = require("path");',
        "const readyPath = process.argv[2];",
        "const descendantPidPath = process.argv[3];",
        "const transportDirectory = process.argv[4];",
        "const cleanupMarkerPath = process.argv[5];",
        "fs.mkdirSync(transportDirectory, { mode: 0o700 });",
        "fs.writeFileSync(",
        '  path.join(transportDirectory, "pgpass"),',
        '  "db.example.invalid:5432:postgres:postgres:not-a-real-password\\n",',
        "  { mode: 0o600 }",
        ");",
        "const psql = childProcess.spawn(",
        '  "/bin/bash",',
        `  ["-c", "trap '' TERM; while :; do /bin/sleep 1; done"],`,
        "  {",
        "    detached: true,",
        '    env: { HOME: "/nonexistent", LANG: "C", LC_ALL: "C", PATH: "/usr/bin:/bin" },',
        '    stdio: "ignore",',
        "  }",
        ");",
        "let childClosed = false;",
        'psql.once("close", () => { childClosed = true; });',
        "function signalGroup(signal) {",
        "  try { process.kill(-psql.pid, signal); } catch (error) {",
        '    if (!error || error.code !== "ESRCH") throw error;',
        "  }",
        "}",
        "function groupAlive() {",
        '  try { process.kill(-psql.pid, 0); return true; } catch (error) {',
        '    return !error || error.code !== "ESRCH";',
        "  }",
        "}",
        "let shutdownStarted = false;",
        "let killSent = false;",
        "let termDeadline = null;",
        "let killDeadline = null;",
        "function finishCleanup(exitCode) {",
        "  fs.rmSync(transportDirectory, { recursive: true, force: true });",
        '  fs.writeFileSync(cleanupMarkerPath, "cleanup complete\\n", { mode: 0o600 });',
        "  process.exit(exitCode);",
        "}",
        "function pollShutdown() {",
        "  if (childClosed && !groupAlive()) { finishCleanup(143); return; }",
        "  const now = Date.now();",
        "  if (!killSent && now >= termDeadline) {",
        '    signalGroup("SIGKILL");',
        "    killSent = true;",
        "    killDeadline = now + 2000;",
        "  } else if (killSent && now >= killDeadline) {",
        '    signalGroup("SIGKILL");',
        "    finishCleanup(2);",
        "    return;",
        "  }",
        "  setTimeout(pollShutdown, 20);",
        "}",
        'process.on("SIGTERM", () => {',
        "  if (shutdownStarted) return;",
        "  shutdownStarted = true;",
        '  signalGroup("SIGTERM");',
        "  termDeadline = Date.now() + 2000;",
        "  setTimeout(pollShutdown, 20);",
        "});",
        'fs.writeFileSync(descendantPidPath, `${psql.pid}\\n`, { mode: 0o600 });',
        'fs.writeFileSync(readyPath, "ready\\n", { mode: 0o600 });',
        "setInterval(() => {}, 1000);",
        "",
      ].join("\n"),
      { mode: 0o600 }
    );
    fs.writeFileSync(
      probePath,
      [
        '"use strict";',
        'const { execFileTracked } = require(process.argv[2]);',
        "execFileTracked(process.execPath, process.argv.slice(3), {",
        "  env: { HOME: '/nonexistent', LANG: 'C', LC_ALL: 'C', PATH: '/usr/bin:/bin' },",
        "  timeout: 30000, maxBuffer: 65536, shell: false, windowsHide: true,",
        "}).then(() => { process.exitCode = 97; }).catch(() => { process.exitCode = 98; });",
        "",
      ].join("\n"),
      { mode: 0o600 }
    );

    let descendantPid = null;
    const probe = childProcess.spawn(
      process.execPath,
      [
        probePath,
        harnessPath,
        launcherPath,
        readyPath,
        descendantPidPath,
        transportDirectory,
        cleanupMarkerPath,
      ],
      { stdio: ["ignore", "pipe", "pipe"] }
    );
    try {
      await waitForFile(readyPath);
      descendantPid = Number(
        fs.readFileSync(descendantPidPath, "utf8").trim()
      );
      assert.ok(Number.isInteger(descendantPid) && descendantPid > 1);
      assert.ok(fs.existsSync(path.join(transportDirectory, "pgpass")));

      const probeResult = waitForChild(probe);
      probe.kill("SIGTERM");
      assert.deepEqual(
        await probeResult,
        { code: null, signal: "SIGTERM" }
      );
      assert.equal(
        fs.readFileSync(cleanupMarkerPath, "utf8"),
        "cleanup complete\n"
      );
      assert.equal(
        fs.existsSync(transportDirectory),
        false,
        "private pgpass directory survived outer cancellation"
      );
      assert.throws(
        () => process.kill(-descendantPid, 0),
        (error) => error && error.code === "ESRCH"
      );
    } finally {
      if (Number.isInteger(descendantPid)) {
        try {
          process.kill(-descendantPid, "SIGKILL");
        } catch (_error) {
          // The nested launcher already reaped its complete process group.
        }
      }
      if (probe.exitCode === null && probe.signalCode === null) {
        probe.kill("SIGKILL");
      }
      fs.rmSync(nestedRoot, { recursive: true, force: true });
    }
  }

  // TERM delivered only to the harness PID terminates the active external
  // command's complete process group and is re-raised after the child is gone.
  {
    const signalRoot = fs.mkdtempSync(
      path.join(os.tmpdir(), "gallr-postgrest-signal-test-")
    );
    fs.chmodSync(signalRoot, 0o700);
    const programPath = path.join(signalRoot, "program.sh");
    const programPidPath = path.join(signalRoot, "program.pid");
    const probePath = path.join(signalRoot, "probe.js");
    const idleProbePath = path.join(signalRoot, "idle-probe.js");
    const idleReadyPath = path.join(signalRoot, "idle.ready");
    const harnessPath = path.resolve(
      __dirname,
      "fetch-exhibitions.integration.test.js"
    );
    fs.writeFileSync(
      programPath,
      "#!/bin/bash\nset -euo pipefail\nprintf '%s\\n' \"$$\" >\"$GALLR_SIGNAL_PID_PATH\"\ntrap 'exit 0' TERM\nwhile :; do /bin/sleep 1; done\n",
      { mode: 0o700 }
    );
    fs.writeFileSync(
      probePath,
      [
        '"use strict";',
        'const { execFileTracked, trackedExternalSignal } = require(process.argv[2]);',
        "execFileTracked(process.argv[3], [], {",
        "  env: { HOME: '/nonexistent', LANG: 'C', LC_ALL: 'C', PATH: '/usr/bin:/bin',",
        "    GALLR_SIGNAL_PID_PATH: process.argv[4] },",
        "  timeout: 30000, maxBuffer: 65536, shell: false, windowsHide: true,",
        "}).then(() => { process.exitCode = 99; }).catch(() => {",
        "  process.exitCode = trackedExternalSignal() === 'SIGTERM' ? 143 : 1;",
        "});",
        "",
      ].join("\n"),
      { mode: 0o600 }
    );
    fs.writeFileSync(
      idleProbePath,
      [
        '"use strict";',
        'const fs = require("fs");',
        'const { execFileTracked } = require(process.argv[2]);',
        'execFileTracked("/usr/bin/true", [], {',
        "  env: { HOME: '/nonexistent', LANG: 'C', LC_ALL: 'C', PATH: '/usr/bin:/bin' },",
        "  timeout: 5000, maxBuffer: 1024, shell: false, windowsHide: true,",
        "}).then(() => {",
        '  fs.writeFileSync(process.argv[3], "ready\\n");',
        "  setInterval(() => {}, 1000);",
        "}).catch(() => { process.exitCode = 1; });",
        "",
      ].join("\n"),
      { mode: 0o600 }
    );

    const probe = childProcess.spawn(
      process.execPath,
      [probePath, harnessPath, programPath, programPidPath],
      { stdio: ["ignore", "pipe", "pipe"] }
    );
    try {
      await waitForFile(programPidPath);
      const programPid = Number(fs.readFileSync(programPidPath, "utf8").trim());
      assert.ok(Number.isInteger(programPid) && programPid > 1);
      const probeResult = waitForChild(probe);
      probe.kill("SIGTERM");
      assert.deepEqual(
        await probeResult,
        { code: null, signal: "SIGTERM" }
      );
      assert.throws(
        () => process.kill(programPid, 0),
        (error) => error && error.code === "ESRCH"
      );

      const idleProbe = childProcess.spawn(
        process.execPath,
        [idleProbePath, harnessPath, idleReadyPath],
        { stdio: ["ignore", "pipe", "pipe"] }
      );
      await waitForFile(idleReadyPath);
      const idleProbeResult = waitForChild(idleProbe);
      idleProbe.kill("SIGTERM");
      assert.deepEqual(
        await idleProbeResult,
        { code: null, signal: "SIGTERM" }
      );
    } finally {
      if (probe.exitCode === null && probe.signalCode === null) {
        probe.kill("SIGKILL");
      }
      fs.rmSync(signalRoot, { recursive: true, force: true });
    }
  }

  console.log("fetch-exhibitions integration harness tests passed");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
