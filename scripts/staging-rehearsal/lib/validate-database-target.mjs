#!/usr/bin/env node

// Validate a Supabase Postgres URI without ever printing it. Inputs are passed
// through the child environment so credentials never appear in argv.

const projectRef = String(process.env.GALLR_VALIDATION_PROJECT_REF || "");
const databaseUrl = String(process.env.GALLR_VALIDATION_DATABASE_URL || "");
const requireDirectRaw = String(
  process.env.GALLR_VALIDATION_REQUIRE_DIRECT || "false"
);
const projectRefPattern = /^[a-z0-9]{20}$/;

function fail() {
  console.error("database URL does not identify the expected staging project");
  process.exit(1);
}

if (!projectRefPattern.test(projectRef) || databaseUrl.length === 0) fail();
if (!new Set(["true", "false"]).has(requireDirectRaw)) fail();
if (databaseUrl.trim() !== databaseUrl || /[\u0000-\u001f\u007f]/u.test(databaseUrl)) {
  fail();
}

let parsed;
try {
  parsed = new URL(databaseUrl);
} catch (_error) {
  fail();
}

if (!new Set(["postgres:", "postgresql:"]).has(parsed.protocol)) fail();
if (parsed.hash !== "" || parsed.pathname !== "/postgres") fail();

const queryKeys = [...parsed.searchParams.keys()];
if (
  queryKeys.length !== 2 ||
  queryKeys.filter((key) => key === "sslmode").length !== 1 ||
  queryKeys.filter((key) => key === "sslrootcert").length !== 1
) {
  fail();
}
const sslModes = parsed.searchParams.getAll("sslmode");
if (sslModes.length !== 1 || sslModes[0] !== "verify-full") fail();

const sslRootCerts = parsed.searchParams.getAll("sslrootcert");
if (sslRootCerts.length !== 1) fail();
const sslRootCert = sslRootCerts[0];
if (
  !sslRootCert.startsWith("/") ||
  /[\u0000-\u001f\u007f\u0085\u2028\u2029]/u.test(sslRootCert)
) {
  fail();
}

const direct =
  parsed.hostname === `db.${projectRef}.supabase.co` &&
  parsed.username === "postgres" &&
  (parsed.port === "" || parsed.port === "5432");

const poolerHostname =
  /^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.pooler\.supabase\.com$/.test(
    parsed.hostname
  );
const pooler =
  poolerHostname &&
  parsed.username === `postgres.${projectRef}` &&
  (parsed.port === "" || parsed.port === "5432" || parsed.port === "6543");

if (!direct && !pooler) fail();
if (requireDirectRaw === "true" && !direct) fail();
