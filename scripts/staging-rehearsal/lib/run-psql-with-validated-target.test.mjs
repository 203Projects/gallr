import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  assertCertificateSourceUnchanged,
  validateDatabaseTarget,
} from "./database-target.mjs";

const launcherPath = fileURLToPath(
  new URL("./run-psql-with-validated-target.mjs", import.meta.url)
);
const stagingRef = "aaaaaaaaaaaaaaaaaaaa";
const productionRef = "bbbbbbbbbbbbbbbbbbbb";
const password = String.raw`pa:ss@word%+\backslash`;
const certificatePem = `-----BEGIN CERTIFICATE-----
MIIDxDCCAqygAwIBAgIUbLxMod62P2ktCiAkxnKJwtE9VPYwDQYJKoZIhvcNAQEL
BQAwazELMAkGA1UEBhMCVVMxEDAOBgNVBAgMB0RlbHdhcmUxEzARBgNVBAcMCk5l
dyBDYXN0bGUxFTATBgNVBAoMDFN1cGFiYXNlIEluYzEeMBwGA1UEAwwVU3VwYWJh
c2UgUm9vdCAyMDIxIENBMB4XDTIxMDQyODEwNTY1M1oXDTMxMDQyNjEwNTY1M1ow
azELMAkGA1UEBhMCVVMxEDAOBgNVBAgMB0RlbHdhcmUxEzARBgNVBAcMCk5ldyBD
YXN0bGUxFTATBgNVBAoMDFN1cGFiYXNlIEluYzEeMBwGA1UEAwwVU3VwYWJhc2Ug
Um9vdCAyMDIxIENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqQXW
QyHOB+qR2GJobCq/CBmQ40G0oDmCC3mzVnn8sv4XNeWtE5XcEL0uVih7Jo4Dkx1Q
DmGHBH1zDfgs2qXiLb6xpw/CKQPypZW1JssOTMIfQppNQ87K75Ya0p25Y3ePS2t2
GtvHxNjUV6kjOZjEn2yWEcBdpOVCUYBVFBNMB4YBHkNRDa/+S4uywAoaTWnCJLUi
cvTlHmMw6xSQQn1UfRQHk50DMCEJ7Cy1RxrZJrkXXRP3LqQL2ijJ6F4yMfh+Gyb4
O4XajoVj/+R4GwywKYrrS8PrSNtwxr5StlQO8zIQUSMiq26wM8mgELFlS/32Uclt
NaQ1xBRizkzpZct9DwIDAQABo2AwXjALBgNVHQ8EBAMCAQYwHQYDVR0OBBYEFKjX
uXY32CztkhImng4yJNUtaUYsMB8GA1UdIwQYMBaAFKjXuXY32CztkhImng4yJNUt
aUYsMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAB8spzNn+4VU
tVxbdMaX+39Z50sc7uATmus16jmmHjhIHz+l/9GlJ5KqAMOx26mPZgfzG7oneL2b
VW+WgYUkTT3XEPFWnTp2RJwQao8/tYPXWEJDc0WVQHrpmnWOFKU/d3MqBgBm5y+6
jB81TU/RG2rVerPDWP+1MMcNNy0491CTL5XQZ7JfDJJ9CCmXSdtTl4uUQnSuv/Qx
Cea13BX2ZgJc7Au30vihLhub52De4P/4gonKsNHYdbWjg7OWKwNv/zitGDVDB9Y2
CMTyZKG3XEu5Ghl1LEnI3QmEKsqaCLv12BnVjbkSeZsMnevJPs1Ye6TjjJwdik5P
o/bKiIz+Fq8=
-----END CERTIFICATE-----
`;

const temporaryParent = fs.mkdtempSync(
  path.join(fs.realpathSync.native(os.tmpdir()), "gallr-launcher-test-")
);
fs.chmodSync(temporaryParent, 0o700);
const certificatePath = path.join(temporaryParent, "root.crt");
fs.writeFileSync(certificatePath, certificatePem, { mode: 0o400 });
fs.chmodSync(certificatePath, 0o400);
const fakeBin = path.join(temporaryParent, "bin");
fs.mkdirSync(fakeBin, { mode: 0o700 });
const fakePsqlPath = path.join(fakeBin, "psql");
const fakePsqlImplementationPath = path.join(fakeBin, "fake-psql.mjs");
const fakeDescendantImplementationPath = path.join(
  fakeBin,
  "fake-psql-descendant.mjs"
);
const capturePath = path.join(temporaryParent, "capture.json");
const controlPath = path.join(temporaryParent, "control.json");
const descendantStatePath = path.join(
  temporaryParent,
  "descendant-state.json"
);

function sha256File(pathname) {
  const descriptor = fs.openSync(pathname, fs.constants.O_RDONLY);
  const hash = crypto.createHash("sha256");
  const buffer = Buffer.allocUnsafe(1024 * 1024);
  try {
    for (;;) {
      const count = fs.readSync(
        descriptor,
        buffer,
        0,
        buffer.length,
        null
      );
      if (count === 0) break;
      hash.update(buffer.subarray(0, count));
    }
  } finally {
    buffer.fill(0);
    fs.closeSync(descriptor);
  }
  return hash.digest("hex");
}

const fakePsql = `#!/bin/sh
exec ${JSON.stringify(process.execPath)} ${JSON.stringify(fakePsqlImplementationPath)} "$@"
`;
const fakePsqlImplementation = `
import fs from "node:fs";
import { spawn } from "node:child_process";
const control = JSON.parse(fs.readFileSync(${JSON.stringify(controlPath)}, "utf8"));
if (control.ignoreTerm) {
  process.on("SIGTERM", () => {});
}
const forbidden = [
  "PGPASSWORD", "PGHOSTADDR", "PGSERVICE", "PGSERVICEFILE",
  "GALLR_VALIDATION_DATABASE_URL", "GALLR_STAGING_DATABASE_URL",
  "DATABASE_URL", "SUPABASE_DB_PASSWORD", "NODE_OPTIONS",
  "OPENSSL_CONF", "SSL_CERT_FILE", "PGSSLCOMPRESSION", "PGGEQO"
];
const passfile = fs.readFileSync(process.env.PGPASSFILE, "utf8");
const expectedPassfile = "db.${stagingRef}.supabase.co:5432:postgres:postgres:pa\\\\:ss@word%+\\\\\\\\backslash\\n";
const argv = process.argv.slice(2);
const sqlFilePaths = [];
for (let index = 0; index < argv.length; index += 1) {
  if (argv[index] === "-f" || argv[index] === "--file") {
    sqlFilePaths.push(argv[index + 1]);
    index += 1;
  } else if (argv[index].startsWith("--file=")) {
    sqlFilePaths.push(argv[index].slice("--file=".length));
  }
}
const sqlSnapshots = sqlFilePaths.map((sqlPath) => {
  const contents = fs.readFileSync(sqlPath, "utf8");
  const include = contents.match(/^\\\\ir[\\t ]+([^\\r\\n]+)$/m);
  const includedPath = include
    ? new URL(include[1].trim(), "file://" + sqlPath).pathname
    : null;
  return {
    path: sqlPath,
    mode: fs.statSync(sqlPath).mode & 0o777,
    contents,
    includedPath,
    includedMode: includedPath ? fs.statSync(includedPath).mode & 0o777 : null,
    includedContents: includedPath
      ? fs.readFileSync(includedPath, "utf8")
      : null,
  };
});
const capture = {
  argv,
  target: {
    host: process.env.PGHOST,
    port: process.env.PGPORT,
    database: process.env.PGDATABASE,
    user: process.env.PGUSER,
    sslmode: process.env.PGSSLMODE,
    sslcertmode: process.env.PGSSLCERTMODE,
    gssencmode: process.env.PGGSSENCMODE,
    appname: process.env.PGAPPNAME,
    timeout: process.env.PGCONNECT_TIMEOUT,
    options: process.env.PGOPTIONS,
  },
  forbiddenPresent: forbidden.filter((key) =>
    Object.prototype.hasOwnProperty.call(process.env, key)
  ),
  launcherVariablesPresent: Object.keys(process.env).filter((key) =>
    key.startsWith("GALLR_PSQL_") ||
    key.startsWith("GALLR_VALIDATION_") ||
    key.startsWith("SUPABASE_")
  ),
  passfileMatches: passfile === expectedPassfile,
  passfilePath: process.env.PGPASSFILE,
  passfileMode: fs.statSync(process.env.PGPASSFILE).mode & 0o777,
  certificatePath: process.env.PGSSLROOTCERT,
  certificateMode: fs.statSync(process.env.PGSSLROOTCERT).mode & 0o777,
  certificateMatches:
    fs.readFileSync(process.env.PGSSLROOTCERT, "utf8") ===
    ${JSON.stringify(certificatePem)},
  sqlSnapshots,
};
fs.writeFileSync(${JSON.stringify(capturePath)}, JSON.stringify(capture));
process.stdout.write("fake psql stdout\\n");
process.stderr.write("fake psql stderr\\n");
if (control.spawnDescendant) {
  spawn(
    process.execPath,
    [${JSON.stringify(fakeDescendantImplementationPath)}],
    { env: process.env, stdio: "ignore" }
  );
  const readinessDeadline = Date.now() + 5000;
  const readinessTimer = setInterval(() => {
    if (fs.existsSync(${JSON.stringify(descendantStatePath)})) {
      clearInterval(readinessTimer);
      process.exit(0);
    }
    if (Date.now() >= readinessDeadline) {
      clearInterval(readinessTimer);
      process.exit(91);
    }
  }, 5);
} else if (control.signalParent) {
  setTimeout(() => process.kill(process.ppid, control.signalParent), 25);
  setInterval(() => {}, 1000);
} else if (control.signalSelf) {
  process.kill(process.pid, control.signalSelf);
} else {
  process.exit(Number(control.exitCode || 0));
}
`;
const fakeDescendantImplementation = `
import fs from "node:fs";
process.on("SIGTERM", () => {});
const passfilePath = process.env.PGPASSFILE;
const certificatePath = process.env.PGSSLROOTCERT;
const state = {
  pid: process.pid,
  passfilePath,
  certificatePath,
  passfileReadable:
    fs.readFileSync(passfilePath, "utf8").length > 0,
  certificateReadable:
    fs.readFileSync(certificatePath, "utf8").includes(
      "BEGIN CERTIFICATE"
    ),
};
fs.writeFileSync(
  ${JSON.stringify(descendantStatePath)},
  JSON.stringify(state),
  { mode: 0o600 }
);
setInterval(() => {}, 1000);
`;
fs.writeFileSync(fakePsqlPath, fakePsql, { mode: 0o700 });
fs.chmodSync(fakePsqlPath, 0o700);
fs.writeFileSync(fakePsqlImplementationPath, fakePsqlImplementation, {
  mode: 0o400,
});
fs.chmodSync(fakePsqlImplementationPath, 0o400);
fs.writeFileSync(
  fakeDescendantImplementationPath,
  fakeDescendantImplementation,
  { mode: 0o400 }
);
fs.chmodSync(fakeDescendantImplementationPath, 0o400);
const fakePsqlSha256 = sha256File(fakePsqlPath);

function encodeUriComponent(value) {
  return encodeURIComponent(value).replace(
    /[!'()*]/g,
    (character) =>
      `%${character.charCodeAt(0).toString(16).toUpperCase()}`
  );
}

function databaseUrl({
  ref = stagingRef,
  encodedPassword = encodeUriComponent(password),
  query = `sslmode=verify-full&sslrootcert=${encodeUriComponent(certificatePath)}`,
  pooler = false,
} = {}) {
  if (pooler) {
    return `postgresql://postgres.${ref}:${encodedPassword}@aws-0-test.pooler.supabase.com:6543/postgres?${query}`;
  }
  return `postgresql://postgres:${encodedPassword}@db.${ref}.supabase.co:5432/postgres?${query}`;
}

function launcherEnvironment(overrides = {}) {
  return {
    ...process.env,
    PATH: `${fakeBin}${path.delimiter}${process.env.PATH}`,
    TEST_CAPTURE_PATH: capturePath,
    GALLR_VALIDATION_PROJECT_REF: stagingRef,
    GALLR_VALIDATION_DATABASE_URL: databaseUrl(),
    GALLR_VALIDATION_REQUIRE_DIRECT: "true",
    GALLR_PSQL_APPNAME: "gallr-launcher-test",
    GALLR_PSQL_CONNECT_TIMEOUT: "15",
    GALLR_PSQL_OPTIONS:
      "-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000",
    GALLR_VALIDATED_PSQL_PATH: fakePsqlPath,
    GALLR_VALIDATED_PSQL_SHA256: fakePsqlSha256,
    PGHOST: "production.invalid",
    PGHOSTADDR: "127.0.0.1",
    PGDATABASE: "production",
    PGUSER: "poisoned",
    PGPASSWORD: "must-not-reach-child",
    PGSERVICE: "poisoned-service",
    PGSERVICEFILE: "/must/not/reach/psql",
    DATABASE_URL: databaseUrl({ ref: productionRef }),
    GALLR_STAGING_DATABASE_URL: databaseUrl(),
    SUPABASE_DB_PASSWORD: "must-not-reach-child",
    GALLR_PSQL_POISONED_FUTURE_SETTING: "must-not-reach-child",
    OPENSSL_CONF: "/must/not/reach/psql",
    SSL_CERT_FILE: "/must/not/reach/psql",
    PGGSSENCMODE: "prefer",
    PGSSLCERTMODE: "allow",
    PGSSLCOMPRESSION: "1",
    PGGEQO: "off",
    ...overrides,
  };
}

function launch(
  args = [
    "--",
    "-Atq",
    "-F",
    "\t",
    "--set=ON_ERROR_STOP=1",
    "-v",
    "safe_value=one",
    "-c",
    "select 1",
  ],
  overrides = {}
) {
  fs.writeFileSync(
    controlPath,
    JSON.stringify({
      exitCode: overrides.TEST_EXIT_CODE || "0",
      signalSelf: overrides.TEST_SIGNAL || null,
      signalParent: overrides.TEST_SIGNAL_PARENT || null,
      ignoreTerm: overrides.TEST_IGNORE_TERM === "true",
      spawnDescendant: overrides.TEST_SPAWN_DESCENDANT === "true",
    }),
    { mode: 0o600 }
  );
  return spawnSync(process.execPath, [launcherPath, ...args], {
    encoding: "utf8",
    env: launcherEnvironment(overrides),
  });
}

function processIsAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error && error.code === "ESRCH") return false;
    throw error;
  }
}

try {
  const successful = launch(undefined, { TEST_EXIT_CODE: "23" });
  assert.equal(successful.status, 23);
  assert.equal(successful.signal, null);
  assert.equal(successful.stdout, "fake psql stdout\n");
  assert.equal(successful.stderr, "fake psql stderr\n");

  const capture = JSON.parse(fs.readFileSync(capturePath, "utf8"));
  assert.deepEqual(capture.argv, [
    "-X",
    "--no-password",
    "-Atq",
    "-F",
    "\t",
    "--set=ON_ERROR_STOP=1",
    "-v",
    "safe_value=one",
    "-c",
    "select 1",
  ]);
  assert.deepEqual(capture.target, {
    host: `db.${stagingRef}.supabase.co`,
    port: "5432",
    database: "postgres",
    user: "postgres",
    sslmode: "verify-full",
    sslcertmode: "disable",
    gssencmode: "disable",
    appname: "gallr-launcher-test",
    timeout: "15",
    options:
      "-c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=3000",
  });
  assert.deepEqual(capture.forbiddenPresent, []);
  assert.deepEqual(capture.launcherVariablesPresent, []);
  assert.equal(capture.passfileMatches, true);
  assert.equal(capture.passfileMode, 0o600);
  assert.equal(capture.certificateMode, 0o400);
  assert.equal(capture.certificateMatches, true);
  assert.notEqual(capture.certificatePath, certificatePath);
  assert.equal(fs.existsSync(capture.passfilePath), false);
  assert.equal(fs.existsSync(capture.certificatePath), false);
  assert.equal(fs.existsSync(path.dirname(capture.passfilePath)), false);

  const poisonedTemporaryRoot = path.join(temporaryParent, "poisoned-tmp");
  fs.mkdirSync(poisonedTemporaryRoot, { mode: 0o777 });
  fs.chmodSync(poisonedTemporaryRoot, 0o777);
  const poisonedTemporaryResult = launch(undefined, {
    TMPDIR: poisonedTemporaryRoot,
  });
  assert.equal(poisonedTemporaryResult.status, 0);
  const poisonedTemporaryCapture = JSON.parse(
    fs.readFileSync(capturePath, "utf8")
  );
  assert.equal(
    poisonedTemporaryCapture.passfilePath.startsWith(
      `${poisonedTemporaryRoot}${path.sep}`
    ),
    false
  );

  const poisonedBin = path.join(temporaryParent, "poisoned-bin");
  fs.mkdirSync(poisonedBin, { mode: 0o700 });
  const poisonedPsqlMarker = path.join(temporaryParent, "poisoned-psql-ran");
  const poisonedPsql = path.join(poisonedBin, "psql");
  fs.writeFileSync(
    poisonedPsql,
    `#!/bin/sh\n: > ${JSON.stringify(poisonedPsqlMarker)}\nexit 99\n`,
    { mode: 0o700 }
  );
  fs.chmodSync(poisonedPsql, 0o700);
  const poisonedPathResult = launch(undefined, {
    PATH: `${poisonedBin}${path.delimiter}${process.env.PATH}`,
  });
  assert.equal(poisonedPathResult.status, 0);
  assert.equal(fs.existsSync(poisonedPsqlMarker), false);

  const sqlSourceRoot = path.join(temporaryParent, "sql-source");
  fs.mkdirSync(sqlSourceRoot, { mode: 0o700 });
  const includedSqlPath = path.join(sqlSourceRoot, "tracked-state.sql");
  const rootSqlPath = path.join(sqlSourceRoot, "provision.sql");
  const includedSql = "select 'included snapshot';\n";
  const rootSql = "\\set ON_ERROR_STOP on\nselect 1;\n\\ir tracked-state.sql\n";
  fs.writeFileSync(includedSqlPath, includedSql, { mode: 0o400 });
  fs.writeFileSync(rootSqlPath, rootSql, { mode: 0o400 });
  fs.chmodSync(includedSqlPath, 0o400);
  fs.chmodSync(rootSqlPath, 0o400);
  const fileResult = launch(["--", "-Atq", "-f", rootSqlPath]);
  assert.equal(fileResult.status, 0, fileResult.stderr);
  const fileCapture = JSON.parse(fs.readFileSync(capturePath, "utf8"));
  assert.equal(fileCapture.sqlSnapshots.length, 1);
  const snapshot = fileCapture.sqlSnapshots[0];
  assert.notEqual(snapshot.path, rootSqlPath);
  assert.equal(path.basename(snapshot.path), path.basename(rootSqlPath));
  assert.equal(snapshot.mode, 0o400);
  assert.equal(snapshot.contents, rootSql);
  assert.equal(snapshot.includedMode, 0o400);
  assert.equal(snapshot.includedContents, includedSql);
  assert.equal(fs.existsSync(snapshot.path), false);
  assert.equal(fs.existsSync(snapshot.includedPath), false);

  const directReconnectPath = path.join(sqlSourceRoot, "reconnect.sql");
  fs.writeFileSync(
    directReconnectPath,
    "select 1; \\connect postgresql://production.invalid/postgres\n",
    { mode: 0o400 }
  );
  fs.chmodSync(directReconnectPath, 0o400);
  const includedReconnectPath = path.join(sqlSourceRoot, "evil-include.sql");
  fs.writeFileSync(includedReconnectPath, "\\c production\n", { mode: 0o400 });
  fs.chmodSync(includedReconnectPath, 0o400);
  const rootReconnectPath = path.join(sqlSourceRoot, "root-evil.sql");
  fs.writeFileSync(rootReconnectPath, "\\ir evil-include.sql\n", {
    mode: 0o400,
  });
  fs.chmodSync(rootReconnectPath, 0o400);
  const dynamicIncludePath = path.join(sqlSourceRoot, "dynamic-include.sql");
  fs.writeFileSync(dynamicIncludePath, "\\ir :dynamic_path\n", { mode: 0o400 });
  fs.chmodSync(dynamicIncludePath, 0o400);
  const shellEscapePath = path.join(sqlSourceRoot, "shell-escape.sql");
  fs.writeFileSync(shellEscapePath, "\\! /usr/bin/env\n", { mode: 0o400 });
  fs.chmodSync(shellEscapePath, 0o400);
  const localCopyPath = path.join(sqlSourceRoot, "local-copy.sql");
  fs.writeFileSync(
    localCopyPath,
    "\\copy public.exhibitions to program '/usr/bin/env'\n",
    { mode: 0o400 }
  );
  fs.chmodSync(localCopyPath, 0o400);
  const localOutputPath = path.join(sqlSourceRoot, "local-output.sql");
  fs.writeFileSync(localOutputPath, "\\g /tmp/credential-leak\n", {
    mode: 0o400,
  });
  fs.chmodSync(localOutputPath, 0o400);
  const backquotedSetPath = path.join(sqlSourceRoot, "backquoted-set.sql");
  fs.writeFileSync(
    backquotedSetPath,
    "\\set stolen `/usr/bin/cat \"$PGPASSFILE\"`\n",
    { mode: 0o400 }
  );
  fs.chmodSync(backquotedSetPath, 0o400);
  const formFeedConnectPath = path.join(
    sqlSourceRoot,
    "form-feed-connect.sql"
  );
  fs.writeFileSync(formFeedConnectPath, "\\connect\fproduction\n", {
    mode: 0o400,
  });
  fs.chmodSync(formFeedConnectPath, 0o400);
  const verticalTabIncludePath = path.join(
    sqlSourceRoot,
    "vertical-tab-include.sql"
  );
  fs.writeFileSync(verticalTabIncludePath, "\\ir\u000btracked-state.sql\n", {
    mode: 0o400,
  });
  fs.chmodSync(verticalTabIncludePath, 0o400);
  const unapprovedMetaCommandPath = path.join(
    sqlSourceRoot,
    "unapproved-meta-command.sql"
  );
  fs.writeFileSync(unapprovedMetaCommandPath, "\\prompt secret\n", {
    mode: 0o400,
  });
  fs.chmodSync(unapprovedMetaCommandPath, 0o400);
  const defaultQuitPath = path.join(sqlSourceRoot, "default-quit.sql");
  fs.writeFileSync(defaultQuitPath, "\\quit\nselect 1;\n", { mode: 0o400 });
  fs.chmodSync(defaultQuitPath, 0o400);
  const zeroQuitPath = path.join(sqlSourceRoot, "zero-quit.sql");
  fs.writeFileSync(zeroQuitPath, "\\quit 0\nselect 1;\n", { mode: 0o400 });
  fs.chmodSync(zeroQuitPath, 0o400);
  const disabledErrorStopPath = path.join(
    sqlSourceRoot,
    "disabled-error-stop.sql"
  );
  fs.writeFileSync(disabledErrorStopPath, "\\set ON_ERROR_STOP off\n", {
    mode: 0o400,
  });
  fs.chmodSync(disabledErrorStopPath, 0o400);
  const unsupportedPsetPath = path.join(
    sqlSourceRoot,
    "unsupported-pset.sql"
  );
  fs.writeFileSync(unsupportedPsetPath, "\\pset pager always\n", {
    mode: 0o400,
  });
  fs.chmodSync(unsupportedPsetPath, 0o400);
  const cycleOnePath = path.join(sqlSourceRoot, "cycle-one.sql");
  const cycleTwoPath = path.join(sqlSourceRoot, "cycle-two.sql");
  fs.writeFileSync(cycleOnePath, "\\ir cycle-two.sql\n", { mode: 0o400 });
  fs.writeFileSync(cycleTwoPath, "\\ir cycle-one.sql\n", { mode: 0o400 });
  fs.chmodSync(cycleOnePath, 0o400);
  fs.chmodSync(cycleTwoPath, 0o400);

  const emptyOptions = launch(undefined, {
    GALLR_PSQL_OPTIONS: "",
    TEST_EXIT_CODE: "0",
  });
  assert.equal(emptyOptions.status, 0, emptyOptions.stderr);
  const emptyOptionsCapture = JSON.parse(
    fs.readFileSync(capturePath, "utf8")
  );
  assert.equal(
    Object.prototype.hasOwnProperty.call(emptyOptionsCapture.target, "options"),
    false
  );

  const canonicalTemporaryRoot = fs.realpathSync.native("/tmp");
  const transportDirectoriesBefore = new Set(
    fs.readdirSync(canonicalTemporaryRoot)
      .filter((name) => name.startsWith("gallr-validated-psql-"))
  );
  const spawnFailure = launch(undefined, {
    GALLR_VALIDATED_PSQL_PATH: "/nonexistent/psql",
    TEST_EXIT_CODE: "0",
  });
  assert.notEqual(spawnFailure.status, 0);
  assert.equal(
    spawnFailure.stderr,
    "ERROR: validated database launcher failed\n"
  );
  const transportDirectoriesAfter = new Set(
    fs.readdirSync(canonicalTemporaryRoot)
      .filter((name) => name.startsWith("gallr-validated-psql-"))
  );
  assert.deepEqual(transportDirectoriesAfter, transportDirectoriesBefore);

  for (const unsafeArguments of [
    [],
    ["--"],
    ["--", "--dbname=postgresql://example.invalid/postgres"],
    ["--", "--db=postgresql://example.invalid/postgres"],
    ["--", "-d", "postgres"],
    ["--", "-Xhproduction.invalid"],
    ["--", "-p6543"],
    ["--", "-Uproduction"],
    ["--", "-W"],
    ["--", "-o", "/tmp/credential-leak", "-c", "select 1"],
    ["--", "--output=/tmp/credential-leak", "-c", "select 1"],
    ["--", "--single-step", "-c", "select 1"],
    ["--", "postgres"],
    ["--", "--", "postgres"],
    ["--", "-c", "\\c production"],
    ["--", "-cselect 1; \\connect production"],
    ["--", "--command", "\\connect production"],
    ["--", "--command=select 1; \\c production"],
    ["--", "-f", "-"],
    ["--", "-f", directReconnectPath],
    ["--", "--file", rootReconnectPath],
    ["--", "--file", dynamicIncludePath],
    ["--", "--file", shellEscapePath],
    ["--", "--file", localCopyPath],
    ["--", "--file", localOutputPath],
    ["--", "--file", backquotedSetPath],
    ["--", "--file", formFeedConnectPath],
    ["--", "--file", verticalTabIncludePath],
    ["--", "--file", unapprovedMetaCommandPath],
    ["--", "--file", defaultQuitPath],
    ["--", "--file", zeroQuitPath],
    ["--", "--file", disabledErrorStopPath],
    ["--", "--file", unsupportedPsetPath],
    ["--", "-f", cycleOnePath],
  ]) {
    fs.rmSync(capturePath, { force: true });
    const result = launch(unsafeArguments);
    assert.notEqual(result.status, 0);
    assert.equal(result.stdout, "");
    assert.equal(result.stderr, "ERROR: validated database launcher failed\n");
    assert.equal(fs.existsSync(capturePath), false);
  }

  for (const unsafeUrl of [
    databaseUrl({ encodedPassword: "" }),
    databaseUrl({ encodedPassword: "bad%ZZpassword" }),
    databaseUrl({
      query:
        `sslmode=verify-full&%73slrootcert=${encodeUriComponent(certificatePath)}`,
    }),
    databaseUrl({
      query:
        `sslmode=verify-full&sslrootcert=${encodeUriComponent(certificatePath)}&host=production.invalid`,
    }),
    databaseUrl({ ref: productionRef }),
  ]) {
    const result = launch(undefined, {
      GALLR_VALIDATION_DATABASE_URL: unsafeUrl,
    });
    assert.notEqual(result.status, 0);
    assert.equal(result.stdout, "");
    assert.equal(result.stderr, "ERROR: validated database launcher failed\n");
    assert.ok(!result.stderr.includes(password));
    assert.ok(!result.stderr.includes(stagingRef));
    assert.ok(!result.stderr.includes(unsafeUrl));
  }

  const wrongCertificateDigest = launch(undefined, {
    GALLR_VALIDATION_SSLROOTCERT_SHA256: "0".repeat(64),
  });
  assert.notEqual(wrongCertificateDigest.status, 0);
  assert.equal(
    wrongCertificateDigest.stderr,
    "ERROR: validated database launcher failed\n"
  );

  const wrongPsqlDigest = launch(undefined, {
    GALLR_VALIDATED_PSQL_SHA256: "0".repeat(64),
  });
  assert.notEqual(wrongPsqlDigest.status, 0);
  assert.equal(
    wrongPsqlDigest.stderr,
    "ERROR: validated database launcher failed\n"
  );

  const packagePrefix = path.join(temporaryParent, "package-prefix");
  const packagePsqlDirectory = path.join(
    packagePrefix,
    "Cellar",
    "libpq",
    "1",
    "bin"
  );
  const packageLinkRoot = path.join(packagePrefix, "opt");
  fs.mkdirSync(packagePsqlDirectory, { recursive: true, mode: 0o700 });
  fs.mkdirSync(packageLinkRoot, { mode: 0o770 });
  fs.chmodSync(packageLinkRoot, 0o770);
  const packagePsqlPath = path.join(packagePsqlDirectory, "psql");
  fs.copyFileSync(fakePsqlPath, packagePsqlPath);
  fs.chmodSync(packagePsqlPath, 0o700);
  const packagePsqlSha256 = crypto
    .createHash("sha256")
    .update(fs.readFileSync(packagePsqlPath))
    .digest("hex");
  const insecurePackageLinkRoot = launch(undefined, {
    GALLR_VALIDATED_PSQL_PATH: packagePsqlPath,
    GALLR_VALIDATED_PSQL_SHA256: packagePsqlSha256,
  });
  assert.notEqual(insecurePackageLinkRoot.status, 0);
  fs.chmodSync(packageLinkRoot, 0o700);
  const securePackageLinkRoot = launch(undefined, {
    GALLR_VALIDATED_PSQL_PATH: packagePsqlPath,
    GALLR_VALIDATED_PSQL_SHA256: packagePsqlSha256,
  });
  assert.equal(securePackageLinkRoot.status, 0, securePackageLinkRoot.stderr);

  fs.chmodSync(certificatePath, 0o644);
  const insecureCertificateMode = launch();
  assert.notEqual(insecureCertificateMode.status, 0);
  fs.chmodSync(certificatePath, 0o400);

  const certificateSymlink = path.join(temporaryParent, "linked-root.crt");
  fs.symlinkSync(certificatePath, certificateSymlink);
  const symlinkedCertificate = launch(undefined, {
    GALLR_VALIDATION_DATABASE_URL: databaseUrl({
      query:
        `sslmode=verify-full&sslrootcert=${encodeUriComponent(certificateSymlink)}`,
    }),
  });
  assert.notEqual(symlinkedCertificate.status, 0);
  fs.unlinkSync(certificateSymlink);

  const rejectedPooler = launch(undefined, {
    GALLR_VALIDATION_DATABASE_URL: databaseUrl({ pooler: true }),
  });
  assert.notEqual(rejectedPooler.status, 0);

  const acceptedPooler = launch(undefined, {
    GALLR_VALIDATION_DATABASE_URL: databaseUrl({ pooler: true }),
    GALLR_VALIDATION_REQUIRE_DIRECT: "false",
    TEST_EXIT_CODE: "0",
  });
  assert.equal(acceptedPooler.status, 0, acceptedPooler.stderr);
  const poolerCapture = JSON.parse(fs.readFileSync(capturePath, "utf8"));
  assert.equal(
    poolerCapture.target.host,
    "aws-0-test.pooler.supabase.com"
  );
  assert.equal(poolerCapture.target.port, "6543");
  assert.equal(poolerCapture.target.user, `postgres.${stagingRef}`);

  const validated = validateDatabaseTarget({
    projectRef: stagingRef,
    databaseUrl: databaseUrl(),
    requireDirect: "true",
  });
  fs.chmodSync(certificatePath, 0o600);
  fs.appendFileSync(certificatePath, "\n");
  fs.chmodSync(certificatePath, 0o400);
  assert.throws(() => assertCertificateSourceUnchanged(validated.certificate));
  fs.chmodSync(certificatePath, 0o600);
  fs.writeFileSync(certificatePath, certificatePem, { mode: 0o400 });
  fs.chmodSync(certificatePath, 0o400);

  const signaled = launch(undefined, {
    TEST_SIGNAL: "SIGTERM",
    TEST_EXIT_CODE: "0",
  });
  assert.equal(signaled.status, null);
  assert.equal(signaled.signal, "SIGTERM");

  const externallySignaled = launch(undefined, {
    TEST_SIGNAL_PARENT: "SIGTERM",
  });
  assert.equal(externallySignaled.status, null);
  assert.equal(externallySignaled.signal, "SIGTERM");
  const externalSignalCapture = JSON.parse(
    fs.readFileSync(capturePath, "utf8")
  );
  assert.equal(fs.existsSync(externalSignalCapture.passfilePath), false);
  assert.equal(fs.existsSync(externalSignalCapture.certificatePath), false);

  // A database client that ignores TERM must not hold the launcher or its
  // credential-bearing transport directory open indefinitely. The launcher
  // owns the client's process group, escalates to KILL, reaps it, cleans the
  // transport, and only then re-raises the operator's original signal.
  const resistantSignalStartedAt = Date.now();
  const resistantSignal = launch(undefined, {
    TEST_SIGNAL_PARENT: "SIGTERM",
    TEST_IGNORE_TERM: "true",
  });
  const resistantSignalElapsed = Date.now() - resistantSignalStartedAt;
  assert.equal(resistantSignal.status, null);
  assert.equal(resistantSignal.signal, "SIGTERM");
  assert.ok(
    resistantSignalElapsed < 5000,
    `TERM-resistant psql took ${resistantSignalElapsed}ms to stop`
  );
  const resistantSignalCapture = JSON.parse(
    fs.readFileSync(capturePath, "utf8")
  );
  assert.equal(
    fs.existsSync(resistantSignalCapture.passfilePath),
    false
  );
  assert.equal(
    fs.existsSync(resistantSignalCapture.certificatePath),
    false
  );
  assert.equal(
    fs.existsSync(path.dirname(resistantSignalCapture.passfilePath)),
    false
  );

  // A direct child that exits zero while a TERM-resistant descendant still
  // holds the libpq environment is not a successful psql run. Kill the entire
  // process group, reject the run, and remove every transport file.
  fs.rmSync(descendantStatePath, { force: true });
  const descendantStartedAt = Date.now();
  const descendantResult = launch(undefined, {
    TEST_SPAWN_DESCENDANT: "true",
  });
  const descendantElapsed = Date.now() - descendantStartedAt;
  assert.equal(descendantResult.status, 1);
  assert.equal(descendantResult.signal, null);
  assert.equal(descendantResult.stdout, "fake psql stdout\n");
  assert.equal(
    descendantResult.stderr,
    "fake psql stderr\nERROR: validated database launcher failed\n"
  );
  assert.ok(
    descendantElapsed < 5000,
    `descendant cleanup took ${descendantElapsed}ms`
  );
  const descendantState = JSON.parse(
    fs.readFileSync(descendantStatePath, "utf8")
  );
  assert.equal(descendantState.passfileReadable, true);
  assert.equal(descendantState.certificateReadable, true);
  assert.equal(fs.existsSync(descendantState.passfilePath), false);
  assert.equal(fs.existsSync(descendantState.certificatePath), false);
  assert.equal(
    fs.existsSync(path.dirname(descendantState.passfilePath)),
    false
  );
  assert.equal(processIsAlive(descendantState.pid), false);

  // Signal the launcher after its private transport exists but while it is
  // still synchronously validating a large reviewed executable. The handler
  // must already be installed, must forward the queued signal once psql is
  // attached, and must remove the passfile/certificate directory.
  const slowPsqlPath = path.join(fakeBin, "slow-reviewed-psql");
  const slowPsqlPidPath = path.join(temporaryParent, "slow-psql.pid");
  const slowDescriptor = fs.openSync(
    slowPsqlPath,
    fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL,
    0o700
  );
  try {
    fs.writeSync(
      slowDescriptor,
      Buffer.from(
        `#!/bin/sh\nprintf '%s\\n' "$$" > ${JSON.stringify(slowPsqlPidPath)}\n` +
          "trap 'exit 0' HUP INT QUIT TERM\nwhile :; do /bin/sleep 1; done\n"
      )
    );
    const padding = Buffer.alloc(1024 * 1024, 0x78);
    padding[0] = 0x23;
    padding[padding.length - 1] = 0x0a;
    for (let index = 0; index < 128; index += 1) {
      fs.writeSync(slowDescriptor, padding);
    }
    padding.fill(0);
  } finally {
    fs.closeSync(slowDescriptor);
  }
  fs.chmodSync(slowPsqlPath, 0o700);
  const slowPsqlSha256 = sha256File(slowPsqlPath);
  fs.writeFileSync(controlPath, JSON.stringify({ exitCode: 0 }), {
    mode: 0o600,
  });
  const directoriesBeforeEarlySignal = new Set(
    fs.readdirSync(canonicalTemporaryRoot).filter((name) =>
      name.startsWith("gallr-validated-psql-")
    )
  );
  const earlySignalLauncher = spawn(
    process.execPath,
    [launcherPath, "--", "-c", "select 1"],
    {
      env: launcherEnvironment({
        GALLR_VALIDATED_PSQL_PATH: slowPsqlPath,
        GALLR_VALIDATED_PSQL_SHA256: slowPsqlSha256,
      }),
      stdio: ["ignore", "pipe", "pipe"],
    }
  );
  let earlySignalTransportDirectory = null;
  for (let attempt = 0; attempt < 2500; attempt += 1) {
    const candidate = fs
      .readdirSync(canonicalTemporaryRoot)
      .find(
        (name) =>
          name.startsWith("gallr-validated-psql-") &&
          !directoriesBeforeEarlySignal.has(name)
      );
    if (candidate) {
      earlySignalTransportDirectory = path.join(
        canonicalTemporaryRoot,
        candidate
      );
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
  assert.notEqual(
    earlySignalTransportDirectory,
    null,
    "launcher did not create a transport directory before the signal timeout"
  );
  assert.equal(earlySignalLauncher.kill("SIGTERM"), true);
  const earlySignalResult = await new Promise((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error("early-signal launcher did not exit")),
      10000
    );
    earlySignalLauncher.once("close", (code, signal) => {
      clearTimeout(timeout);
      resolve({ code, signal });
    });
    earlySignalLauncher.once("error", reject);
  });
  assert.deepEqual(earlySignalResult, { code: null, signal: "SIGTERM" });
  assert.equal(fs.existsSync(earlySignalTransportDirectory), false);
  assert.equal(
    fs.existsSync(slowPsqlPidPath),
    false,
    "a queued pre-spawn signal must prevent psql from starting"
  );

  console.log("validated psql launcher tests passed");
} finally {
  if (fs.existsSync(descendantStatePath)) {
    try {
      const descendantState = JSON.parse(
        fs.readFileSync(descendantStatePath, "utf8")
      );
      if (processIsAlive(descendantState.pid)) {
        process.kill(descendantState.pid, "SIGKILL");
      }
    } catch (_error) {
      // The main assertions report malformed state; best-effort test cleanup
      // must not replace that failure.
    }
  }
  fs.rmSync(temporaryParent, { recursive: true, force: true });
}
