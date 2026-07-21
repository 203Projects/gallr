// Focused, network-free tests for the opt-in real PostgREST evidence harness.
// Run: node tests/fetch-exhibitions.integration-harness.test.js

const assert = require("assert").strict;
const crypto = require("crypto");
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
  executeExternalMutationHook,
  readIntegrationConfig,
  runIntegration,
} = require("./fetch-exhibitions.integration.test.js");

function checksum(value) {
  return crypto.createHash("sha256").update(value, "utf8").digest("hex");
}

function response(body, { total = Array.isArray(body) ? body.length : 1, start = 0 } = {}) {
  const snapshot = JSON.parse(JSON.stringify(body));
  const contentRange = Array.isArray(snapshot) && snapshot.length > 0
    ? `${start}-${start + snapshot.length - 1}/${total}`
    : `*/${total}`;
  return {
    ok: true,
    status: 200,
    headers: {
      get(name) {
        return name.toLowerCase() === "content-range" ? contentRange : null;
      },
    },
    json: async () => JSON.parse(JSON.stringify(snapshot)),
    clone() {
      return response(snapshot, { total, start });
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
    SUPABASE_ANON_KEY: "publishable-test-key",
    GALLR_EXPECTED_EXHIBITION_COUNT: "0",
    GALLR_EXPECTED_MIN_EXHIBITIONS: "0",
    GALLR_EXPECTED_STAGING_PROJECT_REF: stagingRef,
    GALLR_PRODUCTION_PROJECT_REF: "bbbbbbbbbbbbbbbbbbbb",
    GALLR_STAGING_REHEARSAL_CONFIRM: stagingRef,
    ...overrides,
  };
}

const HOOK_PATH = "/tmp/gallr-secure-hook/staging-hook";
const MANIFEST_PATH = "/tmp/gallr-fixture-evidence/fixtures-catalog-test01/manifest.json";
const TARGET_GUARD_PATH = "/tmp/gallr-reviewed-repository/scripts/staging-rehearsal/assert-disposable-clone-target.sh";
const HOOK_BYTES = Buffer.from("#!/bin/sh\nexit 0\n", "utf8");
const TARGET_GUARD_BYTES = Buffer.from("#!/bin/sh\nprintf 'PASS: target guard test\\n'\n", "utf8");
const FIXTURE_RUN_ID = "catalog-test01";
const FIXTURE_PREFIX = `gallr-rehearsal-${FIXTURE_RUN_ID}-`;
const FIXTURE_EVENT_ID = `${FIXTURE_PREFIX}event.catalog.v2,(load):한글`;
const FIXTURE_CURSOR_ID = `${FIXTURE_PREFIX}catalog-0500.cursor,(reserved):한글`;
const FIXTURE_MUTATION_ID = `${FIXTURE_PREFIX}catalog-0750.mutate,(same-id):한글`;

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
    },
    ...overrides,
  };
}

function secureMutationFilesystem({
  hookBytes = HOOK_BYTES,
  manifest = fixtureManifest(),
  hookSymlink = false,
  hookMode = 0o100700,
  hookParentMode = 0o40700,
} = {}) {
  const manifestBytes = Buffer.from(JSON.stringify(manifest), "utf8");
  const fileStat = (mode, symbolicLink = false) => ({
    uid: 501,
    mode,
    nlink: 1,
    isFile: () => !symbolicLink,
    isSymbolicLink: () => symbolicLink,
  });
  const directoryStat = (mode) => ({
    uid: 501,
    mode,
    isDirectory: () => true,
  });
  return {
    accessSync: () => {},
    getuid: () => 501,
    lstatSync: (filePath) => {
      if (filePath === HOOK_PATH) return fileStat(hookMode, hookSymlink);
      if (filePath === MANIFEST_PATH) return fileStat(0o100400, false);
      if (filePath === TARGET_GUARD_PATH) return fileStat(0o100700, false);
      throw new Error("unexpected lstat path");
    },
    statSync: (filePath) => {
      if (filePath === path.dirname(HOOK_PATH)) return directoryStat(hookParentMode);
      if (filePath === path.dirname(MANIFEST_PATH)) return directoryStat(0o40700);
      if (filePath === path.dirname(TARGET_GUARD_PATH)) return directoryStat(0o40755);
      throw new Error("unexpected stat path");
    },
    realpathSync: (filePath) => filePath,
    readFileSync: (filePath) => {
      if (filePath === HOOK_PATH) return hookBytes;
      if (filePath === MANIFEST_PATH) return manifestBytes;
      if (filePath === TARGET_GUARD_PATH) return TARGET_GUARD_BYTES;
      throw new Error("unexpected read path");
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
    GALLR_POSTGREST_MUTATION_HOOK: HOOK_PATH,
    GALLR_POSTGREST_MUTATION_HOOK_SHA256: checksum(HOOK_BYTES),
    GALLR_POSTGREST_FIXTURE_MANIFEST: MANIFEST_PATH,
    GALLR_POSTGREST_TARGET_GUARD: TARGET_GUARD_PATH,
    GALLR_POSTGREST_TARGET_GUARD_SHA256: checksum(TARGET_GUARD_BYTES),
    GALLR_STAGING_DATABASE_URL: "postgresql://postgres:test@db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-test-supabase-ca.crt",
    GALLR_STAGING_EVIDENCE_DIR: "/tmp/gallr-fixture-evidence",
    GALLR_STAGING_IDENTITY_POLICY_PATH: "/tmp/gallr-identity/policy.txt",
    GALLR_POSTGREST_MUTATION_ATTESTATION: MUTATION_ATTESTATION,
    GALLR_POSTGREST_MUTATION_TARGET_ID: FIXTURE_MUTATION_ID,
    GALLR_EXPECTED_FETCH_ATTEMPTS: "2",
    GALLR_EXPECTED_INTEGRITY_CALLS: "2",
    ...overrides,
  });
}

function executableMutation(overrides = {}) {
  return {
    hookPath: "/tmp/staging-hook",
    hookSha256: checksum(HOOK_BYTES),
    targetGuardPath: TARGET_GUARD_PATH,
    targetGuardSha256: checksum(TARGET_GUARD_BYTES),
    targetGuardEnvironment: {
      GALLR_EXPECTED_STAGING_PROJECT_REF: "aaaaaaaaaaaaaaaaaaaa",
      GALLR_PRODUCTION_PROJECT_REF: "bbbbbbbbbbbbbbbbbbbb",
      GALLR_STAGING_DATABASE_URL: "postgresql://postgres:test@db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-test-supabase-ca.crt",
      GALLR_STAGING_REHEARSAL_CONFIRM: "aaaaaaaaaaaaaaaaaaaa",
      GALLR_STAGING_EVIDENCE_DIR: "/tmp/gallr-fixture-evidence",
      GALLR_STAGING_IDENTITY_POLICY_PATH: "/tmp/gallr-identity/policy.txt",
    },
    targetId: "target-id",
    timeoutMs: 1234,
    ...overrides,
  };
}

(async () => {
  // Mutation coordination is inert unless the integration test itself is enabled.
  {
    let filesystemChecks = 0;
    const config = readIntegrationConfig({
      GALLR_POSTGREST_MUTATION_HOOK: "/should/not/run",
      GALLR_POSTGREST_MUTATION_ATTESTATION: MUTATION_ATTESTATION,
    }, {
      accessSync: () => { filesystemChecks += 1; },
      statSync: () => { filesystemChecks += 1; },
    });
    assert.deepEqual(config, { enabled: false });
    assert.equal(filesystemChecks, 0);
  }

  // A stable run retains one-attempt defaults and does not inspect a hook.
  {
    const config = readIntegrationConfig(integrationEnv());
    assert.equal(config.expectedAttempts, 1);
    assert.equal(config.expectedIntegrityCalls, 1);
    assert.equal(config.mutation, null);
  }

  // Local mode remains available for network-isolated PostgREST proofs but is
  // restricted to loopback and can never enable the mutation hook.
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
  }

  // Partial, unattested, legacy, or non-executable hook configuration fails
  // before any request can leave the process.
  {
    assert.throws(
      () => readIntegrationConfig(integrationEnv({
        GALLR_POSTGREST_MUTATION_HOOK: "/tmp/staging-hook",
      })),
      /attestation/
    );
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv({
        GALLR_POSTGREST_MUTATION_HOOK: "relative-hook",
      })),
      /absolute executable path/
    );
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv({
        GALLR_EXHIBITION_SOURCE: "legacy",
      }), secureMutationFilesystem()),
      /canonical-v2/
    );
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv(), {
        lstatSync: () => { throw new Error("missing"); },
        accessSync: () => {},
      }),
      /must exist and be readable/
    );
  }

  // An attested executable requires explicit retry evidence counts.
  {
    const filesystem = secureMutationFilesystem();
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv({
        GALLR_EXPECTED_FETCH_ATTEMPTS: "",
        GALLR_EXPECTED_INTEGRITY_CALLS: "",
      }), filesystem),
      /explicit expected fetch-attempt and integrity-call counts/
    );
    const config = readIntegrationConfig(mutationIntegrationEnv(), filesystem);
    assert.deepEqual(config.mutation, {
      hookPath: HOOK_PATH,
      hookSha256: checksum(HOOK_BYTES),
      targetGuardPath: TARGET_GUARD_PATH,
      targetGuardSha256: checksum(TARGET_GUARD_BYTES),
      targetGuardEnvironment: {
        GALLR_EXPECTED_STAGING_PROJECT_REF: "aaaaaaaaaaaaaaaaaaaa",
        GALLR_PRODUCTION_PROJECT_REF: "bbbbbbbbbbbbbbbbbbbb",
        GALLR_STAGING_DATABASE_URL: "postgresql://postgres:test@db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-test-supabase-ca.crt",
        GALLR_STAGING_REHEARSAL_CONFIRM: "aaaaaaaaaaaaaaaaaaaa",
        GALLR_STAGING_EVIDENCE_DIR: "/tmp/gallr-fixture-evidence",
        GALLR_STAGING_IDENTITY_POLICY_PATH: "/tmp/gallr-identity/policy.txt",
      },
      fixtureManifestSha256: checksum(
        Buffer.from(JSON.stringify(fixtureManifest()), "utf8")
      ),
      fixturePrefix: FIXTURE_PREFIX,
      targetId: FIXTURE_MUTATION_ID,
      timeoutMs: 30_000,
    });
  }

  // Mutation evidence refuses identical refs, a mismatched confirmation, and
  // a URL that does not identify the approved staging project.
  {
    const filesystem = secureMutationFilesystem();
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv({
        GALLR_PRODUCTION_PROJECT_REF: "aaaaaaaaaaaaaaaaaaaa",
      }), filesystem),
      /distinct staging and production/
    );
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv({
        GALLR_STAGING_REHEARSAL_CONFIRM: "bbbbbbbbbbbbbbbbbbbb",
      }), filesystem),
      /must exactly match/
    );
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv({
        SUPABASE_URL: "https://cccccccccccccccccccc.supabase.co",
      }), filesystem),
      /explicitly confirmed staging project/
    );
    assert.throws(
      () => readIntegrationConfig(mutationIntegrationEnv({
        SUPABASE_URL: "https://aaaaaaaaaaaaaaaaaaaa.supabase.co:8443",
      }), filesystem),
      /default HTTPS port/
    );
  }

  // The mutation is bound to immutable hook bytes and the sealed fixture
  // manifest; symlinks, writable parents, swapped targets, and bad hashes fail.
  {
    assert.throws(
      () => readIntegrationConfig(
        mutationIntegrationEnv(),
        secureMutationFilesystem({ hookSymlink: true })
      ),
      /must not be a symbolic link/
    );
    assert.throws(
      () => readIntegrationConfig(
        mutationIntegrationEnv(),
        secureMutationFilesystem({ hookParentMode: 0o40777 })
      ),
      /parent must have mode 0700/
    );
    assert.throws(
      () => readIntegrationConfig(
        mutationIntegrationEnv({
          GALLR_POSTGREST_MUTATION_HOOK_SHA256: "0".repeat(64),
        }),
        secureMutationFilesystem()
      ),
      /do not match the reviewed SHA-256/
    );
    assert.throws(
      () => readIntegrationConfig(
        mutationIntegrationEnv(),
        secureMutationFilesystem({
          manifest: fixtureManifest({ staging_ref_sha256: "0".repeat(64) }),
        })
      ),
      /staging fingerprint does not match/
    );
    assert.throws(
      () => readIntegrationConfig(
        mutationIntegrationEnv({
          GALLR_POSTGREST_MUTATION_TARGET_ID: `${FIXTURE_PREFIX}different`,
        }),
        secureMutationFilesystem()
      ),
      /exactly match the sealed fixture manifest/
    );
  }

  // The hook uses execFile without a shell and receives only non-secret context.
  {
    let captured;
    let guardCaptured;
    await executeExternalMutationHook(executableMutation(), {
      attempt: 1,
      pageRequest: 2,
      readerSource: "canonical-v2",
      baseUrl: "http://127.0.0.1:55321",
    }, {
      cwd: "/tmp",
      sourceEnv: {
        PATH: "/usr/bin:/bin",
        LANG: "C",
        SUPABASE_ANON_KEY: "do-not-forward",
        SUPABASE_SERVICE_ROLE_KEY: "do-not-forward-either",
        DATABASE_URL: "also-do-not-forward",
        GALLR_STAGING_DATABASE_URL: "do-not-forward-database-url",
      },
      readFileImpl: (filePath) => filePath === TARGET_GUARD_PATH
        ? TARGET_GUARD_BYTES
        : HOOK_BYTES,
      execGuardImpl: async (file, args, options) => {
        guardCaptured = { file, args, options };
        return {
          stdout: "PASS: independent policy and disposable-clone marker identify staging\n",
          stderr: "",
        };
      },
      execFileImpl: async (file, args, options) => {
        captured = { file, args, options };
      },
    });
    assert.equal(captured.file, "/tmp/staging-hook");
    assert.equal(guardCaptured.file, TARGET_GUARD_PATH);
    assert.equal(guardCaptured.options.shell, false);
    assert.equal(
      guardCaptured.options.env.GALLR_STAGING_DATABASE_URL,
      "postgresql://postgres:test@db.aaaaaaaaaaaaaaaaaaaa.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=%2Ftmp%2Fgallr-test-supabase-ca.crt"
    );
    assert.equal(guardCaptured.options.env.BASH_ENV, "/dev/null");
    assert.deepEqual(captured.args, []);
    assert.equal(captured.options.shell, false);
    assert.equal(captured.options.timeout, 1234);
    assert.equal(captured.options.env.PATH, "/usr/bin:/bin");
    assert.equal(captured.options.env.GALLR_MUTATION_TARGET_ID, "target-id");
    assert.equal(captured.options.env.GALLR_MUTATION_ATTEMPT, "1");
    assert.equal(captured.options.env.GALLR_MUTATION_PAGE_REQUEST, "2");
    assert.equal(captured.options.env.GALLR_MUTATION_READER_SOURCE, "canonical-v2");
    assert.equal(captured.options.env.GALLR_MUTATION_BASE_URL, "http://127.0.0.1:55321");
    assert.equal(captured.options.env.SUPABASE_ANON_KEY, undefined);
    assert.equal(captured.options.env.SUPABASE_SERVICE_ROLE_KEY, undefined);
    assert.equal(captured.options.env.DATABASE_URL, undefined);
    assert.equal(captured.options.env.GALLR_STAGING_DATABASE_URL, undefined);

    let changedHookExecuted = false;
    await assert.rejects(
      executeExternalMutationHook(executableMutation(), {
        attempt: 1,
        pageRequest: 1,
        readerSource: "canonical-v2",
        baseUrl: "http://127.0.0.1:55321",
      }, {
        readFileImpl: () => Buffer.from("changed", "utf8"),
        execFileImpl: async () => { changedHookExecuted = true; },
      }),
      /attested exhibition mutation hook failed/
    );
    assert.equal(changedHookExecuted, false);

    await assert.rejects(
      executeExternalMutationHook(executableMutation(), {
        attempt: 1,
        pageRequest: 1,
        readerSource: "canonical-v2",
        baseUrl: "http://127.0.0.1:55321",
      }, {
        readFileImpl: (filePath) => filePath === TARGET_GUARD_PATH
          ? TARGET_GUARD_BYTES
          : HOOK_BYTES,
        execGuardImpl: async () => ({
          stdout: "PASS: independent policy and disposable-clone marker identify staging\n",
          stderr: "",
        }),
        execFileImpl: async () => {
          const error = new Error("secret stderr");
          error.code = 7;
          error.stderr = "secret service credential";
          throw error;
        },
      }),
      (error) => error.message === "attested exhibition mutation hook failed with code 7"
    );
  }

  // A same-ID content mutation after the target's first response snapshot
  // invalidates attempt one, triggers exactly one full retry, and proves the
  // final attempt carries the target's new checksum.
  {
    const targetId = "id-0002";
    const originalRows = ["id-0001", targetId, "id-0003"].map((id) => ({
      id,
      content_checksum_sha256: checksum(`before:${id}`),
    }));
    let databaseRows = originalRows.map((row) => ({ ...row }));
    let mutationCalls = 0;
    const fetchImpl = async (url) => {
      const parsed = new URL(url);
      if (parsed.pathname.endsWith("/rpc/exhibition_catalog_v2_integrity")) {
        return response([{
          row_count: databaseRows.length,
          id_checksum_sha256: checksumExhibitionIds(databaseRows),
          catalog_checksum_sha256: checksumExhibitionContent(databaseRows),
        }]);
      }
      assert.ok(parsed.pathname.endsWith("/exhibition_catalog_v2"));
      const cursor = parsed.searchParams.get("id");
      const afterId = cursor === null ? null : cursor.slice(3);
      const start = afterId === null
        ? 0
        : databaseRows.findIndex((row) => row.id > afterId);
      const safeStart = start === -1 ? databaseRows.length : start;
      const page = databaseRows.slice(safeStart, safeStart + 500);
      return response(page, { total: databaseRows.length, start: safeStart });
    };
    const config = {
      enabled: true,
      baseUrl: "http://127.0.0.1:55321",
      key: "publishable-test-key",
      exact: 3,
      minimum: 3,
      minimumPageRequests: 2,
      expectedAttempts: 2,
      expectedIntegrityCalls: 2,
      eventId: "",
      expectedCursor: "",
      featuredOnly: false,
      readerSource: CANONICAL_V2_EXHIBITION_READER_SOURCE,
      targetEnvironment: "staging",
      filters: [],
      mutation: {
        hookPath: "/tmp/staging-hook",
        hookSha256: checksum(HOOK_BYTES),
        fixtureManifestSha256: checksum("fixture-manifest"),
        fixturePrefix: "fixture-prefix-",
        targetId,
        timeoutMs: 30_000,
      },
    };
    const { evidence, evidenceSummary, rows } = await runIntegration(config, {
      fetchImpl,
      executeMutation: async (_mutation, context) => {
        mutationCalls += 1;
        assert.equal(context.attempt, 1);
        assert.equal(context.pageRequest, 1);
        databaseRows = databaseRows.map((row) => row.id === targetId
          ? { ...row, content_checksum_sha256: checksum(`after:${row.id}`) }
          : row);
      },
    });

    assert.equal(mutationCalls, 1);
    assert.equal(evidence.attempts.length, 2);
    assert.equal(evidence.integrityCalls.length, 2);
    assert.equal(evidence.mismatches.length, 1);
    assert.deepEqual(evidence.attempts.map((attempt) => attempt.pageLengths), [[3, 0], [3, 0]]);
    assert.equal(rows.find((row) => row.id === targetId).content_checksum_sha256, checksum(`after:${targetId}`));
    assert.deepEqual(evidenceSummary.final_snapshot, {
      row_count: 3,
      id_checksum_sha256: checksumExhibitionIds(rows),
      catalog_checksum_sha256: checksumExhibitionContent(rows),
    });
    assert.equal(evidenceSummary.integrity_calls.length, 2);
    assert.equal(evidenceSummary.discarded_attempts[0].field, "catalog_checksum_sha256");
    assert.deepEqual(
      evidenceSummary.page_requests.map((request) => request.rowCount),
      [3, 0, 3, 0]
    );
    assert.equal(evidenceSummary.mutation.target_id, targetId);
    assert.notEqual(
      evidenceSummary.mutation.checksum_before,
      evidenceSummary.mutation.checksum_after
    );
  }

  console.log("fetch-exhibitions integration harness tests passed");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
