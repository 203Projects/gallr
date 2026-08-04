import { createLegacyCatalogMirrorHandler } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const token = "test-mirror-token-with-enough-entropy-123456";

function request(options: {
  method?: string;
  authorization?: string;
  body?: string;
} = {}): Request {
  const method = options.method ?? "POST";
  return new Request(
    "https://oqrvbstopuppznxqoonp.supabase.co/functions/v1/legacy-catalog-mirror",
    {
      method,
      headers: {
        Authorization: options.authorization ?? `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: method === "POST"
        ? options.body ?? JSON.stringify({ source: "outbox" })
        : undefined,
    },
  );
}

Deno.test("authenticated outbox and reconciliation calls run one mirror", async () => {
  const sources: string[] = [];
  const handler = createLegacyCatalogMirrorHandler({
    env: (name) => name === "LEGACY_CATALOG_MIRROR_TOKEN" ? token : undefined,
    mirror: (source) => {
      sources.push(source);
      return Promise.resolve();
    },
  });

  for (const source of ["outbox", "five-minute-reconciliation"]) {
    const response = await handler(
      request({ body: JSON.stringify({ source }) }),
    );
    assert(response.status === 204, `${source} was not acknowledged`);
    assert((await response.text()) === "", "mirror response exposed a body");
  }
  assert(
    sources.join(",") === "outbox,five-minute-reconciliation",
    "wrong sources run",
  );
});

Deno.test("authentication and configuration fail closed", async () => {
  let calls = 0;
  const handler = createLegacyCatalogMirrorHandler({
    env: (name) => name === "LEGACY_CATALOG_MIRROR_TOKEN" ? token : undefined,
    mirror: () => {
      calls += 1;
      return Promise.resolve();
    },
  });
  assert(
    (await handler(request({ authorization: "Bearer wrong" }))).status === 401,
    "bad token was accepted",
  );
  const missing = createLegacyCatalogMirrorHandler({
    env: () => undefined,
    mirror: () => Promise.resolve(),
  });
  assert(
    (await missing(request())).status === 500,
    "missing token was accepted",
  );
  assert(calls === 0, "unauthorized request reached mirror backend");
});

Deno.test("invalid requests are rejected before mirror work", async () => {
  let calls = 0;
  const handler = createLegacyCatalogMirrorHandler({
    env: (name) => name === "LEGACY_CATALOG_MIRROR_TOKEN" ? token : undefined,
    mirror: () => {
      calls += 1;
      return Promise.resolve();
    },
  });
  assert(
    (await handler(request({ method: "GET" }))).status === 405,
    "GET accepted",
  );
  assert(
    (await handler(request({ body: "{" }))).status === 400,
    "bad JSON accepted",
  );
  assert(
    (await handler(request({ body: JSON.stringify({ source: "browser" }) })))
      .status === 400,
    "unknown source accepted",
  );
  assert(calls === 0, "invalid request reached mirror backend");
});

Deno.test("backend failures remain retryable without diagnostic leakage", async () => {
  const handler = createLegacyCatalogMirrorHandler({
    env: (name) => name === "LEGACY_CATALOG_MIRROR_TOKEN" ? token : undefined,
    mirror: () => Promise.reject(new Error("secret internal detail")),
  });
  const response = await handler(request());
  assert(response.status === 502, "backend failure was acknowledged");
  assert((await response.text()) === "", "backend failure leaked diagnostics");
});
