import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OwnerApp } from "./OwnerApp";
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

function createAuth(session: OwnerSession | null): OwnerAuth & {
  sendOtp: ReturnType<typeof vi.fn>;
  signOut: ReturnType<typeof vi.fn>;
} {
  return {
    getSession: vi.fn().mockResolvedValue(session),
    subscribe: vi.fn().mockReturnValue(() => undefined),
    sendOtp: vi.fn().mockResolvedValue(undefined),
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
    createGalleryClaim: vi.fn().mockResolvedValue(pendingAccess),
    listExhibitions: vi.fn().mockResolvedValue([]),
    createExhibitionDraft: vi.fn(),
    saveExhibitionDraft: vi.fn(),
    uploadCover: vi.fn(),
    submitExhibition: vi.fn(),
    listLaunchKits: vi.fn().mockResolvedValue([]),
    startLaunchCheckout: vi.fn(),
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
  });

  it("opens Launch Kit after a successful checkout return and cleans the URL", async () => {
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
      <OwnerApp auth={createAuth(signedIn)} repository={createRepository(active)} />,
    );

    expect(
      await screen.findByRole("heading", { name: "No Launch Kits yet." }),
    ).toBeInTheDocument();
    await waitFor(() => {
      expect(window.location.pathname).toBe("/");
      expect(window.location.search).toBe("?source=checkout");
      expect(window.location.hash).toBe("#launch-kit");
    });
  });

  it("keeps a cancelled checkout on exhibitions and cleans the URL", async () => {
    window.history.replaceState({}, "", "/?launch=cancelled&session_id=legacy");
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
    await waitFor(() => expect(window.location.search).toBe(""));
  });

  it("leaves unrelated launch and session parameters untouched", async () => {
    window.history.replaceState({}, "", "/?launch=preview&session_id=other");
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
    expect(window.location.search).toBe("?launch=preview&session_id=other");
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
