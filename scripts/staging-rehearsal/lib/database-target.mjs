import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const genericMessage = "database target validation failed";
const projectRefPattern = /^[a-z0-9]{20}$/;
const encodedUserInfoPattern =
  /^(?:[A-Za-z0-9._~!$&'()*+,;=:-]|%[0-9A-Fa-f]{2})+$/;
const encodedPathPattern = /^(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+$/;
const unsafeDecodedValuePattern = /[\u0000-\u001f\u007f\u0085\u2028\u2029]/u;
const sha256Pattern = /^[0-9a-f]{64}$/;
// Reviewed Supabase Root 2021 CA PEM bytes. Rotation is intentionally a code
// change so a caller-controlled URI cannot substitute another self-signed CA.
export const approvedCertificateSha256 =
  "700723581420dd1ac98fd7e9ac529f0ef210eadcaf87fc868a3ad7d114c2f3b7";
const maximumUriBytes = 16 * 1024;
const maximumPasswordBytes = 4 * 1024;
const maximumCertificatePathBytes = 4 * 1024;
const maximumCertificateBytes = 64 * 1024;

export class DatabaseTargetValidationError extends Error {
  constructor() {
    super(genericMessage);
    this.name = "DatabaseTargetValidationError";
  }
}

function reject() {
  throw new DatabaseTargetValidationError();
}

function strictDecode(value) {
  try {
    return decodeURIComponent(value);
  } catch (_error) {
    reject();
  }
}

function assertSafeDecodedValue(value, maximumBytes) {
  if (
    value.length === 0 ||
    Buffer.byteLength(value, "utf8") > maximumBytes ||
    unsafeDecodedValuePattern.test(value)
  ) {
    reject();
  }
}

function parseRawQuery(rawQuery) {
  const pairs = rawQuery.split("&");
  if (pairs.length !== 2) reject();

  const values = new Map();
  for (const pair of pairs) {
    const separator = pair.indexOf("=");
    if (
      separator <= 0 ||
      separator !== pair.lastIndexOf("=") ||
      separator === pair.length - 1
    ) {
      reject();
    }

    const key = pair.slice(0, separator);
    const value = pair.slice(separator + 1);
    if (
      !new Set(["sslmode", "sslrootcert"]).has(key) ||
      values.has(key)
    ) {
      reject();
    }
    values.set(key, value);
  }

  if (values.get("sslmode") !== "verify-full") reject();

  const encodedCertificatePath = values.get("sslrootcert");
  if (
    typeof encodedCertificatePath !== "string" ||
    !encodedPathPattern.test(encodedCertificatePath) ||
    !/^%2f/i.test(encodedCertificatePath)
  ) {
    reject();
  }

  const certificatePath = strictDecode(encodedCertificatePath);
  assertSafeDecodedValue(certificatePath, maximumCertificatePathBytes);
  if (
    !path.posix.isAbsolute(certificatePath) ||
    path.posix.normalize(certificatePath) !== certificatePath ||
    certificatePath.startsWith("//")
  ) {
    reject();
  }

  return certificatePath;
}

function statIdentity(stat) {
  return [
    stat.dev,
    stat.ino,
    stat.size,
    stat.mtimeNs,
    stat.ctimeNs,
    stat.mode,
    stat.uid,
    stat.nlink,
  ].map(String).join(":");
}

function assertOwnedSecureFile(stat) {
  const effectiveUid =
    typeof process.geteuid === "function" ? BigInt(process.geteuid()) : stat.uid;
  const permissions = Number(stat.mode & 0o7777n);
  if (
    !stat.isFile() ||
    stat.isSymbolicLink() ||
    stat.uid !== effectiveUid ||
    stat.nlink !== 1n ||
    !new Set([0o400, 0o600]).has(permissions) ||
    stat.size <= 0n ||
    stat.size > BigInt(maximumCertificateBytes)
  ) {
    reject();
  }
}

function assertSecureParentDirectory(parentPath) {
  let stat;
  try {
    stat = fs.lstatSync(parentPath, { bigint: true });
  } catch (_error) {
    reject();
  }

  const effectiveUid =
    typeof process.geteuid === "function" ? BigInt(process.geteuid()) : stat.uid;
  if (
    !stat.isDirectory() ||
    stat.isSymbolicLink() ||
    stat.uid !== effectiveUid ||
    Number(stat.mode & 0o7777n) !== 0o700
  ) {
    reject();
  }
}

function validateCertificateBytes(bytes) {
  if (
    !Buffer.isBuffer(bytes) ||
    bytes.length === 0 ||
    bytes.length > maximumCertificateBytes
  ) {
    reject();
  }

  let certificate;
  try {
    certificate = new crypto.X509Certificate(bytes);
  } catch (_error) {
    reject();
  }

  let selfSignatureValid = false;
  try {
    selfSignatureValid =
      certificate.ca === true &&
      certificate.checkIssued(certificate) &&
      certificate.verify(certificate.publicKey);
  } catch (_error) {
    reject();
  }

  const validFrom = Date.parse(certificate.validFrom);
  const validTo = Date.parse(certificate.validTo);
  const now = Date.now();
  if (
    !selfSignatureValid ||
    !Number.isFinite(validFrom) ||
    !Number.isFinite(validTo) ||
    validFrom > now ||
    validTo <= now
  ) {
    reject();
  }
}

function readSecureCertificate(sourcePath) {
  let resolvedPath;
  let lstat;
  try {
    if (!path.isAbsolute(sourcePath)) reject();
    resolvedPath = fs.realpathSync.native(sourcePath);
    if (path.resolve(sourcePath) !== resolvedPath) reject();
    assertSecureParentDirectory(path.dirname(resolvedPath));
    lstat = fs.lstatSync(resolvedPath, { bigint: true });
    assertOwnedSecureFile(lstat);
  } catch (error) {
    if (error instanceof DatabaseTargetValidationError) throw error;
    reject();
  }

  const noFollow = fs.constants.O_NOFOLLOW ?? 0;
  let descriptor;
  let before;
  let after;
  let bytes;
  try {
    descriptor = fs.openSync(
      resolvedPath,
      fs.constants.O_RDONLY | noFollow
    );
    before = fs.fstatSync(descriptor, { bigint: true });
    assertOwnedSecureFile(before);
    if (statIdentity(before) !== statIdentity(lstat)) reject();
    bytes = fs.readFileSync(descriptor);
    after = fs.fstatSync(descriptor, { bigint: true });
    assertOwnedSecureFile(after);
  } catch (error) {
    if (error instanceof DatabaseTargetValidationError) throw error;
    reject();
  } finally {
    if (descriptor !== undefined) {
      try {
        fs.closeSync(descriptor);
      } catch (_error) {
        // A close failure is handled by the stability checks below.
      }
    }
  }

  if (statIdentity(before) !== statIdentity(after)) reject();
  validateCertificateBytes(bytes);

  return {
    sourcePath: resolvedPath,
    bytes,
    sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
    statIdentity: statIdentity(after),
  };
}

export function validateDatabaseTarget({
  projectRef,
  databaseUrl,
  requireDirect,
  expectedCertificateSha256 = approvedCertificateSha256,
}) {
  try {
    const effectiveCertificateSha256 =
      expectedCertificateSha256 === ""
        ? approvedCertificateSha256
        : expectedCertificateSha256;
    if (
      !projectRefPattern.test(projectRef) ||
      !new Set(["true", "false"]).has(requireDirect) ||
      typeof databaseUrl !== "string" ||
      databaseUrl.length === 0 ||
      Buffer.byteLength(databaseUrl, "utf8") > maximumUriBytes ||
      databaseUrl.trim() !== databaseUrl ||
      unsafeDecodedValuePattern.test(databaseUrl)
    ) {
      reject();
    }
    if (
      !sha256Pattern.test(effectiveCertificateSha256) ||
      effectiveCertificateSha256 !== approvedCertificateSha256
    ) {
      reject();
    }

    const uriMatch =
      /^(postgres|postgresql):\/\/([^/?#]+)\/postgres\?([^?#]+)$/.exec(
        databaseUrl
      );
    if (!uriMatch) reject();

    const [, scheme, authority, rawQuery] = uriMatch;
    const atIndex = authority.indexOf("@");
    if (atIndex <= 0 || atIndex !== authority.lastIndexOf("@")) reject();

    const userInfo = authority.slice(0, atIndex);
    const hostInfo = authority.slice(atIndex + 1);
    const passwordSeparator = userInfo.indexOf(":");
    if (
      passwordSeparator <= 0 ||
      passwordSeparator === userInfo.length - 1
    ) {
      reject();
    }

    const encodedUsername = userInfo.slice(0, passwordSeparator);
    const encodedPassword = userInfo.slice(passwordSeparator + 1);
    if (!encodedUserInfoPattern.test(encodedPassword)) reject();

    const password = strictDecode(encodedPassword);
    assertSafeDecodedValue(password, maximumPasswordBytes);

    const hostMatch =
      /^([a-z0-9-]+(?:\.[a-z0-9-]+)*)(?::([0-9]+))?$/.exec(hostInfo);
    if (!hostMatch) reject();

    const host = hostMatch[1];
    const explicitPort = hostMatch[2] || "";
    const direct =
      host === `db.${projectRef}.supabase.co` &&
      encodedUsername === "postgres" &&
      (explicitPort === "" || explicitPort === "5432");
    const pooler =
      /^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.pooler\.supabase\.com$/.test(host) &&
      encodedUsername === `postgres.${projectRef}` &&
      new Set(["", "5432", "6543"]).has(explicitPort);

    if ((!direct && !pooler) || (requireDirect === "true" && !direct)) {
      reject();
    }

    let parsed;
    try {
      parsed = new URL(databaseUrl);
    } catch (_error) {
      reject();
    }
    if (
      parsed.protocol !== `${scheme}:` ||
      parsed.hostname !== host ||
      parsed.port !== explicitPort ||
      parsed.pathname !== "/postgres" ||
      parsed.hash !== ""
    ) {
      reject();
    }

    const certificatePath = parseRawQuery(rawQuery);
    const certificate = readSecureCertificate(certificatePath);
    if (certificate.sha256 !== effectiveCertificateSha256) {
      reject();
    }

    return {
      kind: direct ? "direct" : "pooler",
      host,
      port: explicitPort || "5432",
      database: "postgres",
      user: encodedUsername,
      password,
      sslmode: "verify-full",
      certificate,
    };
  } catch (error) {
    if (error instanceof DatabaseTargetValidationError) throw error;
    reject();
  }
}

export function assertCertificateSourceUnchanged(certificate) {
  try {
    const current = readSecureCertificate(certificate.sourcePath);
    if (
      current.sourcePath !== certificate.sourcePath ||
      current.sha256 !== certificate.sha256 ||
      current.statIdentity !== certificate.statIdentity
    ) {
      reject();
    }
    return current;
  } catch (error) {
    if (error instanceof DatabaseTargetValidationError) throw error;
    reject();
  }
}
