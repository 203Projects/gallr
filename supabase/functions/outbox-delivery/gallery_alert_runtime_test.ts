import {
  type GalleryAlertAuthorizer,
  HttpGalleryAlertProvider,
} from "./gallery_alert_runtime.ts";
import type { GalleryAlertJob } from "./gallery_alert.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function target(overrides: Partial<GalleryAlertJob> = {}): GalleryAlertJob {
  return {
    job_id: 1,
    lease_token: "00000000-0000-4000-8000-000000000001",
    provider: "apns",
    provider_token: "a".repeat(64),
    provider_environment: "sandbox",
    locale: "ko-KR",
    gallery_name_ko: "국제갤러리",
    gallery_name_en: "Kukje Gallery",
    exhibition_name_ko: "공명",
    exhibition_name_en: "Resonance",
    exhibition_id: "exhibition-one",
    deduplication_key: "dedupe-one",
    ...overrides,
  };
}

function provider(response: Response) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const authorizer: GalleryAlertAuthorizer = {
    apns: () => Promise.resolve({ token: "apns-jwt", topic: "com.gallr.app" }),
    fcm: () =>
      Promise.resolve({ token: "oauth-token", projectId: "gallr-test" }),
  };
  return {
    calls,
    provider: new HttpGalleryAlertProvider(
      (input, init) => {
        calls.push({ url: String(input), init });
        return Promise.resolve(response.clone());
      },
      authorizer,
    ),
  };
}

Deno.test("APNs delivery uses the sandbox host, topic, collapse key, and deeplink", async () => {
  const test = provider(new Response(null, { status: 200 }));
  const result = await test.provider.send(target());
  assert(result.outcome === "delivered", "APNs success was rejected");
  assert(
    test.calls[0]?.url.startsWith(
      "https://api.sandbox.push.apple.com/3/device/",
    ),
    "APNs sandbox host was not selected",
  );
  const headers = new Headers(test.calls[0]?.init?.headers);
  assert(headers.get("apns-topic") === "com.gallr.app", "APNs topic missing");
  assert(headers.get("apns-push-type") === "alert", "push type missing");
  assert(
    (headers.get("apns-collapse-id")?.length ?? 99) <= 64,
    "collapse key too long",
  );
  const body = JSON.parse(String(test.calls[0]?.init?.body));
  assert(body.exhibitionId === "exhibition-one", "deeplink identity missing");
  assert(body.aps.alert.title.includes("국제갤러리"), "Korean title missing");
});

Deno.test("FCM delivery uses HTTP v1 and English content", async () => {
  const test = provider(
    new Response(JSON.stringify({ name: "message-one" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  const result = await test.provider.send(target({
    provider: "fcm",
    provider_token: "fcm-registration-token-12345",
    provider_environment: "production",
    locale: "en-US",
  }));
  assert(result.outcome === "delivered", "FCM success was rejected");
  assert(
    test.calls[0]?.url ===
      "https://fcm.googleapis.com/v1/projects/gallr-test/messages:send",
    "wrong FCM endpoint",
  );
  const body = JSON.parse(String(test.calls[0]?.init?.body));
  assert(
    body.message.fid === "fcm-registration-token-12345",
    "target missing",
  );
  assert(
    body.message.notification.title.includes("Kukje"),
    "English title missing",
  );
  assert(
    body.message.data["gallr.notification.deeplink.exhibitionId"] ===
      "exhibition-one",
    "FCM deeplink missing",
  );
});

Deno.test("provider responses classify invalid, retryable, and permanent failures", async () => {
  const invalidApns = provider(
    new Response(
      JSON.stringify({ reason: "Unregistered" }),
      { status: 410, headers: { "Content-Type": "application/json" } },
    ),
  );
  assert(
    (await invalidApns.provider.send(target())).outcome === "invalid_token",
    "APNs invalid token was not disabled",
  );

  const retryableFcm = provider(new Response(null, { status: 503 }));
  assert(
    (await retryableFcm.provider.send(target({
      provider: "fcm",
      provider_token: "fcm-registration-token-12345",
      provider_environment: "production",
    }))).outcome === "retryable",
    "FCM outage was not retried",
  );

  const permanent = provider(new Response(null, { status: 403 }));
  assert(
    (await permanent.provider.send(target())).outcome === "permanent",
    "provider authorization failure was not dead-lettered",
  );
});
