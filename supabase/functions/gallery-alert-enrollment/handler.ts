import {
  type GalleryAlertEnrollmentBackend,
  GalleryAlertEnrollmentError,
} from "./backend.ts";

const MAX_BYTES = 8192;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;
const LOCALE_PATTERN = /^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$/u;

/** Stable error code to HTTP status. Anything unlisted degrades to 503. */
const STATUS_BY_CODE: Readonly<Record<string, number>> = {
  enrollment_invalid: 400,
  gallery_not_alertable: 400,
  gallery_alert_installation_unauthorized: 403,
  installation_account_conflict: 409,
  revision_conflict: 409,
  gallery_alert_subscription_limit_reached: 429,
  gallery_alert_rate_limited: 429,
  enrollment_unavailable: 503,
};

type EnvironmentReader = (name: string) => string | undefined;

export interface EnrollmentHandlerDependencies {
  env?: EnvironmentReader;
  digest?: (value: string) => Promise<string>;
  createBackend: (
    environment: Record<string, string>,
  ) => GalleryAlertEnrollmentBackend;
}

function environment(env: EnvironmentReader): Record<string, string> {
  return Object.fromEntries([
    "SUPABASE_URL",
    "SUPABASE_SECRET_KEY",
    "SUPABASE_SECRET_KEYS",
    "SUPABASE_SERVICE_ROLE_KEY",
    "SUPABASE_PUBLISHABLE_KEY",
    "SUPABASE_PUBLISHABLE_KEYS",
    "SUPABASE_ANON_KEY",
  ].map((name) => [name, env(name) ?? ""]));
}

function response(body: unknown, status: number): Response {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function failure(code: string): Response {
  return response({ error: code }, STATUS_BY_CODE[code] ?? 503);
}

async function sha256(value: string): Promise<string> {
  const result = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(result))
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * The enrollment source. Only the ingress-supplied client address is used, and
 * it is never stored or logged in the clear: the database sees a keyed digest.
 */
function requestSource(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
}

function text(value: unknown, min: number, max: number): string | null {
  return typeof value === "string" && value.length >= min &&
      value.length <= max
    ? value
    : null;
}

function uuid(value: unknown): string | null {
  return typeof value === "string" && UUID_PATTERN.test(value) ? value : null;
}

function revision(value: unknown): number | null {
  return typeof value === "number" && Number.isInteger(value) && value >= 0
    ? value
    : null;
}

interface Credentials {
  installationId: string;
  installationSecret: string;
}

function credentials(input: Record<string, unknown>): Credentials | null {
  const installationId = uuid(input.installation_id);
  const installationSecret = text(input.installation_secret, 32, 256);
  return installationId && installationSecret
    ? { installationId, installationSecret }
    : null;
}

function hasOnly(input: Record<string, unknown>, allowed: string[]): boolean {
  return Object.keys(input).every((key) => allowed.includes(key));
}

export function createGalleryAlertEnrollmentHandler(
  dependencies: EnrollmentHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const digest = dependencies.digest ?? sha256;

  return async (request: Request): Promise<Response> => {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Headers": "authorization, content-type, apikey",
          "Access-Control-Allow-Methods": "POST, OPTIONS",
          "Access-Control-Max-Age": "86400",
        },
      });
    }
    if (request.method !== "POST") {
      return response({ error: "method_not_allowed" }, 405);
    }
    if (!request.headers.get("content-type")?.startsWith("application/json")) {
      return response({ error: "content_type_invalid" }, 415);
    }

    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_BYTES) {
      return response({ error: "request_too_large" }, 413);
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return failure("enrollment_invalid");
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return failure("enrollment_invalid");
    }
    const input = parsed as Record<string, unknown>;
    const identity = credentials(input);
    if (!identity) return failure("enrollment_invalid");

    const secret = env("GALLERY_ALERT_HASH_SECRET")?.trim();
    if (!secret || secret.length < 32) {
      return response({ error: "enrollment_unavailable" }, 503);
    }

    let backend: GalleryAlertEnrollmentBackend;
    try {
      backend = dependencies.createBackend(environment(env));
    } catch {
      return response({ error: "enrollment_unavailable" }, 503);
    }

    try {
      switch (input.action) {
        case "register_installation": {
          if (
            !hasOnly(input, [
              "action",
              "installation_id",
              "installation_secret",
              "platform",
              "locale",
              "expected_revision",
            ])
          ) return failure("enrollment_invalid");
          const platform = text(input.platform, 1, 16);
          const locale = text(input.locale, 2, 35);
          const expectedRevision = revision(input.expected_revision);
          if (
            !platform || !locale || !LOCALE_PATTERN.test(locale) ||
            expectedRevision === null
          ) return failure("enrollment_invalid");
          return response(
            await backend.registerInstallation({
              ...identity,
              sourceDigest: await digest(
                `${secret}:${requestSource(request)}`,
              ),
              platform,
              locale,
              expectedRevision,
              actorUserId: await backend.resolveActor(
                request.headers.get("authorization"),
              ),
            }),
            200,
          );
        }
        case "register_push_token": {
          if (
            !hasOnly(input, [
              "action",
              "installation_id",
              "installation_secret",
              "provider",
              "provider_token",
              "provider_environment",
              "expected_revision",
            ])
          ) return failure("enrollment_invalid");
          const provider = text(input.provider, 1, 8);
          const providerToken = text(input.provider_token, 1, 4096);
          const providerEnvironment = text(input.provider_environment, 1, 16);
          const expectedRevision = revision(input.expected_revision);
          if (
            !provider || !providerToken || !providerEnvironment ||
            expectedRevision === null
          ) return failure("enrollment_invalid");
          return response(
            await backend.registerPushToken({
              ...identity,
              provider,
              providerToken,
              providerEnvironment,
              expectedRevision,
            }),
            200,
          );
        }
        case "set_subscription": {
          if (
            !hasOnly(input, [
              "action",
              "installation_id",
              "installation_secret",
              "gallery_id",
              "enabled",
              "expected_revision",
            ])
          ) return failure("enrollment_invalid");
          const galleryId = uuid(input.gallery_id);
          const expectedRevision = revision(input.expected_revision);
          if (
            !galleryId || typeof input.enabled !== "boolean" ||
            expectedRevision === null
          ) return failure("enrollment_invalid");
          return response(
            await backend.setSubscription({
              ...identity,
              galleryId,
              enabled: input.enabled,
              expectedRevision,
            }),
            200,
          );
        }
        case "get_installation": {
          if (
            !hasOnly(input, [
              "action",
              "installation_id",
              "installation_secret",
            ])
          ) return failure("enrollment_invalid");
          return response(await backend.getInstallation(identity), 200);
        }
        default:
          return failure("enrollment_invalid");
      }
    } catch (error) {
      return failure(
        error instanceof GalleryAlertEnrollmentError
          ? error.code
          : "enrollment_unavailable",
      );
    }
  };
}
