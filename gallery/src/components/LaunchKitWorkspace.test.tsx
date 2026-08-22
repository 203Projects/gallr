import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { LaunchGuest, LaunchKit, LocalPromotion } from "../domain";
import { LaunchKitWorkspace } from "./LaunchKitWorkspace";
import { LocaleProvider } from "../i18n";

const qrDownload = vi.hoisted(() => vi.fn());

vi.mock("../rsvpQr", () => ({
  downloadRsvpQr: qrDownload,
}));

const kit: LaunchKit = {
  id: "launch-one",
  exhibitionId: "exhibition-one",
  status: "active",
  entitlementSource: "free_beta",
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

const secondKit: LaunchKit = {
  ...kit,
  id: "launch-two",
  exhibitionId: "exhibition-two",
  entitlementSource: "free_beta",
  publicToken: "00000000-0000-4000-8000-000000000010",
  nameKo: "여름의 기록",
  nameEn: "A Record of Summer",
  rsvpCount: 1,
  guestCount: 1,
  updatedAt: "2026-08-01T10:00:00Z",
};

const paidKit: LaunchKit = {
  ...kit,
  id: "launch-paid",
  exhibitionId: "exhibition-paid",
  entitlementSource: "paid",
  publicToken: "00000000-0000-4000-8000-000000000020",
  nameKo: "계절 사이",
  nameEn: "Between Seasons",
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

const jordan: LaunchGuest = {
  ...maya,
  id: "guest-jordan",
  launchKitId: secondKit.id,
  name: "Jordan Lee",
  email: "jordan@example.test",
  partySize: 1,
  status: "checked_in",
  checkedInAt: "2026-08-01T10:30:00Z",
};

const promotion: LocalPromotion = {
  id: "promotion-one",
  launchKitId: paidKit.id,
  exhibitionId: paidKit.exhibitionId,
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => { resolve = complete; });
  return { promise, resolve };
}

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
  beforeEach(() => {
    qrDownload.mockReset();
    qrDownload.mockResolvedValue(undefined);
  });

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

  it("keeps the separately released R4 promotion surface dark by default", async () => {
    const source = repository();
    render(<LaunchKitWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />);

    expect(await screen.findByRole("heading", { name: "Opening night" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Promoted near you" }))
      .not.toBeInTheDocument();
    expect(source.listLocalPromotions).not.toHaveBeenCalled();
    expect(source.requestLocalPromotion).not.toHaveBeenCalled();
  });

  it("does not query or show paid promotion for a free-beta entitlement", async () => {
    const source = repository();
    source.listLaunchKits.mockReset();
    source.listLaunchKits.mockResolvedValue([secondKit]);
    render(
      <LaunchKitWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
        promotionEnabled
      />,
    );

    expect(await screen.findByRole("heading", { name: "Opening night" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Promoted near you" }))
      .not.toBeInTheDocument();
    expect(source.listLocalPromotions).not.toHaveBeenCalled();
  });

  it("copies the environment-matched RSVP link", async () => {
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, "writeText");
    render(
      <LaunchKitWorkspace
        repository={repository()}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
        publicSiteUrl="https://public-preview.example.test/base/"
      />,
    );

    await user.click(await screen.findByRole("button", { name: "Copy RSVP link" }));
    expect(writeText).toHaveBeenCalledWith(
      "https://public-preview.example.test/rsvp/?token=00000000-0000-4000-8000-000000000001",
    );
    expect(await screen.findByRole("status")).toHaveTextContent("RSVP link copied.");
    writeText.mockRestore();
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
    const filters = screen.getByRole("group", { name: "Guest status filter" });
    expect(within(filters).getByRole("button", { name: "Going" }))
      .toHaveAttribute("aria-pressed", "true");
    expect(within(filters).getByRole("button", { name: "Checked in" }))
      .toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("textbox", { name: "Search name or email" })).toBeInTheDocument();
  });

  it("replaces the environment-matched public RSVP URL only after explicit confirmation", async () => {
    const user = userEvent.setup();
    const source = repository();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(
      <LaunchKitWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
        publicSiteUrl="https://public-preview.example.test/base/"
      />,
    );

    expect(await screen.findByRole("link", { name: "View RSVP page" })).toHaveAttribute(
      "href",
      "https://public-preview.example.test/rsvp/?token=00000000-0000-4000-8000-000000000001",
    );
    await user.click(await screen.findByRole("button", { name: "Replace RSVP link" }));

    await waitFor(() => expect(source.rotateLaunchRsvpToken).toHaveBeenCalledWith(kit.id));
    expect(screen.getByRole("link", { name: "View RSVP page" })).toHaveAttribute(
      "href",
      "https://public-preview.example.test/rsvp/?token=00000000-0000-4000-8000-000000000002",
    );
    await user.click(screen.getByRole("button", { name: "Download QR code" }));
    expect(qrDownload).toHaveBeenCalledWith({
      rsvpUrl: "https://public-preview.example.test/rsvp/?token=00000000-0000-4000-8000-000000000002",
      launchKitId: kit.id,
    });
    confirm.mockRestore();
  });

  it("switches between exhibition Kits and resets guest search and filters", async () => {
    const user = userEvent.setup();
    const source = repository();
    source.listLaunchKits.mockReset();
    source.listLaunchKits.mockResolvedValue([kit, secondKit]);
    source.listLaunchGuests.mockImplementation(async (launchKitId: string) => ({
      records: launchKitId === secondKit.id ? [jordan] : [maya],
      nextCursor: null,
    }));

    render(<LaunchKitWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />);

    expect(await screen.findByText("Maya Chen")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "Search guests" }), "Maya");
    await user.click(screen.getByRole("button", { name: "Checked in" }));
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Launch Kit exhibition" }),
      secondKit.id,
    );

    await waitFor(() => expect(source.listLaunchGuests)
      .toHaveBeenCalledWith(secondKit.id, "", "all"));
    expect(screen.getByRole("textbox", { name: "Search guests" })).toHaveValue("");
    expect(screen.getByRole("button", { name: "All" })).toHaveClass("is-active");
    expect(await screen.findByText("Jordan Lee")).toBeInTheDocument();
    expect(screen.queryByText("Maya Chen")).not.toBeInTheDocument();
  });

  it("discards a previous Kit load-more result after switching exhibitions", async () => {
    const user = userEvent.setup();
    const source = repository();
    const oldPage = deferred<{ records: LaunchGuest[]; nextCursor: null }>();
    source.listLaunchKits.mockReset();
    source.listLaunchKits.mockResolvedValue([kit, secondKit]);
    source.listLaunchGuests.mockImplementation(
      async (launchKitId: string, _query: string, _status: string, cursor?: unknown) => {
        if (launchKitId === secondKit.id) return { records: [jordan], nextCursor: null };
        if (cursor) return oldPage.promise;
        return {
          records: [maya],
          nextCursor: { createdAt: maya.createdAt, id: maya.id },
        };
      },
    );

    render(<LaunchKitWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />);
    expect(await screen.findByText("Maya Chen")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more guests" }));
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Launch Kit exhibition" }),
      secondKit.id,
    );
    expect(await screen.findByText("Jordan Lee")).toBeInTheDocument();

    oldPage.resolve({
      records: [{ ...maya, id: "guest-stale", name: "Stale first-Kit guest" }],
      nextCursor: null,
    });
    await waitFor(() => expect(source.listLaunchGuests)
      .toHaveBeenCalledWith(secondKit.id, "", "all"));
    expect(screen.queryByText("Stale first-Kit guest")).not.toBeInTheDocument();
  });

  it("does not insert a completed add-guest result into a newly selected Kit", async () => {
    const user = userEvent.setup();
    const source = repository();
    const oldGuest = deferred<LaunchGuest>();
    source.listLaunchKits.mockReset();
    source.listLaunchKits.mockResolvedValue([kit, secondKit]);
    source.listLaunchGuests.mockImplementation(async (launchKitId: string) => ({
      records: launchKitId === secondKit.id ? [jordan] : [maya],
      nextCursor: null,
    }));
    source.addLaunchGuest.mockReturnValue(oldGuest.promise);

    render(<LaunchKitWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />);
    expect(await screen.findByText("Maya Chen")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add guest" }));
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Old Kit Guest");
    await user.type(screen.getByRole("textbox", { name: "Email" }), "old@example.test");
    await user.click(screen.getByRole("button", { name: "Save guest" }));
    await waitFor(() => expect(source.addLaunchGuest).toHaveBeenCalled());
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Launch Kit exhibition" }),
      secondKit.id,
    );
    expect(await screen.findByText("Jordan Lee")).toBeInTheDocument();

    oldGuest.resolve({ ...maya, id: "guest-old-complete", name: "Old Kit Guest" });
    await waitFor(() => expect(source.listLaunchKits).toHaveBeenCalledTimes(2));
    expect(screen.queryByText("Old Kit Guest")).not.toBeInTheDocument();
  });

  it("shows the explicitly enabled paid, staff-reviewed R4 promotion UI", async () => {
    const user = userEvent.setup();
    const source = repository();
    source.listLaunchKits.mockReset();
    source.listLaunchKits.mockResolvedValue([paidKit]);
    render(
      <LaunchKitWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
        promotionEnabled
      />,
    );

    expect(await screen.findByRole("heading", { name: "Promoted near you" })).toBeInTheDocument();
    expect(source.listLocalPromotions).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/paid placement/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Request local promotion" }));
    await waitFor(() => expect(source.requestLocalPromotion).toHaveBeenCalledWith(paidKit.id));
    expect(await screen.findByText("Submitted for review")).toBeInTheDocument();
    expect(screen.getByText(/Editorial Featured remains separate/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /feature/i })).not.toBeInTheDocument();
  });
});
