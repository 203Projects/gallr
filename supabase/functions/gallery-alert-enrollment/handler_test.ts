import { assertEquals } from "@std/assert";
import {
  type GalleryAlertEnrollmentBackend,
  GalleryAlertEnrollmentError,
  type RegisterInstallationInput,
} from "./backend.ts";
import { createGalleryAlertEnrollmentHandler } from "./handler.ts";

const SECRET = "gallery-alert-enrollment-secret-0000000000";
const INSTALLATION_ID = "a2000000-0000-0000-0000-000000000001";
const INSTALLATION_SECRET = "anonymous-installation-secret-000000000001";
const GALLERY_ID = "a1000000-0000-0000-0000-000000000001";

interface Recorded {
  registered: RegisterInstallationInput[];
  authorizations: (string | null)[];
}

function backend(
  overrides: Partial<GalleryAlertEnrollmentBackend> = {},
  recorded: Recorded = { registered: [], authorizations: [] },
): GalleryAlertEnrollmentBackend {
  return {
    resolveActor: (authorization) => {
      recorded.authorizations.push(authorization);
      return Promise.resolve(null);
    },
    registerInstallation: (input) => {
      recorded.registered.push(input);
      return Promise.resolve({ revision: 1, subscriptions: [] });
    },
    registerPushToken: () =>
      Promise.resolve({ push_token_revision: 1, push_token_status: "active" }),
    setSubscription: () => Promise.resolve({ revision: 2, subscriptions: [] }),
    getInstallation: () => Promise.resolve({ revision: 1, subscriptions: [] }),
    ...overrides,
  };
}

function handler(
  instance: GalleryAlertEnrollmentBackend,
  environment: Record<string, string> = {},
): (request: Request) => Promise<Response> {
  return createGalleryAlertEnrollmentHandler({
    env: (name) =>
      ({ GALLERY_ALERT_HASH_SECRET: SECRET, ...environment })[name],
    createBackend: () => instance,
  });
}

function post(
  body: unknown,
  init: { headers?: Record<string, string> } = {},
): Request {
  return new Request("https://functions.test/gallery-alert-enrollment", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Forwarded-For": "203.0.113.9",
      ...init.headers,
    },
    body: JSON.stringify(body),
  });
}

function registration(overrides: Record<string, unknown> = {}) {
  return {
    action: "register_installation",
    installation_id: INSTALLATION_ID,
    installation_secret: INSTALLATION_SECRET,
    platform: "ios",
    locale: "ko-KR",
    expected_revision: 0,
    ...overrides,
  };
}

Deno.test("registers an installation and returns the database state", async () => {
  const recorded: Recorded = { registered: [], authorizations: [] };
  const response = await handler(backend({}, recorded))(post(registration()));

  assertEquals(response.status, 200);
  assertEquals(await response.json(), { revision: 1, subscriptions: [] });
  assertEquals(recorded.registered.length, 1);
  assertEquals(recorded.registered[0].installationId, INSTALLATION_ID);
  assertEquals(recorded.registered[0].actorUserId, null);
});

Deno.test("derives a source key the caller cannot choose", async () => {
  const recorded: Recorded = { registered: [], authorizations: [] };
  const run = handler(backend({}, recorded));
  await run(post(registration()));
  await run(
    post(registration(), { headers: { "X-Forwarded-For": "198.51.100.4" } }),
  );

  const [first, second] = recorded.registered;
  assertEquals(/^[0-9a-f]{64}$/u.test(first.sourceDigest), true);
  assertEquals(first.sourceDigest === second.sourceDigest, false);
});

Deno.test("the source key is not a bare digest of the address", async () => {
  const recorded: Recorded = { registered: [], authorizations: [] };
  const withSecret = handler(backend({}, recorded));
  const withOtherSecret = handler(backend({}, recorded), {
    GALLERY_ALERT_HASH_SECRET: `${SECRET}-rotated`,
  });
  await withSecret(post(registration()));
  await withOtherSecret(post(registration()));

  const [first, second] = recorded.registered;
  assertEquals(first.sourceDigest === second.sourceDigest, false);
});

Deno.test("passes the bearer token to the backend for account resolution", async () => {
  const recorded: Recorded = { registered: [], authorizations: [] };
  await handler(
    backend({
      resolveActor: (authorization) => {
        recorded.authorizations.push(authorization);
        return Promise.resolve("b3000000-0000-0000-0000-000000000001");
      },
    }, recorded),
  )(post(registration(), { headers: { Authorization: "Bearer signed-in" } }));

  assertEquals(recorded.authorizations, ["Bearer signed-in"]);
  assertEquals(
    recorded.registered[0].actorUserId,
    "b3000000-0000-0000-0000-000000000001",
  );
});

Deno.test("rate limiting is reported as 429", async () => {
  const response = await handler(
    backend({
      registerInstallation: () =>
        Promise.reject(
          new GalleryAlertEnrollmentError("gallery_alert_rate_limited"),
        ),
    }),
  )(post(registration()));

  assertEquals(response.status, 429);
  assertEquals(await response.json(), { error: "gallery_alert_rate_limited" });
});

Deno.test("a subscription ceiling is reported as 429", async () => {
  const response = await handler(
    backend({
      setSubscription: () =>
        Promise.reject(
          new GalleryAlertEnrollmentError(
            "gallery_alert_subscription_limit_reached",
          ),
        ),
    }),
  )(post({
    action: "set_subscription",
    installation_id: INSTALLATION_ID,
    installation_secret: INSTALLATION_SECRET,
    gallery_id: GALLERY_ID,
    enabled: true,
    expected_revision: 0,
  }));

  assertEquals(response.status, 429);
});

Deno.test("preserves the existing 4096 character FCM token contract", async () => {
  let receivedLength = 0;
  const response = await handler(
    backend({
      registerPushToken: (input) => {
        receivedLength = input.providerToken.length;
        return Promise.resolve({
          push_token_revision: 1,
          push_token_status: "active",
        });
      },
    }),
  )(post({
    action: "register_push_token",
    installation_id: INSTALLATION_ID,
    installation_secret: INSTALLATION_SECRET,
    provider: "fcm",
    provider_token: "a".repeat(4096),
    provider_environment: "production",
    expected_revision: 0,
  }));

  assertEquals(response.status, 200);
  assertEquals(receivedLength, 4096);
});

Deno.test("a wrong installation secret is reported as 403", async () => {
  const response = await handler(
    backend({
      getInstallation: () =>
        Promise.reject(
          new GalleryAlertEnrollmentError(
            "gallery_alert_installation_unauthorized",
          ),
        ),
    }),
  )(post({
    action: "get_installation",
    installation_id: INSTALLATION_ID,
    installation_secret: INSTALLATION_SECRET,
  }));

  assertEquals(response.status, 403);
});

Deno.test("a stale revision is reported as 409", async () => {
  const response = await handler(
    backend({
      registerInstallation: () =>
        Promise.reject(new GalleryAlertEnrollmentError("revision_conflict")),
    }),
  )(post(registration()));

  assertEquals(response.status, 409);
});

Deno.test("an unexpected backend failure never leaks upstream detail", async () => {
  const response = await handler(
    backend({
      registerInstallation: () =>
        Promise.reject(new Error("connection to 10.0.0.1:5432 refused")),
    }),
  )(post(registration()));

  assertEquals(response.status, 503);
  assertEquals(await response.json(), { error: "enrollment_unavailable" });
});

Deno.test("missing enrollment configuration fails closed", async () => {
  const response = await handler(backend(), {
    GALLERY_ALERT_HASH_SECRET: "too-short",
  })(post(registration()));

  assertEquals(response.status, 503);
});

Deno.test("rejects a non-POST method", async () => {
  const response = await handler(backend())(
    new Request("https://functions.test/gallery-alert-enrollment", {
      method: "GET",
    }),
  );

  assertEquals(response.status, 405);
});

Deno.test("rejects a non-JSON content type", async () => {
  const response = await handler(backend())(
    new Request("https://functions.test/gallery-alert-enrollment", {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: "{}",
    }),
  );

  assertEquals(response.status, 415);
});

Deno.test("rejects an oversized body before parsing", async () => {
  const response = await handler(backend())(
    post(registration({ locale: "k".repeat(8192) })),
  );

  assertEquals(response.status, 413);
});

Deno.test("rejects unknown fields and unknown actions", async () => {
  const run = handler(backend());

  assertEquals(
    (await run(post(registration({ smuggled: "value" })))).status,
    400,
  );
  assertEquals(
    (await run(post(registration({ action: "delete_everything" })))).status,
    400,
  );
});

Deno.test("rejects malformed identities and revisions", async () => {
  const run = handler(backend());

  assertEquals(
    (await run(post(registration({ installation_id: "not-a-uuid" })))).status,
    400,
  );
  assertEquals(
    (await run(post(registration({ installation_secret: "short" })))).status,
    400,
  );
  assertEquals(
    (await run(post(registration({ expected_revision: -1 })))).status,
    400,
  );
  assertEquals(
    (await run(post(registration({ locale: "!!" })))).status,
    400,
  );
});

Deno.test("answers a preflight without exposing credentials", async () => {
  const response = await handler(backend())(
    new Request("https://functions.test/gallery-alert-enrollment", {
      method: "OPTIONS",
    }),
  );

  assertEquals(response.status, 204);
  assertEquals(
    response.headers.get("Access-Control-Allow-Methods"),
    "POST, OPTIONS",
  );
});
