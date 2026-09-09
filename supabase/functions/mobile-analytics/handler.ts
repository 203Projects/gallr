import type {
  MobileAnalyticsBackend,
  ValidatedMobileAnalyticsEvent,
} from "./backend.ts";

type EnvironmentReader = (name: string) => string | undefined;

export interface MobileAnalyticsHandlerDependencies {
  env?: EnvironmentReader;
  digest?: (value: string) => Promise<string>;
  now?: () => Date;
  createBackend: (
    environment: Record<string, string>,
  ) => MobileAnalyticsBackend;
}

const MAX_BODY_BYTES = 16 * 1024;
const MAX_BATCH_SIZE = 20;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/u;
const IDENTIFIER_PATTERN = /^[\p{L}\p{N}_-]{1,128}$/u;
const DAY_MILLISECONDS = 24 * 60 * 60 * 1000;

const PLATFORMS = new Set(["android", "ios"]);
const SURFACES = new Set([
  "featured",
  "list",
  "map",
  "my_gallr",
  "exhibition_detail",
  "gallery_detail",
  "event_detail",
  "editor_detail",
  "settings",
]);
const ENTRY_POINTS = new Set([
  "tab",
  "card",
  "notification",
  "deep_link",
  "recommendation",
  "route",
]);
const DISCOVERY_KINDS = new Set([
  "featured",
  "organic",
  "search",
  "editor",
  "event",
  "gallery",
  "nearby",
  "saved",
  "notification",
  "recommendation",
  "route",
]);
const POSITION_BUCKETS = new Set([
  "unranked",
  "top_three",
  "four_to_ten",
  "after_ten",
]);
const INTENT_ACTIONS = new Set([
  "bookmark_add",
  "bookmark_remove",
  "share",
  "open_maps",
  "ticket",
  "contact",
  "visit_recorded",
  "gallery_open",
  "follow_gallery",
]);
const ROUTE_MODES = new Set([
  "neighborhood",
  "for_you",
  "closing_soon",
  "saved",
]);
const DISTANCE_BANDS = new Set([
  "under_two_km",
  "two_to_five_km",
  "over_five_km",
]);
const DURATION_BANDS = new Set([
  "under_two_hours",
  "two_to_four_hours",
  "over_four_hours",
]);
const COMMON_KEYS = [
  "event_id",
  "occurred_on",
  "platform",
  "app_major",
  "event_name",
] as const;

function environment(env: EnvironmentReader): Record<string, string> {
  return Object.fromEntries(
    [
      "SUPABASE_URL",
      "SUPABASE_SECRET_KEY",
      "SUPABASE_SECRET_KEYS",
      "SUPABASE_SERVICE_ROLE_KEY",
    ].map((name) => [name, env(name) ?? ""]),
  );
}

function empty(status: number, headers: HeadersInit = {}): Response {
  return new Response(null, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...headers,
    },
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function hasExactKeys(
  value: Record<string, unknown>,
  keys: readonly string[],
): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...COMMON_KEYS, ...keys].sort();
  return actual.length === expected.length &&
    actual.every((key, index) => key === expected[index]);
}

function stringIn(
  value: unknown,
  values: ReadonlySet<string>,
): value is string {
  return typeof value === "string" && values.has(value);
}

function validIdentifier(value: unknown): value is string {
  return typeof value === "string" && IDENTIFIER_PATTERN.test(value);
}

function validOccurredOn(value: unknown, now: Date): value is string {
  if (typeof value !== "string" || !DATE_PATTERN.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00.000Z`);
  if (
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== value
  ) return false;
  const today = Date.UTC(
    now.getUTCFullYear(),
    now.getUTCMonth(),
    now.getUTCDate(),
  );
  const dayOffset = (parsed.getTime() - today) / DAY_MILLISECONDS;
  return dayOffset >= -7 && dayOffset <= 1;
}

function validCommon(event: Record<string, unknown>, now: Date): boolean {
  return typeof event.event_id === "string" &&
    UUID_PATTERN.test(event.event_id) &&
    validOccurredOn(event.occurred_on, now) &&
    stringIn(event.platform, PLATFORMS) &&
    typeof event.app_major === "number" &&
    Number.isInteger(event.app_major) &&
    event.app_major >= 1 && event.app_major <= 999 &&
    typeof event.event_name === "string";
}

function validateEvent(
  value: unknown,
  now: Date,
): ValidatedMobileAnalyticsEvent | null {
  if (!isRecord(value) || !validCommon(value, now)) return null;
  switch (value.event_name) {
    case "surface_viewed":
      return hasExactKeys(value, ["surface", "entry_point"]) &&
          stringIn(value.surface, SURFACES) &&
          stringIn(value.entry_point, ENTRY_POINTS)
        ? value
        : null;
    case "exhibition_impression":
    case "exhibition_opened":
      return hasExactKeys(value, [
          "surface",
          "exhibition_id",
          "discovery_kind",
          "position_bucket",
        ]) &&
          stringIn(value.surface, SURFACES) &&
          validIdentifier(value.exhibition_id) &&
          stringIn(value.discovery_kind, DISCOVERY_KINDS) &&
          stringIn(value.position_bucket, POSITION_BUCKETS)
        ? value
        : null;
    case "exhibition_intent":
      return hasExactKeys(value, ["surface", "exhibition_id", "action"]) &&
          stringIn(value.surface, SURFACES) &&
          validIdentifier(value.exhibition_id) &&
          stringIn(value.action, INTENT_ACTIONS)
        ? value
        : null;
    case "recommendations_shown":
      return hasExactKeys(value, [
          "surface",
          "discovery_kind",
          "result_count",
        ]) &&
          stringIn(value.surface, SURFACES) &&
          value.discovery_kind === "recommendation" &&
          typeof value.result_count === "number" &&
          Number.isInteger(value.result_count) &&
          value.result_count >= 0 && value.result_count <= 20
        ? value
        : null;
    case "route_created":
    case "route_started":
      return hasExactKeys(value, [
          "route_mode",
          "stop_count",
          "distance_band",
          "duration_band",
        ]) &&
          stringIn(value.route_mode, ROUTE_MODES) &&
          typeof value.stop_count === "number" &&
          Number.isInteger(value.stop_count) &&
          value.stop_count >= 2 && value.stop_count <= 5 &&
          stringIn(value.distance_band, DISTANCE_BANDS) &&
          stringIn(value.duration_band, DURATION_BANDS)
        ? value
        : null;
    default:
      return null;
  }
}

function collectionEnabled(env: EnvironmentReader): boolean | null {
  const configured = env("MOBILE_ANALYTICS_ENABLED")?.trim().toLowerCase() ??
    "false";
  if (configured === "true") return true;
  if (configured === "false" || configured === "") return false;
  return null;
}

function allowedOrigins(env: EnvironmentReader): Set<string> {
  const configured = env("MOBILE_ANALYTICS_ALLOWED_ORIGINS")
    ?.split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  return new Set(configured?.length ? configured : ["app://gallr"]);
}

function requestSource(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
}

function isJsonContentType(value: string | null): boolean {
  return value?.split(";", 1)[0]?.trim().toLowerCase() === "application/json";
}

async function sha256(value: string): Promise<string> {
  const result = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(result))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export function createMobileAnalyticsHandler(
  dependencies: MobileAnalyticsHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const digest = dependencies.digest ?? sha256;
  const now = dependencies.now ?? (() => new Date());
  return async (request) => {
    const enabled = collectionEnabled(env);
    if (enabled === false) return empty(204);
    if (enabled === null) return empty(500);

    const origin = request.headers.get("origin") ?? "";
    if (!allowedOrigins(env).has(origin)) return empty(403);
    if (request.method !== "POST") return empty(405, { Allow: "POST" });
    if (!isJsonContentType(request.headers.get("content-type"))) {
      return empty(415);
    }
    const declaredLength = request.headers.get("content-length");
    if (
      declaredLength &&
      (!/^\d+$/u.test(declaredLength) ||
        Number(declaredLength) > MAX_BODY_BYTES)
    ) return empty(413);
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_BODY_BYTES) {
      return empty(413);
    }

    let decoded: unknown;
    try {
      decoded = JSON.parse(raw);
    } catch {
      return empty(400);
    }
    if (
      !isRecord(decoded) || Object.keys(decoded).length !== 1 ||
      !Array.isArray(decoded.events) ||
      decoded.events.length < 1 || decoded.events.length > MAX_BATCH_SIZE
    ) return empty(400);
    const referenceNow = now();
    if (Number.isNaN(referenceNow.getTime())) return empty(500);
    const events = decoded.events.map((event) =>
      validateEvent(event, referenceNow)
    );
    if (events.some((event) => event === null)) return empty(400);

    const secret = env("MOBILE_ANALYTICS_HASH_SECRET")?.trim() ?? "";
    if (secret.length < 32 || secret.length > 256) return empty(500);
    const sourceDigest = await digest(`${secret}:${requestSource(request)}`);
    if (!/^[0-9a-f]{64}$/u.test(sourceDigest)) return empty(500);

    try {
      await dependencies.createBackend(environment(env)).record(
        events as ValidatedMobileAnalyticsEvent[],
        sourceDigest,
      );
      return empty(204);
    } catch {
      return empty(503);
    }
  };
}
