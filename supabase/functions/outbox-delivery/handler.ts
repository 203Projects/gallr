import { validateOpaqueToken } from "../_shared/opaque_token.ts";

type EnvironmentReader = (name: string) => string | undefined;
type Fetcher = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

export interface OutboxDeliveryDependencies {
  env: EnvironmentReader;
  fetch: Fetcher;
}

interface DeliveryEvent {
  id: string;
  event_type: string;
  aggregate_type: string;
  aggregate_id: string;
  deduplication_key: string | null;
  payload: Record<string, unknown>;
}

const MAX_BODY_BYTES = 64 * 1024;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const REBUILD_EVENT_TYPES = new Set([
  "exhibition.archived",
  "exhibition.published",
  "exhibition.restored",
]);
const LEGACY_CATALOG_MIRROR_EVENT_TYPE = "legacy_catalog.sync_requested";

const ACKNOWLEDGED_EVENT_TYPES = new Set([
  ...REBUILD_EVENT_TYPES,
  LEGACY_CATALOG_MIRROR_EVENT_TYPE,
  "gallery.claim_approved",
  "gallery.claim_rejected",
  "gallery.claim_requested",
  "gallery.created_and_claimed",
  "launch_kit.activated",
  "launch_kit.rsvp_token_rotated",
  "local_promotion.approved",
  "local_promotion.rejected",
  "local_promotion.requested",
  "owner_exhibition.submitted",
  "submission.accepted",
  "submission.rejected",
]);

function empty(status: number, extraHeaders: HeadersInit = {}): Response {
  return new Response(null, { status, headers: extraHeaders });
}

function constantTimeEquals(left: string, right: string): boolean {
  const maxLength = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < maxLength; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^
      (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

function configuredToken(env: EnvironmentReader): string | null {
  const token = env("OUTBOX_DELIVERY_TOKEN")?.trim() ?? "";
  return validateOpaqueToken(token).valid ? token : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function parseEvent(value: unknown): DeliveryEvent | null {
  if (!isRecord(value) || !isRecord(value.payload)) return null;
  if (typeof value.id !== "string" || !UUID_PATTERN.test(value.id)) return null;
  if (
    typeof value.event_type !== "string" ||
    typeof value.aggregate_type !== "string" ||
    typeof value.aggregate_id !== "string"
  ) return null;
  if (
    value.deduplication_key !== null &&
    typeof value.deduplication_key !== "string"
  ) return null;
  return value as unknown as DeliveryEvent;
}

function deployHookUrl(env: EnvironmentReader): URL | null {
  const configured = env("VERCEL_DEPLOY_HOOK_URL")?.trim();
  if (!configured) return null;
  try {
    const url = new URL(configured);
    if (
      url.protocol !== "https:" ||
      url.hostname !== "api.vercel.com" ||
      !url.pathname.startsWith("/v1/integrations/deploy/") ||
      url.username ||
      url.password
    ) return null;
    return url;
  } catch {
    return null;
  }
}

interface MirrorConfiguration {
  token: string;
  url: URL;
}

function mirrorConfiguration(
  env: EnvironmentReader,
): MirrorConfiguration | null {
  const configuredUrl = env("LEGACY_CATALOG_MIRROR_URL")?.trim() ?? "";
  const token = env("LEGACY_CATALOG_MIRROR_TOKEN")?.trim() ?? "";
  const sourceUrl = env("SUPABASE_URL")?.trim() ?? "";
  if (!configuredUrl || !validateOpaqueToken(token).valid || !sourceUrl) {
    return null;
  }
  try {
    const source = new URL(sourceUrl);
    const url = new URL(configuredUrl);
    if (
      source.origin !== "https://oqrvbstopuppznxqoonp.supabase.co" ||
      source.username ||
      source.password ||
      source.port ||
      source.pathname !== "/" ||
      source.search ||
      source.hash ||
      url.href !== `${source.origin}/functions/v1/legacy-catalog-mirror`
    ) return null;
    return { token, url };
  } catch {
    return null;
  }
}

async function invokeLegacyCatalogMirror(
  dependencies: OutboxDeliveryDependencies,
  mirror: MirrorConfiguration,
  event: DeliveryEvent,
  expectedKey: string,
): Promise<boolean> {
  try {
    const response = await dependencies.fetch(mirror.url, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${mirror.token}`,
        "Content-Type": "application/json",
        "Idempotency-Key": expectedKey,
        "X-Outbox-Event-Id": event.id,
        "X-Outbox-Event-Type": event.event_type,
      },
      body: JSON.stringify({
        source: "outbox",
        event_id: event.id,
      }),
    });
    return response.ok;
  } catch {
    return false;
  }
}

export function createOutboxDeliveryHandler(
  dependencies: OutboxDeliveryDependencies,
): (request: Request) => Promise<Response> {
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") {
      return empty(405, { Allow: "POST" });
    }

    const token = configuredToken(dependencies.env);
    if (!token) return empty(500);
    const authorization = request.headers.get("authorization") ?? "";
    const match = authorization.match(/^Bearer ([^\s]+)$/);
    if (!match || !constantTimeEquals(match[1], token)) return empty(401);

    const contentLength = Number(request.headers.get("content-length") ?? "0");
    if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
      return empty(413);
    }

    const rawBody = await request.text();
    if (new TextEncoder().encode(rawBody).byteLength > MAX_BODY_BYTES) {
      return empty(413);
    }

    let decoded: unknown;
    try {
      decoded = JSON.parse(rawBody);
    } catch {
      return empty(400);
    }
    const event = parseEvent(decoded);
    if (!event) return empty(400);

    const headerId = request.headers.get("x-outbox-event-id") ?? "";
    const headerType = request.headers.get("x-outbox-event-type") ?? "";
    const idempotencyKey = request.headers.get("idempotency-key") ?? "";
    const expectedKey = event.deduplication_key || event.id;
    if (
      headerId !== event.id ||
      headerType !== event.event_type ||
      idempotencyKey !== expectedKey
    ) return empty(400);

    if (!ACKNOWLEDGED_EVENT_TYPES.has(event.event_type)) return empty(422);
    if (event.event_type === LEGACY_CATALOG_MIRROR_EVENT_TYPE) {
      const mirror = mirrorConfiguration(dependencies.env);
      if (!mirror) return empty(500);
      return (await invokeLegacyCatalogMirror(
          dependencies,
          mirror,
          event,
          expectedKey,
        ))
        ? empty(204)
        : empty(502);
    }
    if (!REBUILD_EVENT_TYPES.has(event.event_type)) return empty(204);

    const hook = deployHookUrl(dependencies.env);
    if (!hook) return empty(500);
    try {
      const response = await dependencies.fetch(hook, {
        method: "POST",
        headers: {
          "Idempotency-Key": expectedKey,
          "X-Outbox-Event-Id": event.id,
          "X-Outbox-Event-Type": event.event_type,
        },
      });
      if (!response.ok) return empty(502);
      return empty(204);
    } catch {
      return empty(502);
    }
  };
}
