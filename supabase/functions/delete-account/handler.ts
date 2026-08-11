import {
  type AccountDeletionBackend,
  AccountDeletionBackendError,
  createAccountDeletionBackend,
} from "./backend.ts";

const CORS_HEADERS = {
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Max-Age": "86400",
} as const;

type LogLevel = "info" | "warn" | "error";

interface Dependencies {
  env?: (name: string) => string | undefined;
  backend?: AccountDeletionBackend;
  requestId?: () => string;
  log?: (
    level: LogLevel,
    event: { event: string; request_id: string; code?: string },
  ) => void;
}

function defaultLog(
  level: LogLevel,
  event: { event: string; request_id: string; code?: string },
): void {
  const output = JSON.stringify(event);
  if (level === "error") console.error(output);
  else if (level === "warn") console.warn(output);
  else console.info(output);
}

function headers(requestId: string): Headers {
  return new Headers({
    ...CORS_HEADERS,
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "X-Content-Type-Options": "nosniff",
    "X-Request-Id": requestId,
  });
}

function json(
  requestId: string,
  status: number,
  body: Record<string, unknown>,
  additionalHeaders?: HeadersInit,
): Response {
  const responseHeaders = headers(requestId);
  if (additionalHeaders) {
    new Headers(additionalHeaders).forEach((value, key) =>
      responseHeaders.set(key, value)
    );
  }
  return new Response(JSON.stringify(body), {
    status,
    headers: responseHeaders,
  });
}

function error(
  requestId: string,
  status: number,
  code: string,
  message: string,
  additionalHeaders?: HeadersInit,
): Response {
  return json(
    requestId,
    status,
    { error: { code, message } },
    additionalHeaders,
  );
}

function validAuthorization(value: string | null): value is string {
  return value !== null && value.length <= 8192 &&
    /^Bearer [^\s]+$/u.test(value);
}

function environment(
  read: (name: string) => string | undefined,
): Record<string, string | undefined> {
  return {
    SUPABASE_URL: read("SUPABASE_URL"),
    SUPABASE_PUBLISHABLE_KEYS: read("SUPABASE_PUBLISHABLE_KEYS"),
    SUPABASE_PUBLISHABLE_KEY: read("SUPABASE_PUBLISHABLE_KEY"),
    SUPABASE_ANON_KEY: read("SUPABASE_ANON_KEY"),
    SUPABASE_SECRET_KEYS: read("SUPABASE_SECRET_KEYS"),
    SUPABASE_SECRET_KEY: read("SUPABASE_SECRET_KEY"),
    SUPABASE_SERVICE_ROLE_KEY: read("SUPABASE_SERVICE_ROLE_KEY"),
  };
}

export function createDeleteAccountHandler(
  dependencies: Dependencies = {},
): (request: Request) => Promise<Response> {
  const requestId = dependencies.requestId ?? (() => crypto.randomUUID());
  const log = dependencies.log ?? defaultLog;
  const env = dependencies.env ?? ((name) => Deno.env.get(name));

  return async (request) => {
    const id = requestId();
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }
    if (request.method !== "POST") {
      return error(id, 405, "method_not_allowed", "Use POST.", {
        Allow: "POST, OPTIONS",
      });
    }

    const authorization = request.headers.get("authorization");
    if (!validAuthorization(authorization)) {
      return error(
        id,
        401,
        "authentication_required",
        "A valid authenticated session is required.",
      );
    }

    let backend: AccountDeletionBackend;
    let userId: string;
    try {
      backend = dependencies.backend ??
        createAccountDeletionBackend(environment(env));
      userId = await backend.authenticate(authorization);
    } catch (backendError) {
      const code = backendError instanceof AccountDeletionBackendError
        ? backendError.code
        : "authentication_unavailable";
      log("warn", {
        event: "account_deletion_auth_failed",
        request_id: id,
        code,
      });
      if (code === "authentication_required") {
        return error(
          id,
          401,
          code,
          "A valid authenticated session is required.",
        );
      }
      return error(
        id,
        503,
        "account_deletion_unavailable",
        "Account deletion is temporarily unavailable.",
      );
    }

    let preparation;
    try {
      preparation = await backend.prepare(authorization);
    } catch (backendError) {
      const code = backendError instanceof AccountDeletionBackendError
        ? backendError.code
        : "preparation_failed";
      log("error", {
        event: "account_deletion_prepare_failed",
        request_id: id,
        code,
      });
      return error(
        id,
        503,
        "account_deletion_unavailable",
        "Account deletion is temporarily unavailable.",
      );
    }

    if (!preparation.allowed) {
      if (preparation.code === "account_deletion_rate_limited") {
        const retryAfter = preparation.retryAfterSeconds ?? 900;
        return error(
          id,
          429,
          "rate_limited",
          "Too many account deletion attempts. Please try again later.",
          { "Retry-After": String(retryAfter) },
        );
      }
      if (preparation.code === "account_deletion_requires_support") {
        return error(
          id,
          409,
          "support_required",
          "This account has an operator role that must be transferred before deletion.",
        );
      }
      return error(
        id,
        409,
        "reauthentication_required",
        "Sign in again before deleting this account.",
      );
    }

    try {
      await backend.deleteIdentity(userId);
    } catch (backendError) {
      const code = backendError instanceof AccountDeletionBackendError
        ? backendError.code
        : "identity_deletion_failed";
      if (code !== "identity_deletion_status_unknown") {
        try {
          await backend.cancelCleanup(preparation.requestId);
        } catch {
          log("error", {
            event: "account_deletion_cleanup_cancel_failed",
            request_id: id,
          });
        }
      }
      log("error", {
        event: "account_deletion_identity_failed",
        request_id: id,
        code,
      });
      if (code === "account_deletion_requires_support") {
        return error(
          id,
          409,
          "support_required",
          "This account has an operator role that must be transferred before deletion.",
        );
      }
      if (code === "identity_deletion_status_unknown") {
        return error(
          id,
          503,
          "deletion_status_unknown",
          "The deletion result could not be confirmed. Refresh your session before retrying.",
        );
      }
      return error(
        id,
        503,
        "account_deletion_unavailable",
        "Account deletion is temporarily unavailable. No account data was deleted.",
      );
    }

    log("info", { event: "account_deletion_completed", request_id: id });
    return json(id, 200, {
      status: "deleted",
      request_id: preparation.requestId,
    });
  };
}
