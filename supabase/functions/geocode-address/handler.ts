import {
  authorizeActiveStaff,
  type Fetcher,
  StaffAuthorizationError,
} from "./auth.ts";
import {
  buildNaverGeocodeRequest,
  GeocodingProviderError,
  parseNaverGeocodeResponse,
} from "./geocode.ts";
import {
  containsAsciiControlCharacters,
  isAbortError,
  PayloadReadError,
  readBoundedJson,
} from "./http.ts";
import {
  consumeGeocodeRateLimit,
  RateLimitServiceError,
} from "./rate_limit.ts";

const MAX_REQUEST_BYTES = 2 * 1024;
const MAX_PROVIDER_RESPONSE_BYTES = 64 * 1024;
const AUTHORIZATION_TIMEOUT_MS = 5_000;
const RATE_LIMIT_TIMEOUT_MS = 5_000;
export const PROVIDER_TIMEOUT_MS = 5_000;
const DEFAULT_PROVIDER_RETRY_AFTER_SECONDS = 60;
const MAX_PROVIDER_RETRY_AFTER_SECONDS = 3_600;

export const CORS_HEADERS = {
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Max-Age": "86400",
} as const;

type LogLevel = "info" | "warn" | "error";

interface LogEvent {
  event: string;
  request_id: string;
  code?: string;
  status?: number;
  candidate_count?: number;
}

/** Injectable runtime seams used by the Edge handler and its no-network tests. */
export interface HandlerDependencies {
  env?: (name: string) => string | undefined;
  fetcher?: Fetcher;
  requestId?: () => string;
  log?: (level: LogLevel, event: LogEvent) => void;
  authorizeStaff?: typeof authorizeActiveStaff;
  consumeRateLimit?: typeof consumeGeocodeRateLimit;
}

interface ErrorBody {
  error: {
    code: string;
    message: string;
  };
}

class RequestValidationError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
    readonly headers?: HeadersInit,
  ) {
    super(message);
    this.name = "RequestValidationError";
  }
}

function defaultLog(level: LogLevel, event: LogEvent): void {
  const output = JSON.stringify(event);
  if (level === "error") console.error(output);
  else if (level === "warn") console.warn(output);
  else console.info(output);
}

function responseHeaders(requestId: string): Headers {
  return new Headers({
    ...CORS_HEADERS,
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "X-Content-Type-Options": "nosniff",
    "X-Request-Id": requestId,
  });
}

function jsonResponse(
  body: unknown,
  status: number,
  requestId: string,
  extraHeaders?: HeadersInit,
): Response {
  const headers = responseHeaders(requestId);
  if (extraHeaders) {
    new Headers(extraHeaders).forEach((value, key) => headers.set(key, value));
  }
  return new Response(JSON.stringify(body), { status, headers });
}

function errorResponse(
  code: string,
  message: string,
  status: number,
  requestId: string,
  extraHeaders?: HeadersInit,
): Response {
  const body: ErrorBody = { error: { code, message } };
  return jsonResponse(body, status, requestId, extraHeaders);
}

function requiredEnv(
  env: (name: string) => string | undefined,
  name: string,
): string {
  const value = env(name)?.trim();
  if (!value) {
    throw new RequestValidationError(
      "server_configuration_missing",
      "The geocoding service is not configured.",
      500,
    );
  }
  return value;
}

function publishableKey(env: (name: string) => string | undefined): string {
  const direct = env("SUPABASE_PUBLISHABLE_KEY")?.trim() ||
    env("SUPABASE_ANON_KEY")?.trim();
  if (direct) return direct;

  const encoded = env("SUPABASE_PUBLISHABLE_KEYS")?.trim();
  if (encoded) {
    try {
      const parsed: unknown = JSON.parse(encoded);
      if (typeof parsed === "string" && parsed.trim()) return parsed.trim();
      if (
        parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)
      ) {
        const keys = parsed as Record<string, unknown>;
        for (const name of ["default", "anon", "geocode-address"]) {
          const value = keys[name];
          if (typeof value === "string" && value.trim()) return value.trim();
        }
        const first = Object.values(keys).find((value) =>
          typeof value === "string" && value.trim()
        );
        if (typeof first === "string") return first.trim();
      }
    } catch {
      // Fall through to one generic configuration error. Never include the
      // environment value in logs or responses.
    }
  }
  throw new RequestValidationError(
    "server_configuration_missing",
    "The geocoding service is not configured.",
    500,
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function validatedAddress(payload: unknown): string {
  if (!isRecord(payload) || typeof payload.address !== "string") {
    throw new RequestValidationError(
      "address_required",
      "address must be a string.",
      400,
    );
  }
  const address = payload.address.trim().normalize("NFC");
  if (
    address.length < 2 || address.length > 300 ||
    containsAsciiControlCharacters(address)
  ) {
    throw new RequestValidationError(
      "address_invalid",
      "address must contain between 2 and 300 valid characters.",
      400,
    );
  }
  return address;
}

function validatedRetryAfter(
  value: string | null,
  fallbackSeconds: number,
): string {
  if (/^[1-9]\d{0,3}$/u.test(value ?? "")) {
    const seconds = Number(value);
    if (
      Number.isSafeInteger(seconds) &&
      seconds <= MAX_PROVIDER_RETRY_AFTER_SECONDS
    ) {
      return String(seconds);
    }
  }
  return String(fallbackSeconds);
}

function providerStatusError(response: Response): RequestValidationError {
  if (response.status === 401 || response.status === 403) {
    return new RequestValidationError(
      "geocoding_provider_configuration_error",
      "The geocoding provider rejected the server configuration.",
      502,
    );
  }
  if (response.status === 429) {
    return new RequestValidationError(
      "geocoding_provider_rate_limited",
      "The geocoding provider rate limit was reached.",
      429,
      {
        "Retry-After": validatedRetryAfter(
          response.headers.get("retry-after"),
          DEFAULT_PROVIDER_RETRY_AFTER_SECONDS,
        ),
      },
    );
  }
  if (response.status === 503) {
    return new RequestValidationError(
      "geocoding_provider_unavailable",
      "The geocoding provider is temporarily unavailable.",
      503,
    );
  }
  if (response.status === 504) {
    return new RequestValidationError(
      "geocoding_provider_timeout",
      "The geocoding provider timed out.",
      504,
    );
  }
  return new RequestValidationError(
    "geocoding_provider_unavailable",
    "The geocoding provider is temporarily unavailable.",
    502,
  );
}

export function createGeocodeHandler(
  dependencies: HandlerDependencies = {},
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const fetcher = dependencies.fetcher ?? fetch;
  const requestId = dependencies.requestId ?? (() => crypto.randomUUID());
  const log = dependencies.log ?? defaultLog;
  const authorizeStaff = dependencies.authorizeStaff ?? authorizeActiveStaff;
  const consumeRateLimit = dependencies.consumeRateLimit ??
    consumeGeocodeRateLimit;

  return async (request: Request): Promise<Response> => {
    const id = requestId();

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: { ...CORS_HEADERS, "X-Request-Id": id },
      });
    }
    if (request.method !== "POST") {
      return errorResponse(
        "method_not_allowed",
        "Only POST is supported.",
        405,
        id,
        { Allow: "POST, OPTIONS" },
      );
    }

    try {
      const authorization = request.headers.get("authorization") ?? "";
      await authorizeStaff({
        authorization,
        supabaseUrl: requiredEnv(env, "SUPABASE_URL"),
        publishableKey: publishableKey(env),
        fetcher,
        signal: AbortSignal.timeout(AUTHORIZATION_TIMEOUT_MS),
      });

      const contentType = request.headers.get("content-type") ?? "";
      if (!contentType.toLowerCase().startsWith("application/json")) {
        throw new RequestValidationError(
          "unsupported_media_type",
          "Content-Type must be application/json.",
          415,
        );
      }

      let payload: unknown;
      try {
        payload = await readBoundedJson(request, MAX_REQUEST_BYTES);
      } catch (error) {
        if (error instanceof PayloadReadError) {
          throw new RequestValidationError(
            error.code === "payload_too_large"
              ? "request_too_large"
              : "invalid_json",
            error.code === "payload_too_large"
              ? "Request body exceeds the size limit."
              : "Request body must be valid JSON.",
            error.code === "payload_too_large" ? 413 : 400,
          );
        }
        throw error;
      }
      const address = validatedAddress(payload);

      const providerRequest = buildNaverGeocodeRequest(
        address,
        requiredEnv(env, "NAVER_MAPS_API_KEY_ID"),
        requiredEnv(env, "NAVER_MAPS_API_KEY"),
      );

      const rateLimit = await consumeRateLimit({
        authorization,
        supabaseUrl: requiredEnv(env, "SUPABASE_URL"),
        publishableKey: publishableKey(env),
        fetcher,
        signal: AbortSignal.timeout(RATE_LIMIT_TIMEOUT_MS),
      });
      if (!rateLimit.allowed) {
        throw new RequestValidationError(
          "geocoding_rate_limited",
          "Too many geocoding requests. Try again shortly.",
          429,
          { "Retry-After": String(rateLimit.retryAfterSeconds) },
        );
      }

      let providerResponse: Response;
      try {
        providerResponse = await fetcher(providerRequest, {
          signal: AbortSignal.timeout(PROVIDER_TIMEOUT_MS),
        });
      } catch (error) {
        if (isAbortError(error)) {
          throw new RequestValidationError(
            "geocoding_provider_timeout",
            "The geocoding provider timed out.",
            504,
          );
        }
        throw new RequestValidationError(
          "geocoding_provider_unavailable",
          "The geocoding provider is temporarily unavailable.",
          502,
        );
      }

      if (!providerResponse.ok) {
        throw providerStatusError(providerResponse);
      }

      let providerPayload: unknown;
      try {
        providerPayload = await readBoundedJson(
          providerResponse,
          MAX_PROVIDER_RESPONSE_BYTES,
        );
      } catch (error) {
        if (isAbortError(error)) {
          throw new RequestValidationError(
            "geocoding_provider_timeout",
            "The geocoding provider timed out.",
            504,
          );
        }
        if (error instanceof PayloadReadError) {
          throw new RequestValidationError(
            error.code === "payload_too_large"
              ? "geocoding_provider_response_too_large"
              : "geocoding_provider_response_invalid",
            "The geocoding provider returned an invalid response.",
            502,
          );
        }
        throw error;
      }

      const candidates = parseNaverGeocodeResponse(providerPayload);
      log("info", {
        event: "geocode_address_completed",
        request_id: id,
        candidate_count: candidates.length,
      });
      return jsonResponse({ candidates }, 200, id);
    } catch (error) {
      if (error instanceof StaffAuthorizationError) {
        const status = error.code === "authentication_required"
          ? 401
          : error.code === "active_staff_membership_required"
          ? 403
          : error.code === "authorization_configuration_invalid"
          ? 500
          : 503;
        log(status >= 500 ? "error" : "warn", {
          event: "geocode_address_rejected",
          request_id: id,
          code: error.code,
          status,
        });
        return errorResponse(error.code, error.message, status, id);
      }
      if (error instanceof GeocodingProviderError) {
        log("error", {
          event: "geocode_address_failed",
          request_id: id,
          code: error.code,
          status: 502,
        });
        return errorResponse(
          "geocoding_provider_response_invalid",
          "The geocoding provider returned an invalid response.",
          502,
          id,
        );
      }
      if (error instanceof RateLimitServiceError) {
        const status = error.code === "rate_limit_configuration_invalid"
          ? 500
          : 503;
        log("error", {
          event: "geocode_address_failed",
          request_id: id,
          code: error.code,
          status,
        });
        return errorResponse(error.code, error.message, status, id);
      }
      if (error instanceof RequestValidationError) {
        log(error.status >= 500 ? "error" : "warn", {
          event: "geocode_address_failed",
          request_id: id,
          code: error.code,
          status: error.status,
        });
        return errorResponse(
          error.code,
          error.message,
          error.status,
          id,
          error.headers,
        );
      }

      log("error", {
        event: "geocode_address_failed",
        request_id: id,
        code: "internal_error",
        status: 500,
      });
      return errorResponse(
        "internal_error",
        "The geocoding request could not be completed.",
        500,
        id,
      );
    }
  };
}
