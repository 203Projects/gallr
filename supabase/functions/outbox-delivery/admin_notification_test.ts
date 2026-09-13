import {
  parseAdminNotification,
  renderAdminNotificationEmail,
} from "./admin_notification.ts";
import type { DeliveryEvent } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function event(payload: Record<string, unknown>): DeliveryEvent {
  return {
    id: "00000000-0000-4000-8000-000000000031",
    event_type: "admin_notification.requested",
    aggregate_type: "admin_notification",
    aggregate_id: "audit-one",
    deduplication_key: "admin_notification:audit:audit-one",
    payload,
  };
}

const validPayload = {
  kind: "exhibition_submission.submitted",
  entity_type: "exhibition_submission",
  entity_id: "10000000-0000-4000-8000-000000000001",
  actor_email: "Owner@Example.com",
  recipient_emails: ["admin@example.com", "Second.Admin@example.com"],
  occurred_at: "2026-09-13T03:00:00+00:00",
  context: {
    exhibition_name: "Notes from a <Small> Room",
    source: "owner_workspace",
    submitter_email: "owner@example.com",
  },
};

Deno.test("parses a valid notification and normalizes addresses", () => {
  const notification = parseAdminNotification(event(validPayload));
  assert(notification !== null, "valid payload was rejected");
  assert(
    notification.kind === "exhibition_submission.submitted",
    "kind was not preserved",
  );
  assert(notification.actorEmail === "owner@example.com", "actor not lowered");
  assert(
    notification.recipientEmails.join(",") ===
      "admin@example.com,second.admin@example.com",
    "recipients were not normalized",
  );
  assert(
    notification.context.exhibition_name === "Notes from a <Small> Room",
    "context was not preserved",
  );
});

Deno.test("accepts a null actor and an empty context", () => {
  const notification = parseAdminNotification(event({
    ...validPayload,
    kind: "launch_kit.activated",
    actor_email: null,
    context: {},
  }));
  assert(notification !== null, "system-originated payload was rejected");
  assert(notification.actorEmail === null, "null actor was not preserved");
});

Deno.test("rejects payloads without usable recipients", () => {
  assert(
    parseAdminNotification(event({ ...validPayload, recipient_emails: [] })) ===
      null,
    "empty recipient list was accepted",
  );
  assert(
    parseAdminNotification(
      event({ ...validPayload, recipient_emails: ["not-an-email"] }),
    ) === null,
    "malformed recipient was accepted",
  );
  assert(
    parseAdminNotification(event({
      ...validPayload,
      recipient_emails: Array.from(
        { length: 51 },
        (_, index) => `admin${index}@example.com`,
      ),
    })) === null,
    "oversized recipient list was accepted",
  );
});

Deno.test("rejects malformed kinds, timestamps, and context values", () => {
  assert(
    parseAdminNotification(event({ ...validPayload, kind: "Bad Kind" })) ===
      null,
    "malformed kind was accepted",
  );
  assert(
    parseAdminNotification(
      event({ ...validPayload, occurred_at: "yesterday" }),
    ) === null,
    "malformed timestamp was accepted",
  );
  assert(
    parseAdminNotification(event({
      ...validPayload,
      context: { nested: { deep: true } },
    })) === null,
    "nested context value was accepted",
  );
  assert(
    parseAdminNotification(event({
      ...validPayload,
      context: { exhibition_name: "x".repeat(501) },
    })) === null,
    "oversized context value was accepted",
  );
});

Deno.test("renders a submission email with escaped names and the admin link", () => {
  const notification = parseAdminNotification(event(validPayload));
  assert(notification !== null, "valid payload was rejected");
  const email = renderAdminNotificationEmail(notification);
  assert(
    email.subject ===
      "[gallr admin] New exhibition submission: Notes from a <Small> Room",
    `unexpected subject: ${email.subject}`,
  );
  assert(email.text.includes("Submissions"), "text lacks the admin section");
  assert(
    email.text.includes("https://admin.gallrmap.com/"),
    "text lacks the admin link",
  );
  assert(
    email.text.includes("Submitted by: owner@example.com"),
    "text lacks the actor",
  );
  assert(
    email.text.includes("Source: gallery owner workspace"),
    "text lacks the humanized source",
  );
  assert(
    email.html.includes("Notes from a &lt;Small&gt; Room"),
    "html did not escape the exhibition name",
  );
  assert(!email.html.includes("<Small>"), "html leaked raw markup");
});

Deno.test("renders known kinds with their admin section", () => {
  const cases: Array<[string, string, string]> = [
    ["gallery.claim_requested", "New gallery claim request", "Gallery claims"],
    ["gallery.created_and_claimed", "New gallery created", "Gallery claims"],
    ["gallery.info_saved", "Gallery profile updated", "Gallery claims"],
    ["owner_exhibition.hidden", "Owner hid an exhibition", "Exhibitions"],
    ["local_promotion.requested", "New promotion request", "Promotions"],
    ["launch_kit.activated", "Launch Kit activated", "Promotions"],
    ["editor.profile_submitted", "Editor profile submitted", "Editors"],
    ["editor.curation_submitted", "Editor curation submitted", "Editors"],
    ["editor.onboarded", "Invited editor finished onboarding", "Editors"],
  ];
  for (const [kind, headline, section] of cases) {
    const notification = parseAdminNotification(event({
      ...validPayload,
      kind,
      context: { gallery_name: "Space One" },
    }));
    assert(notification !== null, `${kind} was rejected`);
    const email = renderAdminNotificationEmail(notification);
    assert(
      email.subject === `[gallr admin] ${headline}: Space One`,
      `unexpected subject for ${kind}: ${email.subject}`,
    );
    assert(
      email.text.includes(`Open gallr admin → ${section}`),
      `${kind} text lacks section ${section}`,
    );
  }
});

Deno.test("renders unknown kinds generically instead of failing", () => {
  const notification = parseAdminNotification(event({
    ...validPayload,
    kind: "future.thing_happened",
    context: {},
  }));
  assert(notification !== null, "unknown kind was rejected");
  const email = renderAdminNotificationEmail(notification);
  assert(
    email.subject ===
      "[gallr admin] Admin attention needed: future.thing_happened",
    `unexpected subject: ${email.subject}`,
  );
  assert(email.text.includes("Open gallr admin"), "generic text lacks link");
});
