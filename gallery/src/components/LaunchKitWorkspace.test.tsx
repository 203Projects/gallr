import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { LaunchGuest, LaunchKit, LocalPromotion } from "../domain";
import { LaunchKitWorkspace } from "./LaunchKitWorkspace";
import { LocaleProvider } from "../i18n";

const kit: LaunchKit = {
  id: "launch-one",
  exhibitionId: "exhibition-one",
  status: "active",
  revision: 2,
  publicToken: "00000000-0000-4000-8000-000000000001",
  nameKo: "작은 방의 기록",
  nameEn: "Notes from a Small Room",
  receptionDate: "2026-09-02",
  receptionStartTime: "19:00",
  rsvpCount: 1,
  guestCount: 2,
  checkedInCount: 0,
  updatedAt: "2026-07-31T10:00:00Z",
};

const maya: LaunchGuest = {
  id: "guest-maya",
  launchKitId: kit.id,
  name: "Maya Chen",
  email: "maya@example.test",
  partySize: 2,
  status: "going",
  checkedInAt: null,
  createdAt: "2026-07-31T10:00:00Z",
};

const promotion: LocalPromotion = {
  id: "promotion-one",
  launchKitId: kit.id,
  exhibitionId: kit.exhibitionId,
  status: "submitted",
  revision: 1,
  cityKo: "서울",
  cityEn: "Seoul",
  regionKo: "용산구",
  regionEn: "Yongsan-gu",
  startsAt: null,
  endsAt: null,
  reviewNotes: "",
  requestedAt: "2026-07-31T10:00:00Z",
};

function repository() {
  return {
    listLaunchKits: vi.fn()
      .mockResolvedValueOnce([kit])
      .mockResolvedValue([{ ...kit, rsvpCount: 2, guestCount: 3 }]),
    listLaunchGuests: vi.fn().mockResolvedValue({ records: [maya], nextCursor: null }),
    addLaunchGuest: vi.fn().mockResolvedValue({
      ...maya,
      id: "guest-jordan",
      name: "Jordan Lee",
      email: "jordan@example.test",
      partySize: 1,
    }),
    checkInLaunchGuest: vi.fn().mockResolvedValue({
      ...maya,
      status: "checked_in" as const,
      checkedInAt: "2026-09-02T10:04:00Z",
    }),
    rotateLaunchRsvpToken: vi.fn().mockResolvedValue({
      ...kit,
      publicToken: "00000000-0000-4000-8000-000000000002",
    }),
    listLocalPromotions: vi.fn().mockResolvedValue([]),
    requestLocalPromotion: vi.fn().mockResolvedValue(promotion),
  };
}

describe("LaunchKitWorkspace", () => {
  it("renders Korean Launch Kit copy and keeps the locale control in check-in mode", async () => {
    const user = userEvent.setup();
    render(
      <LocaleProvider initialLocale="ko">
        <LaunchKitWorkspace repository={repository()} onNavigate={vi.fn()} onSignOut={vi.fn()} />
      </LocaleProvider>,
    );

    expect(await screen.findByRole("heading", { name: "오프닝" })).toBeInTheDocument();
    expect(screen.getByText("작은 방의 기록")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "입장 확인 모드" }));
    expect(screen.getByRole("heading", { name: "게스트 입장 확인" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "언어" })).toBeInTheDocument();
  });

  it("uses Korean confirmation copy when replacing an RSVP link", async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(
      <LocaleProvider initialLocale="ko">
        <LaunchKitWorkspace repository={repository()} onNavigate={vi.fn()} onSignOut={vi.fn()} />
      </LocaleProvider>,
    );

    await user.click(await screen.findByRole("button", { name: "RSVP 링크 교체" }));
    expect(confirm).toHaveBeenCalledWith(
      "이 RSVP 링크를 교체할까요? 현재 링크는 즉시 작동을 멈춥니다.",
    );
    confirm.mockRestore();
  });

  it("shows an active kit, its private guest list, and the public RSVP link", async () => {
    const source = repository();
    render(<LaunchKitWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />);

    expect(await screen.findByRole("heading", { name: "Opening night" })).toBeInTheDocument();
    expect(await screen.findByText("Maya Chen")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View RSVP page" })).toHaveAttribute(
      "href",
      `https://gallrmap.com/rsvp/?token=${kit.publicToken}`,
    );
    expect(source.listLaunchGuests).toHaveBeenCalledWith(kit.id, "", "all");
  });

  it("adds a guest, updates totals, and checks in the original guest", async () => {
    const user = userEvent.setup();
    const source = repository();
    render(<LaunchKitWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />);
    await screen.findByText("Maya Chen");

    await user.click(screen.getByRole("button", { name: "Add guest" }));
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Jordan Lee");
    await user.type(screen.getByRole("textbox", { name: "Email" }), "jordan@example.test");
    await user.click(screen.getByRole("button", { name: "Save guest" }));
    await waitFor(() => expect(source.addLaunchGuest).toHaveBeenCalledWith(
      kit.id, "Jordan Lee", "jordan@example.test", 1,
    ));
    expect(await screen.findByText("Jordan Lee")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();

    const mayaRow = screen.getByText("Maya Chen").closest("article");
    expect(mayaRow).not.toBeNull();
    await user.click(within(mayaRow as HTMLElement).getByRole("button", { name: "Check in" }));
    await waitFor(() => expect(source.checkInLaunchGuest).toHaveBeenCalledWith(
      kit.id, maya.id,
    ));
    await waitFor(() => expect(within(mayaRow as HTMLElement).getByText("Checked in")).toBeInTheDocument());
  });

  it("opens the focused check-in surface with Going selected", async () => {
    const user = userEvent.setup();
    render(<LaunchKitWorkspace repository={repository()} onNavigate={vi.fn()} onSignOut={vi.fn()} />);
    await user.click(await screen.findByRole("button", { name: "Check-in mode" }));

    expect(screen.getByRole("heading", { name: "Check in guests" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Going" })).toHaveClass("is-active");
    expect(screen.getByRole("textbox", { name: "Search name or email" })).toBeInTheDocument();
  });

  it("replaces the public RSVP link only after explicit confirmation", async () => {
    const user = userEvent.setup();
    const source = repository();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<LaunchKitWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />);
    await user.click(await screen.findByRole("button", { name: "Replace RSVP link" }));

    await waitFor(() => expect(source.rotateLaunchRsvpToken).toHaveBeenCalledWith(kit.id));
    expect(screen.getByRole("link", { name: "View RSVP page" })).toHaveAttribute(
      "href",
      "https://gallrmap.com/rsvp/?token=00000000-0000-4000-8000-000000000002",
    );
    confirm.mockRestore();
  });

  it("requests a separately labelled, staff-reviewed local promotion", async () => {
    const user = userEvent.setup();
    const source = repository();
    render(<LaunchKitWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />);

    expect(await screen.findByRole("heading", { name: "Promoted near you" })).toBeInTheDocument();
    expect(screen.getByText(/paid placement/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Request local promotion" }));
    await waitFor(() => expect(source.requestLocalPromotion).toHaveBeenCalledWith(kit.id));
    expect(await screen.findByText("Submitted for review")).toBeInTheDocument();
    expect(screen.getByText(/Editorial Featured remains separate/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /feature/i })).not.toBeInTheDocument();
  });
});
