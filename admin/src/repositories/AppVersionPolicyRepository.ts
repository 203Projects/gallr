/**
 * Spec 098: the per-platform minimum and recommended app versions that the
 * mobile app reads at launch. Staff raise the minimum here; no release needed.
 */
export type AppPlatform = "android" | "ios";

export const APP_PLATFORMS: readonly AppPlatform[] = ["android", "ios"];

export interface AppVersionPolicy {
  platform: AppPlatform;
  minimumVersion: string;
  recommendedVersion: string;
  latestReleasedVersion: string | null;
  updatedAt: string | null;
  /** Null for the seeded row and when the staff account is gone. */
  updatedByEmail: string | null;
}

export interface AppVersionPolicyInput {
  minimumVersion: string;
  recommendedVersion: string;
  latestReleasedVersion: string | null;
}

export type AppVersionPolicyRejection =
  | "unknown_platform"
  | "invalid_version"
  | "recommended_below_minimum";

const APP_VERSION_PATTERN = /^\d+\.\d+\.\d+$/u;

/** Three numbers separated by dots; the server enforces the same shape. */
export function isAppVersion(text: string): boolean {
  return APP_VERSION_PATTERN.test(text);
}

/** Numeric by major, minor, patch: 1.14.10 is newer than 1.14.9. Both inputs must pass `isAppVersion`. */
export function compareAppVersions(left: string, right: string): number {
  const a = left.split(".").map(Number);
  const b = right.split(".").map(Number);
  for (let index = 0; index < 3; index += 1) {
    if (a[index] !== b[index]) return a[index] < b[index] ? -1 : 1;
  }
  return 0;
}

export class AppVersionPolicyNotStaffError extends Error {
  constructor() {
    super("Only admins can change app versions.");
    this.name = "AppVersionPolicyNotStaffError";
  }
}

export class AppVersionPolicyRejectedError extends Error {
  constructor(readonly reason: AppVersionPolicyRejection) {
    super(`The server refused the app version policy: ${reason}.`);
    this.name = "AppVersionPolicyRejectedError";
  }
}

export class MalformedAppVersionPolicyPayloadError extends Error {
  constructor(readonly rpcName: string) {
    super(`${rpcName} returned an invalid response.`);
    this.name = "MalformedAppVersionPolicyPayloadError";
  }
}

export interface AppVersionPolicyRepository {
  list(): Promise<AppVersionPolicy[]>;
  save(platform: AppPlatform, input: AppVersionPolicyInput): Promise<AppVersionPolicy>;
}

function isAppPlatform(value: unknown): value is AppPlatform {
  return value === "android" || value === "ios";
}

function optionalString(value: unknown): value is string | null {
  return value === null || value === undefined || typeof value === "string";
}

/** One wire row from either staff RPC; anything off-shape fails closed. */
export function mapAppVersionPolicy(raw: unknown, rpcName: string): AppVersionPolicy {
  if (typeof raw !== "object" || raw === null) throw new MalformedAppVersionPolicyPayloadError(rpcName);
  const row = raw as Record<string, unknown>;
  const minimum = row.minimum_version;
  const recommended = row.recommended_version;
  const latest = row.latest_released_version;
  const updatedAt = row.updated_at;
  const updatedByEmail = row.updated_by_email;
  if (
    !isAppPlatform(row.platform)
    || typeof minimum !== "string" || !isAppVersion(minimum)
    || typeof recommended !== "string" || !isAppVersion(recommended)
    || !optionalString(latest) || (typeof latest === "string" && !isAppVersion(latest))
    || !optionalString(updatedAt)
    || !optionalString(updatedByEmail)
  ) {
    throw new MalformedAppVersionPolicyPayloadError(rpcName);
  }
  return {
    platform: row.platform,
    minimumVersion: minimum,
    recommendedVersion: recommended,
    latestReleasedVersion: latest ?? null,
    updatedAt: updatedAt ?? null,
    updatedByEmail: updatedByEmail ?? null,
  };
}

/** The server's own checks, applied before a write so the in-memory fixture refuses what the RPC refuses. */
export function appVersionPolicyRejection(
  platform: string,
  input: AppVersionPolicyInput,
): AppVersionPolicyRejection | null {
  if (!isAppPlatform(platform)) return "unknown_platform";
  if (
    !isAppVersion(input.minimumVersion)
    || !isAppVersion(input.recommendedVersion)
    || (input.latestReleasedVersion !== null && !isAppVersion(input.latestReleasedVersion))
  ) {
    return "invalid_version";
  }
  if (compareAppVersions(input.recommendedVersion, input.minimumVersion) < 0) return "recommended_below_minimum";
  return null;
}
