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

const ADMIN_PORTAL_URL = "https://admin.gallrmap.com/";
const MAX_RECIPIENTS = 50;
const MAX_CONTEXT_KEYS = 20;
const MAX_CONTEXT_VALUE_LENGTH = 500;
const KIND_PATTERN = /^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface KindPresentation {
  headline: string;
  section: string;
}

const KIND_PRESENTATIONS: Record<string, KindPresentation> = {
  "exhibition_submission.submitted": {
    headline: "New exhibition submission",
    section: "Submissions",
  },
  "gallery.claim_requested": {
    headline: "New gallery claim request",
    section: "Gallery claims",
  },
  "gallery.created_and_claimed": {
    headline: "New gallery created",
    section: "Gallery claims",
  },
  "gallery.info_saved": {
    headline: "Gallery profile updated",
    section: "Gallery claims",
  },
  "owner_exhibition.hidden": {
    headline: "Owner hid an exhibition",
    section: "Exhibitions",
  },
  "local_promotion.requested": {
    headline: "New promotion request",
    section: "Promotions",
  },
  "launch_kit.activated": {
    headline: "Launch Kit activated",
    section: "Promotions",
  },
  "editor.profile_submitted": {
    headline: "Editor profile submitted",
    section: "Editors",
  },
  "editor.curation_submitted": {
    headline: "Editor curation submitted",
    section: "Editors",
  },
  "editor.onboarded": {
    headline: "Invited editor finished onboarding",
    section: "Editors",
  },
};

const SOURCE_LABELS: Record<string, string> = {
  public_form: "public submission form",
  owner_workspace: "gallery owner workspace",
  editor_workspace: "editor workspace",
};

const CONTEXT_LABELS: Record<string, string> = {
  exhibition_name: "Exhibition",
  gallery_name: "Gallery",
  venue_name: "Venue",
  source: "Source",
  submitter_email: "Submitter",
  editor_id: "Editor",
  change_count: "Changes",
  entitlement_source: "Entitlement",
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function normalizeEmail(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  if (email.length === 0 || email.length > 320 || !EMAIL_PATTERN.test(email)) {
    return null;
  }
  return email;
}

function parseRecipients(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length === 0) return null;
  if (value.length > MAX_RECIPIENTS) return null;
  const recipients = new Set<string>();
  for (const candidate of value) {
    const email = normalizeEmail(candidate);
    if (!email) return null;
    recipients.add(email);
  }
  return [...recipients];
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
      if (raw.length > MAX_CONTEXT_VALUE_LENGTH) return null;
      context[key] = raw;
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
  const actorEmail = payload.actor_email === null ||
      payload.actor_email === undefined
    ? null
    : normalizeEmail(payload.actor_email);
  if (
    actorEmail === null && payload.actor_email !== null &&
    payload.actor_email !== undefined
  ) return null;
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

function escapeHtml(value: string): string {
  return value.replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function subjectDetail(notification: AdminNotification): string | null {
  const { context } = notification;
  for (const key of ["exhibition_name", "gallery_name", "editor_id"]) {
    const value = context[key];
    if (typeof value === "string" && value.trim().length > 0) {
      return value.trim();
    }
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
    `Submitted by: ${notification.actorEmail ?? "system"}`,
    `When: ${notification.occurredAt}`,
    `Record: ${notification.entityType} ${notification.entityId}`,
  );
  return lines;
}

export function renderAdminNotificationEmail(
  notification: AdminNotification,
): RenderedEmail {
  const presentation = KIND_PRESENTATIONS[notification.kind] ?? {
    headline: `Admin attention needed: ${notification.kind}`,
    section: "the relevant section",
  };
  const detail = subjectDetail(notification);
  const subject = detail
    ? `[gallr admin] ${presentation.headline}: ${detail}`
    : `[gallr admin] ${presentation.headline}`;
  const lines = detailLines(notification);
  const callToAction = `Open gallr admin → ${presentation.section}`;
  const text = [
    presentation.headline,
    "",
    ...lines,
    "",
    `${callToAction}: ${ADMIN_PORTAL_URL}`,
  ].join("\n");
  const html = [
    `<p><strong>${escapeHtml(presentation.headline)}</strong></p>`,
    `<ul>${lines.map((line) => `<li>${escapeHtml(line)}</li>`).join("")}</ul>`,
    `<p><a href="${ADMIN_PORTAL_URL}">${escapeHtml(callToAction)}</a></p>`,
  ].join("");
  return { subject, text, html };
}
