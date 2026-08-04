import type { ImpactBackend } from "./backend.ts";

const MAX_REQUEST_BYTES = 512;
const DEFAULT_ALLOWED_ORIGINS = [
  "https://gallrmap.com",
  "https://www.gallrmap.com",
  "http://127.0.0.1:8080",
  "http://127.0.0.1:8081",
] as const;

type EnvironmentReader = (name: string) => string | undefined;

export interface ImpactHandlerDependencies {
  env?: EnvironmentReader;
  createBackend: (environment: Record<string, string>) => ImpactBackend;
}

class RequestError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function environment(env: EnvironmentReader): Record<string, string> {
  return Object.fromEntries(
    [
      "SUPABASE_URL",
      "SUPABASE_SECRET_KEY",
      "SUPABASE_SERVICE_ROLE_KEY",
      "SUPABASE_SECRET_KEYS",
    ].map((name) => [name, env(name) ?? ""]),
  );
}

function allowedOrigins(env: EnvironmentReader): Set<string> {
  const configured = env("IMPACT_ALLOWED_ORIGINS")
    ?.split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  return new Set(configured?.length ? configured : DEFAULT_ALLOWED_ORIGINS);
}

function cors(origin: string): HeadersInit {
  return {
    "Access-Control-Allow-Origin": origin,
    "Cache-Control": "no-store",
    "Vary": "Origin",
    "X-Content-Type-Options": "nosniff",
  };
}

function validExhibitionId(value: unknown): value is string {
  if (
    typeof value !== "string" || value.length < 1 || value.length > 128 ||
    value !== value.trim()
  ) return false;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 32 || code === 127) return false;
  }
  return true;
}

export function createImpactHandler(
  dependencies: ImpactHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const origins = allowedOrigins(env);

  return async (request: Request): Promise<Response> => {
    const origin = request.headers.get("origin") ?? "";
    try {
      if (!origin || !origins.has(origin)) {
        throw new RequestError(403, "Origin is not allowed.");
      }
      if (request.method === "OPTIONS") {
        return new Response(null, {
          status: 204,
          headers: {
            ...cors(origin),
            "Access-Control-Allow-Headers": "content-type",
            "Access-Control-Allow-Methods": "POST, OPTIONS",
            "Access-Control-Max-Age": "86400",
          },
        });
      }
      if (request.method !== "POST") {
        throw new RequestError(405, "Only POST is supported.");
      }
      if (
        !request.headers.get("content-type")?.startsWith("application/json")
      ) {
        throw new RequestError(415, "application/json is required.");
      }
      const length = request.headers.get("content-length");
      if (
        length && (!/^\d+$/u.test(length) || Number(length) > MAX_REQUEST_BYTES)
      ) {
        throw new RequestError(413, "Request is too large.");
      }
      const raw = await request.text();
      if (new TextEncoder().encode(raw).byteLength > MAX_REQUEST_BYTES) {
        throw new RequestError(413, "Request is too large.");
      }
      let payload: unknown;
      try {
        payload = JSON.parse(raw);
      } catch {
        throw new RequestError(400, "Request is invalid.");
      }
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        throw new RequestError(400, "Request is invalid.");
      }
      const values = payload as Record<string, unknown>;
      if (
        Object.keys(values).length !== 1 ||
        !validExhibitionId(values.exhibition_id)
      ) {
        throw new RequestError(400, "Request is invalid.");
      }

      await dependencies.createBackend(environment(env)).record(
        values.exhibition_id,
      );
      return new Response(null, {
        status: 204,
        headers: cors(origin),
      });
    } catch (error) {
      const status = error instanceof RequestError ? error.status : 503;
      return new Response(null, {
        status,
        headers: {
          ...(origin && origins.has(origin) ? cors(origin) : {}),
          ...(status === 405 ? { Allow: "POST, OPTIONS" } : {}),
          "Cache-Control": "no-store",
        },
      });
    }
  };
}
