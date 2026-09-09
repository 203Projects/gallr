import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { GalleryGeocodeCandidate, GalleryInfo, OwnerRepository } from "../domain";
import { GalleryInfoWorkspace } from "./GalleryInfoWorkspace";
import { LocaleProvider } from "../i18n";

const info: GalleryInfo = {
  galleryId: "gallery-one",
  revision: 7,
  nameKo: "갤러리 알파",
  nameEn: "Gallery Alpha",
  venueNameKo: "갤러리 알파",
  venueNameEn: "Gallery Alpha",
  cityKo: "서울특별시",
  cityEn: "Seoul",
  regionKo: "종로구",
  regionEn: "Jongno-gu",
  addressKo: "서울특별시 종로구 삼청로 12",
  addressEn: "12 Samcheong-ro, Jongno-gu, Seoul",
  latitude: 37.582,
  longitude: 126.981,
  hours: "화–일 11:00–18:00",
  contact: "hello@alpha.example",
  updatedAt: "2026-08-05T12:00:00Z",
};

const candidate: GalleryGeocodeCandidate = {
  roadAddress: "서울특별시 종로구 율곡로 3길 4",
  jibunAddress: "서울특별시 종로구 안국동 1",
  englishAddress: "4 Yulgok-ro 3-gil, Jongno-gu, Seoul",
  cityKo: "서울특별시",
  cityEn: "Seoul",
  regionKo: "종로구",
  regionEn: "Jongno-gu",
  latitude: 37.577,
  longitude: 126.986,
};

function repository(): OwnerRepository {
  return {
    currentAccess: vi.fn(),
    searchGalleries: vi.fn(),
    claimExistingGallery: vi.fn(),
    createGalleryClaim: vi.fn(),
    getGalleryInfo: vi.fn().mockResolvedValue(info),
    saveGalleryInfo: vi.fn().mockImplementation(async (_revision, patch) => ({
      ...info,
      ...patch,
      revision: 8,
    })),
    searchGalleryAddress: vi.fn().mockResolvedValue([candidate]),
    listArtTerms: vi.fn().mockResolvedValue([]),
    searchArtists: vi.fn().mockResolvedValue([]),
    listExhibitions: vi.fn(),
    hideExhibition: vi.fn(),
    createExhibitionDraft: vi.fn(),
    saveExhibitionDraft: vi.fn(),
    uploadCover: vi.fn(),
    submitExhibition: vi.fn(),
    listLaunchKits: vi.fn(),
    activateLaunchKit: vi.fn<OwnerRepository["activateLaunchKit"]>(),
    listLaunchGuests: vi.fn(),
    addLaunchGuest: vi.fn(),
    checkInLaunchGuest: vi.fn(),
    rotateLaunchRsvpToken: vi.fn(),
    listLocalPromotions: vi.fn(),
    requestLocalPromotion: vi.fn(),
  };
}

describe("Gallery Info workspace", () => {
  it("renders the Gallery Info workflow in Korean while keeping paired authoring fields", async () => {
    render(
      <LocaleProvider initialLocale="ko">
        <GalleryInfoWorkspace
          repository={repository()}
          onNavigate={vi.fn()}
          onSignOut={vi.fn()}
        />
      </LocaleProvider>,
    );

    expect(await screen.findByRole("heading", { name: "갤러리 정보" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "갤러리명 (한국어)" })).toHaveValue("갤러리 알파");
    expect(screen.getByRole("textbox", { name: "갤러리명 (영어)" })).toHaveValue("Gallery Alpha");
    expect(screen.getAllByRole("navigation", { name: "갤러리 워크스페이스" })).toHaveLength(2);
  });

  it("renders a known save conflict in Korean", async () => {
    const user = userEvent.setup();
    const source = repository();
    vi.mocked(source.saveGalleryInfo).mockRejectedValueOnce(
      new Error("owner_save_gallery_info failed: revision_conflict"),
    );
    render(
      <LocaleProvider initialLocale="ko">
        <GalleryInfoWorkspace repository={source} onNavigate={vi.fn()} onSignOut={vi.fn()} />
      </LocaleProvider>,
    );

    await screen.findByRole("heading", { name: "갤러리 정보" });
    await user.click(screen.getByRole("button", { name: "갤러리 정보 저장" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "다른 곳에서 갤러리 정보가 변경되었습니다. 새로고침 후 다시 시도하세요.",
    );
  });

  it("provides equivalent labelled navigation and current-page state at both responsive surfaces", async () => {
    render(
      <GalleryInfoWorkspace
        repository={repository()}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
      />,
    );

    await screen.findByRole("heading", { name: "Gallery Info" });
    const navigation = screen.getAllByRole("navigation", { name: "Gallery workspace" });
    expect(navigation).toHaveLength(2);
    for (const region of navigation) {
      expect(within(region).getByRole("button", { name: "Gallery Info" }))
        .toHaveAttribute("aria-current", "page");
      expect(within(region).getByRole("button", { name: "Exhibitions" }))
        .not.toHaveAttribute("aria-current");
    }
  });

  it("requires an explicit bounded address selection before saving geocoded fields", async () => {
    const user = userEvent.setup();
    const source = repository();
    render(
      <GalleryInfoWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
      />,
    );

    expect(await screen.findByRole("textbox", { name: "Gallery name (Korean)" })).toHaveValue("갤러리 알파");
    const search = screen.getByRole("searchbox", { name: "Find an address" });
    await user.type(search, "율곡로 3길 4");
    await user.click(screen.getByRole("button", { name: "Search address" }));

    const matches = await screen.findByRole("list", { name: "Address matches" });
    expect(matches).toHaveAttribute("aria-live", "polite");
    expect(within(matches).getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByDisplayValue(info.addressKo)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: `Use this address: ${candidate.roadAddress}` }));
    expect(screen.getByDisplayValue(candidate.roadAddress)).toBeInTheDocument();
    expect(screen.getByDisplayValue(String(candidate.latitude))).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save Gallery Info" }));

    await waitFor(() => expect(source.saveGalleryInfo).toHaveBeenCalledWith(
      7,
      expect.objectContaining({
        addressKo: candidate.roadAddress,
        addressEn: candidate.englishAddress,
        latitude: candidate.latitude,
        longitude: candidate.longitude,
      }),
    ));
    expect(await screen.findByText("Gallery Info · Saved")).toBeInTheDocument();
  });

  it("clears an unsaved candidate when the search query changes", async () => {
    const user = userEvent.setup();
    const source = repository();
    render(
      <GalleryInfoWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
      />,
    );

    const search = await screen.findByRole("searchbox", { name: "Find an address" });
    await user.type(search, "율곡로 3길 4");
    await user.click(screen.getByRole("button", { name: "Search address" }));
    await user.click(await screen.findByRole("button", { name: `Use this address: ${candidate.roadAddress}` }));
    expect(screen.getByDisplayValue(candidate.roadAddress)).toBeInTheDocument();

    await user.type(search, " 변경");
    expect(screen.getByDisplayValue(info.addressKo)).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Address matches" })).not.toBeInTheDocument();
  });

  it("locks the address query while a lookup is pending", async () => {
    const user = userEvent.setup();
    const source = repository();
    let resolveSearch!: (matches: GalleryGeocodeCandidate[]) => void;
    vi.mocked(source.searchGalleryAddress).mockImplementation(() => new Promise((resolve) => {
      resolveSearch = resolve;
    }));
    render(
      <GalleryInfoWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
      />,
    );

    const search = await screen.findByRole("searchbox", { name: "Find an address" });
    await user.type(search, "율곡로 3길 4");
    await user.click(screen.getByRole("button", { name: "Search address" }));

    expect(search).toBeDisabled();
    expect(screen.getByRole("button", { name: "Searching…" })).toBeDisabled();

    resolveSearch([candidate]);
    expect(await screen.findByRole("list", { name: "Address matches" })).toBeInTheDocument();
    await waitFor(() => expect(search).toBeEnabled());
  });

  it("announces a completed address lookup with no bounded matches", async () => {
    const user = userEvent.setup();
    const source = repository();
    vi.mocked(source.searchGalleryAddress).mockResolvedValue([]);
    render(
      <GalleryInfoWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
      />,
    );

    const search = await screen.findByRole("searchbox", { name: "Find an address" });
    await user.type(search, "찾을 수 없는 주소");
    await user.click(screen.getByRole("button", { name: "Search address" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "No address matches found. Try a road name or a broader search.",
    );
    expect(screen.queryByRole("list", { name: "Address matches" })).not.toBeInTheDocument();
  });

  it("announces stale revision failures without discarding edits", async () => {
    const user = userEvent.setup();
    const source = repository();
    vi.mocked(source.saveGalleryInfo).mockRejectedValue(
      new Error("owner_save_gallery_info failed [40001]: revision_conflict DETAIL: current revision 8"),
    );
    render(
      <GalleryInfoWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
      />,
    );

    const contact = await screen.findByRole("textbox", { name: "Contact" });
    await user.clear(contact);
    await user.type(contact, "new@example.test");
    await user.click(screen.getByRole("button", { name: "Save Gallery Info" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Gallery Info changed elsewhere. Reload and try again.");
    expect(screen.getByRole("alert")).not.toHaveTextContent("owner_save_gallery_info");
    expect(screen.getByRole("alert")).not.toHaveTextContent("current revision 8");
    expect(contact).toHaveValue("new@example.test");
  });

  it("does not expose unknown geocoder failure details", async () => {
    const user = userEvent.setup();
    const source = repository();
    vi.mocked(source.searchGalleryAddress).mockRejectedValue(
      new Error("upstream socket failure at internal-host.example:5432"),
    );
    render(
      <GalleryInfoWorkspace
        repository={source}
        onNavigate={vi.fn()}
        onSignOut={vi.fn()}
      />,
    );

    const search = await screen.findByRole("searchbox", { name: "Find an address" });
    await user.type(search, "율곡로 3길 4");
    await user.click(screen.getByRole("button", { name: "Search address" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Address search failed.");
    expect(screen.getByRole("alert")).not.toHaveTextContent("internal-host");
  });
});
