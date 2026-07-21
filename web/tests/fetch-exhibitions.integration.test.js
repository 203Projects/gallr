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
// The absolute executable hook must update only the attested isolated staging
// fixture, preserve the target ID/filter membership, and return after its
// transaction commits. The harness invokes it without a shell and does not
// forward SUPABASE_* keys or other credential environment variables:
// GALLR_POSTGREST_MUTATION_HOOK=/absolute/path/to/executable \
// GALLR_POSTGREST_MUTATION_HOOK_SHA256='<reviewed-lowercase-sha256>' \
// GALLR_POSTGREST_FIXTURE_MANIFEST='/absolute/path/to/sealed/manifest.json' \
// GALLR_POSTGREST_MUTATION_ATTESTATION=I_CONFIRM_THIS_IS_AN_ISOLATED_STAGING_FIXTURE \
// GALLR_EXPECTED_STAGING_PROJECT_REF='<20-character-staging-ref>' \
// GALLR_PRODUCTION_PROJECT_REF='<different-20-character-production-ref>' \
// GALLR_STAGING_REHEARSAL_CONFIRM='<same-staging-ref>' \
// GALLR_POSTGREST_MUTATION_TARGET_ID='<id returned on attempt one>' \
// GALLR_EXPECTED_FETCH_ATTEMPTS=2 \
// GALLR_EXPECTED_INTEGRITY_CALLS=2
// The manifest must be the fixture lifecycle's operator-owned mode-0400 file;
// its parent and the hook's parent must be mode 0700. The hook, target, filter,
// cursor, count, and project fingerprints are bound to that manifest, and hook
// bytes are hashed again immediately before execution. Run from an isolated
// operator session; a malicious same-UID path race is outside this guard's
// threat model. The hook gets no arguments. Its
// sanitized environment contains only ordinary
// process settings plus GALLR_MUTATION_TARGET_ID, GALLR_MUTATION_ATTEMPT,
// GALLR_MUTATION_PAGE_REQUEST, GALLR_MUTATION_READER_SOURCE, and
// GALLR_MUTATION_BASE_URL. It never receives the anonymous or service key.

const assert = require("assert").strict;
const childProcess = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const util = require("util");
const {
  ReaderIntegrityMismatchError,
  checksumExhibitionContent,
  checksumExhibitionIds,
  fetchAllExhibitions,
} = require("../scripts/fetch-exhibitions.js");
const { resolveExhibitionReaderSource } = require(
  "../scripts/lib/exhibition-reader-source.js"
);

const execFileAsync = util.promisify(childProcess.execFile);
const MUTATION_ATTESTATION = "I_CONFIRM_THIS_IS_AN_ISOLATED_STAGING_FIXTURE";
const DEFAULT_MUTATION_TIMEOUT_MS = 30_000;
const MAX_MUTATION_TIMEOUT_MS = 120_000;
const PROJECT_REF_PATTERN = /^[a-z0-9]{20}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const MAX_HOOK_BYTES = 1024 * 1024;
const MAX_FIXTURE_MANIFEST_BYTES = 5 * 1024 * 1024;
const TARGET_GUARD_PASS = "PASS: independent policy and disposable-clone marker identify staging";

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

function readIntegrationConfig(
  env = process.env,
  filesystem = {}
) {
  if (env.GALLR_POSTGREST_INTEGRATION !== "1") return { enabled: false };

  const accessSync = filesystem.accessSync || fs.accessSync;
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

  const hookPath = trimmed(env, "GALLR_POSTGREST_MUTATION_HOOK");
  const attestation = trimmed(env, "GALLR_POSTGREST_MUTATION_ATTESTATION");
  const expectedStagingRef = trimmed(env, "GALLR_EXPECTED_STAGING_PROJECT_REF");
  const productionRef = trimmed(env, "GALLR_PRODUCTION_PROJECT_REF");
  const stagingConfirmation = trimmed(env, "GALLR_STAGING_REHEARSAL_CONFIRM");
  const targetId = trimmed(env, "GALLR_POSTGREST_MUTATION_TARGET_ID");
  const hookSha256 = trimmed(env, "GALLR_POSTGREST_MUTATION_HOOK_SHA256");
  const fixtureManifestPath = trimmed(env, "GALLR_POSTGREST_FIXTURE_MANIFEST");
  const targetGuardPath = trimmed(env, "GALLR_POSTGREST_TARGET_GUARD");
  const targetGuardSha256 = trimmed(env, "GALLR_POSTGREST_TARGET_GUARD_SHA256");
  const stagingDatabaseUrl = trimmed(env, "GALLR_STAGING_DATABASE_URL");
  const stagingEvidenceDir = trimmed(env, "GALLR_STAGING_EVIDENCE_DIR");
  const stagingIdentityPolicyPath = trimmed(env, "GALLR_STAGING_IDENTITY_POLICY_PATH");
  const timeoutRaw = trimmed(env, "GALLR_POSTGREST_MUTATION_HOOK_TIMEOUT_MS");
  const mutationRequested = [
    hookPath,
    attestation,
    targetId,
    hookSha256,
    fixtureManifestPath,
    targetGuardPath,
    targetGuardSha256,
    timeoutRaw,
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
    assert.ok(hookPath, "GALLR_POSTGREST_MUTATION_HOOK is required for mutation evidence");
    assert.ok(
      path.isAbsolute(hookPath),
      "GALLR_POSTGREST_MUTATION_HOOK must be an absolute executable path"
    );
    assert.equal(
      attestation,
      MUTATION_ATTESTATION,
      "mutation hook requires the exact isolated-staging-fixture attestation"
    );
    assert.equal(
      targetEnvironment,
      "staging",
      "same-ID mutation evidence is forbidden against the local target mode"
    );
    assert.ok(targetId, "GALLR_POSTGREST_MUTATION_TARGET_ID is required");
    assert.match(
      hookSha256,
      SHA256_PATTERN,
      "GALLR_POSTGREST_MUTATION_HOOK_SHA256 must be a lowercase SHA-256"
    );
    assert.ok(
      path.isAbsolute(fixtureManifestPath),
      "GALLR_POSTGREST_FIXTURE_MANIFEST must be an absolute path"
    );
    assert.ok(
      path.isAbsolute(targetGuardPath),
      "GALLR_POSTGREST_TARGET_GUARD must be an absolute executable path"
    );
    assert.match(
      targetGuardSha256,
      SHA256_PATTERN,
      "GALLR_POSTGREST_TARGET_GUARD_SHA256 must be a lowercase SHA-256"
    );
    assert.ok(stagingDatabaseUrl, "GALLR_STAGING_DATABASE_URL is required for the immediate target guard");
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

    let hookStat;
    let hookParentStat;
    let manifestStat;
    let manifestParentStat;
    let targetGuardStat;
    let targetGuardParentStat;
    let hookBytes;
    let manifestBytes;
    let targetGuardBytes;
    try {
      hookStat = lstatSync(hookPath);
      hookParentStat = statSync(path.dirname(hookPath));
      manifestStat = lstatSync(fixtureManifestPath);
      manifestParentStat = statSync(path.dirname(fixtureManifestPath));
      targetGuardStat = lstatSync(targetGuardPath);
      targetGuardParentStat = statSync(path.dirname(targetGuardPath));
      accessSync(hookPath, fs.constants.X_OK);
      accessSync(targetGuardPath, fs.constants.X_OK);
      hookBytes = readFileSync(hookPath);
      manifestBytes = readFileSync(fixtureManifestPath);
      targetGuardBytes = readFileSync(targetGuardPath);
    } catch (_error) {
      assert.fail("mutation hook, fixture manifest, and target guard must exist and be readable");
    }
    assertCanonicalPath(hookPath, "GALLR_POSTGREST_MUTATION_HOOK", realpathSync);
    assertCanonicalPath(
      fixtureManifestPath,
      "GALLR_POSTGREST_FIXTURE_MANIFEST",
      realpathSync
    );
    assertCanonicalPath(
      targetGuardPath,
      "GALLR_POSTGREST_TARGET_GUARD",
      realpathSync
    );
    assert.equal(
      hookStat.isSymbolicLink(),
      false,
      "GALLR_POSTGREST_MUTATION_HOOK must not be a symbolic link"
    );
    assert.ok(hookStat.isFile(), "GALLR_POSTGREST_MUTATION_HOOK must be a file");
    assert.equal(
      manifestStat.isSymbolicLink(),
      false,
      "GALLR_POSTGREST_FIXTURE_MANIFEST must not be a symbolic link"
    );
    assert.ok(
      manifestStat.isFile(),
      "GALLR_POSTGREST_FIXTURE_MANIFEST must be a file"
    );
    assert.equal(
      targetGuardStat.isSymbolicLink(),
      false,
      "GALLR_POSTGREST_TARGET_GUARD must not be a symbolic link"
    );
    assert.ok(targetGuardStat.isFile(), "GALLR_POSTGREST_TARGET_GUARD must be a file");
    assert.ok(hookParentStat.isDirectory(), "mutation hook parent must be a directory");
    assert.ok(
      manifestParentStat.isDirectory(),
      "fixture manifest parent must be a directory"
    );
    assert.ok(targetGuardParentStat.isDirectory(), "target guard parent must be a directory");
    assertCurrentUserOwned(hookStat, "mutation hook", getuid);
    assertCurrentUserOwned(hookParentStat, "mutation hook parent", getuid);
    assertCurrentUserOwned(manifestStat, "fixture manifest", getuid);
    assertCurrentUserOwned(manifestParentStat, "fixture manifest parent", getuid);
    assertCurrentUserOwned(targetGuardStat, "target guard", getuid);
    assertCurrentUserOwned(targetGuardParentStat, "target guard parent", getuid);
    assert.equal(hookStat.nlink, 1, "mutation hook must not be hard-linked");
    assert.equal(manifestStat.nlink, 1, "fixture manifest must not be hard-linked");
    assert.equal(targetGuardStat.nlink, 1, "target guard must not be hard-linked");
    assert.equal(
      hookStat.mode & 0o022,
      0,
      "mutation hook must not be group- or world-writable"
    );
    assert.equal(
      hookParentStat.mode & 0o777,
      0o700,
      "mutation hook parent must have mode 0700"
    );
    assert.equal(
      manifestStat.mode & 0o777,
      0o400,
      "fixture manifest must have mode 0400"
    );
    assert.equal(
      manifestParentStat.mode & 0o777,
      0o700,
      "fixture manifest parent must have mode 0700"
    );
    assert.equal(
      targetGuardStat.mode & 0o022,
      0,
      "target guard must not be group- or world-writable"
    );
    assert.equal(
      targetGuardParentStat.mode & 0o022,
      0,
      "target guard parent must not be group- or world-writable"
    );
    assert.ok(hookBytes.length <= MAX_HOOK_BYTES, "mutation hook is unexpectedly large");
    assert.ok(
      manifestBytes.length <= MAX_FIXTURE_MANIFEST_BYTES,
      "fixture manifest is unexpectedly large"
    );
    const actualHookSha256 = sha256(hookBytes);
    assert.equal(
      actualHookSha256,
      hookSha256,
      "mutation hook bytes do not match the reviewed SHA-256"
    );
    assert.equal(
      sha256(targetGuardBytes),
      targetGuardSha256,
      "target guard bytes do not match the reviewed SHA-256"
    );

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

    mutation = {
      hookPath: realpathSync(hookPath),
      hookSha256,
      targetGuardPath: realpathSync(targetGuardPath),
      targetGuardSha256,
      targetGuardEnvironment: {
        GALLR_EXPECTED_STAGING_PROJECT_REF: expectedStagingRef,
        GALLR_PRODUCTION_PROJECT_REF: productionRef,
        GALLR_STAGING_DATABASE_URL: stagingDatabaseUrl,
        GALLR_STAGING_REHEARSAL_CONFIRM: stagingConfirmation,
        GALLR_STAGING_EVIDENCE_DIR: stagingEvidenceDir,
        GALLR_STAGING_IDENTITY_POLICY_PATH: stagingIdentityPolicyPath,
      },
      fixtureManifestSha256: sha256(manifestBytes),
      fixturePrefix: fixtureManifest.fixture_prefix,
      targetId,
      timeoutMs: parseInteger(
        "GALLR_POSTGREST_MUTATION_HOOK_TIMEOUT_MS",
        timeoutRaw,
        String(DEFAULT_MUTATION_TIMEOUT_MS),
        { minimum: 1, maximum: MAX_MUTATION_TIMEOUT_MS }
      ),
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

function sanitizedHookEnvironment(sourceEnv, context) {
  const env = {};
  for (const name of ["PATH", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP"]) {
    if (sourceEnv[name]) env[name] = sourceEnv[name];
  }
  return {
    ...env,
    GALLR_MUTATION_TARGET_ID: context.targetId,
    GALLR_MUTATION_ATTEMPT: String(context.attempt),
    GALLR_MUTATION_PAGE_REQUEST: String(context.pageRequest),
    GALLR_MUTATION_READER_SOURCE: context.readerSource,
    GALLR_MUTATION_BASE_URL: context.baseUrl,
  };
}

function sanitizedTargetGuardEnvironment(sourceEnv, mutation) {
  const env = {};
  for (const name of ["PATH", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP"]) {
    if (sourceEnv[name]) env[name] = sourceEnv[name];
  }
  return {
    ...env,
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

async function executeExternalMutationHook(
  mutation,
  context,
  {
    execFileImpl = execFileAsync,
    execGuardImpl = execFileAsync,
    readFileImpl = fs.readFileSync,
    sourceEnv = process.env,
    cwd = process.cwd(),
  } = {}
) {
  try {
    if (sha256(readFileImpl(mutation.hookPath)) !== mutation.hookSha256) {
      throw new Error("reviewed mutation hook changed after validation");
    }
    if (sha256(readFileImpl(mutation.targetGuardPath)) !== mutation.targetGuardSha256) {
      throw new Error("reviewed target guard changed after validation");
    }
    const guardResult = await execGuardImpl(mutation.targetGuardPath, [], {
      cwd,
      env: sanitizedTargetGuardEnvironment(sourceEnv, mutation),
      timeout: 30_000,
      maxBuffer: 64 * 1024,
      shell: false,
      windowsHide: true,
    });
    if (
      !guardResult ||
      String(guardResult.stdout || "").trim() !== TARGET_GUARD_PASS ||
      String(guardResult.stderr || "").trim() !== ""
    ) {
      throw new Error("immediate target guard did not return its exact pass record");
    }
    // Re-read both executable paths after the guard and immediately before the
    // mutation. This narrows accidental path replacement to the final exec
    // boundary; both files also require canonical, single-link, owned paths.
    if (sha256(readFileImpl(mutation.targetGuardPath)) !== mutation.targetGuardSha256) {
      throw new Error("reviewed target guard changed during execution");
    }
    if (sha256(readFileImpl(mutation.hookPath)) !== mutation.hookSha256) {
      throw new Error("reviewed mutation hook changed during target validation");
    }
    await execFileImpl(mutation.hookPath, [], {
      cwd,
      env: sanitizedHookEnvironment(sourceEnv, {
        ...context,
        targetId: mutation.targetId,
      }),
      timeout: mutation.timeoutMs,
      maxBuffer: 64 * 1024,
      shell: false,
      windowsHide: true,
    });
  } catch (error) {
    const reason = error && error.killed
      ? `timed out after ${mutation.timeoutMs}ms`
      : `failed${error && error.code !== undefined ? ` with code ${error.code}` : ""}`;
    throw new Error(`attested exhibition mutation hook ${reason}`);
  }
}

function createObservedFetch({
  fetchImpl,
  baseUrl,
  readerSource,
  mutation,
  executeMutation = executeExternalMutationHook,
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
    assert.equal(evidence.mutationInvocations.length, 1, "mutation hook must run exactly once");
    assert.equal(evidence.mutationInvocations[0].completed, true, "mutation hook must commit");
    assert.ok(
      evidence.mismatches[0] instanceof ReaderIntegrityMismatchError &&
        evidence.mismatches[0].field === "catalog_checksum_sha256",
      "the first attempt must be discarded for a canonical catalog checksum mismatch"
    );
    for (const attempt of evidence.attempts) {
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
          fixture_prefix: config.mutation.fixturePrefix,
          fixture_manifest_sha256: config.mutation.fixtureManifestSha256,
          mutation_hook_sha256: config.mutation.hookSha256,
          invocation_count: evidence.mutationInvocations.length,
          checksum_before: evidence.attempts[0].targetChecksums[0],
          checksum_after: evidence.attempts.at(-1).targetChecksums[0],
        }
      : null,
  };
}

async function runIntegration(
  config,
  { fetchImpl = global.fetch, executeMutation = executeExternalMutationHook } = {}
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
    console.error(error);
    process.exitCode = 1;
  });
}

module.exports = {
  MUTATION_ATTESTATION,
  assertIntegrationEvidence,
  createEvidenceSummary,
  createObservedFetch,
  executeExternalMutationHook,
  readIntegrationConfig,
  runIntegration,
  sanitizedHookEnvironment,
  sanitizedTargetGuardEnvironment,
};
