import { assertEquals, assertStrictEquals } from "@std/assert";
import fixture from "../fixtures/osrm-foot.json" with { type: "json" };
import { encodePolyline, type LatLng } from "../polyline.ts";
import { createOsrmProvider } from "./osrm.ts";
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

Deno.test("requests the foot profile with full polyline overview and no steps", async () => {
  const urls: string[] = [];
  const provider = createOsrmProvider({
    baseUrl: "https://osrm.example.test",
    fetcher: (request) => {
      urls.push(request.url);
      assertStrictEquals(request.method, "GET");
      return Promise.resolve(json(fixture));
    },
  });
  await provider.route(WAYPOINTS);

  assertEquals(urls, [
    "https://osrm.example.test/route/v1/foot/127.001000,37.534500;126.994500,37.539000;126.988000,37.541500?overview=full&geometries=polyline&steps=false",
  ]);
});

Deno.test("splits the overview at each snapped waypoint with each leg's distance", async () => {
  const provider = createOsrmProvider({
    baseUrl: "https://osrm.example.test",
    fetcher: () => Promise.resolve(json(fixture)),
  });
  const legs = await provider.route(WAYPOINTS);

  assertStrictEquals(provider.name, "osrm");
  assertStrictEquals(legs.length, 2);
  const first = routed(legs[0]);
  const second = routed(legs[1]);
  assertStrictEquals(first.distanceM, 771.4);
  assertStrictEquals(second.distanceM, 652.9);
  assertStrictEquals(first.points.length, 6);
  assertStrictEquals(second.points.length, 5);
  assertEquals(first.points[5], { lat: 37.53899, lng: 126.99452 });
  assertEquals(second.points[0], { lat: 37.53899, lng: 126.99452 });
});

Deno.test("a path that passes a stop before reaching it splits by distance along the path", async () => {
  // Leg 1 walks A → B → E and back to B; leg 2 walks B → C. The first visit of B is not the boundary.
  const a = { lat: 37.5, lng: 127.0 };
  const b = { lat: 37.501, lng: 127.0 };
  const e = { lat: 37.502, lng: 127.0 };
  const c = { lat: 37.501, lng: 127.002 };
  const body = {
    code: "Ok",
    routes: [{
      geometry: encodePolyline([a, b, e, b, c]),
      legs: [{ distance: 333.6 }, { distance: 176.6 }],
    }],
    waypoints: [
      { location: [a.lng, a.lat] },
      { location: [b.lng, b.lat] },
      { location: [c.lng, c.lat] },
    ],
  };
  const provider = createOsrmProvider({
    baseUrl: "https://osrm.example.test",
    fetcher: () => Promise.resolve(json(body)),
  });
  const legs = await provider.route([a, b, c]);
  assertEquals(routed(legs[0]).points, [a, b, e, b]);
  assertEquals(routed(legs[1]).points, [b, c]);
});

Deno.test("NoRoute, HTTP errors, malformed bodies and timeouts give unavailable legs", async () => {
  const route = fixture.routes[0];
  const cases: Array<() => Promise<Response>> = [
    () => Promise.resolve(json({ code: "NoRoute", message: "No route" }, 400)),
    () => Promise.resolve(json({ code: "NoRoute", routes: [] })),
    () => Promise.resolve(json({ message: "busy" }, 503)),
    () => Promise.resolve(json({ ...fixture, routes: [] })),
    () =>
      Promise.resolve(
        json({ ...fixture, routes: [{ ...route, geometry: "_p~iF" }] }),
      ),
    () =>
      Promise.resolve(
        json({ ...fixture, routes: [{ ...route, legs: route.legs.slice(1) }] }),
      ),
    () =>
      Promise.resolve(
        json({ ...fixture, waypoints: fixture.waypoints.slice(1) }),
      ),
    () => Promise.reject(new TypeError("connection refused")),
  ];
  for (const [index, respond] of cases.entries()) {
    const provider = createOsrmProvider({
      baseUrl: "https://osrm.example.test",
      fetcher: respond,
    });
    assertEquals(
      await provider.route(WAYPOINTS),
      [{ status: "unavailable" }, { status: "unavailable" }],
      `case ${index}`,
    );
  }

  const slow = createOsrmProvider({
    baseUrl: "https://osrm.example.test",
    timeoutMs: 5,
    fetcher: (request) =>
      new Promise((_, reject) => {
        request.signal.addEventListener("abort", () =>
          reject(new DOMException("aborted", "AbortError")));
      }),
  });
  assertEquals(await slow.route(WAYPOINTS), [
    { status: "unavailable" },
    { status: "unavailable" },
  ]);
});

Deno.test("is chosen by WALKING_ROUTE_PROVIDER=osrm with a trusted base URL", () => {
  assertStrictEquals(
    createWalkingProvider({
      WALKING_ROUTE_PROVIDER: "osrm",
      OSRM_BASE_URL: "https://osrm.example.test/",
    })?.name,
    "osrm",
  );
  assertStrictEquals(
    createWalkingProvider({
      WALKING_ROUTE_PROVIDER: "osrm",
      OSRM_BASE_URL: "http://127.0.0.1:5000",
    })?.name,
    "osrm",
  );
  for (
    const base of [
      undefined,
      "",
      "http://osrm.example.test",
      "not a url",
      "https://osrm.example.test/?key=1",
    ]
  ) {
    assertStrictEquals(
      createWalkingProvider({
        WALKING_ROUTE_PROVIDER: "osrm",
        OSRM_BASE_URL: base,
      }),
      null,
      String(base),
    );
  }
});
