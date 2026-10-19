import type {
  CachedLeg,
  LegToStore,
  PositionPair,
  WalkingRouteBackend,
} from "./backend.ts";
import {
  encodePolyline,
  type LatLng,
  reversePolyline,
  simplifyPolyline,
} from "./polyline.ts";
import { distanceMeters, orderedPair, positionKey } from "./position.ts";
import type {
  ProviderLeg,
  ProviderName,
  WalkingProvider,
} from "./providers/provider.ts";
import {
  MAX_HASH_SECRET_LENGTH,
  MIN_HASH_SECRET_LENGTH,
  requestSource,
  sourceDigest,
} from "./quota.ts";

type EnvironmentReader = (name: string) => string | undefined;

export interface WalkingRouteLog {
  event: "walking_route";
  outcome: Outcome;
  legs: number;
  cache_hits: number;
  routed: number;
  unavailable: number;
}

export interface WalkingRouteHandlerDependencies {
  env?: EnvironmentReader;
  now?: () => Date;
  log?: (entry: WalkingRouteLog) => void;
  digest?: (secret: string, source: string) => Promise<string>;
  createBackend: (environment: Record<string, string>) => WalkingRouteBackend;
  createProvider: (
    environment: Record<string, string>,
  ) => WalkingProvider | null;
}

type Outcome =
  | "served"
  | "store_failed"
  | "disabled"
  | "rate_limited"
  | "rejected"
  | "unavailable";

/** A routed leg names the router that drew its path, so the app can credit it; a leg too short to route names none. */
type ResponseLeg =
  | {
    status: "routed";
    distance_m: number;
    polyline: string;
    provider?: ProviderName;
  }
  | { status: "unavailable" };

interface Waypoint extends LatLng {
  exhibitionId: string | null;
}

/** A leg the provider may route: a venue pair (cacheable) or, when enabled, the visitor's starting leg. */
interface RoutableLeg {
  index: number;
  from: Waypoint;
  to: Waypoint;
  pair: (PositionPair & { reversed: boolean }) | null;
  fallback: CachedLeg | null;
}

const MAX_BODY_BYTES = 4 * 1024;
const MIN_WAYPOINTS = 2;
const MAX_WAYPOINTS = 6;
const SHORT_LEG_METERS = 10;
const STALE_AFTER_MS = 365 * 24 * 60 * 60 * 1000;
const EXHIBITION_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/u;
const WAYPOINT_KEYS = new Set(["exhibition_id", "lat", "lng"]);
const DEFAULT_ORIGINS = ["app://gallr"];
const ENVIRONMENT_NAMES = [
  "SUPABASE_URL",
  "SUPABASE_SECRET_KEY",
  "SUPABASE_SECRET_KEYS",
  "SUPABASE_SERVICE_ROLE_KEY",
  "WALKING_ROUTE_PROVIDER",
  "ORS_API_KEY",
  "OSRM_BASE_URL",
];

class RequestError extends Error {
  constructor(readonly status: number) {
    super("Walking route request refused.");
  }
}

function environment(env: EnvironmentReader): Record<string, string> {
  return Object.fromEntries(
    ENVIRONMENT_NAMES.map((name) => [name, env(name) ?? ""]),
  );
}

function allowedOrigins(env: EnvironmentReader): Set<string> {
  const configured = env("WALKING_ROUTE_ALLOWED_ORIGINS")?.split(",")
    .map((value) => value.trim()).filter(Boolean);
  return new Set(configured?.length ? configured : DEFAULT_ORIGINS);
}

function empty(status: number, headers: Record<string, string> = {}) {
  return new Response(null, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...headers,
    },
  });
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function coordinate(value: unknown, min: number, max: number): boolean {
  return typeof value === "number" && Number.isFinite(value) && value >= min &&
    value <= max;
}

async function readBoundedText(request: Request): Promise<string> {
  const declared = request.headers.get("content-length");
  if (
    declared && (!/^\d+$/u.test(declared) || Number(declared) > MAX_BODY_BYTES)
  ) throw new RequestError(413);
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BODY_BYTES) {
      await reader.cancel();
      throw new RequestError(413);
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

function parseWaypoints(raw: string): Waypoint[] {
  let decoded: unknown;
  try {
    decoded = JSON.parse(raw);
  } catch {
    throw new RequestError(400);
  }
  if (
    !isRecord(decoded) || Object.keys(decoded).length !== 1 ||
    !Array.isArray(decoded.waypoints) ||
    decoded.waypoints.length < MIN_WAYPOINTS ||
    decoded.waypoints.length > MAX_WAYPOINTS
  ) throw new RequestError(400);
  return decoded.waypoints.map((value: unknown, index: number) => {
    if (
      !isRecord(value) ||
      Object.keys(value).some((key) => !WAYPOINT_KEYS.has(key)) ||
      !coordinate(value.lat, 33, 39) || !coordinate(value.lng, 124, 132)
    ) throw new RequestError(400);
    const positionOnly = index === 0 &&
      (value.exhibition_id === undefined || value.exhibition_id === null);
    if (
      !positionOnly &&
      (typeof value.exhibition_id !== "string" ||
        !EXHIBITION_ID_PATTERN.test(value.exhibition_id))
    ) throw new RequestError(400);
    return {
      exhibitionId: positionOnly ? null : value.exhibition_id as string,
      lat: value.lat as number,
      lng: value.lng as number,
    };
  });
}

function routedLeg(
  distanceM: number,
  polyline: string,
  provider?: ProviderName,
): ResponseLeg {
  return {
    status: "routed",
    distance_m: Math.round(distanceM),
    polyline,
    ...(provider ? { provider } : {}),
  };
}

function fromCache(row: CachedLeg, reversed: boolean): ResponseLeg | null {
  const polyline = reversed ? reversePolyline(row.polyline) : row.polyline;
  return polyline ? routedLeg(row.distanceM, polyline, row.provider) : null;
}

/** Consecutive legs merge into one provider call; runs with a leg that has no cached fallback go first. */
function runsOf(legs: RoutableLeg[]): RoutableLeg[][] {
  const runs: RoutableLeg[][] = [];
  for (const leg of legs) {
    const current = runs[runs.length - 1];
    if (current && current[current.length - 1].index === leg.index - 1) {
      current.push(leg);
    } else runs.push([leg]);
  }
  const needsRoute = (run: RoutableLeg[]) =>
    run.some((leg) => leg.fallback === null);
  return [
    ...runs.filter(needsRoute),
    ...runs.filter((run) => !needsRoute(run)),
  ];
}

export function createWalkingRouteHandler(
  dependencies: WalkingRouteHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const now = dependencies.now ?? (() => new Date());
  const log = dependencies.log ?? ((entry) => console.log(entry));
  const digest = dependencies.digest ?? sourceDigest;

  return async (request) => {
    const counts = { legs: 0, cache_hits: 0, routed: 0, unavailable: 0 };
    let outcome: Outcome = "rejected";
    let storeFailed = false;
    try {
      if (!allowedOrigins(env).has(request.headers.get("origin") ?? "")) {
        throw new RequestError(403);
      }
      if (request.method !== "POST") throw new RequestError(405);
      const contentType = request.headers.get("content-type");
      if (
        contentType?.split(";", 1)[0]?.trim().toLowerCase() !==
          "application/json"
      ) throw new RequestError(415);
      const waypoints = parseWaypoints(await readBoundedText(request));
      const legCount = waypoints.length - 1;
      counts.legs = legCount;
      const answer: Array<ResponseLeg | null> = new Array(legCount).fill(null);
      const finish = (): Response => {
        const legs: ResponseLeg[] = answer.map((leg) =>
          leg ?? { status: "unavailable" }
        );
        counts.routed = legs.filter((leg) => leg.status === "routed").length;
        counts.unavailable = legCount - counts.routed;
        return json({ legs });
      };

      if (env("WALKING_ROUTE_ENABLED") !== "true") {
        outcome = "disabled";
        return finish();
      }
      const secret = env("WALKING_ROUTE_HASH_SECRET")?.trim() ?? "";
      if (
        secret.length < MIN_HASH_SECRET_LENGTH ||
        secret.length > MAX_HASH_SECRET_LENGTH
      ) throw new Error("WALKING_ROUTE_HASH_SECRET is not configured.");
      const source = await digest(secret, requestSource(request));
      const routePositions = env("WALKING_ROUTE_ROUTE_POSITIONS") === "true";

      const routable: RoutableLeg[] = [];
      for (let index = 0; index < legCount; index += 1) {
        const from = waypoints[index];
        const to = waypoints[index + 1];
        if (from.exhibitionId === null && !routePositions) continue;
        if (distanceMeters(from, to) < SHORT_LEG_METERS) {
          answer[index] = routedLeg(0, encodePolyline([from, to]));
          continue;
        }
        routable.push({
          index,
          from,
          to,
          pair: from.exhibitionId === null ? null : orderedPair(
            positionKey(from.lat, from.lng),
            positionKey(to.lat, to.lng),
          ),
          fallback: null,
        });
      }

      const pairs = new Map<string, PositionPair>();
      for (const leg of routable) {
        if (leg.pair) {
          pairs.set(`${leg.pair.fromKey};${leg.pair.toKey}`, {
            fromKey: leg.pair.fromKey,
            toKey: leg.pair.toKey,
          });
        }
      }
      const backend = dependencies.createBackend(environment(env));
      const [decision, rows] = await Promise.all([
        backend.consumeQuota(source, legCount, 0),
        pairs.size > 0 ? backend.lookup([...pairs.values()]) : [],
      ]);
      if (!decision.allowed) {
        outcome = "rate_limited";
        return empty(429, {
          "Retry-After": String(decision.retryAfterSeconds),
        });
      }

      const cachedRows = new Map(
        rows.map((row) => [`${row.fromKey};${row.toKey}`, row]),
      );
      const staleBefore = now().getTime() - STALE_AFTER_MS;
      const misses: RoutableLeg[] = [];
      for (const leg of routable) {
        const row = leg.pair
          ? cachedRows.get(`${leg.pair.fromKey};${leg.pair.toKey}`)
          : undefined;
        const served = row ? fromCache(row, leg.pair!.reversed) : null;
        if (row && served && row.routedAt.getTime() >= staleBefore) {
          answer[leg.index] = served;
          counts.cache_hits += 1;
        } else {
          misses.push({ ...leg, fallback: served ? row! : null });
        }
      }

      const runs = runsOf(misses);
      const provider = runs.length > 0
        ? dependencies.createProvider(environment(env))
        : null;
      const granted = provider
        ? (await backend.consumeQuota(null, 0, runs.length))
          .providerCallsGranted
        : 0;
      const results: ProviderLeg[][] = provider
        ? await Promise.all(
          runs.slice(0, granted).map((run) =>
            provider.route(
              [run[0].from, ...run.map((leg) => leg.to)].map((point) => ({
                lat: point.lat,
                lng: point.lng,
              })),
            )
          ),
        )
        : [];

      const toStore: LegToStore[] = [];
      runs.forEach((run, runIndex) => {
        run.forEach((leg, legIndex) => {
          const result = results[runIndex]?.[legIndex];
          if (result?.status === "routed") {
            const points = simplifyPolyline(result.points);
            const polyline = encodePolyline(points);
            answer[leg.index] = routedLeg(
              result.distanceM,
              polyline,
              provider!.name,
            );
            if (leg.pair && leg.from.exhibitionId && leg.to.exhibitionId) {
              const [fromId, toId] = leg.pair.reversed
                ? [leg.to.exhibitionId, leg.from.exhibitionId]
                : [leg.from.exhibitionId, leg.to.exhibitionId];
              toStore.push({
                fromKey: leg.pair.fromKey,
                fromExhibitionId: fromId,
                toKey: leg.pair.toKey,
                toExhibitionId: toId,
                distanceM: Math.round(result.distanceM),
                polyline: leg.pair.reversed
                  ? encodePolyline([...points].reverse())
                  : polyline,
                provider: provider!.name,
              });
            }
          } else if (leg.fallback) {
            answer[leg.index] = fromCache(leg.fallback, leg.pair!.reversed);
          }
        });
      });

      if (toStore.length > 0) {
        try {
          await backend.store(toStore);
        } catch {
          storeFailed = true;
        }
      }
      outcome = storeFailed ? "store_failed" : "served";
      return finish();
    } catch (error) {
      if (error instanceof RequestError) {
        outcome = "rejected";
        return empty(
          error.status,
          error.status === 405 ? { Allow: "POST" } : {},
        );
      }
      outcome = "unavailable";
      return empty(503);
    } finally {
      log({ event: "walking_route", outcome, ...counts });
    }
  };
}
