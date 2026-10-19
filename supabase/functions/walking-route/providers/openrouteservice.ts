import { decodePolyline, type LatLng } from "../polyline.ts";
import {
  drawable,
  type Fetcher,
  fetchJson,
  isRecord,
  PROVIDER_TIMEOUT_MS,
  type ProviderLeg,
  unavailableLegs,
  validDistance,
  type WalkingProvider,
} from "./provider.ts";

/** HeiGIT's base URL; api.openrouteservice.org is retired in November 2026 (research R1). */
const ENDPOINT =
  "https://api.heigit.org/openrouteservice/v2/directions/foot-walking/json";

export interface OpenRouteServiceOptions {
  apiKey: string;
  fetcher: Fetcher;
  timeoutMs?: number;
}

/**
 * Reads `routes[0]`: `geometry` is one encoded polyline (precision 5) for the whole walk, `way_points[i]` is the
 * geometry index of waypoint i, and `segments[i].distance` is leg i in metres. Anything else is unavailable.
 */
function parseLegs(body: unknown, waypointCount: number): ProviderLeg[] | null {
  if (!isRecord(body) || !Array.isArray(body.routes)) return null;
  const route = body.routes[0];
  if (!isRecord(route) || typeof route.geometry !== "string") return null;
  const points = decodePolyline(route.geometry);
  const wayPoints = route.way_points;
  const segments = route.segments;
  if (
    !points || points.length === 0 || !Array.isArray(wayPoints) ||
    !Array.isArray(segments) || wayPoints.length !== waypointCount ||
    segments.length !== waypointCount - 1
  ) return null;
  if (
    wayPoints[0] !== 0 || wayPoints[wayPoints.length - 1] !== points.length - 1
  ) return null;
  const legs: ProviderLeg[] = [];
  for (let index = 0; index < waypointCount - 1; index += 1) {
    const start = wayPoints[index];
    const end = wayPoints[index + 1];
    const segment = segments[index];
    if (
      !Number.isSafeInteger(start) || !Number.isSafeInteger(end) ||
      start > end || !isRecord(segment) || !validDistance(segment.distance)
    ) return null;
    legs.push({
      status: "routed",
      distanceM: segment.distance,
      points: drawable(points.slice(start, end + 1)),
    });
  }
  return legs;
}

/**
 * openrouteservice `foot-walking`. One call carries every waypoint of a run. A "no route" answer (error 2009, or
 * 2010 when a point has no walkable way nearby) covers the whole call, so every leg of that call is unavailable;
 * the handler sends each run of consecutive misses separately, so a single-leg run marks only that leg.
 */
export function createOpenRouteServiceProvider(
  options: OpenRouteServiceOptions,
): WalkingProvider {
  const timeoutMs = options.timeoutMs ?? PROVIDER_TIMEOUT_MS;
  return {
    name: "ors",
    async route(waypoints: readonly LatLng[]): Promise<ProviderLeg[]> {
      const request = new Request(ENDPOINT, {
        method: "POST",
        headers: {
          Accept: "application/json",
          Authorization: options.apiKey,
          "Content-Type": "application/json; charset=utf-8",
        },
        body: JSON.stringify({
          coordinates: waypoints.map((point) => [point.lng, point.lat]),
          elevation: false,
          instructions: true,
        }),
        redirect: "error",
      });
      const result = await fetchJson(options.fetcher, request, timeoutMs);
      if (!result || result.status !== 200) {
        return unavailableLegs(waypoints.length);
      }
      return parseLegs(result.body, waypoints.length) ??
        unavailableLegs(waypoints.length);
    },
  };
}
