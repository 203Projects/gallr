import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RouteListingStaleError, type ModeratedRoute } from "../repositories/AdminRouteRepository";
import { InMemoryAdminRouteRepository } from "../repositories/InMemoryAdminRouteRepository";
import { RouteModerationWorkspace } from "./RouteModerationWorkspace";

const FIRST = "11111111-1111-4111-8111-111111111111";
const SECOND = "22222222-2222-4222-8222-222222222222";
const REPORTED = "33333333-3333-4333-8333-333333333333";
const NOW = new Date("2026-10-08T06:00:00Z");

function route(id: string, overrides: Partial<ModeratedRoute>): ModeratedRoute {
  return {
    id,
    name: `동선 ${id.slice(0, 4)}`,
    authorDisplayName: "hanshin",
    isPublished: true,
    publishedAt: "2026-10-07T12:00:00Z",
    revokedAt: null,
    stops: [{ position: 0, exhibitionId: "e-1", nameKo: "빛의 정원", nameEn: "Garden of Light", venueNameKo: "갤러리 빛", venueNameEn: "Gallery Light" }],
    revision: `${id}-rev-1`,
    listingState: "requested",
    listingRequestedAt: null,
    listingLastApprovedAt: null,
    declineReason: null,
    copyCount: 0,
    openReportCount: 0,
    ...overrides,
  };
}

function repository() {
  return new InMemoryAdminRouteRepository(
    [
      route(FIRST, { name: "먼저 요청", listingRequestedAt: "2026-10-08T03:00:00Z" }),
      route(SECOND, {
        name: "다시 요청",
        listingRequestedAt: "2026-10-08T05:30:00Z",
        listingLastApprovedAt: "2026-10-07T00:00:00Z",
      }),
      route(REPORTED, { name: "신고된 동선", listingState: "approved" }),
    ],
    () => "2026-10-08T06:00:00Z",
    [
      { routeId: REPORTED, reason: "promotional", createdAt: "2026-10-08T01:00:00Z" },
      { routeId: REPORTED, reason: "other", createdAt: "2026-10-08T02:00:00Z" },
    ],
  );
}

function renderWorkspace(review = repository()) {
  render(<RouteModerationWorkspace repository={review} reviewRepository={review} now={() => NOW} />);
  return { review, user: userEvent.setup() };
}

describe("route review workspace", () => {
  it("shows the look-up, queue and reports views with their counts", async () => {
    renderWorkspace();

    expect(await screen.findByRole("tab", { name: "Waiting 2" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Look up" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Reports 1" })).toBeInTheDocument();
  });

  it("reads the queue and reports again when their tab is chosen", async () => {
    const { review, user } = renderWorkspace();
    expect(await screen.findByRole("tab", { name: "Waiting 2" })).toBeInTheDocument();

    // Another staff member approves one request while this workspace is open.
    await review.decide(FIRST, { kind: "approve" }, `${FIRST}-rev-1`);
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));

    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
    expect(screen.queryByText("먼저 요청")).not.toBeInTheDocument();
  });

  it("lists waiting routes oldest first and marks edited ones", async () => {
    const { user } = renderWorkspace();
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));

    const rows = within(screen.getByRole("list", { name: "Waiting for review" })).getAllByRole("listitem");
    expect(rows.map((row) => within(row).getByRole("button").textContent)).toEqual([
      expect.stringContaining("먼저 요청"),
      expect.stringContaining("다시 요청"),
    ]);
    expect(within(rows[0]).getByText(/3 hours ago/)).toBeInTheDocument();
    expect(within(rows[1]).getByText("Edited")).toBeInTheDocument();
    expect(within(rows[0]).queryByText("Edited")).not.toBeInTheDocument();
  });

  it("approves the reviewed version and takes it out of the queue", async () => {
    const { review, user } = renderWorkspace();
    const decide = vi.spyOn(review, "decide");
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));

    expect(await screen.findByRole("heading", { name: "먼저 요청" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenCalledWith(FIRST, { kind: "approve" }, `${FIRST}-rev-1`));
    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /먼저 요청/ })).not.toBeInTheDocument();
  });

  it("declines only with a reason, and sends the optional note", async () => {
    const { review, user } = renderWorkspace();
    const decide = vi.spyOn(review, "decide");
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    await user.click(screen.getByRole("button", { name: "Decline" }));
    const dialog = screen.getByRole("dialog", { name: "Decline this listing?" });
    const confirm = within(dialog).getByRole("button", { name: "Decline" });
    expect(confirm).toBeDisabled();

    await user.click(within(dialog).getByRole("radio", { name: "Route composition" }));
    await user.type(within(dialog).getByRole("textbox", { name: "Note to the author (optional)" }), "전시를 줄여 주세요");
    await user.click(confirm);

    await waitFor(() =>
      expect(decide).toHaveBeenCalledWith(
        FIRST,
        { kind: "decline", reason: "composition", note: "전시를 줄여 주세요" },
        `${FIRST}-rev-1`,
      ),
    );
    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
  });

  it("decides on the revision staff are looking at, never on a queue revision they have not seen", async () => {
    const review = repository();
    const decide = vi.spyOn(review, "decide");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    // The author renames the route while staff read it, then a tab click reads the queue again.
    review.edit(FIRST, { name: "다른 이름" });
    const edited = await review.lookUp(FIRST);
    let showEdited: (route: ModeratedRoute | null) => void = () => {};
    vi.spyOn(review, "lookUp").mockImplementationOnce(() => new Promise((done) => { showEdited = done; }));
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));

    expect(await screen.findByText("The route changed. Check it again.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "먼저 요청" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Decline" })).toBeDisabled();
    expect(decide).not.toHaveBeenCalled();

    showEdited(edited);
    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenCalledWith(FIRST, { kind: "approve" }, "2026-10-08T06:00:00Z"));
    expect(decide).toHaveBeenCalledTimes(1);
    expect((await review.lookUp(FIRST))?.listingState).toBe("approved");
    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
  });

  it("refreshes the open route after its reports are upheld from the reports view", async () => {
    const { user } = renderWorkspace();
    await user.type(screen.getByRole("textbox", { name: "Route link or ID" }), REPORTED);
    await user.click(screen.getByRole("button", { name: "Look up" }));
    await screen.findByRole("heading", { name: "신고된 동선" });
    expect(screen.getByText("Listed")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Reports 1" }));
    const row = within(await screen.findByRole("list", { name: "Reported routes" })).getAllByRole("listitem")[0];
    await user.click(within(row).getByRole("button", { name: "Unlist" }));
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Unlist" }));
    await screen.findByText("No open reports");

    await user.click(screen.getByRole("tab", { name: "Look up" }));
    expect(await screen.findByText("Taken off the list")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restore listing" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Unlist" })).not.toBeInTheDocument();
  });

  it("says so when the route cannot be read again after a stale decision", async () => {
    const review = repository();
    vi.spyOn(review, "decide").mockRejectedValueOnce(new RouteListingStaleError());
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });
    lookUp.mockRejectedValueOnce(new Error("offline"));

    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByText("The route could not be looked up.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "먼저 요청" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
  });

  it("reloads a route the author changed during review", async () => {
    const review = repository();
    const decide = vi.spyOn(review, "decide").mockRejectedValueOnce(new RouteListingStaleError());
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByText("The route changed. Check it again.")).toBeInTheDocument();
    expect(decide).toHaveBeenCalledTimes(1);
    expect(lookUp).toHaveBeenCalledTimes(2);
  });

  it("dismisses reports or unlists after a confirm", async () => {
    const { review, user } = renderWorkspace();
    const resolve = vi.spyOn(review, "resolveReports");
    await user.click(await screen.findByRole("tab", { name: "Reports 1" }));

    const rows = within(screen.getByRole("list", { name: "Reported routes" })).getAllByRole("listitem");
    expect(within(rows[0]).getByText("신고된 동선")).toBeInTheDocument();
    expect(within(rows[0]).getByText(/Open reports: 2/)).toBeInTheDocument();
    expect(within(rows[0]).getByText(/Promotion or ad 1/)).toBeInTheDocument();

    await user.click(within(rows[0]).getByRole("button", { name: "Unlist" }));
    const dialog = screen.getByRole("alertdialog", { name: "Take this route off the list?" });
    expect(resolve).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "Unlist" }));

    await waitFor(() => expect(resolve).toHaveBeenCalledWith(REPORTED, "upheld"));
    expect(await screen.findByText("No open reports")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Reports 0" })).toBeInTheDocument();
  });

  it("opens a reported route to read it before acting on its reports", async () => {
    const { user } = renderWorkspace();
    await user.click(await screen.findByRole("tab", { name: "Reports 1" }));
    const reportedRow = () => within(screen.getByRole("list", { name: "Reported routes" })).getAllByRole("listitem")[0];
    expect(screen.queryByRole("heading", { name: "신고된 동선" })).not.toBeInTheDocument();

    await user.click(within(reportedRow()).getByRole("button", { name: "Open" }));

    expect(await screen.findByRole("heading", { name: "신고된 동선" })).toBeInTheDocument();
    expect(screen.getByText("Open reports: 2")).toBeInTheDocument();
    expect(within(screen.getByRole("list", { name: "Stops" })).getByText(/Garden of Light/)).toBeInTheDocument();

    await user.click(within(reportedRow()).getByRole("button", { name: "Dismiss" }));

    expect(await screen.findByText("No open reports")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "신고된 동선" })).toBeInTheDocument();
    expect(screen.queryByText("Open reports: 2")).not.toBeInTheDocument();
  });

  it("dismissing keeps the route listed", async () => {
    const { review, user } = renderWorkspace();
    await user.click(await screen.findByRole("tab", { name: "Reports 1" }));
    await user.click(screen.getByRole("button", { name: "Dismiss" }));

    expect(await screen.findByText("No open reports")).toBeInTheDocument();
    expect((await review.lookUp(REPORTED))?.listingState).toBe("approved");
  });

  // Generated by /ship test coverage audit (spec 089).
  // Value: protects=when the review lists, a decision or a report resolution fails, staff read a notice and the
  // route or report stays on screen to act on again; fails_when=a failed call is silent, clears the reviewed
  // route, or leaves the actions disabled; why_new=the only failing review call tested is the stale decision;
  // seam=none
  it("says so and keeps the route reviewable when a review call fails", async () => {
    const review = repository();
    vi.spyOn(review, "listQueue").mockRejectedValueOnce(new Error("offline"));
    const { user } = renderWorkspace(review);

    expect(await screen.findByText("The review lists could not be loaded.")).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Waiting 0" }));
    await user.click(await screen.findByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });
    expect(screen.queryByText("The review lists could not be loaded.")).not.toBeInTheDocument();

    const decide = vi.spyOn(review, "decide").mockRejectedValueOnce(new Error("offline"));
    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByText("The decision could not be saved.")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Waiting 2" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Approve" }));
    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
    expect(decide).toHaveBeenCalledTimes(2);

    vi.spyOn(review, "resolveReports").mockRejectedValueOnce(new Error("offline"));
    await user.click(screen.getByRole("tab", { name: "Reports 1" }));
    await user.click(await screen.findByRole("button", { name: "Dismiss" }));

    expect(await screen.findByText("The listing could not be changed.")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Reports 1" })).toBeInTheDocument();
    expect(screen.getByText("신고된 동선")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Dismiss" })).toBeEnabled());
  });

  it("shows the empty queue", async () => {
    const review = new InMemoryAdminRouteRepository([]);
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 0" }));

    expect(screen.getByText("Nothing to review")).toBeInTheDocument();
  });

  it("restores a route staff took off the list", async () => {
    const review = new InMemoryAdminRouteRepository([route(FIRST, { name: "내려진 동선", listingState: "removed" })]);
    const { user } = renderWorkspace(review);
    await user.type(screen.getByRole("textbox", { name: "Route link or ID" }), FIRST);
    await user.click(screen.getByRole("button", { name: "Look up" }));
    await screen.findByRole("heading", { name: "내려진 동선" });

    await user.click(screen.getByRole("button", { name: "Restore listing" }));

    await waitFor(async () => expect((await review.lookUp(FIRST))?.listingState).toBe("unlisted"));
  });
});
