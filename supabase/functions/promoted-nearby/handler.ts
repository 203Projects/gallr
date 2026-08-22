import type { PromotionBackend } from "./backend.ts";

const MAX_BYTES = 512;
const DEFAULT_ORIGINS = [
  "https://gallrmap.com",
  "https://www.gallrmap.com",
  "http://127.0.0.1:8080",
  "http://127.0.0.1:8081",
  "app://gallr",
] as const;
type EnvironmentReader = (name: string) => string | undefined;

export interface PromotionHandlerDependencies {
  env?: EnvironmentReader;
  digest?: (value: string) => Promise<string>;
  log?: (value: unknown) => void;
  createBackend: (environment: Record<string, string>) => PromotionBackend;
}

class RequestError extends Error {
  constructor(readonly status: number) {
    super("Promotion request invalid.");
  }
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
  const bytes = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(bytes))
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function boundedText(value: unknown, max: number): value is string {
  if (
    typeof value !== "string" || value !== value.trim() || value.length > max
  ) return false;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 32 || code === 127) return false;
  }
  return true;
}

export function createPromotionHandler(
  dependencies: PromotionHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const digest = dependencies.digest ?? sha256;
  const log = dependencies.log ?? console.log;
  const configured = env("PROMOTION_ALLOWED_ORIGINS")?.split(",")
    .map((value) => value.trim()).filter(Boolean);
  const origins = new Set(configured?.length ? configured : DEFAULT_ORIGINS);
  const deliveryEnabled = env("PROMOTION_DELIVERY_ENABLED") === "true";

  return async (request: Request): Promise<Response> => {
    const origin = request.headers.get("origin") ?? "";
    let outcome = "rejected";
    try {
      if (!origins.has(origin)) throw new RequestError(403);
      if (request.method === "OPTIONS") {
        return new Response(null, {
          status: 204,
          headers: {
            "Access-Control-Allow-Origin": origin,
            "Access-Control-Allow-Headers": "content-type",
            "Access-Control-Allow-Methods": "POST, OPTIONS",
            "Access-Control-Max-Age": "86400",
            Vary: "Origin",
          },
        });
      }
      if (request.method !== "POST") throw new RequestError(405);
      if (!deliveryEnabled) {
        outcome = "none";
        return response(null, 204, origin);
      }
      if (
        !request.headers.get("content-type")?.startsWith("application/json")
      ) {
        throw new RequestError(415);
      }
      const length = request.headers.get("content-length");
      if (length && (!/^\d+$/u.test(length) || Number(length) > MAX_BYTES)) {
        throw new RequestError(413);
      }
      const raw = await request.text();
      if (new TextEncoder().encode(raw).byteLength > MAX_BYTES) {
        throw new RequestError(413);
      }
      let value: unknown;
      try {
        value = JSON.parse(raw);
      } catch {
        throw new RequestError(400);
      }
      if (!value || typeof value !== "object" || Array.isArray(value)) {
        throw new RequestError(400);
      }
      const input = value as Record<string, unknown>;
      if (
        Object.keys(input).some((key) =>
          !["installation_key", "city_ko", "region_ko"].includes(key)
        ) ||
        !boundedText(input.installation_key, 128) ||
        input.installation_key.length < 16 ||
        !/^[A-Za-z0-9_-]+$/u.test(input.installation_key) ||
        !boundedText(input.city_ko, 100) ||
        !boundedText(input.region_ko, 100) ||
        (input.city_ko === "" && input.region_ko === "")
      ) throw new RequestError(400);

      const viewerDigest = await digest(input.installation_key);
      const placement = await dependencies.createBackend(environment(env))
        .select(
          viewerDigest,
          input.city_ko,
          input.region_ko,
        );
      outcome = placement ? "served" : "none";
      return placement
        ? response({ placement }, 200, origin)
        : response(null, 204, origin);
    } catch (error) {
      const status = error instanceof RequestError ? error.status : 503;
      outcome = status === 503 ? "unavailable" : "rejected";
      return new Response(null, {
        status,
        headers: {
          ...(origin && origins.has(origin)
            ? { "Access-Control-Allow-Origin": origin, Vary: "Origin" }
            : {}),
          ...(status === 405 ? { Allow: "POST, OPTIONS" } : {}),
          "Cache-Control": "no-store",
        },
      });
    } finally {
      if (request.method !== "OPTIONS") {
        log({ event: "promotion.delivery", outcome });
      }
    }
  };
}
