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
    aggregate_type: "gallery",
    aggregate_id: "gallery-one",
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
    parseAdminNotification(
      event({ ...validPayload, recipient_emails: "admin@example.com" }),
    ) === null,
    "non-array recipient list was accepted",
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
  const truncated = parseAdminNotification(event({
    ...validPayload,
    context: { exhibition_name: "x".repeat(501) },
  }));
  assert(
    truncated !== null &&
      truncated.context.exhibition_name === "x".repeat(500),
    "oversized context value was not truncated",
  );
});

Deno.test("skips invalid recipients and keeps the valid ones", () => {
  const notification = parseAdminNotification(event({
    ...validPayload,
    recipient_emails: ["broken", "admin@example.com", "ADMIN@example.com "],
  }));
  assert(notification !== null, "one invalid recipient rejected the event");
  assert(
    notification.recipientEmails.join(",") === "admin@example.com",
    "valid recipients were not kept and deduplicated",
  );
  assert(
    parseAdminNotification(
      event({ ...validPayload, recipient_emails: ["broken", 42] }),
    ) === null,
    "an event with no valid recipient was accepted",
  );
  const large = parseAdminNotification(event({
    ...validPayload,
    recipient_emails: Array.from(
      { length: 60 },
      (_, index) => `admin${index}@example.com`,
    ),
  }));
  assert(
    large !== null && large.recipientEmails.length === 60,
    "a large but valid recipient list was truncated",
  );
});

Deno.test("renders the configured portal URL", () => {
  const notification = parseAdminNotification(event(validPayload));
  assert(notification !== null, "valid payload was rejected");
  const email = renderAdminNotificationEmail(
    notification,
    "https://admin.staging.example/",
  );
  assert(
    email.text.includes("https://admin.staging.example/"),
    "text lacks the configured portal URL",
  );
  assert(
    email.html.includes('href="https://admin.staging.example/"'),
    "html lacks the configured portal URL",
  );
  assert(
    !email.text.includes("admin.gallrmap.com"),
    "production URL leaked into a staging email",
  );
});

Deno.test("rejects malformed actors, entities, and context keys", () => {
  assert(
    parseAdminNotification(event({ ...validPayload, actor_email: "nope" })) ===
      null,
    "malformed actor was accepted",
  );
  assert(
    parseAdminNotification(event({ ...validPayload, entity_id: "  " })) ===
      null,
    "blank entity id was accepted",
  );
  assert(
    parseAdminNotification(
      event({ ...validPayload, context: { "Bad-Key": 1 } }),
    ) === null,
    "bad context key was accepted",
  );
  assert(
    parseAdminNotification(event({ ...validPayload, context: [] })) === null,
    "array context was accepted",
  );
});

Deno.test("neutralizes control characters in names before rendering", () => {
  const notification = parseAdminNotification(event({
    ...validPayload,
    context: {
      exhibition_name: "Real\r\nSubmitted by: attacker@example.com\tX",
    },
  }));
  assert(notification !== null, "control characters rejected the event");
  assert(
    notification.context.exhibition_name ===
      "Real Submitted by: attacker@example.com X",
    `control characters survived: ${notification.context.exhibition_name}`,
  );
  const email = renderAdminNotificationEmail(notification);
  assert(!/[\r\n]/.test(email.subject), "subject contains a line break");
  assert(
    !email.text.includes("\nSubmitted by: attacker@example.com"),
    "text body gained a forged line",
  );
});

Deno.test("caps the subject detail and falls back to the editor id", () => {
  const long = parseAdminNotification(event({
    ...validPayload,
    context: { exhibition_name: "y".repeat(200) },
  }));
  assert(long !== null, "long name rejected");
  const subject = renderAdminNotificationEmail(long).subject;
  assert(
    subject === `[gallr admin] New exhibition submission: ${"y".repeat(79)}…`,
    `subject detail was not capped: ${subject.length}`,
  );
  const editor = parseAdminNotification(event({
    ...validPayload,
    kind: "editor.onboarded",
    actor_email: null,
    context: { editor_id: "editor-two" },
  }));
  assert(editor !== null, "editor payload rejected");
  const rendered = renderAdminNotificationEmail(editor);
  assert(
    rendered.subject ===
      "[gallr admin] Invited editor finished onboarding: editor-two",
    `unexpected editor subject ${rendered.subject}`,
  );
  assert(
    rendered.text.includes("Submitted by: system"),
    "system actor was not rendered",
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

Deno.test("prefers the editor display name and labels both fields", () => {
  const notification = parseAdminNotification(event({
    ...validPayload,
    kind: "editor.curation_submitted",
    context: { editor_id: "editor-two", editor_name: "Second Editor" },
  }));
  assert(notification !== null, "editor payload rejected");
  const email = renderAdminNotificationEmail(notification);
  assert(
    email.subject === "[gallr admin] Editor curation submitted: Second Editor",
    `unexpected subject ${email.subject}`,
  );
  assert(email.text.includes("Editor: Second Editor"), "name not rendered");
  assert(email.text.includes("Editor id: editor-two"), "id not rendered");
});

Deno.test("strips format and separator characters and keeps surrogate pairs whole", () => {
  const notification = parseAdminNotification(event({
    ...validPayload,
    context: {
      exhibition_name: "Real\u202eName\u2028Line\u200bZero",
      gallery_name: "😀".repeat(600),
    },
  }));
  assert(notification !== null, "unicode names rejected the event");
  assert(
    notification.context.exhibition_name === "Real Name Line Zero",
    `format characters survived: ${
      JSON.stringify(notification.context.exhibition_name)
    }`,
  );
  const gallery = String(notification.context.gallery_name);
  assert(
    Array.from(gallery).length === 500,
    `truncation did not count code points: ${Array.from(gallery).length}`,
  );
  assert(
    !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(gallery),
    "truncation split a surrogate pair",
  );
  const subject = renderAdminNotificationEmail(notification).subject;
  assert(
    !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(subject),
    "subject cap split a surrogate pair",
  );
});
