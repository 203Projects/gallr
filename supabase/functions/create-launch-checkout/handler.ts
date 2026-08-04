import type { LaunchCheckoutBackend } from "./backend.ts";

const MAX_BYTES = 512;
const DEFAULT_ORIGINS = [
  "https://gallery.gallrmap.com",
  "http://127.0.0.1:4173",
  "http://127.0.0.1:5173",
] as const;

type EnvironmentReader = (name: string) => string | undefined;

export interface CheckoutHandlerDependencies {
  env?: EnvironmentReader;
  requestId?: () => string;
  createBackend: (environment: Record<string, string>) => LaunchCheckoutBackend;
}

function json(body: unknown, status: number, origin?: string): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      ...(origin
        ? { "Access-Control-Allow-Origin": origin, Vary: "Origin" }
        : {}),
    },
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
    "STRIPE_SECRET_KEY",
    "STRIPE_LAUNCH_KIT_PRICE_ID",
    "GALLERY_WORKSPACE_URL",
  ].map((name) => [name, env(name) ?? ""]));
}

export function createLaunchCheckoutHandler(
  dependencies: CheckoutHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const origins = new Set(
    env("LAUNCH_CHECKOUT_ALLOWED_ORIGINS")?.split(",").map((value) =>
      value.trim()
    ).filter(Boolean) ||
      DEFAULT_ORIGINS,
  );
  const requestId = dependencies.requestId ?? (() => crypto.randomUUID());

  return async (request: Request): Promise<Response> => {
    const origin = request.headers.get("origin") ?? "";
    if (!origins.has(origin)) return json({ error: "origin_not_allowed" }, 403);
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": origin,
          "Access-Control-Allow-Headers": "authorization, content-type",
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
    if (!/^Bearer\s+\S+$/u.test(authorization)) {
      return json({ error: "authorization_required" }, 401, origin);
    }
    if (!request.headers.get("content-type")?.startsWith("application/json")) {
      return json({ error: "content_type_invalid" }, 415, origin);
    }
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_BYTES) {
      return json({ error: "request_too_large" }, 413, origin);
    }
    let payload: unknown;
    try {
      payload = JSON.parse(raw);
    } catch {
      return json({ error: "request_invalid" }, 400, origin);
    }
    const exhibitionId =
      payload && typeof payload === "object" && !Array.isArray(payload)
        ? (payload as Record<string, unknown>).exhibition_id
        : null;
    if (
      typeof exhibitionId !== "string" ||
      exhibitionId !== exhibitionId.trim() ||
      exhibitionId.length < 1 || exhibitionId.length > 128 ||
      Object.keys(payload as Record<string, unknown>).length !== 1
    ) return json({ error: "request_invalid" }, 400, origin);

    try {
      const backend = dependencies.createBackend(environment(env));
      const context = await backend.prepare(
        authorization,
        exhibitionId,
        requestId(),
      );
      if (context.status === "active") {
        return json(
          { active: true, launchKitId: context.launchKitId },
          200,
          origin,
        );
      }
      const checkout = await backend.createCheckout(context);
      return json({ active: false, url: checkout.url }, 200, origin);
    } catch {
      return json({ error: "checkout_unavailable" }, 502, origin);
    }
  };
}
