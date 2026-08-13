import {
  EditorInviteAuthorizationError,
  type EditorInviteBackend,
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

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.valueOf()) &&
    parsed.toISOString().slice(0, 10) === value;
}

function parsePayload(value: unknown): EditorInvitePayload | null {
  if (!isRecord(value)) return null;
  const keys = [
    "email",
    "editor_id",
    "name_ko",
    "name_en",
    "title_ko",
    "title_en",
    "bio_ko",
    "bio_en",
    "curation_description_ko",
    "curation_description_en",
    "is_active",
    "active_from",
    "active_to",
  ];
  if (
    Object.keys(value).length !== keys.length ||
    keys.some((key) => !(key in value))
  ) return null;
  const stringKeys = [
    "email",
    "editor_id",
    "name_ko",
    "name_en",
    "title_ko",
    "title_en",
    "bio_ko",
    "bio_en",
    "curation_description_ko",
    "curation_description_en",
    "active_from",
  ] as const;
  if (stringKeys.some((key) => typeof value[key] !== "string")) return null;
  if (typeof value.is_active !== "boolean") return null;
  if (value.active_to !== null && typeof value.active_to !== "string") {
    return null;
  }

  const payload = Object.fromEntries(
    Object.entries(value).map(([key, item]) => [
      key,
      typeof item === "string" ? item.trim() : item,
    ]),
  ) as unknown as EditorInvitePayload;
  if (
    payload.email.length > 254 ||
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(payload.email) ||
    payload.editor_id.length < 3 || payload.editor_id.length > 64 ||
    !/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(payload.editor_id) ||
    !payload.name_ko || payload.name_ko.length > 120 ||
    payload.name_en.length > 120 ||
    !payload.title_ko || payload.title_ko.length > 160 ||
    payload.title_en.length > 160 ||
    !payload.bio_ko || payload.bio_ko.length > 4000 ||
    payload.bio_en.length > 4000 ||
    !payload.curation_description_ko ||
    payload.curation_description_ko.length > 4000 ||
    payload.curation_description_en.length > 4000 ||
    !validDate(payload.active_from) ||
    (payload.active_to !== null && !validDate(payload.active_to)) ||
    (payload.active_to !== null && payload.active_to < payload.active_from)
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
    } catch {
      return json({ error: "editor_invitation_failed" }, 409, origin);
    }
  };
}
