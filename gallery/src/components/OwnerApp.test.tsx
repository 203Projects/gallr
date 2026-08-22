import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OwnerApp } from "./OwnerApp";
import { LocaleProvider } from "../i18n";
import type {
  OwnerAccess,
  OwnerAuth,
  OwnerRepository,
  OwnerSession,
} from "../domain";

const pendingAccess: OwnerAccess = {
  membership: { role: "owner", status: "pending" },
  gallery: {
    id: "gallery-alpha",
    nameKo: "알파 갤러리",
    nameEn: "Gallery Alpha",
    status: "active",
    addressKo: "서울특별시 용산구 알파로 1",
    addressEn: "",
  },
};

const newPendingAccess: OwnerAccess = {
  ...pendingAccess,
  gallery: { ...pendingAccess.gallery, status: "pending" },
};

function createAuth(session: OwnerSession | null): OwnerAuth & {
  getOAuthCallbackError: ReturnType<typeof vi.fn>;
  sendOtp: ReturnType<typeof vi.fn>;
  signInWithGoogle: ReturnType<typeof vi.fn>;
  signOut: ReturnType<typeof vi.fn>;
} {
  return {
    getSession: vi.fn().mockResolvedValue(session),
    subscribe: vi.fn().mockReturnValue(() => undefined),
    getOAuthCallbackError: vi.fn().mockReturnValue(null),
    sendOtp: vi.fn().mockResolvedValue(undefined),
    signInWithGoogle: vi.fn().mockResolvedValue(undefined),
    signOut: vi.fn().mockResolvedValue(undefined),
  };
}

function createRepository(access: OwnerAccess | null): OwnerRepository & {
  searchGalleries: ReturnType<typeof vi.fn>;
  claimExistingGallery: ReturnType<typeof vi.fn>;
  createGalleryClaim: ReturnType<typeof vi.fn>;
} {
  return {
    currentAccess: vi.fn().mockResolvedValue(access),
    searchGalleries: vi.fn().mockResolvedValue([
      {
        galleryId: "gallery-alpha",
        nameKo: "알파 갤러리",
        nameEn: "Gallery Alpha",
        addressKo: "서울특별시 용산구 알파로 1",
        addressEn: "",
        isClaimed: false,
      },
    ]),
    claimExistingGallery: vi.fn().mockResolvedValue(pendingAccess),
    createGalleryClaim: vi.fn().mockResolvedValue(newPendingAccess),
    getGalleryInfo: vi.fn().mockResolvedValue({
      galleryId: "gallery-alpha",
      revision: 1,
      nameKo: "알파 갤러리",
      nameEn: "Gallery Alpha",
      venueNameKo: "알파 갤러리",
      venueNameEn: "Gallery Alpha",
      cityKo: "서울특별시",
      cityEn: "Seoul",
      regionKo: "종로구",
      regionEn: "Jongno-gu",
      addressKo: "서울특별시 종로구 알파로 1",
      addressEn: "1 Alpha-ro, Jongno-gu, Seoul",
      latitude: 37.57,
      longitude: 126.98,
      hours: "",
      contact: "",
      updatedAt: "2026-08-05T00:00:00Z",
    }),
    saveGalleryInfo: vi.fn(),
    searchGalleryAddress: vi.fn().mockResolvedValue([]),
    listExhibitions: vi.fn().mockResolvedValue([]),
    hideExhibition: vi.fn(),
    createExhibitionDraft: vi.fn(),
    saveExhibitionDraft: vi.fn(),
    uploadCover: vi.fn(),
    submitExhibition: vi.fn(),
    listLaunchKits: vi.fn().mockResolvedValue([]),
    activateLaunchKit: vi.fn<OwnerRepository["activateLaunchKit"]>(),
    listLaunchGuests: vi.fn().mockResolvedValue({ records: [], nextCursor: null }),
    addLaunchGuest: vi.fn(),
    checkInLaunchGuest: vi.fn(),
    rotateLaunchRsvpToken: vi.fn(),
    listLocalPromotions: vi.fn().mockResolvedValue([]),
    requestLocalPromotion: vi.fn(),
  };
}

const signedIn: OwnerSession = {
  userId: "owner-one",
  email: "owner@example.test",
};

describe("gallery owner workspace", () => {
  afterEach(() => {
    window.history.replaceState({}, "", "/");
  });

  it("sends a one-time sign-in code without asking for a password", async () => {
    const user = userEvent.setup();
    const auth = createAuth(null);
    render(<OwnerApp auth={auth} repository={createRepository(null)} />);

    await screen.findByRole("heading", { name: "Publish with gallr" });
    await user.type(
      screen.getByRole("textbox", { name: "Email" }),
      "owner@example.test",
    );
    await user.click(screen.getByRole("button", { name: "Send sign-in code" }));

    expect(auth.sendOtp).toHaveBeenCalledWith("owner@example.test");
    expect(await screen.findByText("Check your email")).toBeInTheDocument();
    expect(screen.queryByLabelText(/password/i)).not.toBeInTheDocument();
  });

  it("switches the signed-out workflow to English without losing the entered email", async () => {
    const user = userEvent.setup();
    render(
      <LocaleProvider initialLocale="ko">
        <OwnerApp auth={createAuth(null)} repository={createRepository(null)} />
      </LocaleProvider>,
    );

    expect(await screen.findByRole("heading", { name: "gallr와 함께 전시를 게시하세요" }))
      .toBeInTheDocument();
    const email = screen.getByRole("textbox", { name: "이메일" });
    await user.type(email, "owner@example.test");
    await user.click(screen.getByRole("button", { name: "EN" }));

    expect(screen.getByRole("heading", { name: "Publish with gallr" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Email" })).toHaveValue("owner@example.test");
  });

  it("offers Google sign-in without bypassing gallery verification", async () => {
    const user = userEvent.setup();
    const auth = createAuth(null);
    const repository = createRepository(null);
    render(<OwnerApp auth={auth} repository={repository} />);

    await screen.findByRole("heading", { name: "Publish with gallr" });
    await user.click(screen.getByRole("button", { name: "Continue with Google" }));

    expect(auth.signInWithGoogle).toHaveBeenCalledOnce();
    expect(repository.currentAccess).not.toHaveBeenCalled();
  });

  it("explains when first-time OAuth signup is disabled", async () => {
    const auth = createAuth(null);
    auth.getOAuthCallbackError.mockReturnValue("signup-disabled");

    render(<OwnerApp auth={auth} repository={createRepository(null)} />);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Account creation is temporarily unavailable. Try again later.",
    );
  });

  it("shows a bounded message for other OAuth callback failures", async () => {
    const auth = createAuth(null);
    auth.getOAuthCallbackError.mockReturnValue("oauth-failed");

    render(<OwnerApp auth={auth} repository={createRepository(null)} />);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Google sign-in couldn’t be completed. Try again.",
    );
  });

  it("labels Google OAuth progress without calling it an email send", async () => {
    const user = userEvent.setup();
    const auth = createAuth(null);
    auth.signInWithGoogle.mockReturnValue(new Promise<void>(() => undefined));
    render(<OwnerApp auth={auth} repository={createRepository(null)} />);

    await screen.findByRole("heading", { name: "Publish with gallr" });
    await user.click(screen.getByRole("button", { name: "Continue with Google" }));

    expect(screen.getByRole("button", { name: "Opening Google…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Send sign-in code" })).toBeDisabled();
  });

  it("searches first and requests access to an existing gallery", async () => {
    const user = userEvent.setup();
    const repository = createRepository(null);
    render(<OwnerApp auth={createAuth(signedIn)} repository={repository} />);

    await screen.findByRole("heading", { name: "Set up your gallery" });
    await user.type(
      screen.getByRole("searchbox", { name: "Gallery name" }),
      "알파",
    );
    await user.click(screen.getByRole("button", { name: "Search" }));

    expect(repository.searchGalleries).toHaveBeenCalledWith("알파");
    expect(await screen.findByText("알파 갤러리")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Request access" }));
    await user.type(
      screen.getByRole("textbox", { name: "Official website" }),
      "https://alpha.example.test",
    );
    await user.click(screen.getByRole("button", { name: "Submit claim" }));

    expect(repository.claimExistingGallery).toHaveBeenCalledWith({
      galleryId: "gallery-alpha",
      websiteUrl: "https://alpha.example.test",
      socialUrl: "",
      claimNote: "",
    });
    expect(
      await screen.findByRole("heading", { name: "My exhibitions" }),
    ).toBeInTheDocument();
  });

  it("creates a new pending gallery when search has no match", async () => {
    const user = userEvent.setup();
    const repository = createRepository(null);
    repository.searchGalleries.mockResolvedValue([]);
    render(<OwnerApp auth={createAuth(signedIn)} repository={repository} />);

    await screen.findByRole("heading", { name: "Set up your gallery" });
    await user.click(
      screen.getByRole("button", {
        name: "Create a new gallery",
      }),
    );
    await user.type(
      screen.getByRole("textbox", { name: "Gallery name (Korean)" }),
      "감마 갤러리",
    );
    await user.type(
      screen.getByRole("textbox", { name: "Official website" }),
      "https://gamma.example.test",
    );
    await user.click(screen.getByRole("button", { name: "Create gallery" }));

    expect(repository.createGalleryClaim).toHaveBeenCalledWith({
      nameKo: "감마 갤러리",
      nameEn: "",
      websiteUrl: "https://gamma.example.test",
      socialUrl: "",
      claimNote: "",
    });
    expect(await screen.findByText("Gallery claim pending")).toBeInTheDocument();
  });

  it("shows the quiet pending dashboard without fake metrics", async () => {
    render(
      <OwnerApp
        auth={createAuth(signedIn)}
        repository={createRepository(pendingAccess)}
      />,
    );

    expect(
      await screen.findByRole("heading", { name: "My exhibitions" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Gallery claim pending")).toBeInTheDocument();
    expect(
      await screen.findByText("Your exhibitions will appear here."),
    ).toBeInTheDocument();
    expect(screen.queryByText(/views|analytics|revenue/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Gallery Info" })).not.toBeInTheDocument();
  });

  it("shows an active owner the workspace without a claim notice", async () => {
    const active: OwnerAccess = {
      ...pendingAccess,
      membership: { role: "owner", status: "active" },
    };
    render(
      <OwnerApp auth={createAuth(signedIn)} repository={createRepository(active)} />,
    );

    expect(
      await screen.findByRole("heading", { name: "My exhibitions" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Gallery claim pending")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create exhibition" }))
      .toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Gallery Info" }).length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "Launch Kit" }))
      .not.toBeInTheDocument();
  });

  it("opens Gallery Info for an active owner", async () => {
    const user = userEvent.setup();
    const active: OwnerAccess = {
      ...pendingAccess,
      membership: { role: "owner", status: "active" },
    };
    render(<OwnerApp auth={createAuth(signedIn)} repository={createRepository(active)} />);

    await user.click((await screen.findAllByRole("button", { name: "Gallery Info" }))[0]);
    expect(await screen.findByRole("heading", { name: "Gallery Info" })).toBeInTheDocument();
  });

  it("shows the R3 navigation only to an active gallery member", async () => {
    const pendingRepository = createRepository(pendingAccess);
    const pendingView = render(
      <OwnerApp
        auth={createAuth(signedIn)}
        repository={pendingRepository}
        launchKitEnabled
      />,
    );

    expect(
      await screen.findByRole("heading", { name: "My exhibitions" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Launch Kit" }))
      .not.toBeInTheDocument();
    expect(pendingRepository.listLaunchKits).not.toHaveBeenCalled();
    pendingView.unmount();

    const active: OwnerAccess = {
      ...pendingAccess,
      membership: { role: "owner", status: "active" },
    };
    render(
      <OwnerApp
        auth={createAuth(signedIn)}
        repository={createRepository(active)}
        launchKitEnabled
      />,
    );

    await screen.findByRole("heading", { name: "My exhibitions" });
    expect(screen.getAllByRole("button", { name: "Launch Kit" }).length)
      .toBeGreaterThan(0);
  });

  it("does not route or rewrite legacy checkout query parameters", async () => {
    window.history.replaceState(
      {},
      "",
      "/?launch=success&session_id=cs_test&source=checkout#launch-kit",
    );
    const active: OwnerAccess = {
      ...pendingAccess,
      membership: { role: "owner", status: "active" },
    };

    render(
      <OwnerApp
        auth={createAuth(signedIn)}
        repository={createRepository(active)}
        launchKitEnabled
      />,
    );

    expect(
      await screen.findByRole("heading", { name: "My exhibitions" }),
    ).toBeInTheDocument();
    expect(window.location.search)
      .toBe("?launch=success&session_id=cs_test&source=checkout");
    expect(window.location.hash).toBe("#launch-kit");
  });

  it("fails closed for a suspended membership while preserving sign out", async () => {
    const auth = createAuth(signedIn);
    const suspended: OwnerAccess = {
      ...pendingAccess,
      membership: { role: "owner", status: "suspended" },
    };
    render(
      <OwnerApp auth={auth} repository={createRepository(suspended)} />,
    );

    expect(await screen.findByText("Gallery access suspended")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create exhibition" }))
      .not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(auth.signOut).toHaveBeenCalledTimes(1));
  });
});
