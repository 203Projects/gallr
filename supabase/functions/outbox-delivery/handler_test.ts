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
  fetchStatus?: number;
  fetchBody?: string;
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
      return undefined;
    },
    fetch: (input, init) => {
      calls.push({ url: String(input), init });
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
