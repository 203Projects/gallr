import { createLegacyCatalogMirrorReceiverHandler } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const token = "receiver-token-with-enough-entropy-123456";
const payload = {
  p_snapshot: { exhibitions: [{ id: "show" }], events: [], editors: [] },
  p_source_project_ref: "oqrvbstopuppznxqoonp",
  p_reason: "test receiver",
};

function request(
  overrides: { authorization?: string; body?: string } = {},
): Request {
  return new Request(
    "https://yhuhjxswjbrtmbpbrciq.supabase.co/functions/v1/legacy-catalog-mirror-receiver",
    {
      method: "POST",
      headers: { Authorization: overrides.authorization ?? `Bearer ${token}` },
      body: overrides.body ?? JSON.stringify(payload),
    },
  );
}

Deno.test("authenticated Seoul snapshot reaches the target backend", async () => {
  let calls = 0;
  const handler = createLegacyCatalogMirrorReceiverHandler({
    env: (name) => name === "LEGACY_CATALOG_RECEIVER_TOKEN" ? token : undefined,
    apply: (received) => {
      calls += 1;
      assert(
        received.p_source_project_ref === payload.p_source_project_ref,
        "wrong ref",
      );
      return Promise.resolve("applied");
    },
  });
  const response = await handler(request());
  assert(response.status === 200, "snapshot was not applied");
  assert((await response.json()).status === "applied", "receipt was invalid");
  assert(calls === 1, "backend not called exactly once");
});

Deno.test("receiver rejects bad authentication and source identity", async () => {
  let calls = 0;
  const handler = createLegacyCatalogMirrorReceiverHandler({
    env: (name) => name === "LEGACY_CATALOG_RECEIVER_TOKEN" ? token : undefined,
    apply: () => {
      calls += 1;
      return Promise.resolve("applied");
    },
  });
  assert(
    (await handler(request({ authorization: "Bearer wrong" }))).status === 401,
    "bad token accepted",
  );
  assert(
    (await handler(request({
      body: JSON.stringify({
        ...payload,
        p_source_project_ref: "abcdefghijklmnopqrst",
      }),
    }))).status === 400,
    "wrong source ref accepted",
  );
  assert(calls === 0, "invalid request reached backend");
});

Deno.test("target failures stay retryable and private", async () => {
  const handler = createLegacyCatalogMirrorReceiverHandler({
    env: (name) => name === "LEGACY_CATALOG_RECEIVER_TOKEN" ? token : undefined,
    apply: () => Promise.reject(new Error("private target error")),
  });
  const response = await handler(request());
  assert(response.status === 502, "target failure was acknowledged");
  assert((await response.text()) === "", "target error leaked");
});
