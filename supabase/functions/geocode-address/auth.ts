import { isAbortError, PayloadReadError, readBoundedJson } from "./http.ts";

const MAX_AUTH_RESPONSE_BYTES = 8 * 1024;
const STAFF_ROLES = new Set(["contributor", "publisher", "admin"]);
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

export type Fetcher = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export type AuthorizationErrorCode =
  | "authentication_required"
  | "geocode_access_required"
  | "active_staff_membership_required"
  | "authorization_configuration_invalid"
  | "authorization_service_unavailable";

export class GeocodeAuthorizationError extends Error {
  constructor(
    readonly code: AuthorizationErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "GeocodeAuthorizationError";
  }
}

/** Compatibility seam for older handler tests and downstream imports. */
export class StaffAuthorizationError extends GeocodeAuthorizationError {
  constructor(code: AuthorizationErrorCode, message: string) {
    super(code, message);
    this.name = "StaffAuthorizationError";
  }
}

export type GeocodeCallerIdentity =
  | {
    callerType: "staff";
    userId: string;
    role: "contributor" | "publisher" | "admin";
  }
  | {
    callerType: "owner";
    userId: string;
    galleryId: string;
  };

export interface GeocodeAuthorizationOptions {
  authorization: string;
  supabaseUrl: string;
  publishableKey: string;
  fetcher: Fetcher;
  signal: AbortSignal;
}

/** Backwards-compatible name used by existing injectable handler tests. */
export type StaffAuthorizationOptions = GeocodeAuthorizationOptions;

function rpcUrl(baseUrl: string): URL {
  let url: URL;
  try {
    url = new URL(baseUrl);
  } catch {
    throw new GeocodeAuthorizationError(
      "authorization_configuration_invalid",
      "SUPABASE_URL is invalid.",
    );
  }

  const localHttp = url.protocol === "http:" &&
    (url.hostname === "127.0.0.1" || url.hostname === "localhost");
  if (url.protocol !== "https:" && !localHttp) {
    throw new GeocodeAuthorizationError(
      "authorization_configuration_invalid",
      "SUPABASE_URL must use HTTPS outside local development.",
    );
  }
  url.pathname = "/rest/v1/rpc/geocode_current_caller";
  url.search = "";
  url.hash = "";
  return url;
}

function validBearerAuthorization(value: string): boolean {
  return value.length <= 8192 && /^Bearer [^\s]+$/u.test(value);
}

function unavailable(
  message = "Geocoding authorization returned an invalid response.",
) {
  return new GeocodeAuthorizationError(
    "authorization_service_unavailable",
    message,
  );
}

function parseCallerIdentity(payload: unknown): GeocodeCallerIdentity {
  if (
    payload === null || typeof payload !== "object" || Array.isArray(payload)
  ) {
    throw unavailable();
  }
  const record = payload as Record<string, unknown>;
  if (
    typeof record.user_id !== "string" || !UUID_PATTERN.test(record.user_id)
  ) {
    throw unavailable();
  }

  if (
    record.caller_type === "staff" &&
    typeof record.role === "string" &&
    STAFF_ROLES.has(record.role) &&
    record.gallery_id === undefined
  ) {
    return {
      callerType: "staff",
      userId: record.user_id,
      role: record.role as "contributor" | "publisher" | "admin",
    };
  }

  if (
    record.caller_type === "owner" &&
    typeof record.gallery_id === "string" &&
    UUID_PATTERN.test(record.gallery_id) &&
    record.role === undefined
  ) {
    return {
      callerType: "owner",
      userId: record.user_id,
      galleryId: record.gallery_id,
    };
  }
  throw unavailable();
}

export async function authorizeGeocodeCaller(
  options: GeocodeAuthorizationOptions,
): Promise<GeocodeCallerIdentity> {
  if (!validBearerAuthorization(options.authorization)) {
    throw new GeocodeAuthorizationError(
      "authentication_required",
      "A valid bearer authorization header is required.",
    );
  }
  if (!options.publishableKey.trim()) {
    throw new GeocodeAuthorizationError(
      "authorization_configuration_invalid",
      "A Supabase publishable key is required.",
    );
  }

  const request = new Request(rpcUrl(options.supabaseUrl), {
    method: "POST",
    headers: {
      Accept: "application/json",
      apikey: options.publishableKey.trim(),
      Authorization: options.authorization,
      "Content-Type": "application/json",
    },
    body: "{}",
    redirect: "error",
    signal: options.signal,
  });

  let response: Response;
  try {
    response = await options.fetcher(request);
  } catch {
    throw unavailable("Geocoding authorization is temporarily unavailable.");
  }

  if (response.status === 401) {
    throw new GeocodeAuthorizationError(
      "authentication_required",
      "A valid bearer authorization header is required.",
    );
  }
  if (response.status === 403) {
    throw new GeocodeAuthorizationError(
      "geocode_access_required",
      "Gallery Info or active staff access is required.",
    );
  }
  if (!response.ok) {
    throw unavailable("Geocoding authorization is temporarily unavailable.");
  }

  let payload: unknown;
  try {
    payload = await readBoundedJson(response, MAX_AUTH_RESPONSE_BYTES);
  } catch (error) {
    if (isAbortError(error)) {
      throw unavailable("Geocoding authorization is temporarily unavailable.");
    }
    if (error instanceof PayloadReadError) throw unavailable();
    throw error;
  }
  return parseCallerIdentity(payload);
}

/** Compatibility alias; authorization now accepts staff or eligible owners. */
export const authorizeActiveStaff = authorizeGeocodeCaller;
