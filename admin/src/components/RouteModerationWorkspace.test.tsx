import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import {
  RouteModerationNotStaffError,
  type AdminRouteRepository,
  type AdminRouteReviewRepository,
  type ModeratedRoute,
} from "../repositories/AdminRouteRepository";
import { RouteModerationWorkspace } from "./RouteModerationWorkspace";

const ROUTE_ID = "6f1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";
const LINK = `https://gallrmap.com/route/${ROUTE_ID}?s=share&v=1791432000`;

const publicRoute: ModeratedRoute = {
  id: ROUTE_ID,
  name: "토요일 한남 산책",
  authorDisplayName: "hanshin",
  isPublished: true,
  publishedAt: "2026-10-07T12:00:00Z",
  revokedAt: null,
  stops: [
    { position: 0, exhibitionId: "e-1", nameKo: "빛의 정원", nameEn: "Garden of Light", venueNameKo: "갤러리 빛", venueNameEn: "Gallery Light" },
    { position: 1, exhibitionId: "e-2", nameKo: "느린 풍경", nameEn: "Slow Landscape", venueNameKo: "공간 풍경", venueNameEn: "Space Landscape" },
  ],
  revision: "2026-10-08T01:00:00Z",
  listingState: "unlisted",
  listingRequestedAt: null,
  listingLastApprovedAt: null,
  declineReason: null,
  copyCount: 0,
  openReportCount: 0,
};

function repositoryWith(overrides: Partial<AdminRouteRepository> = {}) {
  return {
    lookUp: vi.fn().mockResolvedValue(publicRoute),
    revoke: vi.fn().mockResolvedValue({ ...publicRoute, revokedAt: "2026-10-08T02:00:00Z" }),
    ...overrides,
  };
}

/** Review lists stay empty here; RouteReviewWorkspace.test.tsx covers the queue and reports. */
function reviewStub(): AdminRouteReviewRepository {
  return {
    listQueue: vi.fn().mockResolvedValue([]),
    listReported: vi.fn().mockResolvedValue([]),
    decide: vi.fn(),
    resolveReports: vi.fn(),
    unlist: vi.fn(),
    restore: vi.fn(),
  };
}

async function lookUp(reference = LINK) {
  const user = userEvent.setup();
  await user.type(screen.getByRole("textbox", { name: "Route link or ID" }), reference);
  await user.click(screen.getByRole("button", { name: "Look up" }));
  return user;
}

describe("route moderation workspace", () => {
  it("looks a route up by its link and previews it", async () => {
    let resolve: (route: ModeratedRoute) => void = () => {};
    const repository = repositoryWith({ lookUp: vi.fn(() => new Promise<ModeratedRoute>((done) => { resolve = done; })) });
    render(<RouteModerationWorkspace repository={repository} reviewRepository={reviewStub()} />);

    await lookUp();
    expect(screen.getByRole("button", { name: "Looking up…" })).toBeDisabled();
    resolve(publicRoute);

    expect(await screen.findByRole("heading", { name: "토요일 한남 산책" })).toBeInTheDocument();
    expect(repository.lookUp).toHaveBeenCalledWith(ROUTE_ID);
    expect(screen.getByText("By hanshin")).toBeInTheDocument();
    expect(screen.getByText("Public")).toBeInTheDocument();
    const stops = screen.getByRole("list", { name: "Stops" });
    expect(within(stops).getAllByRole("listitem")).toHaveLength(2);
    expect(within(stops).getByText(/Garden of Light/)).toBeInTheDocument();
  });

  it("explains an unreadable reference and an unknown route", async () => {
    const repository = repositoryWith({ lookUp: vi.fn().mockResolvedValue(null) });
    render(<RouteModerationWorkspace repository={repository} reviewRepository={reviewStub()} />);

    const user = await lookUp("https://gallrmap.com/exhibitions/quiet-lines/");
    expect(await screen.findByText("Enter a gallrmap.com/route link or a route ID.")).toBeInTheDocument();
    expect(repository.lookUp).not.toHaveBeenCalled();

    await user.clear(screen.getByRole("textbox", { name: "Route link or ID" }));
    await user.type(screen.getByRole("textbox", { name: "Route link or ID" }), ROUTE_ID);
    await user.click(screen.getByRole("button", { name: "Look up" }));
    expect(await screen.findByText("No matching route.")).toBeInTheDocument();
  });

  it("revokes after a confirm that names the route", async () => {
    const repository = repositoryWith();
    render(<RouteModerationWorkspace repository={repository} reviewRepository={reviewStub()} />);
    const user = await lookUp();
    await screen.findByRole("heading", { name: "토요일 한남 산책" });

    await user.click(screen.getByRole("button", { name: "Revoke route" }));
    const dialog = screen.getByRole("alertdialog", { name: "Revoke this route?" });
    expect(within(dialog).getByText("토요일 한남 산책")).toBeInTheDocument();
    expect(repository.revoke).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "Revoke" }));

    await waitFor(() => expect(repository.revoke).toHaveBeenCalledWith(ROUTE_ID));
    expect(await screen.findByText("Revoked")).toBeInTheDocument();
    expect(screen.getByText("The public page changes within about 2 minutes.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Revoke route" })).toBeDisabled();
  });

  it("does not offer revoking an already revoked route", async () => {
    const repository = repositoryWith({
      lookUp: vi.fn().mockResolvedValue({ ...publicRoute, revokedAt: "2026-10-08T02:00:00Z" }),
    });
    render(<RouteModerationWorkspace repository={repository} reviewRepository={reviewStub()} />);
    await lookUp();

    expect(await screen.findByText("Revoked")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Revoke route" })).toBeDisabled();
  });

  it("reports a failed revocation and retries it", async () => {
    const revoke = vi
      .fn()
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValueOnce({ ...publicRoute, revokedAt: "2026-10-08T02:00:00Z" });
    const repository = repositoryWith({ revoke });
    render(<RouteModerationWorkspace repository={repository} reviewRepository={reviewStub()} />);
    const user = await lookUp();
    await screen.findByRole("heading", { name: "토요일 한남 산책" });

    await user.click(screen.getByRole("button", { name: "Revoke route" }));
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Revoke" }));
    expect(await screen.findByText("The route could not be revoked.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Revoked")).toBeInTheDocument();
    expect(revoke).toHaveBeenCalledTimes(2);
  });

  it("tells non-staff accounts they cannot moderate", async () => {
    const repository = repositoryWith({ lookUp: vi.fn().mockRejectedValue(new RouteModerationNotStaffError()) });
    render(<RouteModerationWorkspace repository={repository} reviewRepository={reviewStub()} />);
    await lookUp();

    expect(await screen.findByText("Only active staff can moderate routes.")).toBeInTheDocument();
  });

  it("speaks Korean", async () => {
    const repository = repositoryWith({ lookUp: vi.fn().mockResolvedValue(null) });
    render(
      <LocaleProvider initialLocale="ko" storage={null}>
        <RouteModerationWorkspace repository={repository} reviewRepository={reviewStub()} />
      </LocaleProvider>,
    );
    const user = userEvent.setup();
    await user.type(screen.getByRole("textbox", { name: "동선 링크 또는 ID" }), LINK);
    await user.click(screen.getByRole("button", { name: "조회" }));

    expect(await screen.findByText("일치하는 동선이 없습니다")).toBeInTheDocument();
  });

  it("shows the Korean revocation note", async () => {
    const repository = repositoryWith();
    render(
      <LocaleProvider initialLocale="ko" storage={null}>
        <RouteModerationWorkspace repository={repository} reviewRepository={reviewStub()} />
      </LocaleProvider>,
    );
    const user = userEvent.setup();
    await user.type(screen.getByRole("textbox", { name: "동선 링크 또는 ID" }), LINK);
    await user.click(screen.getByRole("button", { name: "조회" }));
    await screen.findByRole("heading", { name: "토요일 한남 산책" });
    await user.click(screen.getByRole("button", { name: "동선 철회" }));
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "철회" }));

    expect(await screen.findByText("철회됨")).toBeInTheDocument();
    expect(screen.getByText("공개 페이지는 약 2분 안에 바뀝니다")).toBeInTheDocument();
  });
});
