import type { LatLng } from "../polyline.ts";
import { createOpenRouteServiceProvider } from "./openrouteservice.ts";
import { createOsrmProvider } from "./osrm.ts";

export type ProviderName = "ors" | "osrm";

/** One leg as the provider walked it: metres along the path and the full path, or nothing. */
export type ProviderLeg =
  | { status: "routed"; distanceM: number; points: LatLng[] }
  | { status: "unavailable" };

/**
 * A walking router. `route` takes 2 or more waypoints and resolves to exactly `waypoints.length - 1` legs, in
 * order. It never rejects: timeouts, HTTP errors, "no route" and malformed responses become unavailable legs, and
 * no upstream text is passed on.
 */
export interface WalkingProvider {
  readonly name: ProviderName;
  route(waypoints: readonly LatLng[]): Promise<ProviderLeg[]>;
}

export type Fetcher = (request: Request) => Promise<Response>;

export const PROVIDER_TIMEOUT_MS = 8_000;
const MAX_PROVIDER_RESPONSE_BYTES = 2 * 1024 * 1024;

export function unavailableLegs(waypointCount: number): ProviderLeg[] {
  return Array.from(
    { length: Math.max(0, waypointCount - 1) },
    () => ({ status: "unavailable" as const }),
  );
}

/** Sends a request with a hard timeout and reads at most 2 MiB of JSON; any failure gives null. */
export async function fetchJson(
  fetcher: Fetcher,
  request: Request,
  timeoutMs: number,
): Promise<{ status: number; body: unknown } | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetcher(
      new Request(request, { signal: controller.signal }),
    );
    if (!response.body) return { status: response.status, body: null };
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_PROVIDER_RESPONSE_BYTES) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    try {
      return {
        status: response.status,
        body: JSON.parse(new TextDecoder().decode(bytes)),
      };
    } catch {
      return { status: response.status, body: null };
    }
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function validDistance(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

/** A leg needs two points to draw; a provider that snaps both ends to one point gives a zero-length segment. */
export function drawable(points: LatLng[]): LatLng[] {
  return points.length === 1 ? [points[0], points[0]] : points;
}

function trustedBaseUrl(value: string | undefined): string | null {
  if (!value?.trim()) return null;
  try {
    const url = new URL(value.trim());
    const local = url.protocol === "http:" &&
      (url.hostname === "127.0.0.1" || url.hostname === "localhost");
    if (url.protocol !== "https:" && !local) return null;
    if (url.search || url.hash || url.username || url.password) return null;
    return url.toString().replace(/\/+$/u, "");
  } catch {
    return null;
  }
}

/**
 * The provider named by WALKING_ROUTE_PROVIDER (`ors` by default, or `osrm`), or null when it is not configured:
 * every miss is then unavailable and no provider budget is spent.
 */
export function createWalkingProvider(
  environment: Record<string, string | undefined>,
  fetcher: Fetcher = (request) => fetch(request),
): WalkingProvider | null {
  const name = environment.WALKING_ROUTE_PROVIDER?.trim() || "ors";
  if (name === "ors") {
    const apiKey = environment.ORS_API_KEY?.trim();
    return apiKey ? createOpenRouteServiceProvider({ apiKey, fetcher }) : null;
  }
  if (name === "osrm") {
    const baseUrl = trustedBaseUrl(environment.OSRM_BASE_URL);
    return baseUrl ? createOsrmProvider({ baseUrl, fetcher }) : null;
  }
  return null;
}
