import { assertEquals, assertRejects, assertStrictEquals } from "@std/assert";
import { createRpcBackend, type RpcCall } from "./backend.ts";

function rpc(
  data: unknown,
  calls: Array<{ name: string; args: Record<string, unknown> }> = [],
  error: unknown = null,
): RpcCall {
  return (name, args) => {
    calls.push({ name, args });
    return Promise.resolve({ data, error });
  };
}

const B = "37.53450,127.00100";
const A = "37.54450,127.05600";

Deno.test("consumes quota with snake_case arguments and reads the decision", async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const backend = createRpcBackend(rpc(
    { allowed: true, retry_after_seconds: 0, provider_calls_granted: 2 },
    calls,
  ));
  assertEquals(await backend.consumeQuota("a".repeat(64), 3, 2), {
    allowed: true,
    retryAfterSeconds: 0,
    providerCallsGranted: 2,
  });
  assertEquals(calls, [{
    name: "service_walking_route_consume",
    args: { p_source: "a".repeat(64), p_legs: 3, p_provider_calls: 2 },
  }]);
});

Deno.test("a refusal carries a retry of at most an hour", async () => {
  const backend = createRpcBackend(rpc({
    allowed: false,
    retry_after_seconds: 1200,
    provider_calls_granted: 0,
  }));
  assertEquals(await backend.consumeQuota("a".repeat(64), 1, 0), {
    allowed: false,
    retryAfterSeconds: 1200,
    providerCallsGranted: 0,
  });
});

Deno.test("malformed quota answers and RPC errors fail closed", async () => {
  for (
    const data of [
      null,
      [],
      { allowed: "yes", retry_after_seconds: 0, provider_calls_granted: 0 },
      { allowed: false, retry_after_seconds: 0, provider_calls_granted: 0 },
      { allowed: false, retry_after_seconds: 3601, provider_calls_granted: 0 },
      { allowed: true, retry_after_seconds: 0, provider_calls_granted: 9 },
      { allowed: true, retry_after_seconds: 0, provider_calls_granted: -1 },
    ]
  ) {
    await assertRejects(
      () => createRpcBackend(rpc(data)).consumeQuota(null, 0, 5),
      Error,
      undefined,
      JSON.stringify(data),
    );
  }
  await assertRejects(() =>
    createRpcBackend(rpc(null, [], { message: "boom" })).consumeQuota(
      null,
      0,
      1,
    )
  );
});

Deno.test("looks up ordered pairs and parses cached rows", async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const backend = createRpcBackend(rpc([{
    from_key: B,
    to_key: A,
    distance_m: 5230,
    polyline: "abc",
    provider: "ors",
    routed_at: "2026-10-14T03:00:00.123456+00:00",
  }], calls));
  const rows = await backend.lookup([{ fromKey: B, toKey: A }]);
  assertEquals(calls[0], {
    name: "service_walking_legs_lookup",
    args: { p_pairs: [{ from_key: B, to_key: A }] },
  });
  assertStrictEquals(rows.length, 1);
  assertStrictEquals(rows[0].distanceM, 5230);
  assertStrictEquals(
    rows[0].routedAt.toISOString(),
    "2026-10-14T03:00:00.123Z",
  );
});

Deno.test("malformed lookup rows fail closed", async () => {
  for (
    const data of [
      null,
      {},
      [{ from_key: B, to_key: A }],
      [{
        from_key: B,
        to_key: A,
        distance_m: 1.5,
        polyline: "abc",
        provider: "ors",
        routed_at: "2026-10-14T03:00:00Z",
      }],
      [{
        from_key: B,
        to_key: A,
        distance_m: 1,
        polyline: "abc",
        provider: "ors",
        routed_at: "yesterday",
      }],
    ]
  ) {
    await assertRejects(() =>
      createRpcBackend(rpc(data)).lookup([{ fromKey: B, toKey: A }])
    );
  }
});

Deno.test("stores legs with snake_case fields and returns the stored count", async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const backend = createRpcBackend(rpc(1, calls));
  assertStrictEquals(
    await backend.store([{
      fromKey: B,
      fromExhibitionId: "ex-b",
      toKey: A,
      toExhibitionId: "ex-a",
      distanceM: 5230,
      polyline: "abc",
      provider: "ors",
    }]),
    1,
  );
  assertEquals(calls[0], {
    name: "service_walking_legs_store",
    args: {
      p_legs: [{
        from_key: B,
        from_exhibition_id: "ex-b",
        to_key: A,
        to_exhibition_id: "ex-a",
        distance_m: 5230,
        polyline: "abc",
        provider: "ors",
      }],
    },
  });
  await assertRejects(() =>
    createRpcBackend(rpc(null, [], { message: "boom" })).store([{
      fromKey: B,
      fromExhibitionId: "ex-b",
      toKey: A,
      toExhibitionId: "ex-a",
      distanceM: 5230,
      polyline: "abc",
      provider: "ors",
    }])
  );
});

Deno.test("an empty store makes no call", async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  assertStrictEquals(await createRpcBackend(rpc(0, calls)).store([]), 0);
  assertStrictEquals(calls.length, 0);
});
