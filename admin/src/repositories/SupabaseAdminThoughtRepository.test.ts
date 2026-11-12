import type { SupabaseClient } from "@supabase/supabase-js";
import {
  MalformedAdminThoughtPayloadError,
  ThoughtInvalidTransitionError,
  ThoughtModerationNotStaffError,
  ThoughtNotFoundError,
  ThoughtReviewOwnError,
  ThoughtReviewStaleError,
} from "./AdminThoughtRepository";
import { SupabaseAdminThoughtRepository } from "./SupabaseAdminThoughtRepository";

const THOUGHT_ID = "8a1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

const wirePending = {
  id: THOUGHT_ID,
  content: "빛이 천천히 움직이는 방이 오래 남았어요.",
  exhibition_id: "garden-of-light",
  exhibition_name_ko: "빛의 정원",
  exhibition_name_en: "Garden of Light",
  author_display_name: "hanshin",
  requested_at: "2026-11-12T01:00:00Z",
  created_at: "2026-11-10T09:00:00Z",
  was_approved_before: false,
};

const wireReported = {
  id: THOUGHT_ID,
  content: "빛이 천천히 움직이는 방이 오래 남았어요.",
  exhibition_id: "garden-of-light",
  exhibition_name_ko: null,
  exhibition_name_en: null,
  author_display_name: "hanshin",
  approved_at: "2026-11-11T03:00:00Z",
  open_count: 3,
  first_reported_at: "2026-11-12T02:00:00Z",
  reasons: { promotional: 2, other: 1 },
};

function createClient(result: { data: unknown; error: unknown }) {
  const rpc = vi.fn().mockResolvedValue(result);
  return { client: { rpc } as unknown as SupabaseClient, rpc };
}

function repositoryReturning(data: unknown) {
  return new SupabaseAdminThoughtRepository(createClient({ data, error: null }).client);
}

describe("SupabaseAdminThoughtRepository", () => {
  it("lists the review queue through list_thought_review_queue", async () => {
    const { client, rpc } = createClient({ data: [wirePending], error: null });

    await expect(new SupabaseAdminThoughtRepository(client).listPending()).resolves.toEqual([
      {
        id: THOUGHT_ID,
        content: "빛이 천천히 움직이는 방이 오래 남았어요.",
        exhibitionId: "garden-of-light",
        exhibitionNameKo: "빛의 정원",
        exhibitionNameEn: "Garden of Light",
        authorDisplayName: "hanshin",
        requestedAt: "2026-11-12T01:00:00Z",
        createdAt: "2026-11-10T09:00:00Z",
        wasApprovedBefore: false,
      },
    ]);
    expect(rpc).toHaveBeenCalledWith("list_thought_review_queue");
  });

  it("reads a null list as empty", async () => {
    await expect(repositoryReturning(null).listPending()).resolves.toEqual([]);
    await expect(repositoryReturning(null).listReported()).resolves.toEqual([]);
  });

  it("lists reported notes through list_reported_thoughts", async () => {
    const { client, rpc } = createClient({ data: [wireReported], error: null });

    await expect(new SupabaseAdminThoughtRepository(client).listReported()).resolves.toEqual([
      {
        id: THOUGHT_ID,
        content: "빛이 천천히 움직이는 방이 오래 남았어요.",
        exhibitionId: "garden-of-light",
        exhibitionNameKo: null,
        exhibitionNameEn: null,
        authorDisplayName: "hanshin",
        approvedAt: "2026-11-11T03:00:00Z",
        openCount: 3,
        firstReportedAt: "2026-11-12T02:00:00Z",
        reasons: { promotional: 2, other: 1 },
      },
    ]);
    expect(rpc).toHaveBeenCalledWith("list_reported_thoughts");
  });

  it("treats a blank or missing author name as none", async () => {
    const [blank] = await repositoryReturning([{ ...wirePending, author_display_name: "  " }]).listPending();
    expect(blank.authorDisplayName).toBeNull();
    const [missing] = await repositoryReturning([{ ...wireReported, author_display_name: null }]).listReported();
    expect(missing.authorDisplayName).toBeNull();
  });

  it("approves and rejects through decide_thought with the request time staff saw", async () => {
    const { client, rpc } = createClient({ data: {}, error: null });
    const repository = new SupabaseAdminThoughtRepository(client);

    await repository.decide(THOUGHT_ID, { kind: "approve" }, "2026-11-12T01:00:00Z");
    expect(rpc).toHaveBeenLastCalledWith("decide_thought", {
      p_id: THOUGHT_ID,
      p_decision: "approve",
      p_reason: null,
      p_expected_requested_at: "2026-11-12T01:00:00Z",
    });

    await repository.decide(THOUGHT_ID, { kind: "reject", reason: "promotional" }, "2026-11-12T01:00:00Z");
    expect(rpc).toHaveBeenLastCalledWith("decide_thought", {
      p_id: THOUGHT_ID,
      p_decision: "reject",
      p_reason: "promotional",
      p_expected_requested_at: "2026-11-12T01:00:00Z",
    });
  });

  it("keeps or takes down through resolve_thought_reports with the approval time staff saw", async () => {
    const { client, rpc } = createClient({ data: {}, error: null });
    const repository = new SupabaseAdminThoughtRepository(client);

    await repository.resolveReports(THOUGHT_ID, { kind: "dismissed" }, "2026-11-11T03:00:00Z");
    expect(rpc).toHaveBeenLastCalledWith("resolve_thought_reports", {
      p_id: THOUGHT_ID,
      p_resolution: "dismissed",
      p_reason: null,
      p_expected_approved_at: "2026-11-11T03:00:00Z",
    });

    await repository.resolveReports(THOUGHT_ID, { kind: "upheld", reason: "personal_information" }, "2026-11-11T03:00:00Z");
    expect(rpc).toHaveBeenLastCalledWith("resolve_thought_reports", {
      p_id: THOUGHT_ID,
      p_resolution: "upheld",
      p_reason: "personal_information",
      p_expected_approved_at: "2026-11-11T03:00:00Z",
    });
  });

  it.each([
    ["thought_not_staff", "42501", ThoughtModerationNotStaffError],
    ["thought_not_found", "PT404", ThoughtNotFoundError],
    ["thought_review_stale", "PT409", ThoughtReviewStaleError],
    ["thought_reports_stale", "PT409", ThoughtReviewStaleError],
    ["thought_review_invalid_transition", "PT409", ThoughtInvalidTransitionError],
    ["thought_no_open_reports", "PT409", ThoughtInvalidTransitionError],
    ["thought_review_own", "42501", ThoughtReviewOwnError],
  ] as const)("maps %s to its error", async (message, code, expected) => {
    const { client } = createClient({ data: null, error: { code, message } });
    const repository = new SupabaseAdminThoughtRepository(client);

    await expect(repository.listPending()).rejects.toBeInstanceOf(expected);
    await expect(repository.listReported()).rejects.toBeInstanceOf(expected);
    await expect(repository.decide(THOUGHT_ID, { kind: "approve" }, "t")).rejects.toBeInstanceOf(expected);
    await expect(repository.resolveReports(THOUGHT_ID, { kind: "dismissed" }, "t")).rejects.toBeInstanceOf(expected);
  });

  it("fails with a plain error, naming the function, when the function is missing (Admin deployed before M2)", async () => {
    const { client } = createClient({
      data: null,
      error: { code: "PGRST202", message: "Could not find the function public.list_thought_review_queue without parameters" },
    });

    const failure = new SupabaseAdminThoughtRepository(client).listPending();
    await expect(failure).rejects.toThrow("list_thought_review_queue failed (PGRST202).");
    await expect(failure).rejects.not.toBeInstanceOf(MalformedAdminThoughtPayloadError);
  });

  it("refuses a list that is not an array or an item that is not an object", async () => {
    await expect(repositoryReturning({}).listPending()).rejects.toBeInstanceOf(MalformedAdminThoughtPayloadError);
    await expect(repositoryReturning(["x"]).listReported()).rejects.toBeInstanceOf(MalformedAdminThoughtPayloadError);
  });

  it.each(Object.keys(wirePending))("refuses a queue item without %s", async (key) => {
    const { [key as keyof typeof wirePending]: _absent, ...withoutKey } = wirePending;
    const failure = repositoryReturning([withoutKey]).listPending();
    await expect(failure).rejects.toBeInstanceOf(MalformedAdminThoughtPayloadError);
  });

  it.each(Object.keys(wireReported))("refuses a reported item without %s", async (key) => {
    const { [key as keyof typeof wireReported]: _absent, ...withoutKey } = wireReported;
    const failure = repositoryReturning([withoutKey]).listReported();
    await expect(failure).rejects.toBeInstanceOf(MalformedAdminThoughtPayloadError);
  });

  it("refuses wrongly typed values", async () => {
    await expect(repositoryReturning([{ ...wirePending, content: 3 }]).listPending()).rejects.toBeInstanceOf(
      MalformedAdminThoughtPayloadError,
    );
    await expect(repositoryReturning([{ ...wirePending, was_approved_before: "no" }]).listPending()).rejects.toBeInstanceOf(
      MalformedAdminThoughtPayloadError,
    );
    await expect(repositoryReturning([{ ...wireReported, open_count: "3" }]).listReported()).rejects.toBeInstanceOf(
      MalformedAdminThoughtPayloadError,
    );
    await expect(
      repositoryReturning([{ ...wireReported, reasons: { spam: 1 } }]).listReported(),
    ).rejects.toThrow("list_reported_thoughts returned malformed data at $[0].reasons.spam");
    await expect(
      repositoryReturning([{ ...wireReported, reasons: { promotional: "2" } }]).listReported(),
    ).rejects.toBeInstanceOf(MalformedAdminThoughtPayloadError);
  });

  it("refuses unknown keys, so a server change is noticed rather than rendered", async () => {
    await expect(
      repositoryReturning([{ ...wirePending, rejection_reason: null }]).listPending(),
    ).rejects.toThrow("list_thought_review_queue returned malformed data at $[0].rejection_reason: expected no such key.");
  });

  it.each(["feeling", "tags", "visit_date", "note"])(
    "refuses any payload carrying the private %s key, so it can never be shown",
    async (key) => {
      await expect(repositoryReturning([{ ...wirePending, [key]: null }]).listPending()).rejects.toBeInstanceOf(
        MalformedAdminThoughtPayloadError,
      );
      await expect(repositoryReturning([{ ...wireReported, [key]: "loved" }]).listReported()).rejects.toBeInstanceOf(
        MalformedAdminThoughtPayloadError,
      );
    },
  );
});
