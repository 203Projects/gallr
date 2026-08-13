import {
  EditorInviteAuthorizationError,
  type EditorInviteBackend,
  EditorInviteFailure,
  type EditorInvitePayload,
} from "./backend.ts";

const MAX_REQUEST_BYTES = 16 * 1024;
const DEFAULT_ORIGINS = [
  "https://admin.gallrmap.com",
  "http://127.0.0.1:4173",
  "http://127.0.0.1:5173",
  "http://localhost:4173",
  "http://localhost:5173",
] as const;

type EnvironmentReader = (name: string) => string | undefined;

export interface InviteEditorHandlerDependencies {
  env?: EnvironmentReader;
  createBackend: (environment: Record<string, string>) => EditorInviteBackend;
}

function responseHeaders(origin: string): Headers {
  return new Headers({
    "Access-Control-Allow-Origin": origin,
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "X-Content-Type-Options": "nosniff",
    Vary: "Origin",
  });
}

function json(body: unknown, status: number, origin: string): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: responseHeaders(origin),
  });
}

function environment(env: EnvironmentReader): Record<string, string> {
  return Object.fromEntries([
    "SUPABASE_URL",
    "SUPABASE_ANON_KEY",
    "SUPABASE_PUBLISHABLE_KEY",
    "SUPABASE_PUBLISHABLE_KEYS",
    "SUPABASE_SECRET_KEY",
    "SUPABASE_SECRET_KEYS",
    "SUPABASE_SERVICE_ROLE_KEY",
    "EDITOR_PORTAL_URL",
  ].map((name) => [name, env(name) ?? ""]));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function parsePayload(value: unknown): EditorInvitePayload | null {
  if (!isRecord(value)) return null;
  if (Object.keys(value).length !== 1 || typeof value.email !== "string") {
    return null;
  }
  const payload = { email: value.email.trim() };
  if (
    payload.email.length > 254 ||
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(payload.email)
  ) return null;
  return payload;
}

export function createInviteEditorHandler(
  dependencies: InviteEditorHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const allowedOrigins = new Set(
    env("INVITE_EDITOR_ALLOWED_ORIGINS")?.split(",").map((item) => item.trim())
      .filter(Boolean) ?? DEFAULT_ORIGINS,
  );

  return async (request: Request): Promise<Response> => {
    const origin = request.headers.get("origin") ?? "";
    if (!allowedOrigins.has(origin)) {
      return new Response(JSON.stringify({ error: "origin_not_allowed" }), {
        status: 403,
        headers: { "Content-Type": "application/json; charset=utf-8" },
      });
    }
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": origin,
          "Access-Control-Allow-Headers":
            "authorization, x-client-info, apikey, content-type",
          "Access-Control-Allow-Methods": "POST, OPTIONS",
          "Access-Control-Max-Age": "86400",
          Vary: "Origin",
        },
      });
    }
    if (request.method !== "POST") {
      return json({ error: "method_not_allowed" }, 405, origin);
    }
    const authorization = request.headers.get("authorization") ?? "";
    if (
      !/^Bearer [^\s]+$/u.test(authorization) || authorization.length > 8192
    ) {
      return json({ error: "authentication_required" }, 401, origin);
    }

    let backend: EditorInviteBackend;
    try {
      backend = dependencies.createBackend(environment(env));
      await backend.authorizeAdmin(authorization);
    } catch (error) {
      if (error instanceof EditorInviteAuthorizationError) {
        return json(
          { error: error.code },
          error.code === "authentication_required" ? 401 : 403,
          origin,
        );
      }
      return json({ error: "service_unavailable" }, 503, origin);
    }

    if (!request.headers.get("content-type")?.startsWith("application/json")) {
      return json({ error: "content_type_invalid" }, 415, origin);
    }
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_REQUEST_BYTES) {
      return json({ error: "request_too_large" }, 413, origin);
    }
    let value: unknown;
    try {
      value = JSON.parse(raw);
    } catch {
      return json({ error: "request_invalid" }, 400, origin);
    }
    const payload = parsePayload(value);
    if (!payload) return json({ error: "request_invalid" }, 400, origin);

    try {
      const created = await backend.invite(authorization, payload);
      return json(created, 201, origin);
    } catch (error) {
      if (error instanceof EditorInviteFailure) {
        const status = error.code === "email_already_registered"
          ? 409
          : error.code === "email_rate_limited"
          ? 429
          : 503;
        return json({ error: error.code }, status, origin);
      }
      return json({ error: "service_unavailable" }, 503, origin);
    }
  };
}
