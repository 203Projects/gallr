import { createOutboxDeliveryHandler } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const token = "test-delivery-token-with-enough-entropy-123456";
const hook = "https://api.vercel.com/v1/integrations/deploy/example/project";

type FetchCall = { url: string; init?: RequestInit };

function buildHandler(overrides: {
  configuredToken?: string;
  configuredHook?: string;
  configuredMirrorToken?: string;
  configuredMirrorUrl?: string;
  configuredResendKey?: string;
  configuredOwnerNotificationFrom?: string;
  galleryAlertEnabled?: string;
  galleryAlertResult?: { ok: true } | { ok: false; code: string };
  adminPortalUrl?: string;
  adminIntakeEmail?: string;
  fetchStatus?: number;
  fetchBody?: string;
  fetchThrows?: boolean;
} = {}) {
  const calls: FetchCall[] = [];
  const galleryAlertEvents: string[] = [];
  const handler = createOutboxDeliveryHandler({
    env: (name) => {
      if (name === "OUTBOX_DELIVERY_TOKEN") {
        return overrides.configuredToken ?? token;
      }
      if (name === "VERCEL_DEPLOY_HOOK_URL") {
        return overrides.configuredHook ?? hook;
      }
      if (name === "SUPABASE_URL") {
        return "https://oqrvbstopuppznxqoonp.supabase.co";
      }
      if (name === "LEGACY_CATALOG_MIRROR_TOKEN") {
        return overrides.configuredMirrorToken;
      }
      if (name === "LEGACY_CATALOG_MIRROR_URL") {
        return overrides.configuredMirrorUrl;
      }
      if (name === "RESEND_API_KEY") {
        return overrides.configuredResendKey;
      }
      if (name === "OWNER_NOTIFICATION_FROM_EMAIL") {
        return overrides.configuredOwnerNotificationFrom;
      }
      if (name === "GALLERY_ALERT_DELIVERY_ENABLED") {
        return overrides.galleryAlertEnabled;
      }
      if (name === "ADMIN_PORTAL_URL") {
        return overrides.adminPortalUrl;
      }
      if (name === "ADMIN_INTAKE_EMAIL") {
        return overrides.adminIntakeEmail;
      }
      return undefined;
    },
    fetch: (input, init) => {
      calls.push({ url: String(input), init });
      if (overrides.fetchThrows) {
        return Promise.reject(new TypeError("connection reset"));
      }
      return Promise.resolve(
        new Response(overrides.fetchBody ?? null, {
          status: overrides.fetchStatus ?? 201,
          headers: overrides.fetchBody
            ? { "Content-Type": "application/json" }
            : undefined,
        }),
      );
    },
    galleryAlerts: (event) => {
      galleryAlertEvents.push(event.id);
      return Promise.resolve(overrides.galleryAlertResult ?? { ok: true });
    },
  });
  return { calls, galleryAlertEvents, handler };
}

function request(options: {
  eventType?: string;
  bodyEventType?: string;
  authorization?: string;
  method?: string;
  eventId?: string;
  bodyEventId?: string;
  idempotencyKey?: string;
  body?: string;
} = {}): Request {
  const eventType = options.eventType ?? "exhibition.published";
  const eventId = options.eventId ?? "00000000-0000-4000-8000-000000000001";
  const body = options.body ?? JSON.stringify({
    id: options.bodyEventId ?? eventId,
    event_type: options.bodyEventType ?? eventType,
    aggregate_type: "exhibition",
    aggregate_id: "exhibition-one",
    deduplication_key: "exhibition.published:exhibition-one:1",
    payload: {
      exhibition_id: "exhibition-one",
      public_site_rebuild_queued: true,
    },
  });
  const method = options.method ?? "POST";
  return new Request(
    "https://project.supabase.co/functions/v1/outbox-delivery",
    {
      method,
      headers: {
        Authorization: options.authorization ?? `Bearer ${token}`,
        "Content-Type": "application/json",
        "Idempotency-Key": options.idempotencyKey ??
          "exhibition.published:exhibition-one:1",
        "X-Outbox-Event-Id": eventId,
        "X-Outbox-Event-Type": eventType,
      },
      body: method === "POST" ? body : undefined,
    },
  );
}

function rebuildRequest(): Request {
  const eventId = "00000000-0000-4000-8000-000000000020";
  const idempotencyKey = `public-site-rebuild:${eventId}`;
  return request({
    eventType: "public_site.rebuild_requested",
    bodyEventType: "public_site.rebuild_requested",
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: "public_site.rebuild_requested",
      aggregate_type: "public_site",
      aggregate_id: "catalogue",
      deduplication_key: idempotencyKey,
      payload: {
        source_event_count: 2,
        first_event_id: "00000000-0000-4000-8000-000000000001",
        latest_event_id: "00000000-0000-4000-8000-000000000002",
      },
    }),
  });
}

Deno.test("durable rebuild event triggers the exact Vercel deploy hook", async () => {
  const { calls, handler } = buildHandler();
  const response = await handler(rebuildRequest());

  assert(response.status === 204, "delivery was not acknowledged");
  assert((await response.text()) === "", "delivery leaked a response body");
  assert(calls.length === 1, "deploy hook was not called exactly once");
  assert(calls[0]?.url === hook, "wrong deploy hook called");
  assert(calls[0]?.init?.method === "POST", "deploy hook was not POSTed");
});

Deno.test("staged publication alerts run without a direct rebuild", async () => {
  const { calls, galleryAlertEvents, handler } = buildHandler({
    galleryAlertEnabled: "true",
  });
  const response = await handler(request({
    body: JSON.stringify({
      id: "00000000-0000-4000-8000-000000000001",
      event_type: "exhibition.published",
      aggregate_type: "exhibition",
      aggregate_id: "exhibition-one",
      deduplication_key: "exhibition.published:exhibition-one:1",
      payload: {
        exhibition_id: "exhibition-one",
        version_id: "00000000-0000-4000-8000-000000000002",
        gallery_id: "00000000-0000-4000-8000-000000000003",
        public_site_rebuild_queued: true,
      },
    }),
  }));

  assert(response.status === 204, "staged alerts were not acknowledged");
  assert(galleryAlertEvents.length === 1, "alert fan-out was not invoked once");
  assert(calls.length === 0, "publication called the deploy hook directly");
});

Deno.test("unmarked lifecycle events retain the direct-hook rollout fallback", async () => {
  const { calls, handler } = buildHandler();
  const response = await handler(request({
    body: JSON.stringify({
      id: "00000000-0000-4000-8000-000000000001",
      event_type: "exhibition.published",
      aggregate_type: "exhibition",
      aggregate_id: "exhibition-one",
      deduplication_key: "exhibition.published:exhibition-one:1",
      payload: { exhibition_id: "exhibition-one" },
    }),
  }));

  assert(response.status === 204, "compatibility event was not acknowledged");
  assert(calls.length === 1, "compatibility event did not call the hook");
});

Deno.test("retryable alert fan-out keeps the outbox event retryable", async () => {
  const { calls, handler } = buildHandler({
    galleryAlertEnabled: "true",
    galleryAlertResult: {
      ok: false,
      code: "gallery_alert_provider_retryable",
    },
  });
  const response = await handler(request({
    body: JSON.stringify({
      id: "00000000-0000-4000-8000-000000000001",
      event_type: "exhibition.published",
      aggregate_type: "exhibition",
      aggregate_id: "exhibition-one",
      deduplication_key: "exhibition.published:exhibition-one:1",
      payload: {
        exhibition_id: "exhibition-one",
        version_id: "00000000-0000-4000-8000-000000000002",
        gallery_id: "00000000-0000-4000-8000-000000000003",
      },
    }),
  }));

  assert(response.status === 502, "retryable alert failure was acknowledged");
  assert(
    (await response.text()) === "gallery_alert_provider_retryable",
    "alert failure did not stay sanitized",
  );
  assert(calls.length === 0, "failed fan-out triggered a rebuild first");
});

Deno.test("lifecycle events defer rebuilds while internal events are acknowledged", async () => {
  for (const eventType of ["exhibition.archived", "exhibition.restored"]) {
    const { calls, handler } = buildHandler();
    const response = await handler(
      request({ eventType, bodyEventType: eventType }),
    );
    assert(response.status === 204, `${eventType} was not acknowledged`);
    assert(calls.length === 0, `${eventType} called the deploy hook directly`);
  }

  const { calls, handler } = buildHandler();
  const response = await handler(request({
    eventType: "owner_exhibition.submitted",
    bodyEventType: "owner_exhibition.submitted",
  }));
  assert(response.status === 204, "known internal event was not acknowledged");
  assert(calls.length === 0, "internal event triggered a public rebuild");
});

Deno.test("owner acceptance sends an idempotent notification email", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_owner_notification_key",
    configuredOwnerNotificationFrom: "gallr <hello@gallrmap.com>",
  });
  const eventId = "00000000-0000-4000-8000-000000000011";
  const idempotencyKey = "owner_submission:submission-one:accepted";
  const response = await handler(request({
    eventType: "submission.accepted",
    bodyEventType: "submission.accepted",
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: "submission.accepted",
      aggregate_type: "exhibition_submission",
      aggregate_id: "submission-one",
      deduplication_key: idempotencyKey,
      payload: {
        source: "owner_workspace",
        recipient_email: "owner@example.com",
        exhibition_name: "Notes from a Small Room",
      },
    }),
  }));

  assert(response.status === 204, "owner acceptance was not acknowledged");
  assert(calls.length === 1, "notification was not sent exactly once");
  assert(
    calls[0]?.url === "https://api.resend.com/emails",
    "wrong email endpoint called",
  );
  const headers = new Headers(calls[0]?.init?.headers);
  assert(
    headers.get("authorization") === "Bearer re_test_owner_notification_key",
    "Resend key was not used",
  );
  assert(
    headers.get("idempotency-key") === idempotencyKey,
    "outbox key was not forwarded",
  );
  assert(
    headers.get("user-agent") === "gallr-outbox-delivery/1.0",
    "required Resend user agent was not sent",
  );
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(body.to[0] === "owner@example.com", "wrong recipient used");
  assert(body.subject.includes("accepted"), "acceptance subject was missing");
});

Deno.test("owner rejection includes escaped review notes and remains retryable", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_owner_notification_key",
    configuredOwnerNotificationFrom: "gallr <hello@gallrmap.com>",
    fetchStatus: 503,
  });
  const eventId = "00000000-0000-4000-8000-000000000012";
  const idempotencyKey = "owner_submission:submission-two:rejected";
  const response = await handler(request({
    eventType: "submission.rejected",
    bodyEventType: "submission.rejected",
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: "submission.rejected",
      aggregate_type: "exhibition_submission",
      aggregate_id: "submission-two",
      deduplication_key: idempotencyKey,
      payload: {
        source: "owner_workspace",
        recipient_email: "owner@example.com",
        exhibition_name: "Notes from a Small Room",
        review_notes: "Use <strong>complete</strong> hours.",
      },
    }),
  }));

  assert(
    response.status === 502,
    "email failure was acknowledged as delivered",
  );
  assert(
    (await response.text()) === "email_provider_http_503",
    "email failure did not expose the safe upstream status",
  );
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    !body.html.includes("<strong>complete</strong>"),
    "review notes were not escaped",
  );
  assert(
    body.html.includes("&lt;strong&gt;complete&lt;/strong&gt;"),
    "escaped review notes were missing",
  );
});

Deno.test("owner notification failures expose only an allowlisted provider code", async () => {
  const { handler } = buildHandler({
    configuredResendKey: "re_test_owner_notification_key",
    configuredOwnerNotificationFrom: "gallr <hello@gallrmap.com>",
    fetchStatus: 403,
    fetchBody: JSON.stringify({
      name: "validation_error",
      message: "sensitive provider detail must not be forwarded",
    }),
  });
  const eventId = "00000000-0000-4000-8000-000000000014";
  const idempotencyKey = "owner_submission:submission-four:accepted";
  const response = await handler(request({
    eventType: "submission.accepted",
    bodyEventType: "submission.accepted",
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: "submission.accepted",
      aggregate_type: "exhibition_submission",
      aggregate_id: "submission-four",
      deduplication_key: idempotencyKey,
      payload: {
        source: "owner_workspace",
        recipient_email: "owner@example.com",
        exhibition_name: "Notes from a Small Room",
      },
    }),
  }));

  assert(response.status === 502, "provider failure was acknowledged");
  assert(
    (await response.text()) ===
      "email_provider_http_403_validation_error",
    "provider failure leaked detail or lost its safe code",
  );
});

Deno.test("non-owner submission decisions remain acknowledged without email", async () => {
  const { calls, handler } = buildHandler();
  const response = await handler(request({
    eventType: "submission.accepted",
    bodyEventType: "submission.accepted",
    body: JSON.stringify({
      id: "00000000-0000-4000-8000-000000000001",
      event_type: "submission.accepted",
      aggregate_type: "exhibition_submission",
      aggregate_id: "submission-public",
      deduplication_key: "exhibition.published:exhibition-one:1",
      payload: { source: "public_submission" },
    }),
  }));
  assert(response.status === 204, "public decision was not acknowledged");
  assert(calls.length === 0, "public decision sent an owner notification");
});

Deno.test("owner decisions fail closed when email configuration is missing", async () => {
  const { calls, handler } = buildHandler();
  const eventId = "00000000-0000-4000-8000-000000000013";
  const idempotencyKey = "owner_submission:submission-three:accepted";
  const response = await handler(request({
    eventType: "submission.accepted",
    bodyEventType: "submission.accepted",
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: "submission.accepted",
      aggregate_type: "exhibition_submission",
      aggregate_id: "submission-three",
      deduplication_key: idempotencyKey,
      payload: {
        source: "owner_workspace",
        recipient_email: "owner@example.com",
        exhibition_name: "Notes from a Small Room",
      },
    }),
  }));
  assert(response.status === 500, "missing email configuration was accepted");
  assert(calls.length === 0, "missing configuration reached the email API");
});

Deno.test("catalogue sync events invoke only the authenticated mirror function", async () => {
  const mirrorUrl =
    "https://oqrvbstopuppznxqoonp.supabase.co/functions/v1/legacy-catalog-mirror";
  const mirrorToken = "test-mirror-token-with-enough-entropy-123456";
  const { calls, handler } = buildHandler({
    configuredMirrorUrl: mirrorUrl,
    configuredMirrorToken: mirrorToken,
  });
  const response = await handler(request({
    eventType: "legacy_catalog.sync_requested",
    bodyEventType: "legacy_catalog.sync_requested",
  }));

  assert(response.status === 204, "mirror request was not acknowledged");
  assert(calls.length === 1, "mirror function was not called exactly once");
  assert(calls[0]?.url === mirrorUrl, "wrong mirror URL called");
  assert(calls[0]?.init?.method === "POST", "mirror function was not POSTed");
  const headers = new Headers(calls[0]?.init?.headers);
  assert(
    headers.get("authorization") === `Bearer ${mirrorToken}`,
    "mirror token was not forwarded",
  );
});

Deno.test("catalogue sync fails closed on partial or foreign mirror configuration", async () => {
  const missingToken = buildHandler({
    configuredMirrorUrl:
      "https://oqrvbstopuppznxqoonp.supabase.co/functions/v1/legacy-catalog-mirror",
  });
  assert(
    (await missingToken.handler(request({
      eventType: "legacy_catalog.sync_requested",
      bodyEventType: "legacy_catalog.sync_requested",
    }))).status === 500,
    "partial mirror configuration was accepted",
  );

  const foreign = buildHandler({
    configuredMirrorUrl:
      "https://attacker.invalid/functions/v1/legacy-catalog-mirror",
    configuredMirrorToken: "test-mirror-token-with-enough-entropy-123456",
  });
  assert(
    (await foreign.handler(request({
      eventType: "legacy_catalog.sync_requested",
      bodyEventType: "legacy_catalog.sync_requested",
    }))).status === 500,
    "foreign mirror URL was accepted",
  );
});

Deno.test("rejects unauthenticated requests before any outbound call", async () => {
  const { calls, handler } = buildHandler();
  const response = await handler(request({ authorization: "Bearer wrong" }));
  assert(response.status === 401, "bad token was accepted");
  assert(calls.length === 0, "bad token reached the deploy hook");
});

Deno.test("rejects mismatched event headers and unknown event types", async () => {
  const mismatch = buildHandler();
  const mismatchResponse = await mismatch.handler(request({
    bodyEventType: "exhibition.archived",
  }));
  assert(mismatchResponse.status === 400, "mismatched event type was accepted");
  assert(mismatch.calls.length === 0, "mismatched event reached deploy hook");

  const mismatchedId = buildHandler();
  const mismatchedIdResponse = await mismatchedId.handler(request({
    bodyEventId: "00000000-0000-4000-8000-000000000002",
  }));
  assert(
    mismatchedIdResponse.status === 400,
    "mismatched event ID was accepted",
  );
  assert(mismatchedId.calls.length === 0, "mismatched ID reached deploy hook");

  const mismatchedKey = buildHandler();
  const mismatchedKeyResponse = await mismatchedKey.handler(request({
    idempotencyKey: "wrong-key",
  }));
  assert(
    mismatchedKeyResponse.status === 400,
    "mismatched idempotency key was accepted",
  );
  assert(
    mismatchedKey.calls.length === 0,
    "mismatched key reached deploy hook",
  );

  const unknown = buildHandler();
  const unknownResponse = await unknown.handler(request({
    eventType: "future.unknown",
    bodyEventType: "future.unknown",
  }));
  assert(
    unknownResponse.status === 422,
    "unknown event was silently discarded",
  );
  assert(unknown.calls.length === 0, "unknown event reached deploy hook");
});

Deno.test("rejects malformed, oversized, and non-POST requests", async () => {
  const malformed = buildHandler();
  assert(
    (await malformed.handler(request({ body: "{" }))).status === 400,
    "malformed JSON was accepted",
  );

  const oversized = buildHandler();
  assert(
    (await oversized.handler(request({ body: "x".repeat(70_000) }))).status ===
      413,
    "oversized body was accepted",
  );

  const get = buildHandler();
  assert(
    (await get.handler(request({ method: "GET" }))).status === 405,
    "GET was accepted",
  );
});

Deno.test("invalid configuration fails closed", async () => {
  const shortToken = buildHandler({ configuredToken: "short" });
  assert(
    (await shortToken.handler(request())).status === 500,
    "short configured token was accepted",
  );
  assert(shortToken.calls.length === 0, "invalid token config reached hook");

  const foreignHook = buildHandler({
    configuredHook: "https://attacker.invalid/v1/integrations/deploy/x/y",
  });
  assert(
    (await foreignHook.handler(rebuildRequest())).status === 500,
    "foreign deploy hook was accepted",
  );
  assert(foreignHook.calls.length === 0, "foreign hook was called");
});

Deno.test("deploy hook failures remain retryable", async () => {
  const { calls, handler } = buildHandler({ fetchStatus: 503 });
  const response = await handler(rebuildRequest());
  assert(response.status === 502, "hook failure was acknowledged as delivered");
  assert(calls.length === 1, "hook was not attempted");
});

function adminNotificationRequest(options: {
  eventId?: string;
  payload?: Record<string, unknown>;
} = {}): Request {
  const eventId = options.eventId ?? "00000000-0000-4000-8000-000000000031";
  const idempotencyKey = "admin_notification:audit:audit-one";
  return request({
    eventType: "admin_notification.requested",
    bodyEventType: "admin_notification.requested",
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: "admin_notification.requested",
      aggregate_type: "gallery",
      aggregate_id: "gallery-one",
      deduplication_key: idempotencyKey,
      payload: options.payload ?? {
        kind: "gallery.claim_requested",
        entity_type: "gallery",
        entity_id: "gallery-one",
        actor_email: "owner@example.com",
        recipient_emails: ["admin@example.com", "second@example.com"],
        occurred_at: "2026-09-13T03:00:00+00:00",
        context: { gallery_name: "Space One" },
      },
    }),
  });
}

Deno.test("admin notifications email every active admin idempotently", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(adminNotificationRequest());
  assert(response.status === 204, `unexpected status ${response.status}`);
  assert(calls.length === 1, "email API was not called exactly once");
  assert(calls[0]?.url === "https://api.resend.com/emails", "wrong email URL");
  const headers = new Headers(calls[0]?.init?.headers);
  assert(
    headers.get("idempotency-key") === "admin_notification:audit:audit-one",
    "outbox key was not forwarded as the idempotency key",
  );
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    JSON.stringify(body.to) ===
      JSON.stringify(["admin@example.com", "second@example.com"]),
    "recipients were not forwarded",
  );
  assert(
    body.subject ===
      "[gallr admin] 기존 갤러리 소유권 신청 / New claim for an existing gallery: Space One",
    `unexpected subject ${body.subject}`,
  );
  assert(body.from === "gallr <notify@gallrmap.com>", "wrong sender");
});

Deno.test("admin notifications with invalid payloads are rejected durably", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(adminNotificationRequest({
    payload: { kind: "gallery.claim_requested", recipient_emails: [] },
  }));
  assert(response.status === 422, `unexpected status ${response.status}`);
  assert(calls.length === 0, "invalid payload reached the email API");
});

Deno.test("admin notifications fail closed without email configuration", async () => {
  const { calls, handler } = buildHandler();
  const response = await handler(adminNotificationRequest());
  assert(response.status === 500, `unexpected status ${response.status}`);
  assert(calls.length === 0, "missing configuration reached the email API");
});

Deno.test("admin notification provider failures remain retryable", async () => {
  const { handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
    fetchStatus: 429,
    fetchBody: JSON.stringify({ name: "rate_limit_exceeded" }),
  });
  const response = await handler(adminNotificationRequest());
  assert(response.status === 502, `unexpected status ${response.status}`);
  assert(
    (await response.text()) === "email_provider_http_429_rate_limit_exceeded",
    "provider code was not allowlisted",
  );
});

Deno.test("email network failures remain retryable with a stable code", async () => {
  const { handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
    fetchThrows: true,
  });
  const response = await handler(adminNotificationRequest());
  assert(response.status === 502, `unexpected status ${response.status}`);
  assert(
    (await response.text()) === "email_provider_network_error",
    "network failure code was not stable",
  );
});

Deno.test("admin notifications link to the configured portal and fail closed on bad URLs", async () => {
  const staging = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
    adminPortalUrl: "https://admin.staging.gallrmap.com/",
  });
  const response = await staging.handler(adminNotificationRequest());
  assert(response.status === 204, `unexpected status ${response.status}`);
  const body = JSON.parse(String(staging.calls[0]?.init?.body));
  assert(
    String(body.text).includes("https://admin.staging.gallrmap.com/"),
    "staging portal URL was not used",
  );

  const insecure = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
    adminPortalUrl: "http://admin.gallrmap.com/",
  });
  assert(
    (await insecure.handler(adminNotificationRequest())).status === 500,
    "insecure portal URL was accepted",
  );
  assert(
    insecure.calls.length === 0,
    "bad configuration reached the email API",
  );
});

Deno.test("admin notifications send large audiences in idempotent batches", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(adminNotificationRequest({
    payload: {
      kind: "gallery.claim_requested",
      entity_type: "gallery",
      entity_id: "gallery-one",
      actor_email: null,
      recipient_emails: Array.from(
        { length: 60 },
        (_, index) => `admin${index}@example.com`,
      ),
      occurred_at: "2026-09-13T03:00:00+00:00",
      context: {},
    },
  }));
  assert(response.status === 204, `unexpected status ${response.status}`);
  assert(calls.length === 2, `expected two batches, got ${calls.length}`);
  const first = JSON.parse(String(calls[0]?.init?.body));
  const second = JSON.parse(String(calls[1]?.init?.body));
  assert(first.to.length === 50 && second.to.length === 10, "batches uneven");
  const firstKey = new Headers(calls[0]?.init?.headers).get("idempotency-key");
  const secondKey = new Headers(calls[1]?.init?.headers).get("idempotency-key");
  assert(
    firstKey === "admin_notification:audit:audit-one" &&
      secondKey === "admin_notification:audit:audit-one:2",
    `batch keys were not distinct: ${firstKey} / ${secondKey}`,
  );
});

Deno.test("admin notifications reject keys that cannot carry a batch suffix", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const eventId = "00000000-0000-4000-8000-000000000032";
  const key = "k".repeat(255);
  const response = await handler(request({
    eventType: "admin_notification.requested",
    bodyEventType: "admin_notification.requested",
    eventId,
    idempotencyKey: key,
    body: JSON.stringify({
      id: eventId,
      event_type: "admin_notification.requested",
      aggregate_type: "gallery",
      aggregate_id: "gallery-one",
      deduplication_key: key,
      payload: {
        kind: "gallery.claim_requested",
        entity_type: "gallery",
        entity_id: "gallery-one",
        actor_email: null,
        recipient_emails: Array.from(
          { length: 60 },
          (_, index) => `admin${index}@example.com`,
        ),
        occurred_at: "2026-09-13T03:00:00+00:00",
        context: {},
      },
    }),
  }));
  assert(response.status === 422, `unexpected status ${response.status}`);
  assert(calls.length === 0, "an unbatchable key reached the email API");
});

function claimDecisionRequest(options: {
  eventType: "gallery_claim.accepted" | "gallery_claim.rejected";
  payload?: Record<string, unknown>;
}): Request {
  const eventId = "00000000-0000-4000-8000-000000000041";
  const idempotencyKey = `gallery_claim:gallery-one:user-one:${
    options.eventType.split(".")[1]
  }`;
  return request({
    eventType: options.eventType,
    bodyEventType: options.eventType,
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: options.eventType,
      aggregate_type: "gallery_membership",
      aggregate_id: "gallery-one:user-one",
      deduplication_key: idempotencyKey,
      payload: options.payload ?? {
        source: "owner_workspace",
        recipient_email: "Owner@Example.com",
        gallery_name: "Space <One>",
        review_notes: "",
      },
    }),
  });
}

Deno.test("claim approval emails the claimant with the gallery name and workspace link", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(
    claimDecisionRequest({ eventType: "gallery_claim.accepted" }),
  );
  assert(response.status === 204, `unexpected status ${response.status}`);
  assert(calls.length === 1, "email API was not called exactly once");
  const headers = new Headers(calls[0]?.init?.headers);
  assert(
    headers.get("idempotency-key") ===
      "gallery_claim:gallery-one:user-one:accepted",
    "outbox key was not forwarded",
  );
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    JSON.stringify(body.to) === JSON.stringify(["owner@example.com"]),
    "claimant address was not normalized",
  );
  assert(
    body.subject ===
      "[gallr] 갤러리 소유권 신청 승인 / Gallery claim approved: Space <One>",
    `unexpected subject ${body.subject}`,
  );
  assert(
    String(body.text).includes("https://gallery.gallrmap.com/"),
    "text lacks the gallery workspace link",
  );
  assert(
    String(body.html).includes("Space &lt;One&gt;") &&
      !String(body.html).includes("Space <One>"),
    "html did not escape the gallery name",
  );
  assert(
    !String(body.text).includes("gallery-one"),
    "internal identifier leaked into a gallery-facing email",
  );
});

Deno.test("claim rejection includes the review notes and stays retryable", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(claimDecisionRequest({
    eventType: "gallery_claim.rejected",
    payload: {
      source: "owner_workspace",
      recipient_email: "owner@example.com",
      gallery_name: "Space One",
      review_notes: "Please add <proof> of ownership.\nThen try again.",
    },
  }));
  assert(response.status === 204, `unexpected status ${response.status}`);
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    body.subject ===
      "[gallr] 갤러리 소유권 신청 거절 / Gallery claim rejected: Space One",
    `unexpected subject ${body.subject}`,
  );
  assert(
    String(body.text).includes("Please add <proof> of ownership."),
    "text lacks the review notes",
  );
  assert(
    String(body.html).includes(
      "Please add &lt;proof&gt; of ownership.<br>Then",
    ),
    "html did not escape and line-break the notes",
  );

  const failing = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
    fetchStatus: 500,
  });
  const retry = await failing.handler(
    claimDecisionRequest({ eventType: "gallery_claim.rejected" }),
  );
  assert(retry.status === 502, "provider failure was not retryable");
});

Deno.test("claim decisions reject invalid payloads and fail closed without configuration", async () => {
  const configured = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const invalid = await configured.handler(claimDecisionRequest({
    eventType: "gallery_claim.accepted",
    payload: { source: "owner_workspace", recipient_email: "nope" },
  }));
  assert(invalid.status === 422, `invalid payload got ${invalid.status}`);
  assert(configured.calls.length === 0, "invalid payload reached the API");

  const unconfigured = buildHandler();
  const missing = await unconfigured.handler(
    claimDecisionRequest({ eventType: "gallery_claim.accepted" }),
  );
  assert(missing.status === 500, `missing config got ${missing.status}`);
});

Deno.test("owner decision emails are bilingual and link to the workspace", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const eventId = "00000000-0000-4000-8000-000000000042";
  const idempotencyKey = "owner_submission:submission-nine:rejected";
  await handler(request({
    eventType: "submission.rejected",
    bodyEventType: "submission.rejected",
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: "submission.rejected",
      aggregate_type: "exhibition_submission",
      aggregate_id: "submission-nine",
      deduplication_key: idempotencyKey,
      payload: {
        source: "owner_workspace",
        recipient_email: "owner@example.com",
        exhibition_name: "Notes",
        review_notes: "Add dates",
      },
    }),
  }));
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    body.subject === "[gallr] 전시 수정 요청 / Changes requested: Notes",
    `unexpected subject ${body.subject}`,
  );
  assert(
    String(body.text).includes("검토 의견 / Review notes:") &&
      String(body.text).includes("Add dates"),
    "bilingual notes missing",
  );
  assert(
    String(body.text).includes("https://gallery.gallrmap.com/"),
    "workspace link missing",
  );
});

Deno.test("admin notifications always include the configured intake inbox", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
    adminIntakeEmail: "Hello@gallrmap.com",
  });
  const response = await handler(adminNotificationRequest());
  assert(response.status === 204, `unexpected status ${response.status}`);
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    JSON.stringify(body.to) === JSON.stringify([
      "admin@example.com",
      "second@example.com",
      "hello@gallrmap.com",
    ]),
    `intake inbox was not merged: ${JSON.stringify(body.to)}`,
  );

  const intakeOnly = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
    adminIntakeEmail: "hello@gallrmap.com",
  });
  const noStaff = await intakeOnly.handler(adminNotificationRequest({
    payload: {
      kind: "gallery.claim_requested",
      entity_type: "gallery",
      entity_id: "gallery-one",
      actor_email: null,
      recipient_emails: [],
      occurred_at: "2026-09-13T03:00:00+00:00",
      context: {},
    },
  }));
  assert(noStaff.status === 204, `intake-only delivery got ${noStaff.status}`);
  const onlyBody = JSON.parse(String(intakeOnly.calls[0]?.init?.body));
  assert(
    JSON.stringify(onlyBody.to) === JSON.stringify(["hello@gallrmap.com"]),
    "intake inbox alone was not used",
  );
});

Deno.test("admin notifications with no audience at all fail closed", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(adminNotificationRequest({
    payload: {
      kind: "gallery.claim_requested",
      entity_type: "gallery",
      entity_id: "gallery-one",
      actor_email: null,
      recipient_emails: [],
      occurred_at: "2026-09-13T03:00:00+00:00",
      context: {},
    },
  }));
  assert(response.status === 500, `unexpected status ${response.status}`);
  assert(calls.length === 0, "an audience-less event reached the email API");

  const badIntake = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
    adminIntakeEmail: "not-an-address",
  });
  assert(
    (await badIntake.handler(adminNotificationRequest())).status === 500,
    "a malformed intake inbox was accepted",
  );
});

Deno.test("claim decision emails neutralize control characters in names and notes", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(claimDecisionRequest({
    eventType: "gallery_claim.rejected",
    payload: {
      source: "owner_workspace",
      recipient_email: "owner@example.com",
      gallery_name: "Space\r\nSubject: forged‮One",
      review_notes: "Line one\nLine two​",
    },
  }));
  assert(response.status === 204, `unexpected status ${response.status}`);
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    !/[\r\n‮]/.test(body.subject),
    `subject not sanitized: ${body.subject}`,
  );
  assert(
    body.subject.endsWith("Gallery claim rejected: Space Subject: forged One"),
    `unexpected subject ${body.subject}`,
  );
  assert(
    String(body.text).includes("Line one\nLine two") &&
      !String(body.text).includes("​"),
    "notes lost their line breaks or kept invisible characters",
  );
});

Deno.test("claim decision events from other sources are acknowledged without email", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(claimDecisionRequest({
    eventType: "gallery_claim.accepted",
    payload: { source: "staff_import", recipient_email: "owner@example.com" },
  }));
  assert(response.status === 204, `unexpected status ${response.status}`);
  assert(calls.length === 0, "a non-owner event sent an email");
});

function publishedRequest(payload?: Record<string, unknown>): Request {
  const eventId = "00000000-0000-4000-8000-000000000051";
  const idempotencyKey = "owner_exhibition:exh-abcd1234:published";
  return request({
    eventType: "owner_exhibition.published",
    bodyEventType: "owner_exhibition.published",
    eventId,
    idempotencyKey,
    body: JSON.stringify({
      id: eventId,
      event_type: "owner_exhibition.published",
      aggregate_type: "exhibition",
      aggregate_id: "exh-abcd1234",
      deduplication_key: idempotencyKey,
      payload: payload ?? {
        source: "owner_workspace",
        recipient_emails: ["Owner@Example.com", "second@example.com"],
        exhibition_id: "exh-abcd1234",
        exhibition_name_en: "Notes from a <Small> Room",
        exhibition_name_ko: "작은 방의 기록",
      },
    }),
  });
}

Deno.test("first publication emails every active owner with the public link", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(publishedRequest());
  assert(response.status === 204, `unexpected status ${response.status}`);
  assert(calls.length === 1, "email API was not called exactly once");
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    JSON.stringify(body.to) ===
      JSON.stringify(["owner@example.com", "second@example.com"]),
    `owners were not all addressed: ${JSON.stringify(body.to)}`,
  );
  assert(
    body.subject ===
      "[gallr] 전시가 게시되었습니다 / Your exhibition is live: Notes from a <Small> Room",
    `unexpected subject ${body.subject}`,
  );
  assert(
    String(body.text).includes(
      "https://gallrmap.com/exhibitions/notes-from-a-small-room-exh-/",
    ),
    `public link missing or wrong: ${body.text}`,
  );
  assert(
    String(body.text).includes("https://gallery.gallrmap.com/"),
    "workspace link missing",
  );
  assert(
    String(body.html).includes("Notes from a &lt;Small&gt; Room") &&
      !String(body.html).includes("<Small>"),
    "html did not escape the exhibition name",
  );
  assert(
    new Headers(calls[0]?.init?.headers).get("idempotency-key") ===
      "owner_exhibition:exh-abcd1234:published",
    "outbox key was not forwarded",
  );
});

Deno.test("publication emails fall back to the Korean name and reject bad payloads", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const korean = await handler(publishedRequest({
    source: "owner_workspace",
    recipient_emails: ["owner@example.com"],
    exhibition_id: "exh-abcd1234",
    exhibition_name_en: "",
    exhibition_name_ko: "작은 방의 기록",
  }));
  assert(korean.status === 204, `korean fallback got ${korean.status}`);
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    body.subject.endsWith("Your exhibition is live: 작은 방의 기록"),
    `unexpected subject ${body.subject}`,
  );
  assert(
    String(body.text).includes(
      encodeURI("https://gallrmap.com/exhibitions/작은-방의-기록-exh-/"),
    ),
    `korean slug wrong: ${body.text}`,
  );

  const noRecipients = await handler(publishedRequest({
    source: "owner_workspace",
    recipient_emails: ["nope"],
    exhibition_id: "exh-abcd1234",
    exhibition_name_en: "Notes",
  }));
  assert(
    noRecipients.status === 422,
    `no recipients got ${noRecipients.status}`,
  );
  const noName = await handler(publishedRequest({
    source: "owner_workspace",
    recipient_emails: ["owner@example.com"],
    exhibition_id: "exh-abcd1234",
    exhibition_name_en: "",
    exhibition_name_ko: "",
  }));
  assert(noName.status === 422, `no name got ${noName.status}`);
  assert(calls.length === 1, "invalid payloads reached the email API");
});

Deno.test("publication slug matches the public site when the English name is blank spaces", async () => {
  const { calls, handler } = buildHandler({
    configuredResendKey: "re_test_key_with_enough_length_123",
    configuredOwnerNotificationFrom: "gallr <notify@gallrmap.com>",
  });
  const response = await handler(publishedRequest({
    source: "owner_workspace",
    recipient_emails: ["owner@example.com"],
    exhibition_id: "exh-abcd1234",
    exhibition_name_en: "   ",
    exhibition_name_ko: "작은 방의 기록",
  }));
  assert(response.status === 204, `unexpected status ${response.status}`);
  const body = JSON.parse(String(calls[0]?.init?.body));
  assert(
    String(body.text).includes("https://gallrmap.com/exhibitions/exh-/"),
    `slug should be suffix-only like the public site: ${body.text}`,
  );
  assert(
    body.subject.endsWith("Your exhibition is live: 작은 방의 기록"),
    `subject should fall back to the Korean name: ${body.subject}`,
  );
});
