import {
  ThoughtInvalidTransitionError,
  ThoughtNotFoundError,
  ThoughtReviewOwnError,
  ThoughtReviewStaleError,
} from "./AdminThoughtRepository";
import { InMemoryAdminThoughtRepository, type InMemoryThought } from "./InMemoryAdminThoughtRepository";

const NOW = "2026-11-12T06:00:00Z";

function thought(id: string, overrides: Partial<InMemoryThought> = {}): InMemoryThought {
  return {
    id,
    content: `감상 ${id}`,
    exhibitionId: "garden-of-light",
    exhibitionNameKo: "빛의 정원",
    exhibitionNameEn: "Garden of Light",
    authorDisplayName: "hanshin",
    status: "pending",
    rejectionReason: null,
    requestedAt: "2026-11-12T01:00:00Z",
    createdAt: "2026-11-10T09:00:00Z",
    approvedAt: null,
    wasApprovedBefore: false,
    ...overrides,
  };
}

function repository() {
  return new InMemoryAdminThoughtRepository(
    [
      thought("later", { requestedAt: "2026-11-12T05:00:00Z" }),
      thought("earlier", { requestedAt: "2026-11-12T02:00:00Z" }),
      thought("public-new", { status: "approved", approvedAt: "2026-11-11T00:00:00Z" }),
      thought("public-old", { status: "approved", approvedAt: "2026-11-10T00:00:00Z" }),
      thought("public-quiet", { status: "approved", approvedAt: "2026-11-10T00:00:00Z" }),
      thought("rejected", { status: "rejected", rejectionReason: "other" }),
    ],
    () => NOW,
    [
      { thoughtId: "public-new", reason: "promotional", createdAt: "2026-11-12T01:00:00Z" },
      { thoughtId: "public-old", reason: "promotional", createdAt: "2026-11-11T08:00:00Z" },
      { thoughtId: "public-new", reason: "other", createdAt: "2026-11-12T04:00:00Z" },
      { thoughtId: "public-old", reason: "inappropriate", createdAt: "2026-11-12T03:00:00Z" },
      { thoughtId: "public-old", reason: "promotional", createdAt: "2026-11-12T04:30:00Z" },
    ],
  );
}

describe("InMemoryAdminThoughtRepository", () => {
  it("lists pending notes oldest request first", async () => {
    const pending = await repository().listPending();

    expect(pending.map((item) => item.id)).toEqual(["earlier", "later"]);
    expect(pending[0]).toEqual({
      id: "earlier",
      content: "감상 earlier",
      exhibitionId: "garden-of-light",
      exhibitionNameKo: "빛의 정원",
      exhibitionNameEn: "Garden of Light",
      authorDisplayName: "hanshin",
      requestedAt: "2026-11-12T02:00:00Z",
      createdAt: "2026-11-10T09:00:00Z",
      wasApprovedBefore: false,
    });
  });

  it("lists reported notes by their oldest open report, oldest first (owner decision 9)", async () => {
    const reported = await repository().listReported();

    expect(reported.map((item) => item.id)).toEqual(["public-old", "public-new"]);
    expect(reported[0]).toMatchObject({
      openCount: 3,
      firstReportedAt: "2026-11-11T08:00:00Z",
      approvedAt: "2026-11-10T00:00:00Z",
      reasons: { promotional: 2, inappropriate: 1 },
    });
  });

  it("approves a pending note, which leaves the queue and becomes public", async () => {
    const review = repository();

    await review.decide("earlier", { kind: "approve" }, "2026-11-12T02:00:00Z");

    expect((await review.listPending()).map((item) => item.id)).toEqual(["later"]);
    expect(review.snapshot("earlier")).toMatchObject({ status: "approved", approvedAt: NOW, rejectionReason: null });
  });

  it("rejects a pending note with its reason", async () => {
    const review = repository();

    await review.decide("earlier", { kind: "reject", reason: "promotional" }, "2026-11-12T02:00:00Z");

    expect(review.snapshot("earlier")).toMatchObject({ status: "rejected", rejectionReason: "promotional" });
  });

  it("refuses a decision on text the author changed since it was read", async () => {
    const review = repository();
    review.edit("earlier", "새로 고친 감상");

    await expect(review.decide("earlier", { kind: "approve" }, "2026-11-12T02:00:00Z")).rejects.toBeInstanceOf(
      ThoughtReviewStaleError,
    );
    expect(review.snapshot("earlier").status).toBe("pending");
  });

  it("sends an edited approved note back to review, marked as asked again", async () => {
    const review = repository();
    review.edit("public-quiet", "고친 감상");

    const [edited] = (await review.listPending()).filter((item) => item.id === "public-quiet");
    expect(edited).toMatchObject({ content: "고친 감상", requestedAt: NOW, wasApprovedBefore: true });
  });

  it("refuses to decide a note that is no longer pending, or does not exist", async () => {
    const review = repository();

    await expect(review.decide("public-new", { kind: "approve" }, "2026-11-12T01:00:00Z")).rejects.toBeInstanceOf(
      ThoughtInvalidTransitionError,
    );
    await expect(review.decide("missing", { kind: "approve" }, "x")).rejects.toBeInstanceOf(ThoughtNotFoundError);
  });

  it("keeps a reported note public and closes its reports", async () => {
    const review = repository();

    await review.resolveReports("public-old", { kind: "dismissed" }, "2026-11-10T00:00:00Z");

    expect((await review.listReported()).map((item) => item.id)).toEqual(["public-new"]);
    expect(review.snapshot("public-old").status).toBe("approved");
  });

  it("takes a reported note down with a reason and closes its reports", async () => {
    const review = repository();

    await review.resolveReports("public-old", { kind: "upheld", reason: "promotional" }, "2026-11-10T00:00:00Z");

    expect((await review.listReported()).map((item) => item.id)).toEqual(["public-new"]);
    expect(review.snapshot("public-old")).toMatchObject({ status: "rejected", rejectionReason: "promotional" });
  });

  it("refuses to resolve reports on text approved after staff read it, or with none open", async () => {
    const review = repository();

    await expect(
      review.resolveReports("public-old", { kind: "dismissed" }, "2026-11-09T00:00:00Z"),
    ).rejects.toBeInstanceOf(ThoughtReviewStaleError);
    await expect(
      review.resolveReports("public-quiet", { kind: "dismissed" }, "2026-11-10T00:00:00Z"),
    ).rejects.toBeInstanceOf(ThoughtInvalidTransitionError);
    await expect(review.resolveReports("missing", { kind: "dismissed" }, "x")).rejects.toBeInstanceOf(
      ThoughtNotFoundError,
    );
  });

  it("refuses a decision on the reviewer's own note, before any other check, and changes nothing", async () => {
    const review = new InMemoryAdminThoughtRepository(
      [
        thought("mine", { ownedByReviewer: true }),
        thought("mine-public", { ownedByReviewer: true, status: "approved", approvedAt: "2026-11-11T00:00:00Z" }),
      ],
      () => NOW,
      [{ thoughtId: "mine-public", reason: "promotional", createdAt: "2026-11-12T01:00:00Z" }],
    );

    await expect(review.decide("mine", { kind: "approve" }, "2026-11-12T01:00:00Z")).rejects.toBeInstanceOf(
      ThoughtReviewOwnError,
    );
    await expect(review.decide("mine", { kind: "approve" }, "stale")).rejects.toBeInstanceOf(ThoughtReviewOwnError);
    await expect(
      review.resolveReports("mine-public", { kind: "dismissed" }, "2026-11-11T00:00:00Z"),
    ).rejects.toBeInstanceOf(ThoughtReviewOwnError);
    expect(review.snapshot("mine").status).toBe("pending");
    expect((await review.listReported()).map((item) => item.id)).toEqual(["mine-public"]);
  });
});
