import { assertEquals, assertStrictEquals } from "@std/assert";
import type {
  CachedLeg,
  LegToStore,
  PositionPair,
  QuotaDecision,
  WalkingRouteBackend,
} from "./backend.ts";
import { createWalkingRouteHandler } from "./handler.ts";
import { decodePolyline, encodePolyline, type LatLng } from "./polyline.ts";
import type { ProviderLeg, WalkingProvider } from "./providers/provider.ts";

const SECRET = "s".repeat(40);
const A = { exhibition_id: "ex-a", lat: 37.5445, lng: 127.056 };
const B = { exhibition_id: "ex-b", lat: 37.5345, lng: 127.001 };
const C = { exhibition_id: "ex-c", lat: 37.57, lng: 126.98 };
const D = { exhibition_id: "ex-d", lat: 37.58, lng: 126.99 };
const E = { exhibition_id: "ex-e", lat: 37.56, lng: 127.02 };
const POSITION = { lat: 37.55123, lng: 127.01234 };
const KEY = {
  a: "37.54450,127.05600",
  b: "37.53450,127.00100",
  c: "37.57000,126.98000",
  d: "37.58000,126.99000",
  e: "37.56000,127.02000",
};
const NOW = new Date("2026-10-14T03:00:00Z");

class FakeBackend implements WalkingRouteBackend {
  rows: CachedLeg[] = [];
  consumed: Array<{ source: string | null; legs: number; calls: number }> = [];
  lookups: PositionPair[][] = [];
  stored: LegToStore[][] = [];
  sourceDecision: QuotaDecision = {
    allowed: true,
    retryAfterSeconds: 0,
    providerCallsGranted: 0,
  };
  providerBudget = 30;
  failLookup = false;
  failQuota = false;
  failStore = false;

  consumeQuota(source: string | null, legs: number, calls: number) {
    this.consumed.push({ source, legs, calls });
    if (this.failQuota) return Promise.reject(new Error("quota down"));
    if (legs > 0 && !this.sourceDecision.allowed) {
      return Promise.resolve(this.sourceDecision);
    }
    const granted = Math.min(calls, this.providerBudget);
    this.providerBudget -= granted;
    return Promise.resolve({
      allowed: true,
      retryAfterSeconds: 0,
      providerCallsGranted: granted,
    });
  }
  lookup(pairs: PositionPair[]) {
    this.lookups.push(pairs);
    if (this.failLookup) return Promise.reject(new Error("lookup down"));
    return Promise.resolve(
      this.rows.filter((row) =>
        pairs.some((pair) =>
          pair.fromKey === row.fromKey && pair.toKey === row.toKey
        )
      ),
    );
  }
  store(legs: LegToStore[]) {
    this.stored.push(legs);
    if (this.failStore) return Promise.reject(new Error("store down"));
    return Promise.resolve(legs.length);
  }
}

class FakeProvider implements WalkingProvider {
  readonly name = "ors" as const;
  calls: LatLng[][] = [];
  fail = false;
  pointsPerLeg = 3;
  route(waypoints: readonly LatLng[]): Promise<ProviderLeg[]> {
    this.calls.push([...waypoints]);
    return Promise.resolve(
      waypoints.slice(1).map((to, index): ProviderLeg => {
        if (this.fail) return { status: "unavailable" };
        const from = waypoints[index];
        const points = Array.from(
          { length: this.pointsPerLeg },
          (_, step) => {
            const t = step / (this.pointsPerLeg - 1);
            return {
              lat: from.lat + (to.lat - from.lat) * t +
                (step % 2) * 0.0002,
              lng: from.lng + (to.lng - from.lng) * t,
            };
          },
        );
        return { status: "routed", distanceM: 1000.4, points };
      }),
    );
  }
}

interface Setup {
  backend: FakeBackend;
  provider: FakeProvider | null;
  logs: Record<string, unknown>[];
  environment: Record<string, string | undefined>;
  backendCreations: number;
  providerCreations: number;
}

function setup(overrides: Record<string, string | undefined> = {}): Setup {
  return {
    backend: new FakeBackend(),
    provider: new FakeProvider(),
    logs: [],
    environment: {
      WALKING_ROUTE_ENABLED: "true",
      WALKING_ROUTE_HASH_SECRET: SECRET,
      SUPABASE_URL: "https://project.supabase.co",
      ...overrides,
    },
    backendCreations: 0,
    providerCreations: 0,
  };
}

function handler(state: Setup) {
  return createWalkingRouteHandler({
    env: (name) => state.environment[name],
    now: () => NOW,
    log: (entry) => state.logs.push({ ...entry }),
    createBackend: () => {
      state.backendCreations += 1;
      return state.backend;
    },
    createProvider: () => {
      state.providerCreations += 1;
      return state.provider;
    },
  });
}

function request(
  body: unknown,
  options: {
    origin?: string | null;
    method?: string;
    contentType?: string;
    raw?: string;
    headers?: Record<string, string>;
  } = {},
): Request {
  const headers: Record<string, string> = {
    "Content-Type": options.contentType ?? "application/json",
    "cf-connecting-ip": "203.0.113.7",
    ...options.headers,
  };
  const origin = options.origin === undefined ? "app://gallr" : options.origin;
  if (origin !== null) headers.Origin = origin;
  const method = options.method ?? "POST";
  return new Request(
    "https://project.supabase.co/functions/v1/walking-route",
    {
      method,
      headers,
      body: method === "POST" ? options.raw ?? JSON.stringify(body) : undefined,
    },
  );
}

async function call(state: Setup, body: unknown, options = {}) {
  const response = await handler(state)(request(body, options));
  const text = await response.text();
  return {
    response,
    body: text ? JSON.parse(text) : null,
  };
}

function cached(
  fromKey: string,
  toKey: string,
  points: LatLng[],
  distanceM = 777,
  routedAt = new Date("2026-09-01T00:00:00Z"),
  provider: "ors" | "osrm" = "ors",
): CachedLeg {
  return {
    fromKey,
    toKey,
    distanceM,
    polyline: encodePolyline(points),
    routedAt,
    provider,
  };
}

const unavailable = { status: "unavailable" };

// Transport and validation

Deno.test("only the exact allowed origins pass", async () => {
  for (
    const origin of [
      null,
      "https://gallrmap.com",
      "app://gallr.evil",
      "APP://GALLR",
    ]
  ) {
    const state = setup();
    const { response } = await call(state, { waypoints: [A, B] }, { origin });
    assertStrictEquals(response.status, 403, String(origin));
    assertStrictEquals(state.backendCreations, 0);
  }
  const configured = setup({
    WALKING_ROUTE_ALLOWED_ORIGINS: "app://gallr-staging, app://gallr",
  });
  assertStrictEquals(
    (await call(configured, { waypoints: [A, B] }, {
      origin: "app://gallr-staging",
    })).response.status,
    200,
  );
});

Deno.test("only POST is accepted", async () => {
  const { response } = await call(setup(), null, { method: "GET" });
  assertStrictEquals(response.status, 405);
  assertStrictEquals(response.headers.get("allow"), "POST");
});

Deno.test("only JSON is accepted", async () => {
  const { response } = await call(setup(), { waypoints: [A, B] }, {
    contentType: "text/plain",
  });
  assertStrictEquals(response.status, 415);
  const charset = await call(setup(), { waypoints: [A, B] }, {
    contentType: "application/json; charset=utf-8",
  });
  assertStrictEquals(charset.response.status, 200);
});

Deno.test("bodies over 4 KiB are refused", async () => {
  const large = JSON.stringify({ waypoints: [A, B], pad: "x".repeat(5000) });
  assertStrictEquals(
    (await call(setup(), null, { raw: large })).response.status,
    413,
  );
  assertStrictEquals(
    (await call(setup(), null, {
      raw: "{}",
      headers: { "Content-Length": "5000" },
    })).response.status,
    413,
  );
});

Deno.test("invalid bodies are refused with 400 and never echoed", async () => {
  const invalid: unknown[] = [
    "not json",
    [],
    {},
    { waypoints: [A] },
    { waypoints: [A, B, C, D, E, A, B] },
    { waypoints: [A, B], extra: true },
    { waypoints: [A, { ...B, lat: 32.9 }] },
    { waypoints: [A, { ...B, lat: 39.1 }] },
    { waypoints: [A, { ...B, lng: 123.9 }] },
    { waypoints: [A, { ...B, lng: 132.1 }] },
    { waypoints: [A, { ...B, lat: "37.5" }] },
    { waypoints: [A, { lat: B.lat, lng: B.lng }] },
    { waypoints: [A, { ...B, exhibition_id: null }] },
    { waypoints: [A, { ...B, exhibition_id: "ex b" }] },
    { waypoints: [A, { ...B, exhibition_id: "" }] },
    { waypoints: [A, { ...B, exhibition_id: "x".repeat(129) }] },
    { waypoints: [A, { ...B, name: "venue" }] },
    { waypoints: [A, "37.5,127.0"] },
  ];
  for (const body of invalid) {
    const state = setup();
    const { response, body: answer } = await call(
      state,
      null,
      { raw: typeof body === "string" ? body : JSON.stringify(body) },
    );
    assertStrictEquals(response.status, 400, JSON.stringify(body));
    assertStrictEquals(answer, null);
    assertStrictEquals(state.backendCreations, 0);
  }
  const longest = await call(setup(), {
    waypoints: [A, { ...B, exhibition_id: "x".repeat(128) }],
  });
  assertStrictEquals(longest.response.status, 200);
});

// Kill switch

for (const enabled of [undefined, "false", "TRUE", "1"]) {
  Deno.test(`kill switch ${enabled ?? "absent"}: every leg is unavailable, no backend or provider`, async () => {
    const state = setup({ WALKING_ROUTE_ENABLED: enabled });
    const { response, body } = await call(state, { waypoints: [A, B, C] });
    assertStrictEquals(response.status, 200);
    assertEquals(body, { legs: [unavailable, unavailable] });
    assertStrictEquals(state.backendCreations, 0);
    assertStrictEquals(state.providerCreations, 0);
    assertStrictEquals(state.logs[0].outcome, "disabled");
  });
}

// Cache

Deno.test("a full cache hit never calls the provider", async () => {
  const state = setup();
  const ab = [{ lat: 37.5345, lng: 127.001 }, { lat: 37.5445, lng: 127.056 }];
  const bc = [{ lat: 37.5345, lng: 127.001 }, { lat: 37.57, lng: 126.98 }];
  state.backend.rows = [
    cached(KEY.b, KEY.a, ab, 5230),
    cached(KEY.b, KEY.c, bc, 4100),
  ];
  const { response, body } = await call(state, { waypoints: [B, A, B, C] });

  assertStrictEquals(response.status, 200);
  assertStrictEquals(response.headers.get("cache-control"), "no-store");
  assertEquals(body.legs.map((leg: { distance_m: number }) => leg.distance_m), [
    5230,
    5230,
    4100,
  ]);
  assertStrictEquals(state.provider!.calls.length, 0);
  assertStrictEquals(state.backend.stored.length, 0);
  assertEquals(state.backend.consumed, [{
    source: state.backend.consumed[0].source,
    legs: 3,
    calls: 0,
  }]);
  assertEquals(state.logs, [{
    event: "walking_route",
    outcome: "served",
    legs: 3,
    cache_hits: 3,
    routed: 3,
    unavailable: 0,
  }]);
});

Deno.test("a reverse lookup returns the reversed path", async () => {
  const state = setup();
  const path = [
    { lat: 37.5345, lng: 127.001 },
    { lat: 37.54, lng: 127.03 },
    { lat: 37.5445, lng: 127.056 },
  ];
  state.backend.rows = [cached(KEY.b, KEY.a, path)];
  const { body } = await call(state, { waypoints: [A, B] });
  assertEquals(state.backend.lookups[0], [{ fromKey: KEY.b, toKey: KEY.a }]);
  assertEquals(decodePolyline(body.legs[0].polyline), [...path].reverse());
});

Deno.test("each routed leg names the router that drew it; a short leg names none", async () => {
  const state = setup();
  const ab = [{ lat: 37.5345, lng: 127.001 }, { lat: 37.5445, lng: 127.056 }];
  state.backend.rows = [cached(KEY.b, KEY.a, ab, 5230, undefined, "osrm")];
  const nearA = { ...A, lat: A.lat + 0.00001 };
  const { response, body } = await call(state, {
    waypoints: [B, A, nearA, C],
  });

  assertStrictEquals(response.status, 200);
  assertEquals(
    body.legs.map((leg: { status: string; provider?: string }) => [
      leg.status,
      leg.provider ?? null,
    ]),
    [["routed", "osrm"], ["routed", null], ["routed", state.provider!.name]],
  );
  assertStrictEquals(state.provider!.calls.length, 1);
});

Deno.test("a mixed request routes only the misses, one provider call per consecutive run", async () => {
  const state = setup();
  state.backend.rows = [
    cached(KEY.b, KEY.c, [{ lat: B.lat, lng: B.lng }, {
      lat: C.lat,
      lng: C.lng,
    }]),
  ];
  // Legs: A→B miss, B→C hit, C→D miss, D→E miss.
  const { body } = await call(state, { waypoints: [A, B, C, D, E] });

  assertEquals(state.provider!.calls, [
    [{ lat: A.lat, lng: A.lng }, { lat: B.lat, lng: B.lng }],
    [{ lat: C.lat, lng: C.lng }, { lat: D.lat, lng: D.lng }, {
      lat: E.lat,
      lng: E.lng,
    }],
  ]);
  assertEquals(state.backend.consumed.map((entry) => entry.calls), [0, 2]);
  assertEquals(
    body.legs.map((leg: { status: string; distance_m: number }) => [
      leg.status,
      leg.distance_m,
    ]),
    [["routed", 1000], ["routed", 777], ["routed", 1000], ["routed", 1000]],
  );
  assertStrictEquals(state.logs[0].cache_hits, 1);
});

Deno.test("only venue pairs are stored, smaller key first with the path drawn that way", async () => {
  const state = setup();
  const { body } = await call(state, { waypoints: [A, B, C] });
  const stored = state.backend.stored.flat();

  assertEquals(
    stored.map((leg) => [
      leg.fromKey,
      leg.fromExhibitionId,
      leg.toKey,
      leg.toExhibitionId,
      leg.distanceM,
      leg.provider,
    ]),
    [
      [KEY.b, "ex-b", KEY.a, "ex-a", 1000, "ors"],
      [KEY.b, "ex-b", KEY.c, "ex-c", 1000, "ors"],
    ],
  );
  // A→B was walked from A, so the stored B→A path is the response path reversed.
  assertEquals(
    decodePolyline(stored[0].polyline),
    decodePolyline(body.legs[0].polyline)!.reverse(),
  );
  assertStrictEquals(stored[1].polyline, body.legs[1].polyline);
});

Deno.test("a stale row is routed again when the budget allows", async () => {
  const state = setup();
  state.backend.rows = [
    cached(
      KEY.b,
      KEY.a,
      [{ lat: B.lat, lng: B.lng }, { lat: A.lat, lng: A.lng }],
      5230,
      new Date("2025-10-01T00:00:00Z"),
    ),
  ];
  const { body } = await call(state, { waypoints: [B, A] });
  assertStrictEquals(state.provider!.calls.length, 1);
  assertStrictEquals(body.legs[0].distance_m, 1000);
  assertStrictEquals(state.backend.stored.flat().length, 1);
});

Deno.test("a stale row is served when the budget is spent", async () => {
  const state = setup();
  state.backend.providerBudget = 0;
  state.backend.rows = [
    cached(
      KEY.b,
      KEY.a,
      [{ lat: B.lat, lng: B.lng }, { lat: A.lat, lng: A.lng }],
      5230,
      new Date("2025-10-01T00:00:00Z"),
    ),
  ];
  const { body } = await call(state, { waypoints: [B, A] });
  assertStrictEquals(state.provider!.calls.length, 0);
  assertStrictEquals(body.legs[0].distance_m, 5230);
});

// Budgets and quotas

Deno.test("provider budget exhausted: misses unavailable, hits served", async () => {
  const state = setup();
  state.backend.providerBudget = 0;
  state.backend.rows = [
    cached(KEY.b, KEY.c, [{ lat: B.lat, lng: B.lng }, {
      lat: C.lat,
      lng: C.lng,
    }]),
  ];
  const { response, body } = await call(state, { waypoints: [A, B, C] });
  assertStrictEquals(response.status, 200);
  assertEquals(body.legs[0], unavailable);
  assertStrictEquals(body.legs[1].status, "routed");
  assertStrictEquals(state.provider!.calls.length, 0);
  assertStrictEquals(state.logs[0].unavailable, 1);
});

Deno.test("a partly granted budget routes runs without a fallback first", async () => {
  const state = setup();
  state.backend.providerBudget = 1;
  state.backend.rows = [
    cached(
      KEY.b,
      KEY.a,
      [{ lat: B.lat, lng: B.lng }, { lat: A.lat, lng: A.lng }],
      5230,
      new Date("2025-10-01T00:00:00Z"),
    ),
    cached(KEY.b, KEY.c, [{ lat: B.lat, lng: B.lng }, {
      lat: C.lat,
      lng: C.lng,
    }]),
  ];
  // Legs: A→B stale, B→C hit, C→D missing.
  const { body } = await call(state, { waypoints: [A, B, C, D] });
  assertEquals(state.provider!.calls, [[{ lat: C.lat, lng: C.lng }, {
    lat: D.lat,
    lng: D.lng,
  }]]);
  assertStrictEquals(body.legs[0].distance_m, 5230);
  assertStrictEquals(body.legs[2].distance_m, 1000);
});

Deno.test("source quota exceeded: 429 with Retry-After of at most an hour", async () => {
  const state = setup();
  state.backend.sourceDecision = {
    allowed: false,
    retryAfterSeconds: 1200,
    providerCallsGranted: 0,
  };
  const { response, body } = await call(state, { waypoints: [A, B] });
  assertStrictEquals(response.status, 429);
  assertStrictEquals(response.headers.get("retry-after"), "1200");
  assertStrictEquals(body, null);
  assertStrictEquals(state.provider!.calls.length, 0);
  assertStrictEquals(state.backend.stored.length, 0);
  assertStrictEquals(state.logs[0].outcome, "rate_limited");
});

Deno.test("the source is an HMAC of the ingress address, never the address", async () => {
  const state = setup();
  await call(state, { waypoints: [A, B] });
  const source = state.backend.consumed[0].source!;
  assertStrictEquals(/^[0-9a-f]{64}$/u.test(source), true);
  const other = setup({ WALKING_ROUTE_HASH_SECRET: "t".repeat(40) });
  await call(other, { waypoints: [A, B] });
  assertStrictEquals(other.backend.consumed[0].source === source, false);
  assertStrictEquals(
    JSON.stringify(state.backend.consumed).includes("203.0.113.7"),
    false,
  );
});

Deno.test("a missing or short hash secret is unavailable before any backend work", async () => {
  for (const secret of [undefined, "short"]) {
    const state = setup({ WALKING_ROUTE_HASH_SECRET: secret });
    const { response } = await call(state, { waypoints: [A, B] });
    assertStrictEquals(response.status, 503);
    assertStrictEquals(state.backendCreations, 0);
  }
});

Deno.test("an unreachable cache store is 503", async () => {
  for (const failure of ["lookup", "quota"] as const) {
    const state = setup();
    if (failure === "lookup") state.backend.failLookup = true;
    else state.backend.failQuota = true;
    const { response, body } = await call(state, { waypoints: [A, B] });
    assertStrictEquals(response.status, 503, failure);
    assertStrictEquals(body, null);
    assertStrictEquals(state.provider!.calls.length, 0);
    assertStrictEquals(state.logs[0].outcome, "unavailable");
  }
});

Deno.test("a failed store still answers with the routed legs", async () => {
  const state = setup();
  state.backend.failStore = true;
  const { response, body } = await call(state, { waypoints: [A, B] });
  assertStrictEquals(response.status, 200);
  assertStrictEquals(body.legs[0].status, "routed");
  assertStrictEquals(state.logs[0].outcome, "store_failed");
});

// Provider results

Deno.test("a provider failure gives unavailable legs and stores nothing", async () => {
  const state = setup();
  state.provider!.fail = true;
  const { body } = await call(state, { waypoints: [A, B, C] });
  assertEquals(body, { legs: [unavailable, unavailable] });
  assertStrictEquals(state.backend.stored.flat().length, 0);
});

Deno.test("an unconfigured provider spends no budget", async () => {
  const state = setup();
  state.provider = null;
  const { body } = await call(state, { waypoints: [A, B] });
  assertEquals(body, { legs: [unavailable] });
  assertEquals(state.backend.consumed.map((entry) => entry.calls), [0]);
});

Deno.test("long provider paths are simplified to at most 500 points", async () => {
  const state = setup();
  state.provider!.pointsPerLeg = 2000;
  const { body } = await call(state, { waypoints: [A, B] });
  const points = decodePolyline(body.legs[0].polyline)!;
  assertStrictEquals(points.length <= 500, true);
  assertStrictEquals(points.length >= 2, true);
});

Deno.test("legs under 10 m are routed with 0 m and two points, without lookup or provider", async () => {
  const state = setup();
  const twin = { exhibition_id: "ex-a2", lat: 37.54451, lng: 127.05601 };
  const { body } = await call(state, { waypoints: [A, twin] });
  assertEquals(body.legs[0].status, "routed");
  assertStrictEquals(body.legs[0].distance_m, 0);
  assertEquals(decodePolyline(body.legs[0].polyline), [
    { lat: A.lat, lng: A.lng },
    { lat: twin.lat, lng: twin.lng },
  ]);
  assertStrictEquals(state.backend.lookups.length, 0);
  assertStrictEquals(state.provider!.calls.length, 0);
  assertStrictEquals(state.backend.stored.length, 0);
});

// The visitor's position

Deno.test("a position-only first waypoint is unavailable while position routing is off", async () => {
  for (const setting of [undefined, "false"]) {
    const state = setup({ WALKING_ROUTE_ROUTE_POSITIONS: setting });
    const { body } = await call(state, { waypoints: [POSITION, A, B] });
    assertEquals(body.legs[0], unavailable);
    assertStrictEquals(body.legs[1].status, "routed");
    const sent = JSON.stringify(state.provider!.calls);
    assertStrictEquals(sent.includes("37.55123"), false);
    assertStrictEquals(
      JSON.stringify(state.backend.stored).includes("37.55123"),
      false,
    );
    assertStrictEquals(
      JSON.stringify(state.backend.lookups).includes("37.55123"),
      false,
    );
  }
});

Deno.test("a position-only first waypoint is routed when enabled, and never stored", async () => {
  const state = setup({ WALKING_ROUTE_ROUTE_POSITIONS: "true" });
  const { body } = await call(state, { waypoints: [POSITION, A, B] });
  assertStrictEquals(body.legs[0].status, "routed");
  assertEquals(state.provider!.calls[0][0], POSITION);
  assertStrictEquals(
    JSON.stringify(state.backend.stored).includes("37.55123"),
    false,
  );
  assertStrictEquals(
    JSON.stringify(state.backend.lookups).includes("37.55"),
    false,
  );
  assertEquals(
    state.backend.stored.flat().map((leg) => leg.fromExhibitionId),
    ["ex-b"],
  );
});

Deno.test("a null exhibition id marks the first waypoint position-only", async () => {
  const state = setup();
  const { response, body } = await call(state, {
    waypoints: [{ ...POSITION, exhibition_id: null }, A],
  });
  assertStrictEquals(response.status, 200);
  assertEquals(body.legs, [unavailable]);
  assertStrictEquals(state.provider!.calls.length, 0);
});

Deno.test("only the first waypoint may be position-only", async () => {
  const { response } = await call(setup(), { waypoints: [A, POSITION] });
  assertStrictEquals(response.status, 400);
});

// Logs

Deno.test("logs carry only bounded counts and an outcome", async () => {
  const state = setup({ WALKING_ROUTE_ROUTE_POSITIONS: "true" });
  state.backend.rows = [
    cached(KEY.b, KEY.c, [{ lat: B.lat, lng: B.lng }, {
      lat: C.lat,
      lng: C.lng,
    }]),
  ];
  const { body } = await call(state, { waypoints: [POSITION, A, B, C] });
  await call(state, null, {
    raw: JSON.stringify({ waypoints: [A, { ...B, lat: 40 }] }),
  });
  await call(state, { waypoints: [A, B] }, { origin: "https://evil.example" });

  assertStrictEquals(state.logs.length, 3);
  const text = JSON.stringify(state.logs);
  assertStrictEquals(/\d+\.\d+/u.test(text), false);
  assertStrictEquals(text.includes("ex-"), false);
  for (const leg of body.legs) {
    if (leg.polyline) assertStrictEquals(text.includes(leg.polyline), false);
  }
  for (const entry of state.logs) {
    assertEquals(Object.keys(entry).sort(), [
      "cache_hits",
      "event",
      "legs",
      "outcome",
      "routed",
      "unavailable",
    ]);
  }
  assertEquals(state.logs.map((entry) => entry.outcome), [
    "served",
    "rejected",
    "rejected",
  ]);
});
