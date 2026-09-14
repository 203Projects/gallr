import type { DeliveryEvent } from "./handler.ts";

/**
 * Staff notification rendered from one `admin_notification.requested` outbox
 * event. The database enqueues these for owner, editor, and public-form
 * actions that staff must review or should know about.
 */
export interface AdminNotification {
  kind: string;
  entityType: string;
  entityId: string;
  actorEmail: string | null;
  recipientEmails: string[];
  occurredAt: string;
  context: Record<string, string | number | boolean>;
}

export interface RenderedEmail {
  subject: string;
  text: string;
  html: string;
}

const DEFAULT_ADMIN_PORTAL_URL = "https://admin.gallrmap.com/";
/** Resend accepts at most 50 addresses per message. */
export const RECIPIENT_BATCH_SIZE = 50;
const MAX_RECIPIENTS = 500;
const MAX_CONTEXT_KEYS = 20;
const MAX_CONTEXT_VALUE_LENGTH = 500;
const MAX_SUBJECT_DETAIL_LENGTH = 80;
const UNSAFE_TEXT_CHARACTERS = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]+/gu;
const KIND_PATTERN = /^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface KindPresentation {
  headline: string;
  section: string;
  slug: string | null;
}

const KIND_PRESENTATIONS: Record<string, KindPresentation> = {
  "exhibition_submission.submitted": {
    headline: "새 전시 제출 / New exhibition submission",
    section: "Submissions",
    slug: "submissions",
  },
  "gallery.claim_requested": {
    headline: "기존 갤러리 소유권 신청 / New claim for an existing gallery",
    section: "Gallery claims",
    slug: "gallery-claims",
  },
  "gallery.created_and_claimed": {
    headline: "새 갤러리 등록 및 소유권 신청 / New gallery created and claimed",
    section: "Gallery claims",
    slug: "gallery-claims",
  },
  "gallery.info_saved": {
    headline: "갤러리 프로필 수정 / Gallery profile updated",
    section: "Gallery claims",
    slug: "gallery-claims",
  },
  "owner_exhibition.hidden": {
    headline: "갤러리가 전시를 숨김 / Owner hid an exhibition",
    section: "Exhibitions",
    slug: "exhibitions",
  },
  "local_promotion.requested": {
    headline: "새 프로모션 요청 / New promotion request",
    section: "Promotions",
    slug: "promotions",
  },
  "launch_kit.activated": {
    headline: "런치 키트 활성화 / Launch Kit activated",
    section: "Promotions",
    slug: "promotions",
  },
  "editor.profile_submitted": {
    headline: "에디터 프로필 제출 / Editor profile submitted",
    section: "Editors",
    slug: "editors",
  },
  "editor.curation_submitted": {
    headline: "에디터 큐레이션 제출 / Editor curation submitted",
    section: "Editors",
    slug: "editors",
  },
  "editor.onboarded": {
    headline: "초대된 에디터 온보딩 완료 / Invited editor finished onboarding",
    section: "Editors",
    slug: "editors",
  },
};

const SOURCE_LABELS: Record<string, string> = {
  public_form: "public submission form",
  owner_workspace: "gallery owner workspace",
  editor_workspace: "editor workspace",
};

const CONTEXT_LABELS: Record<string, string> = {
  exhibition_name: "전시 / Exhibition",
  gallery_name: "갤러리 / Gallery",
  venue_name: "장소 / Venue",
  source: "출처 / Source",
  submitter_email: "제출자 / Submitter",
  claim_note: "신청 메모 / Claim note",
  editor_name: "에디터 / Editor",
  editor_id: "에디터 ID / Editor id",
  change_count: "변경 수 / Changes",
  entitlement_source: "권한 출처 / Entitlement",
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function normalizeEmail(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  if (email.length === 0 || email.length > 320 || !EMAIL_PATTERN.test(email)) {
    return null;
  }
  return email;
}

type EnvironmentReader = (name: string) => string | undefined;

/**
 * Staging and production are separate identity planes, so the portal link is
 * configurable. Unset means production; anything set must be an HTTPS URL
 * without credentials, otherwise the caller fails closed.
 */
export function adminPortalUrl(env: EnvironmentReader): string | null {
  const configured = env("ADMIN_PORTAL_URL")?.trim() ?? "";
  if (configured.length === 0) return DEFAULT_ADMIN_PORTAL_URL;
  try {
    const url = new URL(configured);
    if (url.protocol !== "https:" || url.username || url.password) return null;
    return url.href;
  } catch {
    return null;
  }
}

/**
 * A fixed intake inbox (for example hello@gallrmap.com) that receives every
 * staff notification in addition to active admins. Unset means none; a set
 * value must be a well-formed address or delivery fails closed.
 */
export function adminIntakeEmail(
  env: EnvironmentReader,
): { ok: true; email: string | null } | { ok: false } {
  const configured = env("ADMIN_INTAKE_EMAIL")?.trim() ?? "";
  if (configured.length === 0) return { ok: true, email: null };
  const email = normalizeEmail(configured);
  return email ? { ok: true, email } : { ok: false };
}

/**
 * Keeps every well-formed address and drops the rest, so one malformed staff
 * email cannot make every admin notification undeliverable. An empty list is
 * valid: the handler adds the configured intake inbox and fails closed only
 * when nobody at all would receive the message.
 */
function parseRecipients(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null;
  const recipients = new Set<string>();
  for (const candidate of value) {
    const email = normalizeEmail(candidate);
    if (email) recipients.add(email);
    if (recipients.size === MAX_RECIPIENTS) break;
  }
  return [...recipients];
}

/** Truncates by code point so an astral character is never split in half. */
function truncate(value: string, maxLength: number): string {
  const points = Array.from(value);
  return points.length > maxLength
    ? points.slice(0, maxLength).join("")
    : value;
}

/**
 * User-supplied names reach the subject line and the plain-text body, so line
 * breaks, other control characters, invisible format characters (bidi
 * overrides, zero-width joiners), and Unicode line separators are collapsed
 * to spaces before use.
 */
export function sanitizeSingleLine(
  value: string,
  maxLength = MAX_CONTEXT_VALUE_LENGTH,
): string {
  return truncate(value.replace(UNSAFE_TEXT_CHARACTERS, " ").trim(), maxLength);
}

const UNSAFE_MULTILINE_CHARACTERS = /(?!\n)[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]+/gu;

/** Like sanitizeSingleLine but keeps ordinary line breaks for notes. */
export function sanitizeMultiline(value: string, maxLength: number): string {
  return truncate(
    value.replace(/\r\n?/g, "\n").replace(UNSAFE_MULTILINE_CHARACTERS, " ")
      .trim(),
    maxLength,
  );
}

function sanitizeText(value: string): string {
  return sanitizeSingleLine(value);
}

function parseContext(
  value: unknown,
): Record<string, string | number | boolean> | null {
  if (!isRecord(value)) return null;
  const entries = Object.entries(value);
  if (entries.length > MAX_CONTEXT_KEYS) return null;
  const context: Record<string, string | number | boolean> = {};
  for (const [key, raw] of entries) {
    if (!/^[a-z][a-z0-9_]{0,63}$/.test(key)) return null;
    if (typeof raw === "string") {
      const sanitized = sanitizeText(raw);
      if (sanitized.length > 0) context[key] = sanitized;
    } else if (typeof raw === "number" && Number.isFinite(raw)) {
      context[key] = raw;
    } else if (typeof raw === "boolean") {
      context[key] = raw;
    } else if (raw !== null) {
      return null;
    }
  }
  return context;
}

function parseTimestamp(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 64) return null;
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) return null;
  return new Date(parsed).toISOString();
}

export function parseAdminNotification(
  event: DeliveryEvent,
): AdminNotification | null {
  const { payload } = event;
  const kind = payload.kind;
  if (
    typeof kind !== "string" || kind.length > 80 || !KIND_PATTERN.test(kind)
  ) {
    return null;
  }
  const entityType = payload.entity_type;
  const entityId = payload.entity_id;
  if (
    typeof entityType !== "string" || entityType.trim().length === 0 ||
    entityType.length > 80 ||
    typeof entityId !== "string" || entityId.trim().length === 0 ||
    entityId.length > 200
  ) return null;
  let actorEmail: string | null = null;
  if (payload.actor_email !== null && payload.actor_email !== undefined) {
    actorEmail = normalizeEmail(payload.actor_email);
    if (!actorEmail) return null;
  }
  const recipientEmails = parseRecipients(payload.recipient_emails);
  if (!recipientEmails) return null;
  const occurredAt = parseTimestamp(payload.occurred_at);
  if (!occurredAt) return null;
  const context = parseContext(payload.context ?? {});
  if (!context) return null;
  return {
    kind,
    entityType: entityType.trim(),
    entityId: entityId.trim(),
    actorEmail,
    recipientEmails,
    occurredAt,
    context,
  };
}

export function escapeHtml(value: string): string {
  return value.replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function subjectDetail(notification: AdminNotification): string | null {
  const { context } = notification;
  for (
    const key of ["exhibition_name", "gallery_name", "editor_name", "editor_id"]
  ) {
    const value = context[key];
    if (typeof value !== "string" || value.length === 0) continue;
    return Array.from(value).length > MAX_SUBJECT_DETAIL_LENGTH
      ? `${truncate(value, MAX_SUBJECT_DETAIL_LENGTH - 1)}…`
      : value;
  }
  return null;
}

function detailLines(notification: AdminNotification): string[] {
  const lines: string[] = [];
  for (const [key, label] of Object.entries(CONTEXT_LABELS)) {
    const value = notification.context[key];
    if (value === undefined || value === "") continue;
    const rendered = key === "source" && typeof value === "string"
      ? SOURCE_LABELS[value] ?? value
      : String(value);
    lines.push(`${label}: ${rendered}`);
  }
  lines.push(
    `요청자 / Submitted by: ${notification.actorEmail ?? "system"}`,
    `시각 / When: ${notification.occurredAt}`,
    `기록 / Record: ${notification.entityType} ${notification.entityId}`,
  );
  return lines;
}

function sectionLink(portalUrl: string, slug: string | null): string {
  if (!slug) return portalUrl;
  const url = new URL(portalUrl);
  url.searchParams.set("section", slug);
  return url.href;
}

export function renderAdminNotificationEmail(
  notification: AdminNotification,
  portalUrl: string = DEFAULT_ADMIN_PORTAL_URL,
): RenderedEmail {
  const presentation = KIND_PRESENTATIONS[notification.kind] ?? {
    headline: `관리자 확인 필요 / Admin attention needed: ${notification.kind}`,
    section: "the relevant section",
    slug: null,
  };
  const detail = subjectDetail(notification);
  const subject = detail
    ? `[gallr admin] ${presentation.headline}: ${detail}`
    : `[gallr admin] ${presentation.headline}`;
  const lines = detailLines(notification);
  const callToAction =
    `gallr admin 열기 / Open gallr admin → ${presentation.section}`;
  const link = sectionLink(portalUrl, presentation.slug);
  const text = [
    presentation.headline,
    "",
    ...lines,
    "",
    `${callToAction}: ${link}`,
  ].join("\n");
  const html = [
    `<p><strong>${escapeHtml(presentation.headline)}</strong></p>`,
    `<ul>${lines.map((line) => `<li>${escapeHtml(line)}</li>`).join("")}</ul>`,
    `<p><a href="${escapeHtml(link)}">${escapeHtml(callToAction)}</a></p>`,
  ].join("");
  return { subject, text, html };
}
