#!/usr/bin/env node

import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import {
  assertCertificateSourceUnchanged,
  validateDatabaseTarget,
} from "./database-target.mjs";

const genericError = "ERROR: validated database launcher failed\n";
const routingShortOptions = new Set(["d", "h", "p", "U", "W"]);
const safeShortFlags = new Set(["A", "q", "t", "X"]);
const safeShortValueOptions = new Set(["c", "f", "F", "v"]);
const safeLongFlags = new Set(["single-transaction"]);
const safeLongValueOptions = new Set([
  "command",
  "file",
  "field-separator",
  "set",
]);
const libpqEnvironmentKeys = [
  "PGAPPNAME",
  "PGCHANNELBINDING",
  "PGCLIENTENCODING",
  "PGCONNECT_TIMEOUT",
  "PGDATABASE",
  "PGDATESTYLE",
  "PGGEQO",
  "PGGSSDELEGATION",
  "PGGSSENCMODE",
  "PGGSSLIB",
  "PGHOST",
  "PGHOSTADDR",
  "PGKRBSRVNAME",
  "PGLOADBALANCEHOSTS",
  "PGLOCALEDIR",
  "PGMAXPROTOCOLVERSION",
  "PGMINPROTOCOLVERSION",
  "PGOPTIONS",
  "PGPASSFILE",
  "PGPASSWORD",
  "PGPORT",
  "PGREQUIREAUTH",
  "PGREQUIREPEER",
  "PGREQUIRESSL",
  "PGSERVICE",
  "PGSERVICEFILE",
  "PGSSLCERT",
  "PGSSLCERTMODE",
  "PGSSLCOMPRESSION",
  "PGSSLCRL",
  "PGSSLCRLDIR",
  "PGSSLKEY",
  "PGSSLMAXPROTOCOLVERSION",
  "PGSSLMINPROTOCOLVERSION",
  "PGSSLMODE",
  "PGSSLNEGOTIATION",
  "PGSSLROOTCERT",
  "PGSSLSNI",
  "PGSYSCONFDIR",
  "PGTARGETSESSIONATTRS",
  "PGTCP_USER_TIMEOUT",
  "PGTZ",
  "PGUSER",
];
const credentialEnvironmentKeys = [
  "DATABASE_URL",
  "DB_URL",
  "GALLR_PRODUCTION_DATABASE_URL",
  "GALLR_SERVICE_ROLE_KEY",
  "GALLR_STAGING_DATABASE_URL",
  "GALLR_STAGING_DB_URL",
  "GALLR_VALIDATION_DATABASE_URL",
  "GALLR_VALIDATION_PROJECT_REF",
  "GALLR_VALIDATION_REQUIRE_DIRECT",
  "GALLR_VALIDATION_SSLROOTCERT_SHA256",
  "POSTGRES_PRISMA_URL",
  "POSTGRES_URL",
  "POSTGRES_URL_NON_POOLING",
  "SUPABASE_ACCESS_TOKEN",
  "SUPABASE_ANON_KEY",
  "SUPABASE_DB_PASSWORD",
  "SUPABASE_DB_URL",
  "SUPABASE_SECRET_KEY",
  "SUPABASE_SERVICE_ROLE_KEY",
  "SUPABASE_URL",
];
const launcherEnvironmentKeys = [
  "GALLR_PSQL_APPNAME",
  "GALLR_PSQL_CONNECT_TIMEOUT",
  "GALLR_PSQL_OPTIONS",
  "GALLR_VALIDATED_PSQL_PATH",
  "GALLR_VALIDATED_PSQL_SHA256",
];
const handledSignals = ["SIGHUP", "SIGINT", "SIGQUIT", "SIGTERM"];
const maximumSqlFileBytes = 5 * 1024 * 1024;
const maximumSqlSnapshotFiles = 64;
const maximumSqlSnapshotBytes = 20 * 1024 * 1024;
const maximumSqlIncludeDepth = 16;
const trustedTemporaryRootPath = "/tmp";
const processGroupTerminationGraceMs = 2000;
const processGroupKillDrainMs = 2000;
const processGroupPollMs = 10;

function fail() {
  throw new Error("launcher failed");
}

function removeSensitiveEnvironment(environment) {
  for (const key of [
    ...libpqEnvironmentKeys,
    ...credentialEnvironmentKeys,
    ...launcherEnvironmentKeys,
    "NODE_DEBUG",
    "NODE_DEBUG_NATIVE",
    "NODE_OPTIONS",
    "NODE_PATH",
    "OPENSSL_CONF",
    "OPENSSL_MODULES",
    "SSL_CERT_DIR",
    "SSL_CERT_FILE",
    "SSLKEYLOGFILE",
  ]) {
    delete environment[key];
  }
  for (const key of Object.keys(environment)) {
    if (
      key.startsWith("GALLR_PSQL_") ||
      key.startsWith("GALLR_VALIDATION_") ||
      key.startsWith("SUPABASE_") ||
      /^GALLR_.*(?:DATABASE|PASSWORD|SECRET|TOKEN|KEY)/.test(key)
    ) {
      delete environment[key];
    }
  }
}

function validateSessionConfiguration(environment) {
  const appName = String(environment.GALLR_PSQL_APPNAME || "");
  const connectTimeout = String(
    environment.GALLR_PSQL_CONNECT_TIMEOUT || ""
  );
  const options = String(environment.GALLR_PSQL_OPTIONS || "");

  if (
    appName !== "" &&
    !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,62}$/.test(appName)
  ) {
    fail();
  }
  if (
    connectTimeout !== "" &&
    (!/^[0-9]+$/.test(connectTimeout) ||
      Number(connectTimeout) < 1 ||
      Number(connectTimeout) > 60)
  ) {
    fail();
  }
  if (
    options.length > 512 ||
    /[\u0000-\u001f\u007f\u0085\u2028\u2029]/u.test(options)
  ) {
    fail();
  }

  if (options !== "") {
    const tokens = options.split(" ");
    if (tokens.length % 2 !== 0) fail();
    for (let index = 0; index < tokens.length; index += 2) {
      if (tokens[index] !== "-c") fail();
      const setting = tokens[index + 1];
      const separator = setting.indexOf("=");
      if (
        separator <= 0 ||
        separator !== setting.lastIndexOf("=") ||
        separator === setting.length - 1
      ) {
        fail();
      }
      const name = setting.slice(0, separator);
      const value = setting.slice(separator + 1);
      if (name === "default_transaction_read_only") {
        if (value !== "on") fail();
      } else if (new Set(["statement_timeout", "lock_timeout"]).has(name)) {
        if (!/^[1-9][0-9]*(?:ms|s|min)?$/.test(value)) fail();
      } else {
        fail();
      }
    }
  }

  return { appName, connectTimeout, options };
}

function assertPsqlValue(value) {
  if (
    typeof value !== "string" ||
    value === "" ||
    value.includes("\u0000")
  ) {
    fail();
  }
}

export function validatePsqlArguments(argumentsToPsql) {
  if (!Array.isArray(argumentsToPsql)) fail();
  const commands = [];
  const files = [];

  for (let index = 0; index < argumentsToPsql.length; index += 1) {
    const argument = argumentsToPsql[index];
    if (
      typeof argument !== "string" ||
      argument.includes("\u0000") ||
      argument === "--" ||
      !argument.startsWith("-") ||
      argument === "-"
    ) {
      fail();
    }

    if (argument.startsWith("--")) {
      const separator = argument.indexOf("=");
      const name =
        separator === -1 ? argument.slice(2) : argument.slice(2, separator);
      if (safeLongFlags.has(name)) {
        if (separator !== -1) fail();
        continue;
      }
      if (!safeLongValueOptions.has(name)) fail();
      let value;
      if (separator === -1) {
        index += 1;
        if (index >= argumentsToPsql.length) fail();
        value = argumentsToPsql[index];
      } else {
        value = argument.slice(separator + 1);
      }
      assertPsqlValue(value);
      if (name === "command") {
        commands.push(value);
      } else if (name === "file") {
        files.push({
          argumentIndex: index,
          inlinePrefix: separator === -1 ? null : `--${name}=`,
          pathname: value,
        });
      }
      continue;
    }

    const cluster = argument.slice(1);
    for (let offset = 0; offset < cluster.length; offset += 1) {
      const option = cluster[offset];
      if (routingShortOptions.has(option)) fail();
      if (safeShortFlags.has(option)) continue;
      if (!safeShortValueOptions.has(option)) fail();
      let value;
      let valueIndex = index;
      let inlinePrefix = null;
      if (offset === cluster.length - 1) {
        index += 1;
        if (index >= argumentsToPsql.length) fail();
        value = argumentsToPsql[index];
        valueIndex = index;
      } else {
        value = cluster.slice(offset + 1);
        inlinePrefix = `-${cluster.slice(0, offset + 1)}`;
      }
      assertPsqlValue(value);
      if (option === "c") {
        commands.push(value);
      } else if (option === "f") {
        files.push({
          argumentIndex: valueIndex,
          inlinePrefix,
          pathname: value,
        });
      }
      break;
    }
  }

  if (commands.length + files.length === 0) fail();
  for (const command of commands) {
    // A psql -c argument can be either SQL or one backslash command. This
    // launcher deliberately accepts SQL only so \connect cannot replace the
    // target after libpq routing has been fixed.
    if (command.includes("\\")) fail();
  }
  return { commands, files };
}

function assertOwnedFile(pathname, expectedMode) {
  const stat = fs.lstatSync(pathname, { bigint: true });
  const effectiveUid =
    typeof process.geteuid === "function" ? BigInt(process.geteuid()) : stat.uid;
  if (
    !stat.isFile() ||
    stat.isSymbolicLink() ||
    stat.uid !== effectiveUid ||
    stat.nlink !== 1n ||
    Number(stat.mode & 0o7777n) !== expectedMode
  ) {
    fail();
  }
}

function writeExclusiveFile(pathname, bytes, mode) {
  const descriptor = fs.openSync(
    pathname,
    fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_WRONLY,
    0o600
  );
  try {
    fs.writeFileSync(descriptor, bytes);
    fs.fsyncSync(descriptor);
    fs.fchmodSync(descriptor, mode);
  } finally {
    fs.closeSync(descriptor);
  }
  assertOwnedFile(pathname, mode);
}

function escapePgpassPassword(password) {
  return password.replaceAll("\\", "\\\\").replaceAll(":", "\\:");
}

function trustedTemporaryRoot() {
  const temporaryRoot = fs.realpathSync.native(trustedTemporaryRootPath);
  const stat = fs.lstatSync(temporaryRoot, { bigint: true });
  if (
    !stat.isDirectory() ||
    stat.isSymbolicLink() ||
    stat.uid !== 0n ||
    Number(stat.mode & 0o7777n) !== 0o1777
  ) {
    fail();
  }
  return temporaryRoot;
}

function createTransportFiles(target) {
  const temporaryRoot = trustedTemporaryRoot();
  const directory = fs.mkdtempSync(
    path.join(temporaryRoot, "gallr-validated-psql-")
  );
  const passfilePath = path.join(directory, "pgpass");
  const certificatePath = path.join(directory, "root.crt");
  let pgpassBytes;
  let completed = false;

  try {
    fs.chmodSync(directory, 0o700);
    const directoryStat = fs.lstatSync(directory, { bigint: true });
    const effectiveUid =
      typeof process.geteuid === "function"
        ? BigInt(process.geteuid())
        : directoryStat.uid;
    if (
      !directoryStat.isDirectory() ||
      directoryStat.isSymbolicLink() ||
      directoryStat.uid !== effectiveUid ||
      Number(directoryStat.mode & 0o7777n) !== 0o700
    ) {
      fail();
    }

    const pgpassText = [
      target.host,
      target.port,
      target.database,
      target.user,
      escapePgpassPassword(target.password),
    ].join(":") + "\n";
    pgpassBytes = Buffer.from(pgpassText, "utf8");
    writeExclusiveFile(passfilePath, pgpassBytes, 0o600);
    writeExclusiveFile(certificatePath, target.certificate.bytes, 0o400);
    const copiedCertificate = fs.readFileSync(certificatePath);
    const copiedDigest = crypto
      .createHash("sha256")
      .update(copiedCertificate)
      .digest("hex");
    if (copiedDigest !== target.certificate.sha256) fail();
    completed = true;
    return { directory, passfilePath, certificatePath };
  } finally {
    if (pgpassBytes) pgpassBytes.fill(0);
    if (!completed) {
      fs.rmSync(directory, { recursive: true, force: true });
    }
  }
}

function cleanupTransportFiles(transport) {
  if (!transport || typeof transport.directory !== "string") return;
  const expectedParent = trustedTemporaryRoot();
  const basename = path.basename(transport.directory);
  if (
    path.dirname(transport.directory) !== expectedParent ||
    !/^gallr-validated-psql-[A-Za-z0-9]+$/.test(basename)
  ) {
    return;
  }
  fs.rmSync(transport.directory, { recursive: true, force: true });
}

function sameStableFile(left, right) {
  return (
    left.dev === right.dev &&
    left.ino === right.ino &&
    left.uid === right.uid &&
    left.gid === right.gid &&
    left.mode === right.mode &&
    left.nlink === right.nlink &&
    left.size === right.size &&
    left.mtimeNs === right.mtimeNs &&
    left.ctimeNs === right.ctimeNs
  );
}

function readStableSqlFile(pathname) {
  if (
    typeof pathname !== "string" ||
    pathname === "" ||
    pathname.includes("\u0000") ||
    !path.isAbsolute(pathname) ||
    path.normalize(pathname) !== pathname ||
    fs.realpathSync.native(pathname) !== pathname
  ) {
    fail();
  }

  const noFollow = fs.constants.O_NOFOLLOW || 0;
  const descriptor = fs.openSync(pathname, fs.constants.O_RDONLY | noFollow);
  try {
    const before = fs.fstatSync(descriptor, { bigint: true });
    const effectiveUid =
      typeof process.geteuid === "function"
        ? BigInt(process.geteuid())
        : before.uid;
    if (
      !before.isFile() ||
      before.uid !== effectiveUid ||
      before.nlink !== 1n ||
      Number(before.mode & 0o022n) !== 0 ||
      before.size < 1n ||
      before.size > BigInt(maximumSqlFileBytes)
    ) {
      fail();
    }
    const bytes = fs.readFileSync(descriptor);
    const after = fs.fstatSync(descriptor, { bigint: true });
    if (!sameStableFile(before, after) || BigInt(bytes.length) !== before.size) {
      fail();
    }
    return bytes;
  } finally {
    fs.closeSync(descriptor);
  }
}

function inspectSqlBytes(bytes) {
  const sql = bytes.toString("utf8");
  if (
    !Buffer.from(sql, "utf8").equals(bytes) ||
    /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u0085\u2028\u2029`]/u.test(
      sql
    ) ||
    sql.replaceAll("\r\n", "").includes("\r")
  ) {
    fail();
  }

  const includePaths = [];
  const permittedMetaCommands = new Set([
    "echo",
    "else",
    "endif",
    "gset",
    "if",
    "include_relative",
    "ir",
    "pset",
    "quit",
    "set",
    "timing",
    "watch",
  ]);
  const normalizedSql = sql.replaceAll("\r\n", "\n");
  for (const line of normalizedSql.split("\n")) {
    const firstBackslash = line.indexOf("\\");
    if (firstBackslash === -1) continue;
    if (
      firstBackslash !== line.search(/[^\t ]/) ||
      line.indexOf("\\", firstBackslash + 1) !== -1
    ) {
      fail();
    }
    const metaCommand = /^[\t ]*\\([A-Za-z_]+)(?=[\t ]|$)/.exec(line);
    if (
      !metaCommand ||
      !permittedMetaCommands.has(metaCommand[1])
    ) {
      fail();
    }
    const argumentsToMetaCommand = line
      .slice(firstBackslash + metaCommand[1].length + 1)
      .trim();
    switch (metaCommand[1]) {
      case "else":
      case "endif":
        if (argumentsToMetaCommand !== "") fail();
        break;
      case "gset":
        if (
          !/^(?:[A-Za-z_][A-Za-z0-9_]*)?$/.test(
            argumentsToMetaCommand
          )
        ) {
          fail();
        }
        break;
      case "if":
        if (
          !/^:(?:[A-Za-z_][A-Za-z0-9_]*|\{\?[A-Za-z_][A-Za-z0-9_]*\})$/.test(
            argumentsToMetaCommand
          )
        ) {
          fail();
        }
        break;
      case "include_relative":
      case "ir": {
        const include = line.match(
          /^[\t ]*\\(?:ir|include_relative)[\t ]+([A-Za-z0-9][A-Za-z0-9._/-]*)[\t ]*$/
        );
        if (!include) fail();
        const relativePath = include[1];
        const segments = relativePath.split("/");
        if (
          path.posix.isAbsolute(relativePath) ||
          path.posix.normalize(relativePath) !== relativePath ||
          segments.some((segment) => segment === "." || segment === "..")
        ) {
          fail();
        }
        includePaths.push(relativePath);
        break;
      }
      case "pset":
        if (
          !new Set([
            "format unaligned",
            "null '(null)'",
            "pager off",
            "tuples_only on",
          ]).has(argumentsToMetaCommand)
        ) {
          fail();
        }
        break;
      case "quit": {
        if (!/^[1-9][0-9]{0,2}$/.test(argumentsToMetaCommand)) fail();
        const exitCode = Number(argumentsToMetaCommand);
        if (exitCode > 255) fail();
        break;
      }
      case "set":
        if (
          !new Set([
            "ON_ERROR_STOP on",
            "VERBOSITY verbose",
            "expected_legacy_payload_sha256 not-checked",
            "expected_runtime sheet-owned",
            "require_representative_data false",
          ]).has(argumentsToMetaCommand)
        ) {
          fail();
        }
        break;
      case "timing":
        if (argumentsToMetaCommand !== "on") fail();
        break;
      case "watch":
        if (argumentsToMetaCommand !== "0.25") fail();
        break;
      case "echo":
        if (argumentsToMetaCommand === "") fail();
        break;
      default:
        fail();
    }
  }
  return includePaths;
}

function snapshotSqlFileTree(sourcePath, snapshotRoot) {
  const sourceRoot = path.dirname(sourcePath);
  const sourceRootPrefix = `${sourceRoot}${path.sep}`;
  const copied = new Set();
  let copiedBytes = 0;

  const snapshot = (currentSource, ancestry) => {
    if (
      ancestry.has(currentSource) ||
      ancestry.size >= maximumSqlIncludeDepth
    ) {
      fail();
    }
    if (copied.has(currentSource)) return;
    if (copied.size >= maximumSqlSnapshotFiles) fail();
    if (
      currentSource !== sourcePath &&
      !currentSource.startsWith(sourceRootPrefix)
    ) {
      fail();
    }

    const bytes = readStableSqlFile(currentSource);
    copiedBytes += bytes.length;
    if (copiedBytes > maximumSqlSnapshotBytes) fail();
    const includePaths = inspectSqlBytes(bytes);
    const relativeDestination =
      currentSource === sourcePath
        ? path.basename(sourcePath)
        : path.relative(sourceRoot, currentSource);
    if (
      relativeDestination === "" ||
      relativeDestination.startsWith("..") ||
      path.isAbsolute(relativeDestination)
    ) {
      fail();
    }
    const destination = path.join(snapshotRoot, relativeDestination);
    fs.mkdirSync(path.dirname(destination), { recursive: true, mode: 0o700 });
    writeExclusiveFile(destination, bytes, 0o400);
    copied.add(currentSource);

    const nextAncestry = new Set(ancestry);
    nextAncestry.add(currentSource);
    for (const relativeInclude of includePaths) {
      const includedSource = path.resolve(
        path.dirname(currentSource),
        ...relativeInclude.split("/")
      );
      if (!includedSource.startsWith(sourceRootPrefix)) fail();
      snapshot(includedSource, nextAncestry);
    }
  };

  snapshot(sourcePath, new Set());

  return path.join(snapshotRoot, path.basename(sourcePath));
}

function snapshotPsqlInputs(argumentsToPsql, validatedArguments, transport) {
  const rewritten = [...argumentsToPsql];
  for (let index = 0; index < validatedArguments.files.length; index += 1) {
    const file = validatedArguments.files[index];
    const snapshotRoot = path.join(transport.directory, `sql-${index}`);
    fs.mkdirSync(snapshotRoot, { mode: 0o700 });
    const snapshotPath = snapshotSqlFileTree(file.pathname, snapshotRoot);
    rewritten[file.argumentIndex] =
      file.inlinePrefix === null
        ? snapshotPath
        : `${file.inlinePrefix}${snapshotPath}`;
  }
  return rewritten;
}

function hashOpenFile(descriptor) {
  const hash = crypto.createHash("sha256");
  const buffer = Buffer.allocUnsafe(1024 * 1024);
  let offset = 0;
  for (;;) {
    const count = fs.readSync(descriptor, buffer, 0, buffer.length, offset);
    if (count === 0) break;
    hash.update(buffer.subarray(0, count));
    offset += count;
  }
  buffer.fill(0);
  return hash.digest("hex");
}

function validatePsqlExecutable(pathname, expectedSha256) {
  if (
    typeof pathname !== "string" ||
    !path.isAbsolute(pathname) ||
    pathname.includes("\u0000") ||
    path.normalize(pathname) !== pathname ||
    fs.realpathSync.native(pathname) !== pathname ||
    !/^[0-9a-f]{64}$/.test(expectedSha256)
  ) {
    fail();
  }
  const noFollow = fs.constants.O_NOFOLLOW || 0;
  const effectiveUid =
    typeof process.geteuid === "function"
      ? BigInt(process.geteuid())
      : fs.lstatSync(pathname, { bigint: true }).uid;
  const trustedTmp = trustedTemporaryRoot();
  let ancestor = path.dirname(pathname);
  for (;;) {
    const ancestorStat = fs.lstatSync(ancestor, { bigint: true });
    const ancestorMode = Number(ancestorStat.mode & 0o7777n);
    const stickyRootException =
      ancestor === trustedTmp &&
      ancestorStat.uid === 0n &&
      ancestorMode === 0o1777;
    if (
      !ancestorStat.isDirectory() ||
      ancestorStat.isSymbolicLink() ||
      (ancestorStat.uid !== 0n && ancestorStat.uid !== effectiveUid) ||
      ((ancestorMode & 0o022) !== 0 && !stickyRootException)
    ) {
      fail();
    }
    if (ancestor === path.parse(ancestor).root) break;
    ancestor = path.dirname(ancestor);
  }
  const cellarSegment = `${path.sep}Cellar${path.sep}`;
  const cellarIndex = pathname.indexOf(cellarSegment);
  if (cellarIndex > 0) {
    const packageLinkRoot = path.join(
      pathname.slice(0, cellarIndex),
      "opt"
    );
    const linkRootStat = fs.lstatSync(packageLinkRoot, { bigint: true });
    if (
      !linkRootStat.isDirectory() ||
      linkRootStat.isSymbolicLink() ||
      (linkRootStat.uid !== 0n && linkRootStat.uid !== effectiveUid) ||
      Number(linkRootStat.mode & 0o022n) !== 0
    ) {
      fail();
    }
  }

  const descriptor = fs.openSync(pathname, fs.constants.O_RDONLY | noFollow);
  try {
    const before = fs.fstatSync(descriptor, { bigint: true });
    if (
      !before.isFile() ||
      (before.uid !== 0n && before.uid !== effectiveUid) ||
      before.nlink !== 1n ||
      Number(before.mode & 0o022n) !== 0 ||
      Number(before.mode & 0o111n) === 0
    ) {
      fail();
    }
    const digest = hashOpenFile(descriptor);
    const after = fs.fstatSync(descriptor, { bigint: true });
    if (!sameStableFile(before, after) || digest !== expectedSha256) fail();
  } finally {
    fs.closeSync(descriptor);
  }
  return pathname;
}

function buildChildEnvironment(target, transport, sessionConfiguration) {
  const childEnvironment = {
    HOME: "/nonexistent",
    LANG: "C",
    LC_ALL: "C",
    PATH: "/usr/bin:/bin:/usr/sbin:/sbin",
    TMPDIR: trustedTemporaryRoot(),
    PGDATABASE: target.database,
    PGGSSENCMODE: "disable",
    PGHOST: target.host,
    PGPASSFILE: transport.passfilePath,
    PGPORT: target.port,
    PGSSLCERTMODE: "disable",
    PGSSLMODE: target.sslmode,
    PGSSLROOTCERT: transport.certificatePath,
    PGUSER: target.user,
  };
  if (sessionConfiguration.appName !== "") {
    childEnvironment.PGAPPNAME = sessionConfiguration.appName;
  }
  if (sessionConfiguration.connectTimeout !== "") {
    childEnvironment.PGCONNECT_TIMEOUT =
      sessionConfiguration.connectTimeout;
  }
  if (sessionConfiguration.options !== "") {
    childEnvironment.PGOPTIONS = sessionConfiguration.options;
  }
  return childEnvironment;
}

function processGroupIsAlive(processGroupId) {
  if (!Number.isInteger(processGroupId) || processGroupId < 1) return false;
  try {
    process.kill(-processGroupId, 0);
    return true;
  } catch (error) {
    if (error && error.code === "ESRCH") return false;
    return true;
  }
}

function signalProcessGroup(processGroupId, signal) {
  if (!Number.isInteger(processGroupId) || processGroupId < 1) return false;
  try {
    process.kill(-processGroupId, signal);
    return true;
  } catch (error) {
    if (error && error.code === "ESRCH") return false;
    return false;
  }
}

function installSignalHandlers() {
  const state = {
    childProcessGroupId: null,
    notifyCancellation: null,
    receivedSignal: null,
    handlers: new Map(),
    disposed: false,
  };
  state.dispose = () => {
    if (state.disposed) return;
    state.disposed = true;
    for (const [signal, handler] of state.handlers) {
      process.removeListener(signal, handler);
    }
  };
  state.attachChild = (child, notifyCancellation) => {
    state.childProcessGroupId = child.pid;
    state.notifyCancellation = notifyCancellation;
    if (state.receivedSignal) {
      signalProcessGroup(child.pid, "SIGTERM");
      notifyCancellation();
    }
  };
  for (const signal of handledSignals) {
    const handler = () => {
      if (state.receivedSignal !== null) return;
      state.receivedSignal = signal;
      if (state.childProcessGroupId === null) return;
      signalProcessGroup(state.childProcessGroupId, "SIGTERM");
      state.notifyCancellation();
    };
    state.handlers.set(signal, handler);
    process.on(signal, handler);
  }
  return state;
}

function runPsql(
  psqlExecutable,
  argumentsToPsql,
  childEnvironment,
  signalState,
  beforeHandlerRemoval
) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let childClosed = false;
    let childResult = null;
    let groupShutdownReason = null;
    let groupShutdownTimer = null;
    let terminationDeadline = null;
    let killDeadline = null;
    let killSent = false;

    const settle = (complete) => {
      if (settled) return;
      settled = true;
      if (groupShutdownTimer !== null) {
        clearTimeout(groupShutdownTimer);
        groupShutdownTimer = null;
      }
      try {
        beforeHandlerRemoval();
      } catch (_error) {
        signalState.dispose();
        reject(new Error("psql cleanup failed"));
        return;
      }
      signalState.dispose();
      complete();
    };

    let child;
    const finishGroupShutdown = () => {
      if (groupShutdownReason === "signal") {
        settle(() =>
          resolve({
            code: childResult?.code ?? null,
            signal: signalState.receivedSignal,
          })
        );
      } else {
        settle(() => reject(new Error("psql left live descendants")));
      }
    };

    const pollGroupShutdown = () => {
      if (settled) return;
      const groupAlive = processGroupIsAlive(child.pid);
      if (!groupAlive && childClosed) {
        finishGroupShutdown();
        return;
      }

      const now = Date.now();
      if (!killSent && now >= terminationDeadline) {
        signalProcessGroup(child.pid, "SIGKILL");
        killSent = true;
        killDeadline = now + processGroupKillDrainMs;
      } else if (killSent && now >= killDeadline) {
        signalProcessGroup(child.pid, "SIGKILL");
        settle(() => reject(new Error("psql process group did not exit")));
        return;
      }
      groupShutdownTimer = setTimeout(
        pollGroupShutdown,
        processGroupPollMs
      );
    };

    const startGroupShutdown = (reason) => {
      if (settled || groupShutdownReason !== null) return;
      groupShutdownReason = reason;
      terminationDeadline = Date.now() + processGroupTerminationGraceMs;
      signalProcessGroup(child.pid, "SIGTERM");
      groupShutdownTimer = setTimeout(
        pollGroupShutdown,
        processGroupPollMs
      );
    };

    try {
      if (process.platform === "win32") {
        throw new Error("POSIX process groups are required");
      }
      child = spawn(
        psqlExecutable,
        ["-X", "--no-password", ...argumentsToPsql],
        {
          detached: true,
          env: childEnvironment,
          shell: false,
          stdio: "inherit",
        }
      );
    } catch (_error) {
      settle(() => reject(new Error("psql spawn failed")));
      return;
    }

    child.once("error", () => {
      if (groupShutdownReason === null) {
        settle(() => reject(new Error("psql spawn failed")));
      }
    });
    child.once("close", (code, signal) => {
      childClosed = true;
      childResult = { code, signal };
      if (groupShutdownReason !== null) {
        pollGroupShutdown();
        return;
      }
      if (processGroupIsAlive(child.pid)) {
        startGroupShutdown("descendants");
        return;
      }
      settle(() => resolve({ code, signal }));
    });
    signalState.attachChild(child, () => startGroupShutdown("signal"));
  });
}

function signalExitCode(signal) {
  const signalNumber = os.constants.signals[signal];
  return Number.isInteger(signalNumber) ? 128 + signalNumber : 1;
}

async function main() {
  if (process.argv[2] !== "--") fail();
  const argumentsToPsql = process.argv.slice(3);
  const validatedArguments = validatePsqlArguments(argumentsToPsql);

  const input = {
    projectRef: String(process.env.GALLR_VALIDATION_PROJECT_REF || ""),
    databaseUrl: String(process.env.GALLR_VALIDATION_DATABASE_URL || ""),
    requireDirect: String(
      process.env.GALLR_VALIDATION_REQUIRE_DIRECT || ""
    ),
    expectedCertificateSha256: String(
      process.env.GALLR_VALIDATION_SSLROOTCERT_SHA256 || ""
    ),
  };
  const psqlExecutablePath = String(
    process.env.GALLR_VALIDATED_PSQL_PATH || ""
  );
  const psqlExecutableSha256 = String(
    process.env.GALLR_VALIDATED_PSQL_SHA256 || ""
  );
  const sessionConfiguration = validateSessionConfiguration(process.env);
  removeSensitiveEnvironment(process.env);

  let target;
  let transport;
  let cleanupRegistered = false;
  const cleanup = () => cleanupTransportFiles(transport);
  const signalState = installSignalHandlers();
  process.on("exit", cleanup);
  cleanupRegistered = true;

  try {
    target = validateDatabaseTarget(input);
    input.databaseUrl = "";
    const stableCertificate = assertCertificateSourceUnchanged(
      target.certificate
    );
    target.certificate = stableCertificate;
    transport = createTransportFiles(target);
    target.password = "";
    const psqlExecutable = validatePsqlExecutable(
      psqlExecutablePath,
      psqlExecutableSha256
    );
    const safeArgumentsToPsql = snapshotPsqlInputs(
      argumentsToPsql,
      validatedArguments,
      transport
    );
    // Give a signal queued during synchronous validation/snapshotting a chance
    // to run before any database-capable child is spawned.
    await new Promise((resolve) => setImmediate(resolve));
    if (signalState.receivedSignal) {
      const pendingSignal = signalState.receivedSignal;
      cleanup();
      process.removeListener("exit", cleanup);
      cleanupRegistered = false;
      signalState.dispose();
      process.kill(process.pid, pendingSignal);
      process.exit(signalExitCode(pendingSignal));
    }

    const childEnvironment = buildChildEnvironment(
      target,
      transport,
      sessionConfiguration
    );
    const result = await runPsql(
      psqlExecutable,
      safeArgumentsToPsql,
      childEnvironment,
      signalState,
      () => {
        cleanup();
        process.removeListener("exit", cleanup);
        cleanupRegistered = false;
      }
    );

    if (result.signal) {
      process.kill(process.pid, result.signal);
      process.exit(signalExitCode(result.signal));
    }
    process.exitCode = Number.isInteger(result.code) ? result.code : 1;
  } finally {
    if (target) target.password = "";
    signalState.dispose();
    if (cleanupRegistered) {
      cleanup();
      process.removeListener("exit", cleanup);
    } else {
      cleanup();
    }
  }
}

try {
  await main();
} catch (_error) {
  process.stderr.write(genericError);
  process.exitCode = 1;
}
