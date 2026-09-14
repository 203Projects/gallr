import type { DeliveryEvent } from "./handler.ts";
import {
  escapeHtml,
  normalizeEmail,
  type RenderedEmail,
  sanitizeMultiline,
  sanitizeSingleLine,
} from "./admin_notification.ts";

/**
 * Gallery-facing decision emails. The database enqueues one event when staff
 * accept or reject an owner-workspace exhibition submission or a gallery
 * claim. Gallery operators have no server-side language preference, so every
 * message is concise bilingual Korean and English.
 */
export type OwnerDecisionKind =
  | "submission.accepted"
  | "submission.rejected"
  | "gallery_claim.accepted"
  | "gallery_claim.rejected"
  | "owner_exhibition.published";

export const OWNER_DECISION_EVENT_TYPES: ReadonlySet<string> = new Set([
  "submission.accepted",
  "submission.rejected",
  "gallery_claim.accepted",
  "gallery_claim.rejected",
  "owner_exhibition.published",
]);

export interface OwnerDecision {
  kind: OwnerDecisionKind;
  recipientEmails: string[];
  subjectName: string;
  reviewNotes: string;
  /** Present only for publication notices. */
  publicUrl: string | null;
}

const GALLERY_PORTAL_URL = "https://gallery.gallrmap.com/";
const PUBLIC_SITE_URL = "https://gallrmap.com";
const MAX_PUBLICATION_RECIPIENTS = 50;
const MAX_NAME_LENGTH = 500;
const MAX_REVIEW_NOTES_LENGTH = 2000;

function isOwnerDecisionKind(value: string): value is OwnerDecisionKind {
  return OWNER_DECISION_EVENT_TYPES.has(value);
}

/** Only owner-workspace events carry a gallery-facing recipient. */
export function isOwnerWorkspaceEvent(event: DeliveryEvent): boolean {
  return event.payload.source === "owner_workspace";
}

/**
 * Mirrors gallery/src/publicExhibitionUrl.ts and web/scripts/lib/slug.js so
 * the emailed link matches the path the public site builds.
 */
function publicExhibitionSlug(
  id: string,
  nameEn: string,
  nameKo: string,
): string {
  const base = (nameEn || nameKo || "")
    .toLowerCase()
    .normalize("NFKC")
    .replace(/[^\p{L}\p{N}-]+/gu, " ")
    .trim()
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-");
  const suffix = id.slice(0, 4);
  return base ? `${base}-${suffix}` : suffix;
}

function parseRecipientList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const recipients = new Set<string>();
  for (const candidate of value) {
    const email = normalizeEmail(candidate);
    if (email) recipients.add(email);
    if (recipients.size === MAX_PUBLICATION_RECIPIENTS) break;
  }
  return [...recipients];
}

function parsePublication(event: DeliveryEvent): OwnerDecision | null {
  const recipientEmails = parseRecipientList(event.payload.recipient_emails);
  const id = event.payload.exhibition_id;
  const nameEn = typeof event.payload.exhibition_name_en === "string"
    ? sanitizeSingleLine(event.payload.exhibition_name_en, MAX_NAME_LENGTH)
    : "";
  const nameKo = typeof event.payload.exhibition_name_ko === "string"
    ? sanitizeSingleLine(event.payload.exhibition_name_ko, MAX_NAME_LENGTH)
    : "";
  if (
    recipientEmails.length === 0 ||
    typeof id !== "string" || id.trim().length === 0 || id.length > 200 ||
    (nameEn.length === 0 && nameKo.length === 0)
  ) return null;
  const slug = publicExhibitionSlug(id.trim(), nameEn, nameKo);
  return {
    kind: "owner_exhibition.published",
    recipientEmails,
    subjectName: nameEn || nameKo,
    reviewNotes: "",
    publicUrl: new URL(`/exhibitions/${slug}/`, PUBLIC_SITE_URL).toString(),
  };
}

export function parseOwnerDecision(event: DeliveryEvent): OwnerDecision | null {
  if (!isOwnerDecisionKind(event.event_type)) return null;
  if (!isOwnerWorkspaceEvent(event)) return null;
  if (event.event_type === "owner_exhibition.published") {
    return parsePublication(event);
  }
  const recipientEmail = normalizeEmail(event.payload.recipient_email);
  const subjectName = event.event_type.startsWith("gallery_claim.")
    ? event.payload.gallery_name
    : event.payload.exhibition_name;
  const reviewNotes = event.payload.review_notes ?? "";
  if (
    !recipientEmail ||
    typeof subjectName !== "string" ||
    subjectName.trim().length === 0 ||
    subjectName.length > MAX_NAME_LENGTH ||
    typeof reviewNotes !== "string" ||
    reviewNotes.length > MAX_REVIEW_NOTES_LENGTH
  ) return null;
  const cleanName = sanitizeSingleLine(subjectName, MAX_NAME_LENGTH);
  if (cleanName.length === 0) return null;
  return {
    kind: event.event_type,
    recipientEmails: [recipientEmail],
    subjectName: cleanName,
    reviewNotes: sanitizeMultiline(reviewNotes, MAX_REVIEW_NOTES_LENGTH),
    publicUrl: null,
  };
}

interface DecisionCopy {
  subject: string;
  korean: string;
  english: string;
  includeNotes: boolean;
}

function decisionCopy(decision: OwnerDecision): DecisionCopy {
  const name = decision.subjectName;
  switch (decision.kind) {
    case "submission.accepted":
      return {
        subject:
          `[gallr] 전시 제출 승인 / Exhibition submission accepted: ${name}`,
        korean: `제출하신 전시 “${name}”이(가) 승인되었습니다.`,
        english: `Your exhibition submission “${name}” was accepted.`,
        includeNotes: false,
      };
    case "submission.rejected":
      return {
        subject: `[gallr] 전시 수정 요청 / Changes requested: ${name}`,
        korean: `제출하신 전시 “${name}”에 수정이 필요합니다.`,
        english: `Your exhibition submission “${name}” needs changes.`,
        includeNotes: true,
      };
    case "gallery_claim.accepted":
      return {
        subject:
          `[gallr] 갤러리 소유권 신청 승인 / Gallery claim approved: ${name}`,
        korean: `“${name}” 갤러리 소유권 신청이 승인되었습니다.`,
        english: `Your claim for the gallery “${name}” was approved.`,
        includeNotes: false,
      };
    case "gallery_claim.rejected":
      return {
        subject:
          `[gallr] 갤러리 소유권 신청 거절 / Gallery claim rejected: ${name}`,
        korean: `“${name}” 갤러리 소유권 신청이 거절되었습니다.`,
        english: `Your claim for the gallery “${name}” was rejected.`,
        includeNotes: true,
      };
    case "owner_exhibition.published":
      return {
        subject:
          `[gallr] 전시가 게시되었습니다 / Your exhibition is live: ${name}`,
        korean:
          `“${name}” 전시가 게시되었습니다. 공개 페이지는 몇 분 안에 열립니다.`,
        english:
          `Your exhibition “${name}” is now published. The public page opens within a few minutes.`,
        includeNotes: false,
      };
  }
}

export function renderOwnerDecisionEmail(
  decision: OwnerDecision,
): RenderedEmail {
  const copy = decisionCopy(decision);
  const notes = copy.includeNotes && decision.reviewNotes
    ? decision.reviewNotes
    : null;
  const callToAction = "갤러리 워크스페이스 열기 / Open your gallery workspace";
  const publicLabel = "공개 페이지 보기 / View the public page";
  const text = [
    "안녕하세요, / Hello,",
    "",
    copy.korean,
    copy.english,
    ...(notes ? ["", "검토 의견 / Review notes:", notes] : []),
    ...(decision.publicUrl
      ? ["", `${publicLabel}: ${decision.publicUrl}`]
      : []),
    "",
    `${callToAction}: ${GALLERY_PORTAL_URL}`,
  ].join("\n");
  const html = [
    "<p>안녕하세요, / Hello,</p>",
    `<p>${escapeHtml(copy.korean)}<br>${escapeHtml(copy.english)}</p>`,
    ...(notes
      ? [
        "<h2>검토 의견 / Review notes</h2>",
        `<p>${escapeHtml(notes).replaceAll("\n", "<br>")}</p>`,
      ]
      : []),
    ...(decision.publicUrl
      ? [
        `<p><a href="${escapeHtml(decision.publicUrl)}">${
          escapeHtml(publicLabel)
        }</a></p>`,
      ]
      : []),
    `<p><a href="${GALLERY_PORTAL_URL}">${escapeHtml(callToAction)}</a></p>`,
  ].join("");
  return { subject: copy.subject, text, html };
}
