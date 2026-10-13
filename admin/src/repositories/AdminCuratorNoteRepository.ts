/** Curator notes as staff review them (spec 090 US1, US4). */
export type CuratorNoteStatus = "draft" | "unusable" | "replaced" | "declined" | "approved" | "superseded";
export type CuratorNoteDeclineReason = "not_insightful" | "wrong_facts" | "tone" | "exhibition_not_suitable" | "other";
export type CuratorNoteRequestKind = "scheduled" | "single" | "regenerate";
export type CuratorNoteLanguage = "ko" | "en";

/** One insight paragraph and the packet keys it draws on (FR-003). */
export interface CuratorNoteInsight {
  text: string;
  grounding: string[];
}

export interface CuratorNoteVersion {
  id: string;
  exhibitionId: string;
  versionNumber: number;
  status: CuratorNoteStatus;
  hookKo: string;
  hookEn: string;
  insightsKo: CuratorNoteInsight[];
  insightsEn: CuratorNoteInsight[];
  tipKo: string;
  tipEn: string;
  /** Sentence references (`<lang>:<section>:<index>`) an editor marked unsupported; approval is refused while any remain. */
  unsupportedRefs: string[];
  edited: boolean;
  producer: string;
  model: string;
  promptVersion: string;
  sourceFields: string[];
  sourceHash: string;
  requestKind: CuratorNoteRequestKind;
  generatedAt: string;
  rejectionCode: string | null;
  reviewerNameKo: string | null;
  reviewerNameEn: string | null;
  decidedAt: string | null;
  declineReason: CuratorNoteDeclineReason | null;
  declineNote: string | null;
  approvedAt: string | null;
  /** The version staff see; every write names it and is refused once it changed. */
  revision: string;
  /**
   * The exhibition's published text no longer matches `sourceHash` (or the exhibition left the catalogue), so the
   * database refuses approval; editing, marking and declining still work.
   */
  stale: boolean;
}

/** A draft waiting for review, featured exhibitions first, then oldest first. */
export interface CuratorNoteQueueItem {
  versionId: string;
  exhibitionId: string;
  nameKo: string;
  nameEn: string;
  isFeatured: boolean;
  closingDate: string | null;
  versionNumber: number;
  promptVersion: string;
  generatedAt: string;
  requestKind: CuratorNoteRequestKind;
  unsupportedCount: number;
  edited: boolean;
  revision: string;
  /** Same meaning as `CuratorNoteVersion.stale`. */
  stale: boolean;
}

/** Everything about one exhibition's notes: the facts packet beside every version. */
export interface CuratorNoteFacts {
  exhibitionId: string;
  autoDraftBlocked: boolean;
  approvedVersionId: string | null;
  qualifies: boolean;
  /** The published fields, artists and terms a draft may cite; null when the exhibition left the catalogue. */
  packet: Record<string, unknown> | null;
  packetHash: string | null;
  /** Newest first. */
  versions: CuratorNoteVersion[];
}

/** Text fields an editor may change on a draft. */
export interface CuratorNoteEdit {
  hookKo?: string;
  hookEn?: string;
  tipKo?: string;
  tipEn?: string;
  insightsKo?: CuratorNoteInsight[];
  insightsEn?: CuratorNoteInsight[];
}

/** The drafting function's answer to an on-demand request. */
export interface CuratorNoteDraftRequestResult {
  outcome: "drafted" | "unusable";
  code: string | null;
}

/**
 * Staff review of curator notes. Every call is checked against the staff membership by the database; the
 * drafting request goes to the Edge Function with the staff member's session.
 */
export interface AdminCuratorNoteRepository {
  listQueue(): Promise<CuratorNoteQueueItem[]>;
  get(exhibitionId: string): Promise<CuratorNoteFacts>;
  saveEdit(versionId: string, edit: CuratorNoteEdit, expectedRevision: string): Promise<CuratorNoteVersion>;
  markSentence(versionId: string, ref: string, unsupported: boolean, expectedRevision: string): Promise<CuratorNoteVersion>;
  approve(versionId: string, expectedRevision: string): Promise<CuratorNoteVersion>;
  decline(
    versionId: string,
    reason: CuratorNoteDeclineReason,
    note: string | null,
    expectedRevision: string,
  ): Promise<CuratorNoteVersion>;
  requestDraft(exhibitionId: string, kind: "single" | "regenerate"): Promise<CuratorNoteDraftRequestResult>;
  recordExport(versionId: string, language: CuratorNoteLanguage, slideCount: number): Promise<CuratorNoteVersion>;
}

/** The caller is not an active publisher. */
export class CuratorNoteNotStaffError extends Error {
  constructor() {
    super("Only active publishers can review curator notes.");
    this.name = "CuratorNoteNotStaffError";
  }
}

/** Another editor changed the version after it was loaded; reload and review again. */
export class CuratorNoteStaleError extends Error {
  constructor() {
    super("The note changed since it was loaded.");
    this.name = "CuratorNoteStaleError";
  }
}

/** The exhibition's text changed after the draft was written; the draft cannot be approved, only replaced. */
export class CuratorNoteSourceChangedError extends Error {
  constructor() {
    super("The exhibition's text changed after this draft was written.");
    this.name = "CuratorNoteSourceChangedError";
  }
}

/** A sentence is still marked unsupported. */
export class CuratorNoteUnsupportedError extends Error {
  constructor() {
    super("An unsupported sentence blocks approval.");
    this.name = "CuratorNoteUnsupportedError";
  }
}

/** The action is not allowed from the version's status. */
export class CuratorNoteInvalidTransitionError extends Error {
  constructor() {
    super("The note is no longer in a state that allows this.");
    this.name = "CuratorNoteInvalidTransitionError";
  }
}

export class CuratorNoteNotFoundError extends Error {
  constructor() {
    super("No such note or exhibition.");
    this.name = "CuratorNoteNotFoundError";
  }
}

/** The exhibition does not qualify for a draft (no description, cover, dates or it has ended). */
export class CuratorNoteNotQualifyingError extends Error {
  constructor() {
    super("The exhibition does not qualify for a curator note.");
    this.name = "CuratorNoteNotQualifyingError";
  }
}

/** The drafting function or its provider is unavailable; the queue and existing drafts still work. */
export class CuratorNoteDraftingUnavailableError extends Error {
  constructor() {
    super("Drafting is unavailable right now.");
    this.name = "CuratorNoteDraftingUnavailableError";
  }
}

export class MalformedCuratorNotePayloadError extends Error {
  constructor(rpcName: string, path: string, expected: string) {
    super(`${rpcName} returned ${path} that is not ${expected}.`);
    this.name = "MalformedCuratorNotePayloadError";
  }
}

export const CURATOR_NOTE_DECLINE_REASONS: readonly CuratorNoteDeclineReason[] = [
  "not_insightful",
  "wrong_facts",
  "tone",
  "exhibition_not_suitable",
  "other",
];

/** A sentence reference as the database stores it. */
export function sentenceRef(language: CuratorNoteLanguage, section: "hook" | "insight" | "tip", index: number): string {
  return `${language}:${section}:${index}`;
}
