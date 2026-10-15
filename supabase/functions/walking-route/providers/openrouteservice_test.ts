import { assertEquals, assertStrictEquals } from "@std/assert";
import fixture from "../fixtures/ors-foot-walking.json" with { type: "json" };
import noRoute from "../fixtures/ors-no-route.json" with { type: "json" };
import type { LatLng } from "../polyline.ts";
import { createOpenRouteServiceProvider } from "./openrouteservice.ts";
import { createWalkingProvider, type ProviderLeg } from "./provider.ts";

const WAYPOINTS: LatLng[] = [
  { lat: 37.5345, lng: 127.001 },
  { lat: 37.539, lng: 126.9945 },
  { lat: 37.5415, lng: 126.988 },
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function routed(leg: ProviderLeg) {
  if (leg.status !== "routed") throw new Error("expected a routed leg");
  return leg;
}

Deno.test("posts lng,lat waypoints to the HeiGIT foot-walking endpoint with the key", async () => {
  const requests: Request[] = [];
  const provider = createOpenRouteServiceProvider({
    apiKey: "test-key",
    fetcher: (request) => {
      requests.push(request);
      return Promise.resolve(json(fixture));
    },
  });
  await provider.route(WAYPOINTS);

  assertStrictEquals(requests.length, 1);
  const request = requests[0];
  assertStrictEquals(request.method, "POST");
  assertStrictEquals(
    request.url,
    "https://api.heigit.org/openrouteservice/v2/directions/foot-walking/json",
  );
  assertStrictEquals(request.headers.get("authorization"), "test-key");
  assertStrictEquals(
    request.headers.get("content-type")?.startsWith("application/json"),
    true,
  );
  assertEquals(await request.json(), {
    coordinates: [[127.001, 37.5345], [126.9945, 37.539], [126.988, 37.5415]],
    elevation: false,
    instructions: true,
  });
});

Deno.test("splits the geometry by way_points with each segment's distance", async () => {
  const provider = createOpenRouteServiceProvider({
    apiKey: "test-key",
    fetcher: () => Promise.resolve(json(fixture)),
  });
  const legs = await provider.route(WAYPOINTS);

  assertStrictEquals(provider.name, "ors");
  assertStrictEquals(legs.length, 2);
  const first = routed(legs[0]);
  const second = routed(legs[1]);
  assertStrictEquals(first.distanceM, 771.4);
  assertStrictEquals(second.distanceM, 652.9);
  assertStrictEquals(first.points.length, 6);
  assertStrictEquals(second.points.length, 5);
  assertEquals(first.points[0], { lat: 37.53452, lng: 127.00098 });
  assertEquals(first.points[5], second.points[0]);
  assertEquals(second.points[4], { lat: 37.54151, lng: 126.98801 });
});

for (const status of [400, 401, 403, 429, 500, 502, 503]) {
  Deno.test(`HTTP ${status} gives every leg unavailable`, async () => {
    const provider = createOpenRouteServiceProvider({
      apiKey: "test-key",
      fetcher: () => Promise.resolve(json({ error: "upstream" }, status)),
    });
    assertEquals(await provider.route(WAYPOINTS), [
      { status: "unavailable" },
      { status: "unavailable" },
    ]);
  });
}

Deno.test("no route (code 2009) gives the requested leg unavailable", async () => {
  const provider = createOpenRouteServiceProvider({
    apiKey: "test-key",
    fetcher: () => Promise.resolve(json(noRoute, 404)),
  });
  assertEquals(await provider.route(WAYPOINTS.slice(0, 2)), [
    { status: "unavailable" },
  ]);
});

Deno.test("a timeout gives every leg unavailable", async () => {
  const provider = createOpenRouteServiceProvider({
    apiKey: "test-key",
    timeoutMs: 5,
    fetcher: (request) =>
      new Promise((_, reject) => {
        request.signal.addEventListener("abort", () =>
          reject(new DOMException("aborted", "AbortError")));
      }),
  });
  assertEquals(await provider.route(WAYPOINTS), [
    { status: "unavailable" },
    { status: "unavailable" },
  ]);
});

Deno.test("a network failure gives every leg unavailable", async () => {
  const provider = createOpenRouteServiceProvider({
    apiKey: "test-key",
    fetcher: () => Promise.reject(new TypeError("connection refused")),
  });
  assertEquals(await provider.route(WAYPOINTS), [
    { status: "unavailable" },
    { status: "unavailable" },
  ]);
});

Deno.test("a malformed body gives every leg unavailable", async () => {
  const route = fixture.routes[0];
  for (
    const body of [
      {},
      { routes: [] },
      { routes: [{ ...route, geometry: "_p~iF" }] },
      { routes: [{ ...route, way_points: [0, 9] }] },
      { routes: [{ ...route, way_points: [0, 7, 5] }] },
      { routes: [{ ...route, way_points: [0, 5, 12] }] },
      { routes: [{ ...route, segments: route.segments.slice(0, 1) }] },
      {
        routes: [{
          ...route,
          segments: [{ distance: -1 }, route.segments[1]],
        }],
      },
    ]
  ) {
    const provider = createOpenRouteServiceProvider({
      apiKey: "test-key",
      fetcher: () => Promise.resolve(json(body)),
    });
    assertEquals(
      await provider.route(WAYPOINTS),
      [{ status: "unavailable" }, { status: "unavailable" }],
      JSON.stringify(body).slice(0, 80),
    );
  }
});

Deno.test("is the default provider and needs ORS_API_KEY", () => {
  assertStrictEquals(createWalkingProvider({})?.name, undefined);
  assertStrictEquals(createWalkingProvider({ ORS_API_KEY: "k" })?.name, "ors");
  assertStrictEquals(
    createWalkingProvider({ WALKING_ROUTE_PROVIDER: "ors", ORS_API_KEY: "k" })
      ?.name,
    "ors",
  );
  assertStrictEquals(
    createWalkingProvider({ WALKING_ROUTE_PROVIDER: "tmap", ORS_API_KEY: "k" }),
    null,
  );
});
