import { type Fetcher } from "./auth.ts";
import { isAbortError, PayloadReadError, readBoundedJson } from "./http.ts";

const MAX_RATE_LIMIT_RESPONSE_BYTES = 8 * 1024;
const MAX_RETRY_AFTER_SECONDS = 60;

type RateLimitServiceErrorCode =
  | "rate_limit_configuration_invalid"
  | "rate_limit_service_unavailable";

export class RateLimitServiceError extends Error {
  constructor(
    readonly code: RateLimitServiceErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "RateLimitServiceError";
  }
}

/** Validated result from the atomic database-backed geocoding quota RPC. */
export type RateLimitDecision =
  | {
    allowed: true;
    retryAfterSeconds: 0;
    limitedBy: null;
  }
  | {
    allowed: false;
    retryAfterSeconds: number;
    limitedBy: "staff" | "owner" | "project";
  };

/** Caller-scoped inputs used to invoke the private quota through PostgREST. */
export interface RateLimitOptions {
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
    throw new RateLimitServiceError(
      "rate_limit_configuration_invalid",
      "SUPABASE_URL is invalid.",
    );
  }

  const localHttp = url.protocol === "http:" &&
    (url.hostname === "127.0.0.1" || url.hostname === "localhost");
  if (url.protocol !== "https:" && !localHttp) {
    throw new RateLimitServiceError(
      "rate_limit_configuration_invalid",
      "SUPABASE_URL must use HTTPS outside local development.",
    );
  }
  url.pathname = "/rest/v1/rpc/geocode_consume_rate_limit";
  url.search = "";
  url.hash = "";
  return url;
}

function validBearerAuthorization(value: string): boolean {
  return value.length <= 8192 && /^Bearer [^\s]+$/u.test(value);
}

function serviceUnavailable(): RateLimitServiceError {
  return new RateLimitServiceError(
    "rate_limit_service_unavailable",
    "Geocoding rate limiting is temporarily unavailable.",
  );
}

function parseDecision(payload: unknown): RateLimitDecision {
  if (
    payload === null || typeof payload !== "object" || Array.isArray(payload)
  ) {
    throw serviceUnavailable();
  }

  const record = payload as Record<string, unknown>;
  if (
    record.allowed === true && record.retry_after_seconds === 0 &&
    record.limited_by === null
  ) {
    return { allowed: true, retryAfterSeconds: 0, limitedBy: null };
  }

  if (
    record.allowed !== false ||
    !Number.isSafeInteger(record.retry_after_seconds) ||
    (record.retry_after_seconds as number) < 1 ||
    (record.retry_after_seconds as number) > MAX_RETRY_AFTER_SECONDS ||
    (record.limited_by !== "staff" && record.limited_by !== "owner" &&
      record.limited_by !== "project")
  ) {
    throw serviceUnavailable();
  }

  return {
    allowed: false,
    retryAfterSeconds: record.retry_after_seconds as number,
    limitedBy: record.limited_by,
  };
}

/** Atomically consumes both staff and project quota, failing closed on RPC errors. */
export async function consumeGeocodeRateLimit(
  options: RateLimitOptions,
): Promise<RateLimitDecision> {
  if (
    !validBearerAuthorization(options.authorization) ||
    !options.publishableKey.trim()
  ) {
    throw new RateLimitServiceError(
      "rate_limit_configuration_invalid",
      "A bearer authorization header and Supabase publishable key are required.",
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
    throw serviceUnavailable();
  }

  if (!response.ok) throw serviceUnavailable();

  let payload: unknown;
  try {
    payload = await readBoundedJson(
      response,
      MAX_RATE_LIMIT_RESPONSE_BYTES,
    );
  } catch (error) {
    if (isAbortError(error) || error instanceof PayloadReadError) {
      throw serviceUnavailable();
    }
    throw error;
  }
  return parseDecision(payload);
}
