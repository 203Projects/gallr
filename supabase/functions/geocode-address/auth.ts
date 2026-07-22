import { isAbortError, PayloadReadError, readBoundedJson } from "./http.ts";

const MAX_AUTH_RESPONSE_BYTES = 8 * 1024;
const STAFF_ROLES = new Set(["contributor", "publisher", "admin"]);
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

export type Fetcher = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

type AuthorizationErrorCode =
  | "authentication_required"
  | "active_staff_membership_required"
  | "authorization_configuration_invalid"
  | "authorization_service_unavailable";

export class StaffAuthorizationError extends Error {
  constructor(
    readonly code: AuthorizationErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "StaffAuthorizationError";
  }
}

export interface StaffIdentity {
  userId: string;
  role: "contributor" | "publisher" | "admin";
}

export interface StaffAuthorizationOptions {
  authorization: string;
  supabaseUrl: string;
  publishableKey: string;
  fetcher: Fetcher;
  signal: AbortSignal;
}

function rpcUrl(baseUrl: string): URL {
  let url: URL;
  try {
    url = new URL(baseUrl);
  } catch {
    throw new StaffAuthorizationError(
      "authorization_configuration_invalid",
      "SUPABASE_URL is invalid.",
    );
  }

  const localHttp = url.protocol === "http:" &&
    (url.hostname === "127.0.0.1" || url.hostname === "localhost");
  if (url.protocol !== "https:" && !localHttp) {
    throw new StaffAuthorizationError(
      "authorization_configuration_invalid",
      "SUPABASE_URL must use HTTPS outside local development.",
    );
  }
  url.pathname = "/rest/v1/rpc/admin_current_staff";
  url.search = "";
  url.hash = "";
  return url;
}

function validBearerAuthorization(value: string): boolean {
  if (value.length > 8192) return false;
  return /^Bearer [^\s]+$/u.test(value);
}

function parseStaffIdentity(payload: unknown): StaffIdentity {
  if (
    payload === null || typeof payload !== "object" || Array.isArray(payload)
  ) {
    throw new StaffAuthorizationError(
      "authorization_service_unavailable",
      "Staff authorization returned an invalid response.",
    );
  }
  const record = payload as Record<string, unknown>;
  if (record.active !== true) {
    throw new StaffAuthorizationError(
      "active_staff_membership_required",
      "An active staff membership is required.",
    );
  }
  if (
    typeof record.user_id !== "string" ||
    !UUID_PATTERN.test(record.user_id) || typeof record.role !== "string" ||
    !STAFF_ROLES.has(record.role)
  ) {
    throw new StaffAuthorizationError(
      "authorization_service_unavailable",
      "Staff authorization returned an invalid response.",
    );
  }
  return {
    userId: record.user_id,
    role: record.role as StaffIdentity["role"],
  };
}

export async function authorizeActiveStaff(
  options: StaffAuthorizationOptions,
): Promise<StaffIdentity> {
  if (!validBearerAuthorization(options.authorization)) {
    throw new StaffAuthorizationError(
      "authentication_required",
      "A valid bearer authorization header is required.",
    );
  }
  if (!options.publishableKey.trim()) {
    throw new StaffAuthorizationError(
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
    throw new StaffAuthorizationError(
      "authorization_service_unavailable",
      "Staff authorization is temporarily unavailable.",
    );
  }

  if (response.status === 401) {
    throw new StaffAuthorizationError(
      "authentication_required",
      "A valid bearer authorization header is required.",
    );
  }
  if (response.status === 403) {
    throw new StaffAuthorizationError(
      "active_staff_membership_required",
      "An active staff membership is required.",
    );
  }
  if (!response.ok) {
    throw new StaffAuthorizationError(
      "authorization_service_unavailable",
      "Staff authorization is temporarily unavailable.",
    );
  }

  let payload: unknown;
  try {
    payload = await readBoundedJson(response, MAX_AUTH_RESPONSE_BYTES);
  } catch (error) {
    if (isAbortError(error)) {
      throw new StaffAuthorizationError(
        "authorization_service_unavailable",
        "Staff authorization is temporarily unavailable.",
      );
    }
    if (error instanceof PayloadReadError) {
      throw new StaffAuthorizationError(
        "authorization_service_unavailable",
        "Staff authorization returned an invalid response.",
      );
    }
    throw error;
  }
  return parseStaffIdentity(payload);
}
