import type { LaunchRsvpBackend } from "./backend.ts";

const MAX_BYTES = 2048;
const DEFAULT_ORIGINS = [
  "https://gallrmap.com",
  "https://www.gallrmap.com",
  "http://127.0.0.1:8080",
  "http://127.0.0.1:8081",
] as const;
type EnvironmentReader = (name: string) => string | undefined;

export interface RsvpHandlerDependencies {
  env?: EnvironmentReader;
  digest?: (value: string) => Promise<string>;
  createBackend: (environment: Record<string, string>) => LaunchRsvpBackend;
}

function environment(env: EnvironmentReader): Record<string, string> {
  return Object.fromEntries([
    "SUPABASE_URL",
    "SUPABASE_SECRET_KEY",
    "SUPABASE_SECRET_KEYS",
    "SUPABASE_SERVICE_ROLE_KEY",
  ].map((name) => [name, env(name) ?? ""]));
}

function response(body: unknown, status: number, origin: string): Response {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: {
      "Access-Control-Allow-Origin": origin,
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      Vary: "Origin",
    },
  });
}

async function sha256(value: string): Promise<string> {
  const result = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(result))
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function requestIp(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
}

function token(request: Request): string | null {
  const value = new URL(request.url).searchParams.get("token");
  return value &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu
        .test(value)
    ? value
    : null;
}

export function createLaunchRsvpHandler(
  dependencies: RsvpHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const digest = dependencies.digest ?? sha256;
  const origins = new Set(
    env("RSVP_ALLOWED_ORIGINS")?.split(",").map((value) => value.trim()).filter(
      Boolean,
    ) ||
      DEFAULT_ORIGINS,
  );
  return async (request: Request): Promise<Response> => {
    const origin = request.headers.get("origin") ?? "";
    if (!origins.has(origin)) return new Response(null, { status: 403 });
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": origin,
          "Access-Control-Allow-Headers": "content-type",
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
          "Access-Control-Max-Age": "86400",
          Vary: "Origin",
        },
      });
    }
    const publicToken = token(request);
    if (!publicToken) return response({ error: "rsvp_not_found" }, 404, origin);
    const backend = dependencies.createBackend(environment(env));
    try {
      if (request.method === "GET") {
        const kit = await backend.get(publicToken);
        return kit
          ? response({ launchKit: kit }, 200, origin)
          : response({ error: "rsvp_not_found" }, 404, origin);
      }
      if (request.method !== "POST") {
        return response({ error: "method_not_allowed" }, 405, origin);
      }
      if (
        !request.headers.get("content-type")?.startsWith("application/json")
      ) {
        return response({ error: "content_type_invalid" }, 415, origin);
      }
      const raw = await request.text();
      if (new TextEncoder().encode(raw).byteLength > MAX_BYTES) {
        return response({ error: "request_too_large" }, 413, origin);
      }
      let value: unknown;
      try {
        value = JSON.parse(raw);
      } catch {
        return response({ error: "rsvp_invalid" }, 400, origin);
      }
      if (!value || typeof value !== "object" || Array.isArray(value)) {
        return response({ error: "rsvp_invalid" }, 400, origin);
      }
      const input = value as Record<string, unknown>;
      if (
        Object.keys(input).some((key) =>
          !["name", "email", "party_size", "privacy_acknowledged"].includes(key)
        ) ||
        typeof input.name !== "string" || typeof input.email !== "string" ||
        typeof input.party_size !== "number" ||
        !Number.isInteger(input.party_size) ||
        input.privacy_acknowledged !== true
      ) return response({ error: "rsvp_invalid" }, 400, origin);
      const secret = env("RSVP_HASH_SECRET")?.trim();
      if (!secret || secret.length < 32) {
        return response({ error: "rsvp_unavailable" }, 503, origin);
      }
      const sourceDigest = await digest(
        `${secret}:${requestIp(request)}:${publicToken}`,
      );
      const accepted = await backend.submit({
        token: publicToken,
        name: input.name,
        email: input.email,
        partySize: input.party_size,
        privacyAcknowledged: true,
        sourceDigest,
      });
      return accepted
        ? response(null, 204, origin)
        : response({ error: "rsvp_not_found" }, 404, origin);
    } catch (error) {
      if (error instanceof Error && error.message.includes("rate_limited")) {
        return response({ error: "rsvp_rate_limited" }, 429, origin);
      }
      return response({ error: "rsvp_unavailable" }, 503, origin);
    }
  };
}
