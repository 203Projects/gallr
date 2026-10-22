import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  RouteListingInvalidTransitionError,
  RouteListingStaleError,
  type ModeratedRoute,
  type RouteListingQueueItem,
} from "../repositories/AdminRouteRepository";
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

/** The repository clock also stamps the revision an author edit produces, so a test advances it for distinct revisions. */
function repository(now: () => string = () => "2026-10-08T06:00:00Z") {
  return new InMemoryAdminRouteRepository(
    [
      route(FIRST, { name: "먼저 요청", listingRequestedAt: "2026-10-08T03:00:00Z" }),
      route(SECOND, {
        name: "다시 요청",
        listingRequestedAt: "2026-10-08T05:30:00Z",
        listingLastApprovedAt: "2026-10-07T00:00:00Z",
      }),
      route(REPORTED, { name: "신고된 동선", listingState: "approved", listingLastApprovedAt: "2026-10-08T00:00:00Z" }),
    ],
    now,
    [
      { routeId: REPORTED, reason: "promotional", createdAt: "2026-10-08T01:00:00Z" },
      { routeId: REPORTED, reason: "other", createdAt: "2026-10-08T02:00:00Z" },
    ],
  );
}

/** A read the test settles by hand, so the screen can be checked while that read is still pending. */
function held<T>() {
  let resolve: (value: T) => void = () => {};
  const promise = new Promise<T>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
}

const STALE_NOTICE = "The route changed. Check it again.";
const RECONCILING_NOTICE = "Reading the route again…";

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
    await review.decide(FIRST, { kind: "approve" }, `${FIRST}-rev-1`, "hanshin");
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

    await waitFor(() => expect(decide).toHaveBeenCalledWith(FIRST, { kind: "approve" }, `${FIRST}-rev-1`, "hanshin"));
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
        "hanshin",
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

    expect(await screen.findByText(RECONCILING_NOTICE)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "먼저 요청" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Decline" })).toBeDisabled();
    expect(decide).not.toHaveBeenCalled();

    showEdited(edited);
    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenCalledWith(FIRST, { kind: "approve" }, "2026-10-08T06:00:00Z", "hanshin"));
    expect(decide).toHaveBeenCalledTimes(1);
    expect((await review.lookUp(FIRST))?.listingState).toBe("approved");
    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
  });

  it("reads the queue again when the opened route is newer than its queue row, then approves what is shown", async () => {
    const review = repository();
    const decide = vi.spyOn(review, "decide");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await screen.findByRole("button", { name: /먼저 요청/ });

    // The author renames the route after the queue was read and before staff open its row. The queue read that
    // follows is held so the screen can be checked while the two sides disagree.
    review.edit(FIRST, { name: "다른 이름" });
    const fresh = await review.listQueue();
    const queueRead = held<RouteListingQueueItem[]>();
    vi.spyOn(review, "listQueue").mockImplementationOnce(() => queueRead.promise);
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));

    expect(await screen.findByText(RECONCILING_NOTICE)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();

    queueRead.resolve(fresh);
    expect(await screen.findByRole("button", { name: /다른 이름/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenCalledWith(FIRST, { kind: "approve" }, "2026-10-08T06:00:00Z", "hanshin"));
    expect(decide).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
  });

  it("approves only against the author name staff saw, and shows the new name after a rename", async () => {
    const review = repository();
    const decide = vi.spyOn(review, "decide");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });
    expect(screen.getByText("By hanshin")).toBeInTheDocument();

    // The author renames their profile while staff read the route; the route's own revision does not move.
    review.renameAuthor(FIRST, "새 이름");
    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByText("The author's name changed. Check it again.")).toBeInTheDocument();
    expect(await screen.findByText("By 새 이름")).toBeInTheDocument();
    expect((await review.lookUp(FIRST))?.listingState).toBe("requested");
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenLastCalledWith(FIRST, { kind: "approve" }, `${FIRST}-rev-1`, "새 이름"));
    expect(decide).toHaveBeenCalledTimes(2);
    expect((await review.lookUp(FIRST))?.listingState).toBe("approved");
  });

  it("reads the queue again when it still lags the route reloaded after a stale decision", async () => {
    const review = repository();
    const decide = vi.spyOn(review, "decide");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    // The author renames the route while staff read it, and the queue read right after the refused decision
    // still returns the row staff opened. The queue read after that one is held so the screen can be checked while
    // the two sides disagree.
    const queueBefore = await review.listQueue();
    review.edit(FIRST, { name: "다른 이름" });
    const fresh = await review.listQueue();
    const queueRead = held<RouteListingQueueItem[]>();
    vi.spyOn(review, "listQueue").mockResolvedValueOnce(queueBefore).mockImplementationOnce(() => queueRead.promise);
    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByText(RECONCILING_NOTICE)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();

    queueRead.resolve(fresh);
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    // The refused decision's notice stays until staff act again, whichever order the two reads landed in.
    expect(screen.getByText(STALE_NOTICE)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenLastCalledWith(FIRST, { kind: "approve" }, "2026-10-08T06:00:00Z", "hanshin"));
    expect(decide).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    expect((await review.lookUp(FIRST))?.listingState).toBe("approved");
    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
  });

  it("reads both sides again when the queue and the preview land on two newer revisions, then approves what is shown", async () => {
    const clock = { now: "2026-10-08T06:00:00Z" };
    const review = repository(() => clock.now);
    const decide = vi.spyOn(review, "decide");
    const listQueue = vi.spyOn(review, "listQueue");
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    // The author saves twice while staff read: the queue read sees the third version while the preview read, an
    // older response, answers with the second, so the two sides land apart on revisions neither had before.
    clock.now = "2026-10-08T06:01:00Z";
    const second = review.edit(FIRST, { name: "두 번째 이름" });
    clock.now = "2026-10-08T06:02:00Z";
    review.edit(FIRST, { name: "세 번째 이름" });
    lookUp.mockResolvedValueOnce(second);
    const reads = { queue: listQueue.mock.calls.length, route: lookUp.mock.calls.length };
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));

    expect(await screen.findByRole("heading", { name: "세 번째 이름" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    // The tab read the queue; the first mismatch read both sides; the pair that landed apart read both sides once more.
    expect(listQueue.mock.calls.length - reads.queue).toBe(3);
    expect(lookUp.mock.calls.length - reads.route).toBe(2);
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenCalledWith(FIRST, { kind: "approve" }, "2026-10-08T06:02:00Z", "hanshin"));
    expect(decide).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole("tab", { name: "Waiting 1" })).toBeInTheDocument();
  });

  it("keeps the preview disabled when its re-read fails, and reads it again when the Waiting tab is chosen", async () => {
    const review = repository();
    const decide = vi.spyOn(review, "decide");
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    // The author renames the route while staff read it; the read that should show the new version fails once.
    review.edit(FIRST, { name: "다른 이름" });
    lookUp.mockRejectedValueOnce(new Error("offline"));
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));

    expect(await screen.findByText("The route could not be looked up.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "먼저 요청" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Decline" })).toBeDisabled();
    expect(lookUp).toHaveBeenCalledTimes(2);

    // No revision moves, so the retry comes from staff choosing the tab again.
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));

    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    expect(lookUp).toHaveBeenCalledTimes(3);
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenCalledWith(FIRST, { kind: "approve" }, "2026-10-08T06:00:00Z", "hanshin"));
    expect(decide).toHaveBeenCalledTimes(1);
  });

  it("offers Try again when the re-read after a mismatch fails, and reads both sides again from it", async () => {
    const review = repository();
    const listQueue = vi.spyOn(review, "listQueue");
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    review.edit(FIRST, { name: "다른 이름" });
    lookUp.mockRejectedValueOnce(new Error("offline"));
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));
    expect(await screen.findByText("The route could not be looked up.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    const reads = { queue: listQueue.mock.calls.length, route: lookUp.mock.calls.length };

    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    expect(lookUp.mock.calls.length - reads.route).toBe(1);
    expect(listQueue.mock.calls.length - reads.queue).toBe(1);
  });

  it("offers Try again when the queue read of a reconcile fails, and reads both sides again from it", async () => {
    const review = repository();
    const listQueue = vi.spyOn(review, "listQueue");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await screen.findByRole("button", { name: /먼저 요청/ });

    // The author edits after the queue was read; the reconcile's queue read fails while its route read shows the edit.
    review.edit(FIRST, { name: "다른 이름" });
    listQueue.mockRejectedValueOnce(new Error("offline"));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));

    expect(await screen.findByText("The review lists could not be loaded.")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Try again" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
  });

  it("says it is reading the route again while the reads run, marks the decisions busy, and clears both once they agree", async () => {
    const review = repository();
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    review.edit(FIRST, { name: "다른 이름" });
    const pending = held<ModeratedRoute | null>();
    lookUp.mockReturnValueOnce(pending.promise);
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));

    expect(await screen.findByText("Reading the route again…")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Approve" })).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();

    pending.resolve(await review.lookUp(FIRST));

    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Approve" })).toHaveAttribute("aria-busy", "false");
  });

  it("reads each side once per mismatch and stops when the server keeps answering with the same two revisions", async () => {
    const review = repository();
    const listQueue = vi.spyOn(review, "listQueue");
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    // From here the queue always puts the route at a third revision while the route read always answers with a
    // second: the two sides can never agree, so the reads must stop on their own.
    const rows = await review.listQueue();
    const shown = await review.lookUp(FIRST);
    listQueue.mockResolvedValue(rows.map((row) => (row.id === FIRST ? { ...row, revision: "rev-3" } : row)));
    lookUp.mockResolvedValue({ ...shown!, name: "두 번째 이름", revision: "rev-2" });
    const reads = { queue: listQueue.mock.calls.length, route: lookUp.mock.calls.length };
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));

    expect(await screen.findByText(STALE_NOTICE)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "두 번째 이름" })).toBeInTheDocument();
    await waitFor(() => expect(lookUp.mock.calls.length - reads.route).toBe(2));
    await new Promise((settle) => setTimeout(settle, 100));
    // The tab read the queue; the mismatch read both sides; the pair that came back still apart read both sides once
    // more and, unchanged after that, was not read again.
    expect(listQueue.mock.calls.length - reads.queue).toBe(3);
    expect(lookUp.mock.calls.length - reads.route).toBe(2);
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Decline" })).toBeDisabled();

    // Staff can still ask again, and one more read of each side is all that follows.
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));

    await waitFor(() => expect(lookUp.mock.calls.length - reads.route).toBe(3));
    await new Promise((settle) => setTimeout(settle, 100));
    expect(listQueue.mock.calls.length - reads.queue).toBe(4);
    expect(lookUp.mock.calls.length - reads.route).toBe(3);
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  });

  it("clears the stale notice once the queue row and the preview agree again", async () => {
    const review = repository();
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });

    review.edit(FIRST, { name: "다른 이름" });
    const edited = await review.lookUp(FIRST);
    const routeRead = held<ModeratedRoute | null>();
    vi.spyOn(review, "lookUp").mockImplementationOnce(() => routeRead.promise);
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));
    expect(await screen.findByText(RECONCILING_NOTICE)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();

    routeRead.resolve(edited);

    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Decline" })).toBeEnabled();
  });

  it("settles the first mismatch after opening a row with one read of each side when the queue is the fresher one", async () => {
    const clock = { now: "2026-10-08T06:00:00Z" };
    const review = repository(() => clock.now);
    const decide = vi.spyOn(review, "decide");
    const listQueue = vi.spyOn(review, "listQueue");
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await screen.findByRole("tab", { name: "Waiting 2" });

    // The author renames the route before the queue is read, and the preview read answers with the version before.
    const before = await review.lookUp(FIRST);
    clock.now = "2026-10-08T06:01:00Z";
    review.edit(FIRST, { name: "다른 이름" });
    await user.click(screen.getByRole("tab", { name: "Waiting 2" }));
    await screen.findByRole("button", { name: /다른 이름/ });
    lookUp.mockResolvedValueOnce(before);
    const reads = { queue: listQueue.mock.calls.length, route: lookUp.mock.calls.length };
    await user.click(screen.getByRole("button", { name: /다른 이름/ }));

    expect(await screen.findByRole("heading", { name: "다른 이름" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled());
    await waitFor(() => expect(screen.queryByText(STALE_NOTICE)).not.toBeInTheDocument());
    // The queue, already the fresher side, is read once together with the preview and not again on its own.
    expect(listQueue.mock.calls.length - reads.queue).toBe(1);
    expect(lookUp.mock.calls.length - reads.route).toBe(2);
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(decide).toHaveBeenCalledWith(FIRST, { kind: "approve" }, "2026-10-08T06:01:00Z", "hanshin"));
    expect(decide).toHaveBeenCalledTimes(1);
  });

  it("clears the preview and says so when the route is gone after a stale decision", async () => {
    const review = repository();
    vi.spyOn(review, "decide").mockRejectedValueOnce(new RouteListingStaleError());
    const lookUp = vi.spyOn(review, "lookUp");
    const { user } = renderWorkspace(review);
    await user.click(await screen.findByRole("tab", { name: "Waiting 2" }));
    await user.click(screen.getByRole("button", { name: /먼저 요청/ }));
    await screen.findByRole("heading", { name: "먼저 요청" });
    // The author deletes the route while staff review it.
    lookUp.mockResolvedValueOnce(null);

    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByText("No matching route.")).toBeInTheDocument();
    expect(screen.queryByText("The route changed. Check it again.")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "먼저 요청" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
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

    await waitFor(() => expect(resolve).toHaveBeenCalledWith(REPORTED, "upheld", `${REPORTED}-rev-1`, "2026-10-08T00:00:00Z"));
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

  it("offers no Unlist for a route that was never listed", async () => {
    const review = new InMemoryAdminRouteRepository([route(FIRST, { name: "비공개 동선", listingState: "unlisted" })]);
    const { user } = renderWorkspace(review);
    await user.type(screen.getByRole("textbox", { name: "Route link or ID" }), FIRST);
    await user.click(screen.getByRole("button", { name: "Look up" }));
    await screen.findByRole("heading", { name: "비공개 동선" });

    expect(screen.getByText("Not listed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Unlist" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Keep off the list" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Restore listing" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Revoke route" })).toBeInTheDocument();
  });

  it("says the listing could not be changed when the server refuses to unlist", async () => {
    const review = new InMemoryAdminRouteRepository([
      route(FIRST, { name: "철회된 동선", listingState: "unlisted", listingLastApprovedAt: "2026-10-07T00:00:00Z" }),
    ]);
    vi.spyOn(review, "unlist").mockRejectedValueOnce(new RouteListingInvalidTransitionError());
    const { user } = renderWorkspace(review);
    await user.type(screen.getByRole("textbox", { name: "Route link or ID" }), FIRST);
    await user.click(screen.getByRole("button", { name: "Look up" }));
    await screen.findByRole("heading", { name: "철회된 동선" });

    await user.click(screen.getByRole("button", { name: "Keep off the list" }));
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Keep off the list" }));

    expect(await screen.findByText("The listing could not be changed.")).toBeInTheDocument();
    expect(screen.queryByText("The decision could not be saved.")).not.toBeInTheDocument();
    expect(screen.getByText("Not listed")).toBeInTheDocument();
  });

  it("takes a route withdrawn after approval off the list, as the server allows", async () => {
    const review = new InMemoryAdminRouteRepository([
      route(FIRST, { name: "철회된 동선", listingState: "unlisted", listingLastApprovedAt: "2026-10-07T00:00:00Z" }),
    ]);
    const unlist = vi.spyOn(review, "unlist");
    const { user } = renderWorkspace(review);
    await user.type(screen.getByRole("textbox", { name: "Route link or ID" }), FIRST);
    await user.click(screen.getByRole("button", { name: "Look up" }));
    await screen.findByRole("heading", { name: "철회된 동선" });
    expect(screen.getByText("Not listed")).toBeInTheDocument();
    // The chip says the route is not listed, so the action says what it does instead of "Unlist".
    expect(screen.queryByRole("button", { name: "Unlist" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Keep off the list" }));
    const dialog = screen.getByRole("alertdialog", { name: "Keep this route off the list?" });
    expect(within(dialog).getByText(/can no longer list it again/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Keep off the list" }));

    await waitFor(() => expect(unlist).toHaveBeenCalledWith(FIRST));
    expect(await screen.findByText("Taken off the list")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restore listing" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Keep off the list" })).not.toBeInTheDocument();
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
