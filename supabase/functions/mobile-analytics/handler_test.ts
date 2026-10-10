import { assertEquals } from "@std/assert";
import type { MobileAnalyticsBackend } from "./backend.ts";
import { createMobileAnalyticsHandler } from "./handler.ts";

const HASH_SECRET = "mobile-analytics-hash-secret-000000000000";
const EVENT_ID = "a1000000-0000-4000-8000-000000000001";

interface RecordedBatch {
  events: unknown[];
  sourceDigest: string;
}

function backend(recorded: RecordedBatch[]): MobileAnalyticsBackend {
  return {
    record: (events, sourceDigest) => {
      recorded.push({ events, sourceDigest });
      return Promise.resolve(events.length);
    },
  };
}

function handler(
  recorded: RecordedBatch[],
  overrides: Record<string, string> = {},
  backendOverride?: MobileAnalyticsBackend,
) {
  const environment = {
    MOBILE_ANALYTICS_ENABLED: "true",
    MOBILE_ANALYTICS_HASH_SECRET: HASH_SECRET,
    ...overrides,
  };
  return createMobileAnalyticsHandler({
    env: (name) => environment[name as keyof typeof environment],
    now: () => new Date("2026-08-30T12:00:00Z"),
    createBackend: () => backendOverride ?? backend(recorded),
  });
}

function event(overrides: Record<string, unknown> = {}) {
  return {
    event_id: EVENT_ID,
    occurred_on: "2026-08-30",
    platform: "android",
    app_major: 1,
    event_name: "surface_viewed",
    surface: "featured",
    entry_point: "tab",
    ...overrides,
  };
}

function post(
  events: unknown[],
  overrides: { headers?: Record<string, string> } = {},
) {
  return new Request(
    "https://project.supabase.co/functions/v1/mobile-analytics",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Origin": "app://gallr",
        "X-Forwarded-For": "203.0.113.10",
        ...overrides.headers,
      },
      body: JSON.stringify({ events }),
    },
  );
}

Deno.test("valid batch records only validated events and a keyed source digest", async () => {
  const recorded: RecordedBatch[] = [];
  const response = await handler(recorded)(post([event()]));

  assertEquals(response.status, 204);
  assertEquals(recorded.length, 1);
  assertEquals(recorded[0].events, [event()]);
  assertEquals(/^[0-9a-f]{64}$/u.test(recorded[0].sourceDigest), true);
  assertEquals(recorded[0].sourceDigest.includes("203.0.113.10"), false);
});

Deno.test("different source and hash secret produce different quota identities", async () => {
  const recorded: RecordedBatch[] = [];
  const run = handler(recorded);
  await run(post([event()]));
  await run(
    post([event({ event_id: "a1000000-0000-4000-8000-000000000002" })], {
      headers: { "X-Forwarded-For": "198.51.100.3" },
    }),
  );
  await handler(recorded, {
    MOBILE_ANALYTICS_HASH_SECRET: `${HASH_SECRET}-other`,
  })(post([event({ event_id: "a1000000-0000-4000-8000-000000000003" })]));

  assertEquals(new Set(recorded.map((row) => row.sourceDigest)).size, 3);
});

Deno.test("disabled collection acknowledges without parsing or backend creation", async () => {
  let backendCreated = false;
  const run = createMobileAnalyticsHandler({
    env: (name) => name === "MOBILE_ANALYTICS_ENABLED" ? "false" : undefined,
    createBackend: () => {
      backendCreated = true;
      return backend([]);
    },
  });
  const response = await run(new Request("https://test", { method: "DELETE" }));

  assertEquals(response.status, 204);
  assertEquals(backendCreated, false);
});

Deno.test("invalid kill switch fails closed", async () => {
  const response = await handler([], { MOBILE_ANALYTICS_ENABLED: "yes" })(
    post([event()]),
  );
  assertEquals(response.status, 500);
});

Deno.test("rejects method content type origin body and batch violations", async () => {
  const run = handler([]);
  assertEquals(
    (await run(
      new Request("https://test", {
        method: "GET",
        headers: { Origin: "app://gallr" },
      }),
    )).status,
    405,
  );
  assertEquals(
    (await run(
      new Request("https://test", {
        method: "POST",
        headers: { "Content-Type": "text/plain", "Origin": "app://gallr" },
        body: "{}",
      }),
    )).status,
    415,
  );
  assertEquals(
    (await run(post([event()], {
      headers: { "Content-Type": "application/json-seq" },
    }))).status,
    415,
  );
  assertEquals(
    (await run(post([event()], { headers: { Origin: "https://evil.test" } })))
      .status,
    403,
  );
  assertEquals((await run(post([]))).status, 400);
  assertEquals(
    (await run(post(Array.from({ length: 21 }, (_, index) =>
      event({
        event_id: `a1000000-0000-4000-8000-${
          index.toString().padStart(12, "0")
        }`,
      }))))).status,
    400,
  );
  assertEquals(
    (await run(post([event({ payload: "x".repeat(20_000) })]))).status,
    413,
  );
});

Deno.test("rejects unknown fields malformed identifiers and mismatched shapes", async () => {
  const run = handler([]);
  assertEquals((await run(post([event({ latitude: 37.5 })]))).status, 400);
  assertEquals((await run(post([event({ event_id: "invalid" })]))).status, 400);
  for (
    const exhibitionId of [
      "person@example.com",
      "https://example.com/exhibition",
      "37.5,127.0",
      "free form search",
    ]
  ) {
    assertEquals(
      (await run(post([event({
        event_name: "exhibition_opened",
        exhibition_id: exhibitionId,
        discovery_kind: "organic",
        position_bucket: "top_three",
        entry_point: undefined,
      })]))).status,
      400,
    );
  }
  assertEquals(
    (await run(post([event({ event_name: "route_created" })]))).status,
    400,
  );
  assertEquals(
    (await run(post([event({
      event_name: "recommendations_shown",
      discovery_kind: "recommendation",
      surface: "featured",
      entry_point: undefined,
    })]))).status,
    400,
  );
  assertEquals((await run(post([event({ platform: "web" })]))).status, 400);
});

Deno.test("records a bounded recommendation result count", async () => {
  const recorded: RecordedBatch[] = [];
  const response = await handler(recorded)(post([event({
    event_name: "recommendations_shown",
    discovery_kind: "recommendation",
    result_count: 6,
    surface: "featured",
    entry_point: undefined,
  })]));

  assertEquals(response.status, 204);
  assertEquals(recorded[0].events, [{
    event_id: EVENT_ID,
    occurred_on: "2026-08-30",
    platform: "android",
    app_major: 1,
    event_name: "recommendations_shown",
    discovery_kind: "recommendation",
    result_count: 6,
    surface: "featured",
  }]);

  const emptyResponse = await handler(recorded)(post([event({
    event_id: "a1000000-0000-4000-8000-000000000009",
    event_name: "recommendations_shown",
    discovery_kind: "recommendation",
    result_count: 0,
    surface: "featured",
    entry_point: undefined,
  })]));
  assertEquals(emptyResponse.status, 204);
});

Deno.test("accepts an honest unranked map open", async () => {
  const recorded: RecordedBatch[] = [];
  const response = await handler(recorded)(post([event({
    event_name: "exhibition_opened",
    surface: "map",
    exhibition_id: "exhibition-one",
    discovery_kind: "nearby",
    position_bucket: "unranked",
    entry_point: undefined,
  })]));

  assertEquals(response.status, 204);
  assertEquals(recorded.length, 1);
});

Deno.test("rejects malformed and out-of-window calendar dates before storage", async () => {
  const recorded: RecordedBatch[] = [];
  const run = handler(recorded);

  for (
    const occurredOn of [
      "2026-99-99",
      "2026-02-29",
      "2026-08-22",
      "2026-09-01",
    ]
  ) {
    assertEquals(
      (await run(post([event({ occurred_on: occurredOn })]))).status,
      400,
    );
  }
  assertEquals(recorded, []);
});

Deno.test("backend failures are sanitized", async () => {
  const response = await handler([], {}, {
    record: () => Promise.reject(new Error("database secret and event detail")),
  })(post([event()]));

  assertEquals(response.status, 503);
  assertEquals(await response.text(), "");
});

// Spec 089 (E-D6): author-loop events for personal routes. No route id, author or content is ever sent.
Deno.test("accepts the personal route events with only their allowed fields", async () => {
  const recorded: RecordedBatch[] = [];
  const routeEvents = [
    event({
      event_name: "route_draft_started",
      surface: undefined,
      entry_point: undefined,
    }),
    event({
      event_id: "a1000000-0000-4000-8000-000000000002",
      event_name: "route_published",
      surface: undefined,
      entry_point: undefined,
      stop_count: 10,
    }),
    event({
      event_id: "a1000000-0000-4000-8000-000000000003",
      event_name: "route_shared",
      surface: undefined,
      entry_point: undefined,
      stop_count: 2,
    }),
  ].map((routeEvent) => JSON.parse(JSON.stringify(routeEvent)));

  const response = await handler(recorded)(post(routeEvents));

  assertEquals(response.status, 204);
  assertEquals(recorded[0].events, routeEvents);
});

Deno.test("rejects personal route events with extra or out-of-range fields", async () => {
  const cases = [
    {
      event_name: "route_draft_started",
      surface: "map",
      entry_point: undefined,
    },
    {
      event_name: "route_published",
      surface: undefined,
      entry_point: undefined,
    },
    {
      event_name: "route_published",
      surface: undefined,
      entry_point: undefined,
      stop_count: 11,
    },
    {
      event_name: "route_shared",
      surface: undefined,
      entry_point: undefined,
      stop_count: 1,
    },
    {
      event_name: "route_shared",
      surface: undefined,
      entry_point: undefined,
      stop_count: 3,
      route_id: "r",
    },
    {
      event_name: "route_shared",
      surface: undefined,
      entry_point: undefined,
      stop_count: 3,
      exhibition_id: "e",
    },
  ];
  for (const overrides of cases) {
    const recorded: RecordedBatch[] = [];
    const routeEvent = JSON.parse(JSON.stringify(event(overrides)));
    const response = await handler(recorded)(post([routeEvent]));
    assertEquals(response.status, 400, JSON.stringify(overrides));
    assertEquals(recorded.length, 0);
  }
});

Deno.test("accepts a public routes view with only the rows shown", async () => {
  const recorded: RecordedBatch[] = [];
  const views = [1, 3, 10].map((rows, index) =>
    JSON.parse(JSON.stringify(event({
      event_id: `a1000000-0000-4000-8000-00000000004${index}`,
      event_name: "public_routes_viewed",
      surface: undefined,
      entry_point: undefined,
      result_count: rows,
    })))
  );

  const response = await handler(recorded)(post(views));

  assertEquals(response.status, 204);
  assertEquals(recorded[0].events, views);
});

Deno.test("rejects public routes views out of range or with other fields", async () => {
  const cases = [
    { result_count: 0 },
    { result_count: 11 },
    { result_count: 2.5 },
    {},
    { result_count: 3, surface: "map" },
    { result_count: 3, stop_count: 3 },
    { result_count: 3, route_id: "r" },
  ];
  for (const fields of cases) {
    const recorded: RecordedBatch[] = [];
    const view = JSON.parse(JSON.stringify(event({
      event_name: "public_routes_viewed",
      surface: undefined,
      entry_point: undefined,
      ...fields,
    })));
    const response = await handler(recorded)(post([view]));
    assertEquals(response.status, 400, JSON.stringify(fields));
    assertEquals(recorded.length, 0);
  }
});
