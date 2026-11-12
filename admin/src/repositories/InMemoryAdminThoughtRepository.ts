import {
  ThoughtInvalidTransitionError,
  ThoughtNotFoundError,
  ThoughtReviewOwnError,
  ThoughtReviewStaleError,
  type AdminThoughtRepository,
  type PendingThought,
  type ReportedThought,
  type ThoughtDecision,
  type ThoughtRejectionReason,
  type ThoughtReportDecision,
  type ThoughtReportReason,
} from "./AdminThoughtRepository";

/** A 감상 as the in-memory repository holds it, with the moderation columns the database keeps. */
export interface InMemoryThought {
  id: string;
  content: string;
  exhibitionId: string;
  exhibitionNameKo: string | null;
  exhibitionNameEn: string | null;
  authorDisplayName: string | null;
  status: "pending" | "approved" | "rejected";
  rejectionReason: ThoughtRejectionReason | null;
  requestedAt: string;
  createdAt: string;
  approvedAt: string | null;
  wasApprovedBefore: boolean;
  /** The signed-in staff member wrote it; the database refuses their decisions on it (`thought_review_own`). */
  ownedByReviewer?: boolean;
}

/** An open report held by the in-memory repository. */
export interface InMemoryThoughtReport {
  thoughtId: string;
  reason: ThoughtReportReason;
  createdAt: string;
}

/** Fixture-mode and test stand-in for the staff 감상 review functions; same ordering, staleness and transitions. */
export class InMemoryAdminThoughtRepository implements AdminThoughtRepository {
  private readonly thoughts: Map<string, InMemoryThought>;
  private reports: InMemoryThoughtReport[];

  constructor(
    thoughts: InMemoryThought[],
    private readonly now: () => string = () => new Date().toISOString(),
    reports: InMemoryThoughtReport[] = [],
  ) {
    this.thoughts = new Map(thoughts.map((thought) => [thought.id, structuredClone(thought)]));
    this.reports = structuredClone(reports);
  }

  async listPending(): Promise<PendingThought[]> {
    return [...this.thoughts.values()]
      .filter((thought) => thought.status === "pending")
      .sort((a, b) => a.requestedAt.localeCompare(b.requestedAt))
      .map((thought) => ({
        id: thought.id,
        content: thought.content,
        exhibitionId: thought.exhibitionId,
        exhibitionNameKo: thought.exhibitionNameKo,
        exhibitionNameEn: thought.exhibitionNameEn,
        authorDisplayName: thought.authorDisplayName,
        requestedAt: thought.requestedAt,
        createdAt: thought.createdAt,
        wasApprovedBefore: thought.wasApprovedBefore,
      }));
  }

  async decide(id: string, decision: ThoughtDecision, expectedRequestedAt: string): Promise<void> {
    const thought = this.find(id);
    if (thought.ownedByReviewer) throw new ThoughtReviewOwnError();
    if (thought.status !== "pending") throw new ThoughtInvalidTransitionError();
    if (thought.requestedAt !== expectedRequestedAt) throw new ThoughtReviewStaleError();
    if (decision.kind === "approve") {
      thought.status = "approved";
      thought.approvedAt = this.now();
      thought.rejectionReason = null;
    } else {
      thought.status = "rejected";
      thought.rejectionReason = decision.reason;
    }
  }

  async listReported(): Promise<ReportedThought[]> {
    const byThought = new Map<string, InMemoryThoughtReport[]>();
    for (const report of this.reports) {
      byThought.set(report.thoughtId, [...(byThought.get(report.thoughtId) ?? []), report]);
    }
    return [...byThought.entries()]
      .map(([id, reports]) => ({ thought: this.find(id), reports }))
      .filter(({ thought }) => thought.status === "approved" && thought.approvedAt !== null)
      .map(({ thought, reports }) => {
        const reasons: Partial<Record<ThoughtReportReason, number>> = {};
        for (const report of reports) reasons[report.reason] = (reasons[report.reason] ?? 0) + 1;
        return {
          id: thought.id,
          content: thought.content,
          exhibitionId: thought.exhibitionId,
          exhibitionNameKo: thought.exhibitionNameKo,
          exhibitionNameEn: thought.exhibitionNameEn,
          authorDisplayName: thought.authorDisplayName,
          approvedAt: thought.approvedAt ?? "",
          openCount: reports.length,
          firstReportedAt: reports.map((report) => report.createdAt).sort()[0],
          reasons,
        };
      })
      .sort((a, b) => a.firstReportedAt.localeCompare(b.firstReportedAt));
  }

  async resolveReports(id: string, resolution: ThoughtReportDecision, expectedApprovedAt: string): Promise<void> {
    const thought = this.find(id);
    if (thought.ownedByReviewer) throw new ThoughtReviewOwnError();
    if (thought.status !== "approved" || !this.reports.some((report) => report.thoughtId === id)) {
      throw new ThoughtInvalidTransitionError();
    }
    if (thought.approvedAt !== expectedApprovedAt) throw new ThoughtReviewStaleError();
    this.reports = this.reports.filter((report) => report.thoughtId !== id);
    if (resolution.kind === "upheld") {
      thought.status = "rejected";
      thought.rejectionReason = resolution.reason;
    }
  }

  /**
   * Test and fixture seam: the author edits the text, which sends it (back) to review with a new request time, as the
   * database's trigger does. Not a moderation action.
   */
  edit(id: string, content: string): void {
    const thought = this.find(id);
    thought.wasApprovedBefore = thought.wasApprovedBefore || thought.approvedAt !== null;
    Object.assign(thought, { content, status: "pending", rejectionReason: null, approvedAt: null, requestedAt: this.now() });
  }

  /** Test seam: the stored row, moderation columns included. */
  snapshot(id: string): InMemoryThought {
    return structuredClone(this.find(id));
  }

  private find(id: string): InMemoryThought {
    const thought = this.thoughts.get(id);
    if (!thought) throw new ThoughtNotFoundError();
    return thought;
  }
}
