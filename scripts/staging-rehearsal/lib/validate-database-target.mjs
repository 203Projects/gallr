#!/usr/bin/env node

// Validate a Supabase Postgres URI without ever printing it. The launcher
// imports the same parser, so the early guard and the final psql handoff cannot
// interpret the credential-bearing URI differently.

function fail() {
  console.error("database URL does not identify the expected staging project");
  process.exit(1);
}

try {
  const { validateDatabaseTarget } = await import("./database-target.mjs");
  const target = validateDatabaseTarget({
    projectRef: String(process.env.GALLR_VALIDATION_PROJECT_REF || ""),
    databaseUrl: String(process.env.GALLR_VALIDATION_DATABASE_URL || ""),
    requireDirect: String(
      process.env.GALLR_VALIDATION_REQUIRE_DIRECT || "false"
    ),
    expectedCertificateSha256: String(
      process.env.GALLR_VALIDATION_SSLROOTCERT_SHA256 || ""
    ),
  });
  target.password = "";
} catch (_error) {
  fail();
}
