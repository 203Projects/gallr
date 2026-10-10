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

Deno.test("keeps empty audiences for the intake inbox and rejects non-lists", () => {
  const empty = parseAdminNotification(
    event({ ...validPayload, recipient_emails: [] }),
  );
  assert(
    empty !== null && empty.recipientEmails.length === 0,
    "empty recipient list was rejected",
  );
  const malformedOnly = parseAdminNotification(
    event({ ...validPayload, recipient_emails: ["not-an-email"] }),
  );
  assert(
    malformedOnly !== null && malformedOnly.recipientEmails.length === 0,
    "malformed-only recipient list was rejected",
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
  const noneValid = parseAdminNotification(
    event({ ...validPayload, recipient_emails: ["broken", 42] }),
  );
  assert(
    noneValid !== null && noneValid.recipientEmails.length === 0,
    "an event with no valid recipient was rejected",
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
    email.html.includes(
      'href="https://admin.staging.example/?section=submissions"',
    ),
    "html lacks the configured portal URL with its section",
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
  assert(
    email.text.includes("갤러리 / Gallery:") === false ||
      email.text.includes("갤러리 / Gallery: "),
    "gallery label format changed",
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
    subject ===
      `[gallr admin] 새 전시 제출 / New exhibition submission: ${
        "y".repeat(79)
      }…`,
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
      "[gallr admin] 초대된 에디터 온보딩 완료 / Invited editor finished onboarding: editor-two",
    `unexpected editor subject ${rendered.subject}`,
  );
  assert(
    rendered.text.includes("요청자 / Submitted by: system"),
    "system actor was not rendered",
  );
});

Deno.test("renders a submission email with escaped names and the admin link", () => {
  const notification = parseAdminNotification(event(validPayload));
  assert(notification !== null, "valid payload was rejected");
  const email = renderAdminNotificationEmail(notification);
  assert(
    email.subject ===
      "[gallr admin] 새 전시 제출 / New exhibition submission: Notes from a <Small> Room",
    `unexpected subject: ${email.subject}`,
  );
  assert(email.text.includes("Submissions"), "text lacks the admin section");
  assert(
    email.text.includes("https://admin.gallrmap.com/?section=submissions"),
    "text lacks the deep link into the Submissions review area",
  );
  assert(
    email.html.includes(
      'href="https://admin.gallrmap.com/?section=submissions"',
    ),
    "html lacks the deep link",
  );
  assert(
    email.text.includes("요청자 / Submitted by: owner@example.com"),
    "text lacks the actor",
  );
  assert(
    email.text.includes("출처 / Source: gallery owner workspace"),
    "text lacks the bilingual humanized source",
  );
  assert(
    email.html.includes("Notes from a &lt;Small&gt; Room"),
    "html did not escape the exhibition name",
  );
  assert(!email.html.includes("<Small>"), "html leaked raw markup");
});

Deno.test("renders known kinds with their admin section", () => {
  const cases: Array<[string, string, string, string]> = [
    [
      "gallery.claim_requested",
      "기존 갤러리 소유권 신청 / New claim for an existing gallery",
      "Gallery claims",
      "gallery-claims",
    ],
    [
      "gallery.created_and_claimed",
      "새 갤러리 등록 및 소유권 신청 / New gallery created and claimed",
      "Gallery claims",
      "gallery-claims",
    ],
    [
      "gallery.info_saved",
      "갤러리 프로필 수정 / Gallery profile updated",
      "Gallery claims",
      "gallery-claims",
    ],
    [
      "owner_exhibition.hidden",
      "갤러리가 전시를 숨김 / Owner hid an exhibition",
      "Exhibitions",
      "exhibitions",
    ],
    [
      "local_promotion.requested",
      "새 프로모션 요청 / New promotion request",
      "Promotions",
      "promotions",
    ],
    [
      "launch_kit.activated",
      "런치 키트 활성화 / Launch Kit activated",
      "Promotions",
      "promotions",
    ],
    [
      "editor.profile_submitted",
      "에디터 프로필 제출 / Editor profile submitted",
      "Editors",
      "editors",
    ],
    [
      "editor.curation_submitted",
      "에디터 큐레이션 제출 / Editor curation submitted",
      "Editors",
      "editors",
    ],
    [
      "editor.onboarded",
      "초대된 에디터 온보딩 완료 / Invited editor finished onboarding",
      "Editors",
      "editors",
    ],
  ];
  for (const [kind, headline, section, slug] of cases) {
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
      email.text.includes(`gallr admin 열기 / Open gallr admin → ${section}`),
      `${kind} text lacks section ${section}`,
    );
    assert(
      email.text.includes(`https://admin.gallrmap.com/?section=${slug}`),
      `${kind} text lacks the ${slug} deep link`,
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
      "[gallr admin] 관리자 확인 필요 / Admin attention needed: future.thing_happened",
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
    email.subject ===
      "[gallr admin] 에디터 큐레이션 제출 / Editor curation submitted: Second Editor",
    `unexpected subject ${email.subject}`,
  );
  assert(
    email.text.includes("에디터 / Editor: Second Editor"),
    "name not rendered",
  );
  assert(
    email.text.includes("에디터 ID / Editor id: editor-two"),
    "id not rendered",
  );
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

Deno.test("renders claim intake with the claim note and gallery name", () => {
  const notification = parseAdminNotification(event({
    ...validPayload,
    kind: "gallery.claim_requested",
    context: {
      gallery_name: "Space One",
      claim_note: "We run this space",
    },
  }));
  assert(notification !== null, "claim payload rejected");
  const email = renderAdminNotificationEmail(notification);
  assert(
    email.text.includes("신청 메모 / Claim note: We run this space"),
    "claim note was not rendered",
  );
  assert(
    email.text.includes("갤러리 / Gallery: Space One"),
    "gallery name was not rendered",
  );
});
