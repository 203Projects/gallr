// Opt-in PostgREST integration test for the complete public exhibition reader.
//
// Stable fixture example:
// GALLR_POSTGREST_INTEGRATION=1 \
// GALLR_POSTGREST_TARGET=local \
// GALLR_EXHIBITION_SOURCE=canonical-v2 \
// SUPABASE_URL=http://127.0.0.1:55321 \
// SUPABASE_ANON_KEY=... \
// GALLR_EXPECTED_MIN_EXHIBITIONS=1001 \
// GALLR_EXPECTED_EXHIBITION_COUNT=1205 \
// GALLR_TEST_EVENT_ID='catalog.v2,(load):event' \
// GALLR_EXPECTED_CURSOR='<exact reserved-character page-boundary id>' \
// node tests/fetch-exhibitions.integration.test.js
//
// Same-ID, mid-pagination mutation evidence is deliberately harder to enable.
// There is no operator-provided executable hook. The harness runs the fixed
// checked-in disposable-clone target guard, then the fixed checked-in mutation
// SQL through the validated psql launcher and the exact Node.js/psql binaries
// recorded in the sealed preflight operator manifest:
// GALLR_POSTGREST_FIXTURE_MANIFEST='/absolute/path/to/sealed/manifest.json' \
// GALLR_POSTGREST_MUTATION_ATTESTATION=I_CONFIRM_THIS_IS_AN_ISOLATED_STAGING_FIXTURE \
// GALLR_EXPECTED_STAGING_PROJECT_REF='<20-character-staging-ref>' \
// GALLR_PRODUCTION_PROJECT_REF='<different-20-character-production-ref>' \
// GALLR_STAGING_REHEARSAL_CONFIRM='<same-staging-ref>' \
// GALLR_POSTGREST_MUTATION_TARGET_ID='<id returned on attempt one>' \
// GALLR_EXPECTED_FETCH_ATTEMPTS=2 \
// GALLR_EXPECTED_INTEGRITY_CALLS=2
// The fixture manifest must be the lifecycle's operator-owned mode-0400 file
// under the mode-0700 evidence root. The SQL transaction binds the target,
// exact published version, target-row checksum, project fingerprints,
// repository commit, operator manifest, and exact identity-policy marker.
// Filter, cursor, and count assertions remain reader-harness evidence rather
// than SQL inputs. The mutation child receives the staging database URI but
// never the anonymous or service-role key.

const assert = require("assert").strict;
const childProcess = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const {
  ReaderIntegrityMismatchError,
  assertExactResponseUrl,
  checksumExhibitionContent,
  checksumExhibitionIds,
  fetchAllExhibitions,
} = require("../scripts/fetch-exhibitions.js");
const { resolveExhibitionReaderSource } = require(
  "../scripts/lib/exhibition-reader-source.js"
);

const MUTATION_ATTESTATION = "I_CONFIRM_THIS_IS_AN_ISOLATED_STAGING_FIXTURE";
const REPOSITORY_ROOT = path.resolve(__dirname, "../..");
const TARGET_GUARD_PATH = path.join(
  REPOSITORY_ROOT,
  "scripts/staging-rehearsal/assert-disposable-clone-target.sh"
);
const VALIDATED_PSQL_RUNNER_PATH = path.join(
  REPOSITORY_ROOT,
  "scripts/staging-rehearsal/lib/run-psql-with-validated-target.mjs"
);
const MUTATION_SQL_PATH = path.join(
  REPOSITORY_ROOT,
  "scripts/staging-rehearsal/sql/mutate-postgrest-retry-fixture.sql"
);
const MUTATION_COMPLETE_TOKEN = "GALLR_POSTGREST_MUTATION_COMPLETE";
const MUTATION_PROCESS_TIMEOUT_MS = 45_000;
const MUTATION_CONNECT_TIMEOUT_SECONDS = "15";
const MUTATION_PSQL_OPTIONS =
  "-c statement_timeout=30s -c lock_timeout=5s";
const MUTATION_PSQL_APPNAME = "gallr-postgrest-mutation-evidence";
const MUTATION_PAGE_SIZE = 500;
const PROJECT_REF_PATTERN = /^[a-z0-9]{20}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const COMMIT_PATTERN = /^([0-9a-f]{40}|[0-9a-f]{64})$/;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const MARKER_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const UTC_SECOND_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const MAX_FIXTURE_MANIFEST_BYTES = 5 * 1024 * 1024;
const MAX_OPERATOR_MANIFEST_BYTES = 5 * 1024 * 1024;
const MAX_IDENTITY_POLICY_BYTES = 64 * 1024;
const TARGET_GUARD_PASS = "PASS: independent policy and disposable-clone marker identify staging";
const EXTERNAL_SIGNALS = ["SIGHUP", "SIGINT", "SIGQUIT", "SIGTERM"];
// The supervised Node launcher owns a separately detached psql process group
// and reserves up to two seconds for TERM plus two seconds for its KILL drain.
// Keep the outer grace strictly longer so it cannot SIGKILL the launcher before
// that launcher reaps psql and removes its private transport directory.
const EXTERNAL_TERM_GRACE_MS = 6_000;
const EXTERNAL_KILL_DRAIN_MS = 2_000;
const EXTERNAL_GROUP_POLL_MS = 20;
const STABLE_STAT_FIELDS = [
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
const externalChildState = {
  children: new Set(),
  handlers: new Map(),
  handlersInstalled: false,
  receivedSignal: null,
  reraiseScheduled: false,
};

function terminateExternalChild(entry, signal = "SIGTERM") {
  if (!entry || !entry.child || !Number.isInteger(entry.child.pid)) return;
  try {
    if (entry.detached) {
      process.kill(-entry.child.pid, signal);
    } else {
      entry.child.kill(signal);
    }
  } catch (_error) {
    // The direct child's close callback remains the authoritative reap point.
  }
}

function externalProcessGroupExists(entry) {
  if (!entry.detached || !entry.child || !Number.isInteger(entry.child.pid)) {
    return !entry.closed;
  }
  try {
    process.kill(-entry.child.pid, 0);
    return true;
  } catch (error) {
    return !error || error.code !== "ESRCH";
  }
}

function maybeReraiseExternalSignal() {
  if (
    !externalChildState.receivedSignal ||
    externalChildState.children.size !== 0 ||
    externalChildState.reraiseScheduled
  ) {
    return;
  }
  externalChildState.reraiseScheduled = true;
  setImmediate(() => {
    if (externalChildState.children.size !== 0) {
      externalChildState.reraiseScheduled = false;
      return;
    }
    const signal = externalChildState.receivedSignal;
    for (const [handledSignal, handler] of externalChildState.handlers) {
      process.removeListener(handledSignal, handler);
    }
    externalChildState.handlers.clear();
    externalChildState.handlersInstalled = false;
    process.kill(process.pid, signal);
  });
}

function requestExternalTermination(entry, error) {
  if (error && !entry.failure) entry.failure = error;
  if (!entry.terminationRequested) {
    entry.terminationRequested = true;
    terminateExternalChild(entry, "SIGTERM");
    entry.escalationTimer = setTimeout(() => {
      entry.escalationTimer = null;
      terminateExternalChild(entry, "SIGKILL");
      entry.killDeadline = Date.now() + EXTERNAL_KILL_DRAIN_MS;
      finishExternalEntryWhenStopped(entry);
    }, EXTERNAL_TERM_GRACE_MS);
  }
}

function settleExternalEntry(entry) {
  if (entry.finished) return;
  entry.finished = true;
  if (entry.timeoutTimer) clearTimeout(entry.timeoutTimer);
  if (entry.escalationTimer) clearTimeout(entry.escalationTimer);
  if (entry.groupPollTimer) clearTimeout(entry.groupPollTimer);
  externalChildState.children.delete(entry);

  const stdout = Buffer.concat(entry.stdoutChunks).toString(entry.encoding);
  const stderr = Buffer.concat(entry.stderrChunks).toString(entry.encoding);
  if (entry.failure) {
    entry.failure.stdout = stdout;
    entry.failure.stderr = stderr;
    entry.reject(entry.failure);
  } else {
    entry.resolve({ stdout, stderr });
  }
  maybeReraiseExternalSignal();
}

function finishExternalEntryWhenStopped(entry) {
  if (entry.finished || !entry.closed) return;
  if (externalProcessGroupExists(entry)) {
    requestExternalTermination(
      entry,
      entry.failure || new Error("external command left a live descendant")
    );
    if (entry.killDeadline !== null && Date.now() >= entry.killDeadline) {
      terminateExternalChild(entry, "SIGKILL");
      if (!entry.failure) {
        entry.failure = new Error(
          "external process group did not exit after SIGKILL"
        );
      }
      entry.failure.externalProcessGroupStillLive = true;
      settleExternalEntry(entry);
      return;
    }
    entry.groupPollTimer ||= setTimeout(() => {
      entry.groupPollTimer = null;
      finishExternalEntryWhenStopped(entry);
    }, EXTERNAL_GROUP_POLL_MS);
    return;
  }
  settleExternalEntry(entry);
}

function installExternalChildSignalHandlers() {
  if (externalChildState.handlersInstalled) return;
  externalChildState.handlersInstalled = true;
  for (const signal of EXTERNAL_SIGNALS) {
    const handler = () => {
      externalChildState.receivedSignal ||= signal;
      for (const entry of externalChildState.children) {
        // Async Bash children can inherit INT/QUIT ignored. TERM is the
        // portable cancellation signal; the original signal is retained for
        // this harness's final exit status.
        const error = new Error("external execution cancelled");
        error.signal = externalChildState.receivedSignal;
        requestExternalTermination(entry, error);
      }
      maybeReraiseExternalSignal();
    };
    externalChildState.handlers.set(signal, handler);
    process.on(signal, handler);
  }
}

function trackedExternalSignal() {
  return externalChildState.receivedSignal;
}

function signalExitCode(signal) {
  const signalNumber = os.constants.signals[signal];
  return Number.isInteger(signalNumber) ? 128 + signalNumber : 1;
}

function execFileTracked(file, args, options = {}) {
  installExternalChildSignalHandlers();
  if (externalChildState.receivedSignal) {
    const error = new Error("external execution cancelled");
    error.signal = externalChildState.receivedSignal;
    maybeReraiseExternalSignal();
    return Promise.reject(error);
  }

  const {
    encoding = "utf8",
    maxBuffer = 1024 * 1024,
    timeout = 0,
    ...spawnOptions
  } = options;
  assert.ok(Number.isSafeInteger(maxBuffer) && maxBuffer > 0);
  assert.ok(Number.isSafeInteger(timeout) && timeout >= 0);
  const detached = process.platform !== "win32";

  return new Promise((resolve, reject) => {
    const child = childProcess.spawn(file, args, {
      ...spawnOptions,
      detached,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const entry = {
      child,
      closed: false,
      detached,
      encoding,
      escalationTimer: null,
      failure: null,
      finished: false,
      groupPollTimer: null,
      killDeadline: null,
      maxBuffer,
      reject,
      resolve,
      stderrBytes: 0,
      stderrChunks: [],
      stdoutBytes: 0,
      stdoutChunks: [],
      terminationRequested: false,
      timedOut: false,
      timeoutTimer: null,
    };
    externalChildState.children.add(entry);
    const retainOutput = (streamName, chunk) => {
      const bytesKey = `${streamName}Bytes`;
      const chunksKey = `${streamName}Chunks`;
      entry[bytesKey] += chunk.length;
      if (entry[bytesKey] <= entry.maxBuffer) {
        entry[chunksKey].push(chunk);
        return;
      }
      const error = new Error(`${streamName} exceeded maxBuffer`);
      error.code = "ERR_CHILD_PROCESS_STDIO_MAXBUFFER";
      requestExternalTermination(entry, error);
    };
    child.stdout.on("data", (chunk) => retainOutput("stdout", chunk));
    child.stderr.on("data", (chunk) => retainOutput("stderr", chunk));
    child.once("error", (error) => {
      requestExternalTermination(entry, error);
    });
    child.once("close", (code, signal) => {
      entry.closed = true;
      if (!entry.failure && (code !== 0 || signal)) {
        const error = new Error(`external command failed: ${file}`);
        error.code = code;
        error.signal = signal;
        entry.failure = error;
      }
      finishExternalEntryWhenStopped(entry);
    });

    // Close the fork-to-registration race: a signal recorded before this entry
    // reached the set must terminate the newly registered process group.
    if (externalChildState.receivedSignal) {
      const error = new Error("external execution cancelled");
      error.signal = externalChildState.receivedSignal;
      requestExternalTermination(entry, error);
    }
    if (timeout > 0) {
      entry.timeoutTimer = setTimeout(() => {
        entry.timedOut = true;
        const error = new Error(`external command timed out after ${timeout}ms`);
        error.killed = true;
        requestExternalTermination(entry, error);
      }, timeout);
    }
  });
}

function trimmed(env, name) {
  return String(env[name] || "").trim();
}

function parseInteger(name, raw, fallback, { minimum = 0, maximum } = {}) {
  const value = Number(raw === "" ? fallback : raw);
  assert.ok(Number.isSafeInteger(value), `${name} must be an integer`);
  assert.ok(value >= minimum, `${name} must be at least ${minimum}`);
  if (maximum !== undefined) {
    assert.ok(value <= maximum, `${name} must be at most ${maximum}`);
  }
  return value;
}

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function assertCurrentUserOwned(stat, label, getuid) {
  const currentUid = getuid();
  if (currentUid !== null) {
    assert.equal(stat.uid, currentUid, `${label} must be owned by the current user`);
  }
}

function assertCanonicalPath(inputPath, label, realpathSync) {
  let resolved;
  try {
    resolved = realpathSync(inputPath);
  } catch (_error) {
    assert.fail(`${label} must resolve to an existing canonical path`);
  }
  assert.equal(inputPath, resolved, `${label} must not contain symbolic-link path components`);
}

function assertStableSnapshotStat(before, after, label) {
  for (const field of STABLE_STAT_FIELDS) {
    assert.ok(
      Object.is(before[field], after[field]),
      `${label} identity or metadata changed while it was read`
    );
  }
}

function readStableFileSnapshot(
  filePath,
  {
    exactMode = null,
    executable = false,
    filesystem = {},
    getuid = typeof process.getuid === "function"
      ? () => process.getuid()
      : () => null,
    label = "file",
    maximumBytes = Number.MAX_SAFE_INTEGER,
    requireCurrentUser = true,
  } = {}
) {
  const constants = filesystem.constants || fs.constants;
  const openSync = filesystem.openSync || fs.openSync;
  const fstatSync = filesystem.fstatSync || fs.fstatSync;
  const lstatSync = filesystem.lstatSync || fs.lstatSync;
  const realpathSync = filesystem.realpathSync || fs.realpathSync;
  const readFileSync = filesystem.readFileSync || fs.readFileSync;
  const closeSync = filesystem.closeSync || fs.closeSync;

  assert.ok(
    typeof constants.O_NOFOLLOW === "number",
    "this platform cannot safely open validation artifacts"
  );
  assertCanonicalPath(filePath, label, realpathSync);

  const validateStat = (stat) => {
    assert.ok(stat.isFile(), `${label} must be a regular file`);
    assert.equal(stat.nlink, 1, `${label} must not be hard-linked`);
    if (requireCurrentUser) assertCurrentUserOwned(stat, label, getuid);
    if (exactMode === null) {
      assert.equal(
        stat.mode & 0o022,
        0,
        `${label} must not be group- or world-writable`
      );
    } else {
      assert.equal(
        stat.mode & 0o777,
        exactMode,
        `${label} must have mode 0${exactMode.toString(8)}`
      );
    }
    if (executable) {
      assert.notEqual(stat.mode & 0o111, 0, `${label} must be executable`);
    }
    assert.ok(
      Number.isSafeInteger(stat.size) &&
        stat.size >= 0 &&
        stat.size <= maximumBytes,
      `${label} size is invalid`
    );
  };

  const openFlags =
    constants.O_RDONLY |
    constants.O_NOFOLLOW |
    (typeof constants.O_CLOEXEC === "number" ? constants.O_CLOEXEC : 0);
  let descriptor;
  let closeFailure = null;
  let snapshot;
  try {
    descriptor = openSync(filePath, openFlags);
    const before = fstatSync(descriptor);
    validateStat(before);
    const bytes = readFileSync(descriptor);
    assert.ok(Buffer.isBuffer(bytes), `${label} read must return bytes`);
    const after = fstatSync(descriptor);
    validateStat(after);
    assertStableSnapshotStat(before, after, label);
    assert.equal(bytes.length, after.size, `${label} size changed while it was read`);

    const pathnameStat = lstatSync(filePath);
    assert.equal(
      pathnameStat.isSymbolicLink(),
      false,
      `${label} pathname became a symbolic link`
    );
    assert.ok(pathnameStat.isFile(), `${label} pathname must remain a regular file`);
    assert.equal(pathnameStat.dev, after.dev, `${label} pathname device changed`);
    assert.equal(pathnameStat.ino, after.ino, `${label} pathname inode changed`);
    snapshot = { bytes, stat: before };
  } finally {
    if (descriptor !== undefined) {
      try {
        closeSync(descriptor);
      } catch (error) {
        closeFailure = error;
      }
    }
  }
  assert.equal(closeFailure, null, `${label} descriptor could not be closed safely`);
  return snapshot;
}

function readFixedRepositoryFile(filePath, { executable = false } = {}) {
  return readStableFileSnapshot(filePath, {
    executable,
    label: "fixed repository file",
  }).bytes;
}

function parseExactUtc(value, label) {
  assert.match(value, UTC_SECOND_PATTERN, `${label} must use exact UTC second precision`);
  const epoch = Date.parse(value);
  assert.ok(
    Number.isFinite(epoch) &&
      new Date(epoch).toISOString().replace(".000Z", "Z") === value,
    `${label} is invalid`
  );
  return epoch;
}

function parseExactKeyValueFile(text, expectedKeys, label) {
  assert.equal(text.includes("\r"), false, `${label} must use LF line endings`);
  assert.ok(text.endsWith("\n"), `${label} must end with one newline`);
  assert.equal(text.endsWith("\n\n"), false, `${label} must not contain blank lines`);
  const lines = text.slice(0, -1).split("\n");
  assert.equal(lines.length, expectedKeys.length, `${label} field count is invalid`);
  const parsed = new Map();
  for (const [index, line] of lines.entries()) {
    const separator = line.indexOf("=");
    assert.ok(separator > 0, `${label} contains a malformed field`);
    const key = line.slice(0, separator);
    const value = line.slice(separator + 1);
    assert.equal(key, expectedKeys[index], `${label} fields are out of order`);
    assert.ok(value.length > 0, `${label} contains an empty field`);
    assert.equal(parsed.has(key), false, `${label} repeats ${key}`);
    parsed.set(key, value);
  }
  return parsed;
}

function parseIdentityPolicy(bytes) {
  assert.ok(
    Buffer.isBuffer(bytes) &&
      bytes.length > 0 &&
      bytes.length <= MAX_IDENTITY_POLICY_BYTES,
    "identity policy size is invalid"
  );
  const text = bytes.toString("utf8");
  assert.ok(
    Buffer.from(text, "utf8").equals(bytes),
    "identity policy must be valid UTF-8"
  );
  assert.equal(
    /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u0085\u2028\u2029]/u.test(text),
    false,
    "identity policy contains invalid control characters"
  );

  const firstLine = text.split("\n", 1)[0];
  let governanceMode;
  let expectedKeys;
  if (firstLine === "policy_schema=1") {
    governanceMode = "separated_humans";
    expectedKeys = MULTI_PERSON_POLICY_KEYS;
  } else if (firstLine === "policy_schema=2") {
    governanceMode = "solo_operator";
    expectedKeys = SOLO_OPERATOR_POLICY_KEYS;
  } else {
    assert.fail("identity policy schema is unsupported");
  }

  const fields = parseExactKeyValueFile(text, expectedKeys, "identity policy");
  assert.equal(
    fields.get("policy_kind"),
    "gallr_disposable_clone_target",
    "identity policy kind is invalid"
  );
  if (governanceMode === "solo_operator") {
    assert.equal(
      fields.get("governance_mode"),
      governanceMode,
      "identity policy governance mode is invalid"
    );
  }
  for (const key of [
    "staging_project_ref_sha256",
    "production_project_ref_sha256",
    "operator_manifest_sha256",
  ]) {
    assert.match(fields.get(key), SHA256_PATTERN, `identity policy ${key} is invalid`);
  }
  assert.match(
    fields.get("repository_commit"),
    COMMIT_PATTERN,
    "identity policy repository commit is invalid"
  );
  assert.match(
    fields.get("marker_id"),
    MARKER_ID_PATTERN,
    "identity policy marker ID is invalid"
  );
  const issuedAtUtc = fields.get("issued_at_utc");
  const validUntilUtc = fields.get("valid_until_utc");
  const issuedAt = parseExactUtc(issuedAtUtc, "identity policy issue timestamp");
  const validUntil = parseExactUtc(validUntilUtc, "identity policy expiry timestamp");
  assert.ok(
    validUntil > issuedAt,
    "identity policy expiry must be later than its issue timestamp"
  );

  return {
    bytes,
    changeRecord: fields.get("change_record"),
    governanceMode,
    issuedAtUtc,
    markerId: fields.get("marker_id"),
    operatorManifestSha256: fields.get("operator_manifest_sha256"),
    productionRefSha256: fields.get("production_project_ref_sha256"),
    repositoryCommit: fields.get("repository_commit"),
    sha256: sha256(bytes),
    stagingRefSha256: fields.get("staging_project_ref_sha256"),
    validUntilUtc,
  };
}

function parseOperatorManifest(bytes) {
  assert.ok(
    Buffer.isBuffer(bytes) && bytes.length > 0 &&
      bytes.length <= MAX_OPERATOR_MANIFEST_BYTES,
    "operator manifest size is invalid"
  );
  const text = bytes.toString("utf8");
  assert.ok(
    Buffer.from(text, "utf8").equals(bytes),
    "operator manifest must be valid UTF-8"
  );
  assert.equal(
    /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u0085\u2028\u2029]/u.test(text),
    false,
    "operator manifest contains invalid control characters"
  );

  const fields = new Map();
  for (const line of text.replaceAll("\r\n", "\n").split("\n")) {
    if (line === "" || line.startsWith("[")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    const key = line.slice(0, separator);
    const value = line.slice(separator + 1);
    if (!new Set([
      "manifest_schema",
      "target",
      "change_record",
      "governance_mode",
      "repository_commit",
      "staging_project_ref_sha256",
      "production_project_ref_sha256",
      "reviewed_node_path",
      "reviewed_node_sha256",
      "reviewed_psql_path",
      "reviewed_psql_sha256",
    ]).has(key)) {
      continue;
    }
    assert.equal(fields.has(key), false, `operator manifest repeats ${key}`);
    fields.set(key, value);
  }

  const required = (name) => {
    const value = fields.get(name);
    assert.ok(value, `operator manifest is missing ${name}`);
    return value;
  };
  const manifestSchema = required("manifest_schema");
  const target = required("target");
  const changeRecord = required("change_record");
  const repositoryCommit = required("repository_commit");
  const stagingRefSha256 = required("staging_project_ref_sha256");
  const productionRefSha256 = required("production_project_ref_sha256");
  const reviewedNodePath = required("reviewed_node_path");
  const reviewedNodeSha256 = required("reviewed_node_sha256");
  const reviewedPsqlPath = required("reviewed_psql_path");
  const reviewedPsqlSha256 = required("reviewed_psql_sha256");

  assert.ok(
    manifestSchema === "1" || manifestSchema === "2",
    "operator manifest schema is invalid"
  );
  assert.equal(target, "staging", "operator manifest target is invalid");
  const governanceMode = manifestSchema === "2"
    ? required("governance_mode")
    : "separated_humans";
  if (manifestSchema === "2") {
    assert.equal(
      governanceMode,
      "solo_operator",
      "operator manifest governance mode is invalid"
    );
  }
  assert.match(
    changeRecord,
    /^[A-Za-z0-9][A-Za-z0-9 .,:_@/+\-]{2,159}$/,
    "operator manifest change record is invalid"
  );
  assert.match(repositoryCommit, COMMIT_PATTERN, "operator manifest commit is invalid");
  assert.match(stagingRefSha256, SHA256_PATTERN, "operator manifest staging hash is invalid");
  assert.match(
    productionRefSha256,
    SHA256_PATTERN,
    "operator manifest production hash is invalid"
  );
  assert.ok(path.isAbsolute(reviewedNodePath), "reviewed Node.js path must be absolute");
  assert.ok(path.isAbsolute(reviewedPsqlPath), "reviewed psql path must be absolute");
  assert.equal(
    path.normalize(reviewedNodePath),
    reviewedNodePath,
    "reviewed Node.js path must be normalized"
  );
  assert.equal(
    path.normalize(reviewedPsqlPath),
    reviewedPsqlPath,
    "reviewed psql path must be normalized"
  );
  assert.match(reviewedNodeSha256, SHA256_PATTERN, "reviewed Node.js SHA-256 is invalid");
  assert.match(reviewedPsqlSha256, SHA256_PATTERN, "reviewed psql SHA-256 is invalid");

  return {
    changeRecord,
    governanceMode,
    manifestSchema,
    repositoryCommit,
    stagingRefSha256,
    productionRefSha256,
    reviewedNodePath,
    reviewedNodeSha256,
    reviewedPsqlPath,
    reviewedPsqlSha256,
    target,
  };
}

function readIntegrationConfig(
  env = process.env,
  filesystem = {}
) {
  if (env.GALLR_POSTGREST_INTEGRATION !== "1") return { enabled: false };

  const lstatSync = filesystem.lstatSync || fs.lstatSync;
  const statSync = filesystem.statSync || fs.statSync;
  const realpathSync = filesystem.realpathSync || fs.realpathSync;
  const readFileSync = filesystem.readFileSync || fs.readFileSync;
  const getuid = filesystem.getuid || (
    typeof process.getuid === "function" ? () => process.getuid() : () => null
  );

  const baseUrl = trimmed(env, "SUPABASE_URL");
  const key = trimmed(env, "SUPABASE_ANON_KEY");
  const exactRaw = trimmed(env, "GALLR_EXPECTED_EXHIBITION_COUNT");
  const exact = exactRaw === "" ? null : parseInteger(
    "GALLR_EXPECTED_EXHIBITION_COUNT",
    exactRaw,
    "0"
  );
  const minimum = parseInteger(
    "GALLR_EXPECTED_MIN_EXHIBITIONS",
    trimmed(env, "GALLR_EXPECTED_MIN_EXHIBITIONS"),
    exact === 0 ? "0" : "1001"
  );
  const minimumPageRequests = parseInteger(
    "GALLR_EXPECTED_MIN_PAGE_REQUESTS",
    trimmed(env, "GALLR_EXPECTED_MIN_PAGE_REQUESTS"),
    minimum > 1000 ? "4" : "1",
    { minimum: 1 }
  );
  const expectedAttemptsRaw = trimmed(env, "GALLR_EXPECTED_FETCH_ATTEMPTS");
  const expectedIntegrityCallsRaw = trimmed(env, "GALLR_EXPECTED_INTEGRITY_CALLS");
  const expectedAttempts = parseInteger(
    "GALLR_EXPECTED_FETCH_ATTEMPTS",
    expectedAttemptsRaw,
    "1",
    { minimum: 1, maximum: 2 }
  );
  const expectedIntegrityCalls = parseInteger(
    "GALLR_EXPECTED_INTEGRITY_CALLS",
    expectedIntegrityCallsRaw,
    String(expectedAttempts),
    { minimum: 0, maximum: 2 }
  );
  const eventId = trimmed(env, "GALLR_TEST_EVENT_ID");
  const expectedCursor = trimmed(env, "GALLR_EXPECTED_CURSOR");
  const featuredOnlyRaw = trimmed(env, "GALLR_TEST_FEATURED_ONLY") || "0";
  const targetEnvironment = trimmed(env, "GALLR_POSTGREST_TARGET");
  const readerSource = resolveExhibitionReaderSource(env.GALLR_EXHIBITION_SOURCE);

  assert.ok(baseUrl, "SUPABASE_URL is required");
  assert.ok(key, "SUPABASE_ANON_KEY is required");
  assert.ok(
    targetEnvironment === "local" || targetEnvironment === "staging",
    "GALLR_POSTGREST_TARGET must be local or staging"
  );
  let parsedBaseUrl;
  try {
    parsedBaseUrl = new URL(baseUrl);
  } catch (_error) {
    assert.fail("SUPABASE_URL must be an absolute URL");
  }
  assert.equal(parsedBaseUrl.username, "", "SUPABASE_URL must not contain credentials");
  assert.equal(parsedBaseUrl.password, "", "SUPABASE_URL must not contain credentials");
  assert.equal(parsedBaseUrl.pathname, "/", "SUPABASE_URL must be a project origin");
  assert.equal(parsedBaseUrl.search, "", "SUPABASE_URL must not contain query parameters");
  assert.equal(parsedBaseUrl.hash, "", "SUPABASE_URL must not contain a fragment");
  assert.ok(exact === null || exact >= minimum, "exact count is below the expected minimum");
  assert.ok(
    featuredOnlyRaw === "0" || featuredOnlyRaw === "1",
    "GALLR_TEST_FEATURED_ONLY must be 0 or 1"
  );

  const legacyHookPath = trimmed(env, "GALLR_POSTGREST_MUTATION_HOOK");
  const legacyHookSha256 = trimmed(env, "GALLR_POSTGREST_MUTATION_HOOK_SHA256");
  const legacyHookTimeout = trimmed(
    env,
    "GALLR_POSTGREST_MUTATION_HOOK_TIMEOUT_MS"
  );
  const legacyTargetGuardPath = trimmed(env, "GALLR_POSTGREST_TARGET_GUARD");
  const legacyTargetGuardSha256 = trimmed(
    env,
    "GALLR_POSTGREST_TARGET_GUARD_SHA256"
  );
  const attestation = trimmed(env, "GALLR_POSTGREST_MUTATION_ATTESTATION");
  const expectedStagingRef = trimmed(env, "GALLR_EXPECTED_STAGING_PROJECT_REF");
  const productionRef = trimmed(env, "GALLR_PRODUCTION_PROJECT_REF");
  const stagingConfirmation = trimmed(env, "GALLR_STAGING_REHEARSAL_CONFIRM");
  const targetId = trimmed(env, "GALLR_POSTGREST_MUTATION_TARGET_ID");
  const fixtureManifestPath = trimmed(env, "GALLR_POSTGREST_FIXTURE_MANIFEST");
  const stagingDatabaseUrl = trimmed(env, "GALLR_STAGING_DATABASE_URL");
  const stagingEvidenceDir = trimmed(env, "GALLR_STAGING_EVIDENCE_DIR");
  const stagingIdentityPolicyPath = trimmed(env, "GALLR_STAGING_IDENTITY_POLICY_PATH");
  assert.equal(
    [
      legacyHookPath,
      legacyHookSha256,
      legacyHookTimeout,
      legacyTargetGuardPath,
      legacyTargetGuardSha256,
    ].some(Boolean),
    false,
    "operator-provided mutation hook and target-guard paths are forbidden"
  );
  const mutationRequested = [
    attestation,
    targetId,
    fixtureManifestPath,
    stagingDatabaseUrl,
    stagingEvidenceDir,
    stagingIdentityPolicyPath,
  ].some(Boolean);
  let mutation = null;

  if (targetEnvironment === "staging") {
    assert.match(
      expectedStagingRef,
      PROJECT_REF_PATTERN,
      "GALLR_EXPECTED_STAGING_PROJECT_REF must be a 20-character project ref"
    );
    assert.match(
      productionRef,
      PROJECT_REF_PATTERN,
      "GALLR_PRODUCTION_PROJECT_REF must be a 20-character project ref"
    );
    assert.notEqual(
      expectedStagingRef,
      productionRef,
      "integration evidence requires distinct staging and production project refs"
    );
    assert.equal(
      stagingConfirmation,
      expectedStagingRef,
      "GALLR_STAGING_REHEARSAL_CONFIRM must exactly match the staging project ref"
    );
    assert.equal(
      parsedBaseUrl.protocol,
      "https:",
      "staging integration evidence requires an HTTPS Supabase project URL"
    );
    assert.equal(
      parsedBaseUrl.hostname,
      `${expectedStagingRef}.supabase.co`,
      "SUPABASE_URL must identify the explicitly confirmed staging project"
    );
    assert.equal(
      parsedBaseUrl.port,
      "",
      "staging SUPABASE_URL must use the default HTTPS port"
    );
  } else {
    assert.ok(
      parsedBaseUrl.protocol === "http:" || parsedBaseUrl.protocol === "https:",
      "local integration evidence requires an HTTP URL"
    );
    assert.ok(
      ["127.0.0.1", "localhost", "[::1]"].includes(parsedBaseUrl.hostname),
      "local integration evidence is restricted to the loopback interface"
    );
  }

  if (mutationRequested) {
    assert.equal(
      attestation,
      MUTATION_ATTESTATION,
      "mutation evidence requires the exact isolated-staging-fixture attestation"
    );
    assert.equal(
      targetEnvironment,
      "staging",
      "same-ID mutation evidence is forbidden against the local target mode"
    );
    assert.ok(targetId, "GALLR_POSTGREST_MUTATION_TARGET_ID is required");
    assert.ok(
      path.isAbsolute(fixtureManifestPath),
      "GALLR_POSTGREST_FIXTURE_MANIFEST must be an absolute path"
    );
    assert.ok(
      stagingDatabaseUrl,
      "GALLR_STAGING_DATABASE_URL is required for the immediate target guard"
    );
    assert.ok(path.isAbsolute(stagingEvidenceDir), "GALLR_STAGING_EVIDENCE_DIR must be absolute");
    assert.ok(
      path.isAbsolute(stagingIdentityPolicyPath),
      "GALLR_STAGING_IDENTITY_POLICY_PATH must be absolute"
    );
    assert.equal(
      readerSource.integrityMode,
      "id-and-content",
      "same-ID mutation evidence requires the canonical-v2 content integrity reader"
    );
    assert.ok(
      expectedAttemptsRaw !== "" && expectedIntegrityCallsRaw !== "",
      "mutation evidence requires explicit expected fetch-attempt and integrity-call counts"
    );
    assert.ok(
      expectedAttempts >= 2 && expectedIntegrityCalls >= 2,
      "mutation evidence must expect the reader's integrity retry"
    );

    let manifestParentStat;
    let evidenceRootStat;
    let identityPolicyParentLstat;
    let identityPolicyParentStat;
    const operatorManifestPath = path.join(
      stagingEvidenceDir,
      "operator-manifest.txt"
    );
    const identityPolicyParent = path.dirname(stagingIdentityPolicyPath);
    try {
      manifestParentStat = statSync(path.dirname(fixtureManifestPath));
      evidenceRootStat = lstatSync(stagingEvidenceDir);
      identityPolicyParentLstat = lstatSync(identityPolicyParent);
      identityPolicyParentStat = statSync(identityPolicyParent);
    } catch (_error) {
      assert.fail("mutation artifact directories must exist and be inspectable");
    }
    assertCanonicalPath(
      stagingEvidenceDir,
      "GALLR_STAGING_EVIDENCE_DIR",
      realpathSync
    );
    assertCanonicalPath(
      identityPolicyParent,
      "identity policy parent",
      realpathSync
    );
    assert.equal(
      evidenceRootStat.isSymbolicLink(),
      false,
      "evidence root must not be a symbolic link"
    );
    assert.ok(evidenceRootStat.isDirectory(), "evidence root must be a directory");
    assert.equal(
      identityPolicyParentLstat.isSymbolicLink(),
      false,
      "identity policy parent must not be a symbolic link"
    );
    assert.ok(
      identityPolicyParentStat.isDirectory(),
      "identity policy parent must be a directory"
    );
    assert.ok(manifestParentStat.isDirectory(), "fixture manifest parent must be a directory");
    assertCurrentUserOwned(evidenceRootStat, "evidence root", getuid);
    assertCurrentUserOwned(manifestParentStat, "fixture manifest parent", getuid);
    assertCurrentUserOwned(
      identityPolicyParentStat,
      "identity policy parent",
      getuid
    );
    assert.equal(
      evidenceRootStat.mode & 0o777,
      0o700,
      "evidence root must have mode 0700"
    );
    assert.equal(
      manifestParentStat.mode & 0o777,
      0o700,
      "fixture manifest parent must have mode 0700"
    );
    assert.equal(
      identityPolicyParentStat.mode & 0o777,
      0o700,
      "identity policy parent must have mode 0700"
    );
    assert.ok(
      fixtureManifestPath.startsWith(`${stagingEvidenceDir}${path.sep}`),
      "fixture manifest must be inside the evidence root"
    );
    const resolvedRepositoryRoot = realpathSync(REPOSITORY_ROOT);
    const resolvedIdentityPolicyPath = realpathSync(stagingIdentityPolicyPath);
    const relativeIdentityPolicyPath = path.relative(
      resolvedRepositoryRoot,
      resolvedIdentityPolicyPath
    );
    assert.ok(
      relativeIdentityPolicyPath !== "" &&
        (
          relativeIdentityPolicyPath.startsWith(`..${path.sep}`) ||
          path.isAbsolute(relativeIdentityPolicyPath)
        ),
      "identity policy must be outside the repository"
    );

    const manifestSnapshot = readStableFileSnapshot(fixtureManifestPath, {
      exactMode: 0o400,
      filesystem,
      getuid,
      label: "fixture manifest",
      maximumBytes: MAX_FIXTURE_MANIFEST_BYTES,
    });
    const operatorManifestSnapshot = readStableFileSnapshot(
      operatorManifestPath,
      {
        exactMode: 0o444,
        filesystem,
        getuid,
        label: "operator manifest",
        maximumBytes: MAX_OPERATOR_MANIFEST_BYTES,
      }
    );
    const identityPolicySnapshot = readStableFileSnapshot(
      stagingIdentityPolicyPath,
      {
        exactMode: 0o400,
        filesystem,
        getuid,
        label: "identity policy",
        maximumBytes: MAX_IDENTITY_POLICY_BYTES,
      }
    );
    const manifestBytes = manifestSnapshot.bytes;
    const operatorManifestBytes = operatorManifestSnapshot.bytes;
    const identityPolicyBytes = identityPolicySnapshot.bytes;

    let fixtureManifest;
    try {
      fixtureManifest = JSON.parse(manifestBytes.toString("utf8"));
    } catch (_error) {
      assert.fail("fixture manifest must contain valid JSON");
    }
    assert.equal(fixtureManifest.schema_version, 1, "fixture manifest schema is invalid");
    assert.equal(fixtureManifest.state, "provisioned", "fixture manifest is not provisioned");
    assert.equal(fixtureManifest.fixture_count, 1205, "fixture manifest count is invalid");
    assert.match(
      fixtureManifest.run_id,
      /^[a-z0-9][a-z0-9-]{7,31}$/,
      "fixture manifest run ID is invalid"
    );
    assert.equal(
      fixtureManifest.fixture_prefix,
      `gallr-rehearsal-${fixtureManifest.run_id}-`,
      "fixture manifest prefix is invalid"
    );
    assert.equal(
      targetId,
      `${fixtureManifest.fixture_prefix}catalog-0750.mutate,(same-id):한글`,
      "mutation target is not the deterministic fixture mutation ID"
    );
    assert.equal(
      eventId,
      `${fixtureManifest.fixture_prefix}event.catalog.v2,(load):한글`,
      "mutation event is not the deterministic fixture load event"
    );
    assert.equal(
      expectedCursor,
      `${fixtureManifest.fixture_prefix}catalog-0500.cursor,(reserved):한글`,
      "mutation cursor is not the deterministic fixture boundary"
    );
    assert.equal(
      fixtureManifest.staging_ref_sha256,
      sha256(expectedStagingRef),
      "fixture manifest staging fingerprint does not match the target"
    );
    assert.equal(
      fixtureManifest.production_ref_sha256,
      sha256(productionRef),
      "fixture manifest production fingerprint does not match the approved production ref"
    );
    assert.equal(
      targetId,
      fixtureManifest.mutation_target_id,
      "mutation target must exactly match the sealed fixture manifest"
    );
    assert.ok(
      targetId.startsWith(fixtureManifest.fixture_prefix),
      "mutation target must belong to the sealed fixture prefix"
    );
    assert.equal(
      fixtureManifest.database_evidence?.mutation_target_id,
      targetId,
      "database fixture evidence has a different mutation target"
    );
    assert.equal(
      eventId,
      fixtureManifest.database_evidence?.load_event_id,
      "mutation evidence must use the sealed fixture event"
    );
    assert.equal(
      expectedCursor,
      fixtureManifest.database_evidence?.boundary_cursor_id,
      "mutation evidence must use the sealed fixture boundary cursor"
    );
    assert.equal(exact, 1205, "mutation evidence requires the exact fixture count");

    const fixtureExhibitionIds =
      fixtureManifest.database_evidence?.fixture_exhibition_ids;
    const fixtureVersionIds =
      fixtureManifest.database_evidence?.fixture_version_ids;
    assert.ok(
      Array.isArray(fixtureExhibitionIds) &&
        fixtureExhibitionIds.length === fixtureManifest.fixture_count &&
        new Set(fixtureExhibitionIds).size === fixtureExhibitionIds.length &&
        fixtureExhibitionIds.every(
          (value, index) =>
            typeof value === "string" &&
            value.startsWith(fixtureManifest.fixture_prefix) &&
            (
              index === 0 ||
              fixtureExhibitionIds[index - 1] < value
            )
        ),
      "fixture manifest exhibition IDs are invalid or not strictly sorted"
    );
    assert.ok(
      Array.isArray(fixtureVersionIds) &&
        fixtureVersionIds.length === fixtureManifest.fixture_count &&
        new Set(fixtureVersionIds).size === fixtureVersionIds.length &&
        fixtureVersionIds.every((value) => UUID_PATTERN.test(value)),
      "fixture manifest version IDs are invalid"
    );
    const targetIndex = fixtureExhibitionIds.indexOf(targetId);
    assert.equal(
      targetIndex,
      749,
      "mutation target must be the sealed fixture's 750th sorted row"
    );
    assert.equal(
      fixtureExhibitionIds.indexOf(expectedCursor),
      499,
      "boundary cursor must be the sealed fixture's 500th sorted row"
    );
    const publishedVersionId = fixtureVersionIds[targetIndex];
    assert.match(
      publishedVersionId,
      UUID_PATTERN,
      "mutation target's published version ID is invalid"
    );

    const operatorManifest = parseOperatorManifest(operatorManifestBytes);
    assert.equal(
      operatorManifest.stagingRefSha256,
      sha256(expectedStagingRef),
      "operator manifest staging fingerprint does not match the target"
    );
    assert.equal(
      operatorManifest.productionRefSha256,
      sha256(productionRef),
      "operator manifest production fingerprint does not match the approved production ref"
    );
    const identityPolicy = parseIdentityPolicy(identityPolicyBytes);
    assert.equal(
      identityPolicy.stagingRefSha256,
      sha256(expectedStagingRef),
      "identity policy staging fingerprint does not match the target"
    );
    assert.equal(
      identityPolicy.productionRefSha256,
      sha256(productionRef),
      "identity policy production fingerprint does not match the approved production ref"
    );
    assert.equal(
      identityPolicy.repositoryCommit,
      operatorManifest.repositoryCommit,
      "identity policy repository commit does not match the operator manifest"
    );
    assert.equal(
      identityPolicy.operatorManifestSha256,
      sha256(operatorManifestBytes),
      "identity policy does not bind the exact operator manifest bytes"
    );
    assert.equal(
      identityPolicy.changeRecord,
      operatorManifest.changeRecord,
      "identity policy change record does not match the operator manifest"
    );
    assert.equal(
      identityPolicy.governanceMode,
      operatorManifest.governanceMode,
      "identity policy governance mode does not match the operator manifest"
    );
    assert.equal(
      operatorManifest.manifestSchema,
      identityPolicy.governanceMode === "solo_operator" ? "2" : "1",
      "operator manifest schema does not match the identity policy"
    );
    assert.ok(
      Date.parse(identityPolicy.validUntilUtc) >
        Date.now() + 31_000,
      "identity policy must remain valid beyond the SQL transaction timeout"
    );
    assertCanonicalPath(
      operatorManifest.reviewedNodePath,
      "reviewed Node.js path",
      realpathSync
    );
    assertCanonicalPath(
      operatorManifest.reviewedPsqlPath,
      "reviewed psql path",
      realpathSync
    );
    assert.equal(
      sha256(readFileSync(operatorManifest.reviewedNodePath)),
      operatorManifest.reviewedNodeSha256,
      "reviewed Node.js bytes do not match the operator manifest"
    );
    assert.equal(
      sha256(readFileSync(operatorManifest.reviewedPsqlPath)),
      operatorManifest.reviewedPsqlSha256,
      "reviewed psql bytes do not match the operator manifest"
    );

    const targetGuardBytes = readFixedRepositoryFile(
      TARGET_GUARD_PATH,
      { executable: true }
    );
    const psqlRunnerBytes = readFixedRepositoryFile(
      VALIDATED_PSQL_RUNNER_PATH
    );
    const mutationSqlBytes = readFixedRepositoryFile(MUTATION_SQL_PATH);

    mutation = {
      repositoryRoot: REPOSITORY_ROOT,
      targetGuardPath: TARGET_GUARD_PATH,
      targetGuardSha256: sha256(targetGuardBytes),
      psqlRunnerPath: VALIDATED_PSQL_RUNNER_PATH,
      psqlRunnerSha256: sha256(psqlRunnerBytes),
      mutationSqlPath: MUTATION_SQL_PATH,
      mutationSqlSha256: sha256(mutationSqlBytes),
      targetGuardEnvironment: {
        GALLR_EXPECTED_STAGING_PROJECT_REF: expectedStagingRef,
        GALLR_PRODUCTION_PROJECT_REF: productionRef,
        GALLR_STAGING_DATABASE_URL: stagingDatabaseUrl,
        GALLR_STAGING_REHEARSAL_CONFIRM: stagingConfirmation,
        GALLR_STAGING_EVIDENCE_DIR: stagingEvidenceDir,
        GALLR_STAGING_IDENTITY_POLICY_PATH: stagingIdentityPolicyPath,
      },
      stagingProjectRef: expectedStagingRef,
      stagingDatabaseUrl,
      stagingRefSha256: operatorManifest.stagingRefSha256,
      productionRefSha256: operatorManifest.productionRefSha256,
      repositoryCommit: operatorManifest.repositoryCommit,
      markerId: identityPolicy.markerId,
      governanceMode: identityPolicy.governanceMode,
      policyIssuedAtUtc: identityPolicy.issuedAtUtc,
      validUntilUtc: identityPolicy.validUntilUtc,
      identityPolicyPath: stagingIdentityPolicyPath,
      identityPolicySha256: identityPolicy.sha256,
      operatorManifestPath,
      operatorManifestSha256: sha256(operatorManifestBytes),
      reviewedNodePath: operatorManifest.reviewedNodePath,
      reviewedNodeSha256: operatorManifest.reviewedNodeSha256,
      reviewedPsqlPath: operatorManifest.reviewedPsqlPath,
      reviewedPsqlSha256: operatorManifest.reviewedPsqlSha256,
      fixtureExhibitionIds: Object.freeze([...fixtureExhibitionIds]),
      fixtureExhibitionIdChecksum: checksumExhibitionIds(fixtureExhibitionIds),
      fixtureManifestSha256: sha256(manifestBytes),
      fixturePrefix: fixtureManifest.fixture_prefix,
      eventId,
      targetId,
      publishedVersionId,
    };
  }

  const filters = [];
  if (eventId) filters.push(["event_id", `eq.${eventId}`]);
  if (featuredOnlyRaw === "1") filters.push(["is_featured", "eq.true"]);

  return {
    enabled: true,
    baseUrl,
    key,
    exact,
    minimum,
    minimumPageRequests,
    expectedAttempts,
    expectedIntegrityCalls,
    eventId,
    expectedCursor,
    featuredOnly: featuredOnlyRaw === "1",
    readerSource,
    targetEnvironment,
    filters,
    mutation,
  };
}

function fixedChildEnvironment() {
  return {
    HOME: "/nonexistent",
    LANG: "C",
    LC_ALL: "C",
    PATH: "/usr/bin:/bin:/usr/sbin:/sbin",
    TMPDIR: "/tmp",
    NODE_OPTIONS: "",
    NODE_PATH: "",
  };
}

function sanitizedTargetGuardEnvironment(mutation) {
  return {
    ...fixedChildEnvironment(),
    ...mutation.targetGuardEnvironment,
    BASH_ENV: "/dev/null",
    ENV: "/dev/null",
    GIT_CONFIG_GLOBAL: "/dev/null",
    GIT_CONFIG_NOSYSTEM: "1",
    GIT_OPTIONAL_LOCKS: "0",
    NODE_OPTIONS: "",
    NODE_PATH: "",
  };
}

function validatedPsqlEnvironment(mutation) {
  return {
    ...fixedChildEnvironment(),
    GALLR_VALIDATION_PROJECT_REF: mutation.stagingProjectRef,
    GALLR_VALIDATION_DATABASE_URL: mutation.stagingDatabaseUrl,
    GALLR_VALIDATION_REQUIRE_DIRECT: "true",
    GALLR_PSQL_APPNAME: MUTATION_PSQL_APPNAME,
    GALLR_PSQL_CONNECT_TIMEOUT: MUTATION_CONNECT_TIMEOUT_SECONDS,
    GALLR_PSQL_OPTIONS: MUTATION_PSQL_OPTIONS,
    GALLR_VALIDATED_PSQL_PATH: mutation.reviewedPsqlPath,
    GALLR_VALIDATED_PSQL_SHA256: mutation.reviewedPsqlSha256,
  };
}

function mutationPsqlArguments(mutation, expectedContentChecksum) {
  return [
    mutation.psqlRunnerPath,
    "--",
    "-Atq",
    "--set=ON_ERROR_STOP=1",
    `--set=expected_staging_ref_sha256=${mutation.stagingRefSha256}`,
    `--set=expected_production_ref_sha256=${mutation.productionRefSha256}`,
    `--set=expected_repository_commit=${mutation.repositoryCommit}`,
    `--set=expected_operator_manifest_sha256=${mutation.operatorManifestSha256}`,
    `--set=expected_marker_id=${mutation.markerId}`,
    `--set=expected_governance_mode=${mutation.governanceMode}`,
    `--set=expected_policy_issued_at_utc=${mutation.policyIssuedAtUtc}`,
    `--set=expected_valid_until_utc=${mutation.validUntilUtc}`,
    `--set=expected_policy_sha256=${mutation.identityPolicySha256}`,
    `--set=expected_fixture_prefix=${mutation.fixturePrefix}`,
    `--set=expected_event_id=${mutation.eventId}`,
    `--set=expected_target_id=${mutation.targetId}`,
    `--set=expected_published_version_id=${mutation.publishedVersionId}`,
    `--set=expected_content_checksum_sha256=${expectedContentChecksum}`,
    `--file=${mutation.mutationSqlPath}`,
  ];
}

function readMutationInputForRecheck(mutation, filePath) {
  if (
    filePath === mutation.targetGuardPath ||
    filePath === mutation.psqlRunnerPath ||
    filePath === mutation.mutationSqlPath
  ) {
    return readStableFileSnapshot(filePath, {
      executable: filePath === mutation.targetGuardPath,
      label: "fixed repository file",
    }).bytes;
  }
  if (filePath === mutation.operatorManifestPath) {
    return readStableFileSnapshot(filePath, {
      exactMode: 0o444,
      label: "operator manifest",
      maximumBytes: MAX_OPERATOR_MANIFEST_BYTES,
    }).bytes;
  }
  if (filePath === mutation.identityPolicyPath) {
    return readStableFileSnapshot(filePath, {
      exactMode: 0o400,
      label: "identity policy",
      maximumBytes: MAX_IDENTITY_POLICY_BYTES,
    }).bytes;
  }
  return fs.readFileSync(filePath);
}

async function executeValidatedMutation(
  mutation,
  context,
  {
    execFileImpl = execFileTracked,
    execGuardImpl = execFileTracked,
    readFileImpl = null,
  } = {}
) {
  try {
    const readReviewedInput = readFileImpl ||
      ((filePath) => readMutationInputForRecheck(mutation, filePath));
    assert.equal(context.attempt, 1, "mutation is restricted to fetch attempt one");
    assert.equal(
      context.pageRequest,
      2,
      "mutation is restricted to the second page of fetch attempt one"
    );
    assert.equal(
      context.readerSource,
      "canonical-v2",
      "mutation requires the canonical-v2 reader"
    );
    assert.match(
      context.expectedContentChecksum,
      SHA256_PATTERN,
      "mutation requires the first-attempt target-row checksum"
    );
    if (
      sha256(readReviewedInput(mutation.targetGuardPath)) !==
        mutation.targetGuardSha256 ||
      sha256(readReviewedInput(mutation.psqlRunnerPath)) !==
        mutation.psqlRunnerSha256 ||
      sha256(readReviewedInput(mutation.mutationSqlPath)) !==
        mutation.mutationSqlSha256 ||
      sha256(readReviewedInput(mutation.operatorManifestPath)) !==
        mutation.operatorManifestSha256 ||
      sha256(readReviewedInput(mutation.identityPolicyPath)) !==
        mutation.identityPolicySha256 ||
      sha256(readReviewedInput(mutation.reviewedNodePath)) !==
        mutation.reviewedNodeSha256
    ) {
      throw new Error("reviewed mutation inputs changed after validation");
    }

    const guardResult = await execGuardImpl(mutation.targetGuardPath, [], {
      cwd: mutation.repositoryRoot,
      env: sanitizedTargetGuardEnvironment(mutation),
      timeout: 30_000,
      maxBuffer: 64 * 1024,
      shell: false,
      windowsHide: true,
    });
    if (
      !guardResult ||
      guardResult.stdout !== `${TARGET_GUARD_PASS}\n` ||
      guardResult.stderr !== ""
    ) {
      throw new Error("immediate target guard did not return its exact pass record");
    }

    // Narrow replacement races after the guard and immediately before the only
    // database-writing child. The launcher independently revalidates the exact
    // psql path and digest before it creates the libpq transport.
    if (
      sha256(readReviewedInput(mutation.targetGuardPath)) !==
        mutation.targetGuardSha256 ||
      sha256(readReviewedInput(mutation.psqlRunnerPath)) !==
        mutation.psqlRunnerSha256 ||
      sha256(readReviewedInput(mutation.mutationSqlPath)) !==
        mutation.mutationSqlSha256 ||
      sha256(readReviewedInput(mutation.operatorManifestPath)) !==
        mutation.operatorManifestSha256 ||
      sha256(readReviewedInput(mutation.identityPolicyPath)) !==
        mutation.identityPolicySha256 ||
      sha256(readReviewedInput(mutation.reviewedNodePath)) !==
        mutation.reviewedNodeSha256
    ) {
      throw new Error("reviewed mutation inputs changed during target validation");
    }

    const mutationResult = await execFileImpl(
      mutation.reviewedNodePath,
      mutationPsqlArguments(mutation, context.expectedContentChecksum),
      {
        cwd: mutation.repositoryRoot,
        env: validatedPsqlEnvironment(mutation),
        timeout: MUTATION_PROCESS_TIMEOUT_MS,
        maxBuffer: 64 * 1024,
        shell: false,
        windowsHide: true,
      }
    );
    if (
      !mutationResult ||
      mutationResult.stdout !== `${MUTATION_COMPLETE_TOKEN}\n` ||
      mutationResult.stderr !== ""
    ) {
      throw new Error("validated mutation did not return its exact completion record");
    }
  } catch (error) {
    const reason = error && error.killed
      ? `timed out after ${MUTATION_PROCESS_TIMEOUT_MS}ms`
      : `failed${error && error.code !== undefined ? ` with code ${error.code}` : ""}`;
    throw new Error(`validated exhibition mutation ${reason}`);
  }
}

function createObservedFetch({
  fetchImpl,
  baseUrl,
  readerSource,
  mutation,
  executeMutation = executeValidatedMutation,
}) {
  assert.equal(typeof fetchImpl, "function", "fetch implementation is required");
  const evidence = {
    attempts: [],
    integrityCalls: [],
    mismatches: [],
    pageRequests: [],
    mutationInvocations: [],
  };
  let currentAttempt = 0;

  const observedFetch = async (url, options) => {
    const response = await fetchImpl(url, options);
    assertExactResponseUrl(response, url, "PostgREST integration evidence");
    assert.equal(
      new URL(response.url).origin,
      new URL(baseUrl).origin,
      "PostgREST response origin must match the configured target origin"
    );
    const parsed = new URL(url);
    const pathname = parsed.pathname;

    if (pathname.endsWith(`/rpc/${readerSource.integrityRpc}`)) {
      const payload = await response.clone().json();
      evidence.integrityCalls.push({ attempt: currentAttempt, payload });
      return response;
    }

    if (!pathname.endsWith(`/${readerSource.resource}`) || !response.ok) return response;

    const body = await response.clone().json();
    assert.ok(Array.isArray(body), "PostgREST exhibition page must be an array");
    if (parsed.searchParams.get("id") === null) {
      currentAttempt += 1;
      evidence.attempts.push({ number: currentAttempt, pageLengths: [], targetChecksums: [] });
    }
    assert.ok(currentAttempt > 0, "a cursor page cannot precede its attempt's first page");
    const attempt = evidence.attempts[currentAttempt - 1];
    const pageRequest = attempt.pageLengths.length + 1;
    attempt.pageLengths.push(body.length);
    evidence.pageRequests.push({
      attempt: currentAttempt,
      pageRequest,
      cursor: parsed.searchParams.get("id"),
      encodedSearch: parsed.search.slice(1),
      rowCount: body.length,
    });

    if (mutation) {
      const expectedPageIds = mutation.fixtureExhibitionIds.slice(
        (pageRequest - 1) * MUTATION_PAGE_SIZE,
        pageRequest * MUTATION_PAGE_SIZE
      );
      assert.deepEqual(
        body.map((row) => row && row.id),
        expectedPageIds,
        `attempt ${currentAttempt} page ${pageRequest} must exactly match the sealed fixture IDs`
      );
      const targets = body.filter((row) => row && row.id === mutation.targetId);
      if (targets.length > 0) {
        assert.equal(
          targets.length,
          1,
          "the mutation target must appear only once in a PostgREST page"
        );
        const [target] = targets;
        assert.match(
          target.content_checksum_sha256,
          /^[0-9a-f]{64}$/,
          "the mutation target must have a valid pre-mutation content checksum"
        );
        attempt.targetChecksums.push(target.content_checksum_sha256);
        if (currentAttempt === 1 && evidence.mutationInvocations.length === 0) {
          const invocation = {
            attempt: currentAttempt,
            pageRequest,
            targetId: mutation.targetId,
            checksumBeforeMutation: target.content_checksum_sha256,
            completed: false,
          };
          evidence.mutationInvocations.push(invocation);
          await executeMutation(mutation, {
            attempt: currentAttempt,
            pageRequest,
            readerSource: readerSource.name,
            baseUrl,
            expectedContentChecksum: target.content_checksum_sha256,
          });
          invocation.completed = true;
        }
      }
    }

    return response;
  };

  return { evidence, observedFetch };
}

function assertIntegrationEvidence(config, rows, evidence) {
  assert.ok(
    rows.length >= config.minimum,
    `expected at least ${config.minimum} exhibitions, got ${rows.length}`
  );
  if (config.exact !== null) assert.equal(rows.length, config.exact);
  assert.equal(new Set(rows.map((row) => row.id)).size, rows.length, "IDs must be unique");
  assert.equal(
    evidence.attempts.length,
    config.expectedAttempts,
    `expected ${config.expectedAttempts} complete-fetch attempt(s)`
  );
  assert.equal(
    evidence.integrityCalls.length,
    config.expectedIntegrityCalls,
    `expected ${config.expectedIntegrityCalls} integrity call(s)`
  );
  assert.equal(
    evidence.mismatches.length,
    config.expectedAttempts - 1,
    "each discarded attempt must report one retryable mismatch"
  );

  for (const attempt of evidence.attempts) {
    assert.ok(
      attempt.pageLengths.length >= config.minimumPageRequests,
      `attempt ${attempt.number}: expected at least ${config.minimumPageRequests} data requests, ` +
        `got ${attempt.pageLengths.length}`
    );
    assert.equal(
      attempt.pageLengths.at(-1),
      0,
      `attempt ${attempt.number} must request an explicit empty terminal page`
    );
  }

  if (config.expectedCursor) {
    const decodedCursor = `gt.${config.expectedCursor}`;
    const encodedCursorPair = new URLSearchParams([["id", decodedCursor]]).toString();
    const matchingRequest = evidence.pageRequests.find(
      (request) => request.cursor === decodedCursor
    );
    assert.ok(
      matchingRequest,
      `expected a page request after reserved-character cursor '${config.expectedCursor}'`
    );
    assert.ok(
      matchingRequest.encodedSearch.split("&").includes(encodedCursorPair),
      `expected encoded cursor pair '${encodedCursorPair}' on the wire`
    );
  }

  if (config.mutation) {
    assert.equal(evidence.mutationInvocations.length, 1, "mutation must run exactly once");
    assert.equal(evidence.mutationInvocations[0].completed, true, "mutation must commit");
    assert.equal(
      evidence.mutationInvocations[0].pageRequest,
      2,
      "mutation must run while the sealed 750th row is observed on page two"
    );
    assert.equal(
      evidence.mutationInvocations[0].checksumBeforeMutation,
      evidence.attempts[0].targetChecksums[0],
      "mutation must bind the target checksum observed on attempt one's second page"
    );
    assert.ok(
      evidence.mismatches[0] instanceof ReaderIntegrityMismatchError &&
        evidence.mismatches[0].field === "catalog_checksum_sha256",
      "the first attempt must be discarded for a canonical catalog checksum mismatch"
    );
    for (const attempt of evidence.attempts) {
      assert.deepEqual(
        attempt.pageLengths,
        [500, 500, 205, 0],
        `attempt ${attempt.number} must fetch the exact sealed 1,205-row pagination shape`
      );
      assert.equal(
        attempt.targetChecksums.length,
        1,
        `target ID must appear exactly once in attempt ${attempt.number}`
      );
    }
    const before = evidence.attempts[0].targetChecksums[0];
    const after = evidence.attempts.at(-1).targetChecksums[0];
    assert.match(before, /^[0-9a-f]{64}$/, "target checksum before mutation is invalid");
    assert.match(after, /^[0-9a-f]{64}$/, "target checksum after mutation is invalid");
    assert.notEqual(after, before, "the same target ID must have a new checksum on retry");
    const finalTarget = rows.find((row) => row.id === config.mutation.targetId);
    assert.ok(finalTarget, "the mutation target must remain in the final verified collection");
    assert.equal(finalTarget.content_checksum_sha256, after);
    assert.deepEqual(
      rows.map((row) => row.id),
      config.mutation.fixtureExhibitionIds,
      "final fetched IDs must exactly match the sealed, sorted fixture ID set"
    );
    assert.equal(
      checksumExhibitionIds(rows),
      config.mutation.fixtureExhibitionIdChecksum,
      "final fetched ID checksum must match the sealed fixture manifest"
    );
  }
}

function createEvidenceSummary(config, rows, evidence) {
  const idChecksum = checksumExhibitionIds(rows);
  const catalogChecksum = config.readerSource.integrityMode === "id-and-content"
    ? checksumExhibitionContent(rows)
    : null;
  return {
    schema_version: 1,
    reader_source: config.readerSource.name,
    target_environment: config.targetEnvironment,
    scope: {
      event_id: config.eventId || null,
      featured_only: config.featuredOnly,
    },
    final_snapshot: {
      row_count: rows.length,
      id_checksum_sha256: idChecksum,
      catalog_checksum_sha256: catalogChecksum,
    },
    attempts: evidence.attempts.map((attempt) => ({
      attempt: attempt.number,
      page_lengths: attempt.pageLengths,
      target_content_checksums: attempt.targetChecksums,
    })),
    page_requests: evidence.pageRequests,
    integrity_calls: evidence.integrityCalls,
    discarded_attempts: evidence.mismatches.map((mismatch) => ({
      name: mismatch.name,
      field: mismatch.field || null,
      rpc_value: mismatch.expected,
      fetched_value: mismatch.actual,
    })),
    mutation: config.mutation
      ? {
          target_id: config.mutation.targetId,
          published_version_id: config.mutation.publishedVersionId,
          fixture_prefix: config.mutation.fixturePrefix,
          fixture_exhibition_id_checksum_sha256:
            config.mutation.fixtureExhibitionIdChecksum,
          fixture_manifest_sha256: config.mutation.fixtureManifestSha256,
          operator_manifest_sha256: config.mutation.operatorManifestSha256,
          mutation_sql_sha256: config.mutation.mutationSqlSha256,
          invocation_count: evidence.mutationInvocations.length,
          checksum_before: evidence.attempts[0].targetChecksums[0],
          checksum_after: evidence.attempts.at(-1).targetChecksums[0],
        }
      : null,
  };
}

async function runIntegration(
  config,
  { fetchImpl = global.fetch, executeMutation = executeValidatedMutation } = {}
) {
  const { evidence, observedFetch } = createObservedFetch({
    fetchImpl,
    baseUrl: config.baseUrl,
    readerSource: config.readerSource,
    mutation: config.mutation,
    executeMutation,
  });

  const rows = await fetchAllExhibitions({
    baseUrl: config.baseUrl,
    key: config.key,
    fetchImpl: observedFetch,
    readerSource: config.readerSource,
    filters: config.filters,
    onIntegrityMismatch: (error) => evidence.mismatches.push(error),
  });

  assertIntegrationEvidence(config, rows, evidence);
  return {
    evidence,
    evidenceSummary: createEvidenceSummary(config, rows, evidence),
    rows,
  };
}

async function main() {
  const config = readIntegrationConfig();
  if (!config.enabled) {
    console.log("[fetch-exhibitions.integration.test] skipped (set GALLR_POSTGREST_INTEGRATION=1)");
    return;
  }

  const { evidence, evidenceSummary, rows } = await runIntegration(config);
  const requestSummary = evidence.attempts
    .map((attempt) => attempt.pageLengths.length)
    .join("+");
  console.log(
    `[fetch-exhibitions.integration.test] PASS (${config.readerSource.name}` +
    `${config.eventId ? `, event=${config.eventId}` : ""}${config.featuredOnly ? ", featured" : ""}): ` +
    `${rows.length} rows, ${evidence.attempts.length} fetch attempt(s), ` +
    `${evidence.integrityCalls.length} integrity call(s), ${requestSummary} data requests, ` +
    `terminal empty page(s), count/checksum verified` +
    `${config.expectedCursor ? ", reserved cursor verified" : ""}` +
    `${config.mutation ? ", same-ID mutation retry verified" : ""}`
  );
  console.log(
    `[fetch-exhibitions.integration.evidence] ${JSON.stringify(evidenceSummary)}`
  );
}

if (require.main === module) {
  main().catch((error) => {
    const signal = trackedExternalSignal();
    if (signal) {
      process.exitCode = signalExitCode(signal);
    } else {
      console.error(error);
      process.exitCode = 1;
    }
  });
}

module.exports = {
  MUTATION_ATTESTATION,
  assertIntegrationEvidence,
  createEvidenceSummary,
  createObservedFetch,
  execFileTracked,
  executeValidatedMutation,
  mutationPsqlArguments,
  readIntegrationConfig,
  runIntegration,
  sanitizedTargetGuardEnvironment,
  trackedExternalSignal,
  validatedPsqlEnvironment,
};
