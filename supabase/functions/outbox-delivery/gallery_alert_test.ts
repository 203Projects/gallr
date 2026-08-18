import {
  deliverGalleryPublicationAlerts,
  type GalleryAlertBackend,
  type GalleryAlertJob,
  type GalleryAlertProvider,
} from "./gallery_alert.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const event = {
  id: "00000000-0000-4000-8000-000000000101",
  event_type: "exhibition.published",
  aggregate_type: "exhibition",
  aggregate_id: "exhibition-one",
  deduplication_key: "exhibition:exhibition-one:published:version-one",
  payload: {
    exhibition_id: "exhibition-one",
    version_id: "00000000-0000-4000-8000-000000000102",
    gallery_id: "00000000-0000-4000-8000-000000000103",
  },
};

function job(overrides: Partial<GalleryAlertJob> = {}): GalleryAlertJob {
  return {
    job_id: 7,
    lease_token: "00000000-0000-4000-8000-000000000104",
    provider: "apns",
    provider_token: "a".repeat(64),
    provider_environment: "sandbox",
    locale: "ko-KR",
    gallery_name_ko: "국제갤러리",
    gallery_name_en: "Kukje Gallery",
    exhibition_name_ko: "공명",
    exhibition_name_en: "Resonance",
    exhibition_id: "exhibition-one",
    deduplication_key:
      "gallery:00000000-0000-4000-8000-000000000103:exhibition:exhibition-one:version:00000000-0000-4000-8000-000000000102:installation:00000000-0000-4000-8000-000000000105",
    ...overrides,
  };
}

function harness(options: {
  jobs?: GalleryAlertJob[];
  hasMore?: boolean;
  providerResult?: "delivered" | "retryable" | "invalid_token" | "permanent";
} = {}) {
  const delivered: number[] = [];
  const failed: Array<{
    jobId: number;
    code: string;
    retryable: boolean;
    invalidToken: boolean;
  }> = [];
  const sent: GalleryAlertJob[] = [];
  const backend: GalleryAlertBackend = {
    claim: () =>
      Promise.resolve({
        jobs: options.jobs ?? [job()],
        hasMore: options.hasMore ?? false,
      }),
    markDelivered: (jobId) => {
      delivered.push(jobId);
      return Promise.resolve();
    },
    markFailed: (jobId, _leaseToken, failure) => {
      failed.push({ jobId, ...failure });
      return Promise.resolve();
    },
  };
  const provider: GalleryAlertProvider = {
    send: (target) => {
      sent.push(target);
      const outcome = options.providerResult ?? "delivered";
      return Promise.resolve(
        outcome === "delivered"
          ? { outcome }
          : { outcome, code: `provider_${outcome}` },
      );
    },
  };
  return { backend, delivered, failed, provider, sent };
}

Deno.test("publication fan-out sends each claimed job exactly once", async () => {
  const first = job();
  const second = job({
    job_id: 8,
    provider: "fcm",
    provider_token: "firebase-installation-id-12345",
    provider_environment: "production",
    locale: "en-US",
  });
  const test = harness({ jobs: [first, second] });

  const result = await deliverGalleryPublicationAlerts(
    test.backend,
    test.provider,
    event,
  );

  assert(result.ok, "successful delivery was reported as failed");
  assert(test.sent.length === 2, "not every claimed job was sent");
  assert(test.delivered.join(",") === "7,8", "jobs were not completed");
  assert(test.failed.length === 0, "successful jobs were failed");
});

Deno.test("invalid provider tokens are disabled without retrying the event", async () => {
  const test = harness({ providerResult: "invalid_token" });

  const result = await deliverGalleryPublicationAlerts(
    test.backend,
    test.provider,
    event,
  );

  assert(result.ok, "invalid token incorrectly retried the outbox event");
  assert(test.delivered.length === 0, "invalid token was delivered");
  assert(test.failed.length === 1, "invalid token failure was not recorded");
  assert(test.failed[0]?.invalidToken, "token was not marked invalid");
  assert(!test.failed[0]?.retryable, "invalid token was marked retryable");
});

Deno.test("transient provider failures remain retryable and sanitized", async () => {
  const test = harness({ providerResult: "retryable" });

  const result = await deliverGalleryPublicationAlerts(
    test.backend,
    test.provider,
    event,
  );

  assert(!result.ok, "transient provider failure was acknowledged");
  assert(
    result.code === "gallery_alert_provider_retryable",
    "unsafe code escaped",
  );
  assert(test.failed[0]?.retryable, "transient failure was not retried");
  assert(!test.failed[0]?.invalidToken, "transient failure invalidated token");
});

Deno.test("permanent non-token failures dead-letter only their job", async () => {
  const test = harness({ providerResult: "permanent" });

  const result = await deliverGalleryPublicationAlerts(
    test.backend,
    test.provider,
    event,
  );

  assert(result.ok, "permanent job failure retried the whole event");
  assert(!test.failed[0]?.retryable, "permanent failure was marked retryable");
  assert(
    !test.failed[0]?.invalidToken,
    "provider configuration invalidated token",
  );
});

Deno.test("empty fan-out succeeds while another batch remains retryable", async () => {
  const empty = harness({ jobs: [] });
  assert(
    (await deliverGalleryPublicationAlerts(
      empty.backend,
      empty.provider,
      event,
    )).ok,
    "empty fan-out failed",
  );

  const more = harness({ hasMore: true });
  const result = await deliverGalleryPublicationAlerts(
    more.backend,
    more.provider,
    event,
  );
  assert(!result.ok, "unfinished batch was acknowledged");
  assert(result.code === "gallery_alert_batch_incomplete", "wrong batch code");
});

Deno.test("fan-out rejects mismatched or incomplete publication identity", async () => {
  const test = harness();
  const malformed = {
    ...event,
    aggregate_id: "different-exhibition",
    payload: { exhibition_id: "exhibition-one" },
  };
  const result = await deliverGalleryPublicationAlerts(
    test.backend,
    test.provider,
    malformed,
  );
  assert(!result.ok, "malformed publication was accepted");
  assert(
    result.code === "gallery_alert_event_invalid",
    "wrong validation code",
  );
  assert(test.sent.length === 0, "malformed event reached provider");
});
