import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import {
  ThoughtModerationNotStaffError,
  type AdminThoughtRepository,
  type PendingThought,
  type ReportedThought,
} from "../repositories/AdminThoughtRepository";
import { InMemoryAdminThoughtRepository, type InMemoryThought } from "../repositories/InMemoryAdminThoughtRepository";
import { ThoughtModerationWorkspace } from "./ThoughtModerationWorkspace";

const NOW = new Date("2026-11-12T06:00:00Z");

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
      thought("newer", { content: "나중에 요청한 감상", requestedAt: "2026-11-12T05:00:00Z", wasApprovedBefore: true }),
      thought("older", {
        content: "먼저 요청한 감상",
        requestedAt: "2026-11-12T02:00:00Z",
        exhibitionNameKo: null,
        exhibitionNameEn: null,
      }),
      thought("recent-report", { content: "최근 신고된 감상", status: "approved", approvedAt: "2026-11-11T00:00:00Z" }),
      thought("overdue-report", { content: "오래된 신고 감상", status: "approved", approvedAt: "2026-11-09T00:00:00Z" }),
    ],
    () => "2026-11-12T06:30:00Z",
    [
      { thoughtId: "recent-report", reason: "promotional", createdAt: "2026-11-11T08:00:00Z" },
      { thoughtId: "recent-report", reason: "promotional", createdAt: "2026-11-12T01:00:00Z" },
      { thoughtId: "recent-report", reason: "personal_information", createdAt: "2026-11-12T02:00:00Z" },
      { thoughtId: "overdue-report", reason: "inappropriate", createdAt: "2026-11-10T05:00:00Z" },
    ],
  );
}

function renderWorkspace(review: AdminThoughtRepository = repository(), locale: "en" | "ko" = "en") {
  render(
    <LocaleProvider initialLocale={locale} storage={null}>
      <ThoughtModerationWorkspace repository={review} now={() => NOW} />
    </LocaleProvider>,
  );
  return { review, user: userEvent.setup() };
}

function rows(listName: string): string[] {
  return within(screen.getByRole("list", { name: listName }))
    .getAllByRole("button")
    .map((row) => row.textContent ?? "");
}

describe("ThoughtModerationWorkspace", () => {
  it("lists pending notes oldest request first, with counts on both tabs", async () => {
    renderWorkspace();

    expect(await screen.findByRole("tab", { name: "Pending (2)", selected: true })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Reported (2)" })).toBeInTheDocument();
    const pending = rows("Pending");
    expect(pending[0]).toContain("먼저 요청한 감상");
    expect(pending[1]).toContain("나중에 요청한 감상");
    expect(pending[1]).toContain("Edited, asked again");
  });

  it("shows the selected note in full beside the list, with author, exhibition and request time", async () => {
    renderWorkspace();

    const pane = await screen.findByRole("region", { name: "Selected note" });
    expect(within(pane).getByText("먼저 요청한 감상")).toBeInTheDocument();
    expect(within(pane).getByText("hanshin")).toBeInTheDocument();
    expect(within(pane).getByText("Exhibition unavailable")).toBeInTheDocument();
    expect(within(pane).getByText(/^Asked /)).toBeInTheDocument();
  });

  it("lists reported notes oldest open report first, with counts, reasons and the 48-hour clock", async () => {
    const { user } = renderWorkspace();

    await user.click(await screen.findByRole("tab", { name: "Reported (2)" }));

    const reported = rows("Reported");
    expect(reported[0]).toContain("오래된 신고 감상");
    expect(reported[0]).toContain("Over 48 hours");
    expect(reported[1]).toContain("최근 신고된 감상");
    // The oldest open report on it was sent 22 hours before NOW.
    expect(reported[1]).toContain("26h left");

    await user.click(screen.getByRole("button", { name: /최근 신고된 감상/ }));
    const pane = screen.getByRole("region", { name: "Selected note" });
    expect(within(pane).getByText("3 reports")).toBeInTheDocument();
    expect(within(pane).getByText("Promotional 2 · Personal information 1")).toBeInTheDocument();
    expect(within(pane).getByText(/^First report /)).toBeInTheDocument();
  });

  it("approves the selected note, which leaves the queue", async () => {
    const { review, user } = renderWorkspace();

    await user.click(await screen.findByRole("button", { name: "Approve" }));

    expect(await screen.findByRole("tab", { name: "Pending (1)" })).toBeInTheDocument();
    expect(rows("Pending").join()).not.toContain("먼저 요청한 감상");
    expect(review instanceof InMemoryAdminThoughtRepository && review.snapshot("older").status).toBe("approved");
  });

  it("requires one reason before rejecting", async () => {
    const { review, user } = renderWorkspace();

    await user.click(await screen.findByRole("button", { name: "Reject" }));
    const dialog = screen.getByRole("dialog", { name: "Reject this note?" });
    const confirm = within(dialog).getByRole("button", { name: "Reject" });
    expect(confirm).toBeDisabled();
    expect(within(dialog).getAllByRole("radio")).toHaveLength(5);

    await user.click(within(dialog).getByRole("radio", { name: "Promotional" }));
    await user.click(confirm);

    expect(await screen.findByRole("tab", { name: "Pending (1)" })).toBeInTheDocument();
    expect(review instanceof InMemoryAdminThoughtRepository && review.snapshot("older")).toMatchObject({
      status: "rejected",
      rejectionReason: "promotional",
    });
  });

  it("keeps a reported note, closing its reports", async () => {
    const { review, user } = renderWorkspace();

    await user.click(await screen.findByRole("tab", { name: "Reported (2)" }));
    await user.click(screen.getByRole("button", { name: "Keep" }));

    expect(await screen.findByRole("tab", { name: "Reported (1)", selected: true })).toBeInTheDocument();
    expect(review instanceof InMemoryAdminThoughtRepository && review.snapshot("overdue-report").status).toBe("approved");
  });

  it("requires one reason before taking a reported note down", async () => {
    const { review, user } = renderWorkspace();

    await user.click(await screen.findByRole("tab", { name: "Reported (2)" }));
    await user.click(screen.getByRole("button", { name: "Take down" }));
    const dialog = screen.getByRole("dialog", { name: "Take this note down?" });
    const confirm = within(dialog).getByRole("button", { name: "Take down" });
    expect(confirm).toBeDisabled();

    await user.click(within(dialog).getByRole("radio", { name: "Inappropriate language" }));
    await user.click(confirm);

    expect(await screen.findByRole("tab", { name: "Reported (1)" })).toBeInTheDocument();
    expect(review instanceof InMemoryAdminThoughtRepository && review.snapshot("overdue-report")).toMatchObject({
      status: "rejected",
      rejectionReason: "inappropriate_language",
    });
  });

  it("refreshes the list and says so when the author changed the text", async () => {
    const review = repository();
    const { user } = renderWorkspace(review);
    await screen.findByRole("tab", { name: "Pending (2)" });

    review.edit("older", "작성자가 고친 감상");
    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByText("The author changed the text. The list was refreshed.")).toBeInTheDocument();
    expect(rows("Pending").join()).toContain("작성자가 고친 감상");
    expect(review.snapshot("older").status).toBe("pending");
  });

  it("shows a load failure with a retry instead of crashing (Admin deployed before the functions exist)", async () => {
    const review = repository();
    const listPending = vi.spyOn(review, "listPending").mockRejectedValueOnce(new Error("list_thought_review_queue failed (PGRST202)."));
    const { user } = renderWorkspace(review);

    expect(await screen.findByText("Couldn't load notes.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByRole("tab", { name: "Pending (2)" })).toBeInTheDocument();
    expect(screen.queryByText("Couldn't load notes.")).not.toBeInTheDocument();
    expect(listPending).toHaveBeenCalledTimes(2);
  });

  it("says when the account may not review notes", async () => {
    const review = repository();
    vi.spyOn(review, "listPending").mockRejectedValue(new ThoughtModerationNotStaffError());
    renderWorkspace(review);

    expect(await screen.findByText("You don't have permission to review notes.")).toBeInTheDocument();
  });

  it("speaks Korean", async () => {
    const { user } = renderWorkspace(repository(), "ko");

    expect(await screen.findByRole("tab", { name: "검토 대기 (2)" })).toBeInTheDocument();
    expect(screen.getByText("수정 후 재요청")).toBeInTheDocument();
    expect(screen.getByText("전시 정보 없음")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "승인" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "거절" }));
    const dialog = screen.getByRole("dialog");
    for (const reason of ["전시와 관련 없는 내용", "광고·홍보", "부적절한 표현", "개인정보 포함", "기타"]) {
      expect(within(dialog).getByRole("radio", { name: reason })).toBeInTheDocument();
    }
    expect(within(dialog).getByRole("button", { name: "거절하기" })).toBeDisabled();
    await user.click(within(dialog).getByRole("button", { name: "취소" }));

    await user.click(screen.getByRole("tab", { name: "신고됨 (2)" }));
    expect(screen.getAllByText("48시간 경과").length).toBeGreaterThan(0);
    expect(screen.getByText("26시간 남음")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "유지" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "내리기" })).toBeInTheDocument();
  });

  it("never shows a feeling, tags, a visit date or an unpublished note, even if a payload carried them", async () => {
    const leak = { feeling: "loved", tags: ["quiet-room"], visitDate: "2026-10-31", note: "비공개 메모" };
    const pending: PendingThought = {
      id: "p",
      content: "공개를 요청한 감상",
      exhibitionId: "garden-of-light",
      exhibitionNameKo: "빛의 정원",
      exhibitionNameEn: "Garden of Light",
      authorDisplayName: "hanshin",
      requestedAt: "2026-11-12T01:00:00Z",
      createdAt: "2026-11-10T09:00:00Z",
      wasApprovedBefore: false,
    };
    const reported: ReportedThought = {
      id: "r",
      content: "신고된 감상",
      exhibitionId: "garden-of-light",
      exhibitionNameKo: "빛의 정원",
      exhibitionNameEn: "Garden of Light",
      authorDisplayName: "hanshin",
      approvedAt: "2026-11-11T00:00:00Z",
      openCount: 1,
      firstReportedAt: "2026-11-12T01:00:00Z",
      reasons: { other: 1 },
    };
    const review: AdminThoughtRepository = {
      listPending: async () => [{ ...pending, ...leak }],
      listReported: async () => [{ ...reported, ...leak }],
      decide: async () => undefined,
      resolveReports: async () => undefined,
    };
    const { user } = renderWorkspace(review);

    await screen.findByText("공개를 요청한 감상", { selector: ".thought-moderation-text" });
    const pendingText = document.body.textContent ?? "";
    await user.click(screen.getByRole("tab", { name: "Reported (1)" }));
    await waitFor(() => expect(screen.getByRole("region", { name: "Selected note" })).toHaveTextContent("신고된 감상"));
    const reportedText = document.body.textContent ?? "";

    for (const text of [pendingText, reportedText]) {
      expect(text).not.toContain("loved");
      expect(text).not.toContain("quiet-room");
      expect(text).not.toContain("2026-10-31");
      expect(text).not.toContain("비공개 메모");
    }
  });
});
