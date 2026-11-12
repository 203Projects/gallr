/**
 * Staff review of 감상, the visitor notes published from the diary (spec 103 US5). Only the text asked for
 * publication, its author, its exhibition and times reach Admin: a visit's feeling, tags, date and unpublished note
 * never do (FR-026).
 */

export type ThoughtRejectionReason =
  | "not_about_exhibition"
  | "promotional"
  | "inappropriate_language"
  | "personal_information"
  | "other";

export type ThoughtReportReason = "inappropriate" | "promotional" | "not_about_exhibition" | "personal_information" | "other";

export type ThoughtReportResolution = "dismissed" | "upheld";

export const THOUGHT_REJECTION_REASONS: readonly ThoughtRejectionReason[] = [
  "not_about_exhibition",
  "promotional",
  "inappropriate_language",
  "personal_information",
  "other",
];

export const THOUGHT_REPORT_REASONS: readonly ThoughtReportReason[] = [
  "inappropriate",
  "promotional",
  "not_about_exhibition",
  "personal_information",
  "other",
];

/** A 감상 waiting for a first decision, or for another after its author edited it. */
export interface PendingThought {
  id: string;
  content: string;
  exhibitionId: string;
  /** Null when the exhibition is no longer in the catalogue. */
  exhibitionNameKo: string | null;
  exhibitionNameEn: string | null;
  /** Null when the author has no display name. */
  authorDisplayName: string | null;
  /** When the current text was asked for review; a decision names it and is refused once the text changed. */
  requestedAt: string;
  createdAt: string;
  /** True when an earlier text was approved and the author edited it ("수정 후 재요청"). */
  wasApprovedBefore: boolean;
}

/** A public 감상 with open reports. */
export interface ReportedThought {
  id: string;
  content: string;
  exhibitionId: string;
  exhibitionNameKo: string | null;
  exhibitionNameEn: string | null;
  authorDisplayName: string | null;
  /** When the current text was approved; resolving names it and is refused once the text changed. */
  approvedAt: string;
  openCount: number;
  /** The oldest open report; staff decide within 48 hours of it (owner decision 6). */
  firstReportedAt: string;
  reasons: Partial<Record<ThoughtReportReason, number>>;
}

export type ThoughtDecision = { kind: "approve" } | { kind: "reject"; reason: ThoughtRejectionReason };

export type ThoughtReportDecision = { kind: "dismissed" } | { kind: "upheld"; reason: ThoughtRejectionReason };

/** Every call is checked against the staff membership (`publisher`, which `admin` satisfies) by the database. */
export interface AdminThoughtRepository {
  /** Pending 감상, oldest request first. */
  listPending(): Promise<PendingThought[]>;
  decide(id: string, decision: ThoughtDecision, expectedRequestedAt: string): Promise<void>;
  /** Public 감상 with open reports, oldest open report first (owner decision 9). */
  listReported(): Promise<ReportedThought[]>;
  resolveReports(id: string, resolution: ThoughtReportDecision, expectedApprovedAt: string): Promise<void>;
}

export class ThoughtModerationNotStaffError extends Error {
  constructor() {
    super("Only publishers and admins can review visitor notes.");
    this.name = "ThoughtModerationNotStaffError";
  }
}

export class ThoughtNotFoundError extends Error {
  constructor() {
    super("No visitor note matches.");
    this.name = "ThoughtNotFoundError";
  }
}

/** The author changed the text after staff loaded it; the lists must be read again. */
export class ThoughtReviewStaleError extends Error {
  constructor() {
    super("The note changed since it was loaded.");
    this.name = "ThoughtReviewStaleError";
  }
}

/** The 감상 is no longer in the state the action applies to: another decision came first. */
export class ThoughtInvalidTransitionError extends Error {
  constructor() {
    super("The note's review state does not allow this action.");
    this.name = "ThoughtInvalidTransitionError";
  }
}

/**
 * The signed-in staff member wrote this 감상: nobody decides on their own text, so another staff member has to. The
 * lists do not say whose a 감상 is, so this arrives as the server's refusal (`thought_review_own`).
 */
export class ThoughtReviewOwnError extends Error {
  constructor() {
    super("Another staff member has to decide on your own note.");
    this.name = "ThoughtReviewOwnError";
  }
}

export class MalformedAdminThoughtPayloadError extends Error {
  constructor(rpcName: string, path: string, expected: string) {
    super(`${rpcName} returned malformed data at ${path}: expected ${expected}.`);
    this.name = "MalformedAdminThoughtPayloadError";
  }
}
