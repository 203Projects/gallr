import { decodePolyline, type LatLng } from "../polyline.ts";
import { distanceMeters } from "../position.ts";
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

/** A snapped waypoint printed with six decimals lies within this distance of its precision-5 geometry vertex. */
const BOUNDARY_MATCH_METERS = 5;

export interface OsrmOptions {
  baseUrl: string;
  fetcher: Fetcher;
  timeoutMs?: number;
}

function snappedLocation(value: unknown): LatLng | null {
  if (!isRecord(value) || !Array.isArray(value.location)) return null;
  const [lng, lat] = value.location;
  return typeof lat === "number" && typeof lng === "number" &&
      Number.isFinite(lat) && Number.isFinite(lng)
    ? { lat, lng }
    : null;
}

/**
 * The geometry index where each inner leg ends. With `overview=full` the overview passes through every snapped
 * waypoint, so the boundary is a vertex near `waypoints[i].location`. Of the vertices after the previous boundary
 * within 5 m of it, the one whose distance along the path is closest to the sum of the earlier legs wins (a path
 * may pass a stop before it reaches it); with none that close, the closest distance along the path wins.
 */
function boundaries(
  points: LatLng[],
  snapped: LatLng[],
  legDistances: number[],
): number[] | null {
  const along = [0];
  for (let index = 1; index < points.length; index += 1) {
    along.push(
      along[index - 1] + distanceMeters(points[index - 1], points[index]),
    );
  }
  const result = [0];
  let target = 0;
  for (let leg = 0; leg < legDistances.length - 1; leg += 1) {
    target += legDistances[leg];
    const previous = result[result.length - 1];
    let best = -1;
    let bestNear = false;
    let bestGap = Infinity;
    for (let index = previous; index < points.length; index += 1) {
      const near = distanceMeters(points[index], snapped[leg + 1]) <=
        BOUNDARY_MATCH_METERS;
      const gap = Math.abs(along[index] - target);
      if ((near && !bestNear) || (near === bestNear && gap < bestGap)) {
        best = index;
        bestNear = near;
        bestGap = gap;
      }
    }
    if (best < 0) return null;
    result.push(best);
  }
  result.push(points.length - 1);
  return result;
}

function parseLegs(body: unknown, waypointCount: number): ProviderLeg[] | null {
  if (!isRecord(body) || body.code !== "Ok" || !Array.isArray(body.routes)) {
    return null;
  }
  const route = body.routes[0];
  if (
    !isRecord(route) || typeof route.geometry !== "string" ||
    !Array.isArray(route.legs) || route.legs.length !== waypointCount - 1 ||
    !Array.isArray(body.waypoints) || body.waypoints.length !== waypointCount
  ) return null;
  const points = decodePolyline(route.geometry);
  if (!points || points.length === 0) return null;
  const distances = route.legs.map((leg) =>
    isRecord(leg) && validDistance(leg.distance) ? leg.distance : null
  );
  const snapped = body.waypoints.map(snappedLocation);
  if (
    distances.some((value) => value === null) ||
    snapped.some((value) => value === null)
  ) return null;
  const cuts = boundaries(points, snapped as LatLng[], distances as number[]);
  if (!cuts) return null;
  return (distances as number[]).map((distanceM, index) => ({
    status: "routed",
    distanceM,
    points: drawable(points.slice(cuts[index], cuts[index + 1] + 1)),
  }));
}

/** A self-hosted OSRM `foot` profile (research R2), selected with WALKING_ROUTE_PROVIDER=osrm. */
export function createOsrmProvider(options: OsrmOptions): WalkingProvider {
  const timeoutMs = options.timeoutMs ?? PROVIDER_TIMEOUT_MS;
  const base = options.baseUrl.replace(/\/+$/u, "");
  return {
    name: "osrm",
    async route(waypoints: readonly LatLng[]): Promise<ProviderLeg[]> {
      const coordinates = waypoints
        .map((point) => `${point.lng.toFixed(6)},${point.lat.toFixed(6)}`)
        .join(";");
      const request = new Request(
        `${base}/route/v1/foot/${coordinates}?overview=full&geometries=polyline&steps=false`,
        {
          method: "GET",
          headers: { Accept: "application/json" },
          redirect: "error",
        },
      );
      const result = await fetchJson(options.fetcher, request, timeoutMs);
      if (!result || result.status !== 200) {
        return unavailableLegs(waypoints.length);
      }
      return parseLegs(result.body, waypoints.length) ??
        unavailableLegs(waypoints.length);
    },
  };
}
