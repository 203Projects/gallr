import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  getAdminExhibitionValidation,
  getPublishReadiness,
  type AdminGeocodeCandidate,
  type AdminMediaAsset,
} from "../domain";
import {
  exhibitionFixtures,
  exhibitionLookupFixtures,
} from "../data/fixtures";
import { ExhibitionInspector } from "./ExhibitionInspector";

const candidates: AdminGeocodeCandidate[] = [
  {
    roadAddress: "서울 용산구 한남대로 28",
    jibunAddress: "서울 용산구 한남동 1-1",
    englishAddress: "28 Hannam-daero, Yongsan-gu, Seoul",
    cityKo: "서울",
    cityEn: "Seoul",
    regionKo: "용산구",
    regionEn: "Yongsan-gu",
    latitude: "37.5344",
    longitude: "127.0005",
  },
  {
    roadAddress: "서울 용산구 이태원로 55",
    jibunAddress: "서울 용산구 한남동 2-2",
    englishAddress: "55 Itaewon-ro, Yongsan-gu, Seoul",
    cityKo: "서울",
    cityEn: "Seoul",
    regionKo: "용산구",
    regionEn: "Yongsan-gu",
    latitude: "37.5348",
    longitude: "127.0010",
  },
];

const processingCover: AdminMediaAsset = {
  assetId: "processing-cover",
  versionId: exhibitionFixtures[0].workingVersionId,
  role: "cover",
  sortOrder: 0,
  status: "ready",
  bucketId: "exhibition-media",
  objectPath: "processing/cover.jpg",
  mimeType: "image/jpeg",
  byteSize: 1024,
  width: 1600,
  height: 1067,
  checksumSha256: null,
  publicUrl: null,
  altKo: "",
  altEn: "Processing cover",
  credit: "",
  rightsUrl: "",
  originalFilename: "cover.jpg",
  createdAt: "2026-07-30T10:00:00.000Z",
  updatedAt: "2026-07-30T10:00:00.000Z",
  previewUrl: "https://images.example.test/private-preview.jpg",
};

function inspectorProps() {
  const exhibition = exhibitionFixtures[0];
  return {
    exhibition,
    section: "Venue" as const,
    saveState: "saved" as const,
    readiness: getPublishReadiness(exhibition),
    validation: getAdminExhibitionValidation(exhibition),
    lookups: exhibitionLookupFixtures,
    lookupsLoading: false,
    lookupsError: null,
    publishAllowed: true,
    deleteAllowed: true,
    lifecycleBusy: false,
    media: [],
    mediaLoading: false,
    mediaBusy: false,
    mediaError: null,
    mediaEditable: true,
    mediaReadOnlyReason: null,
    geocodeCandidates: [] as AdminGeocodeCandidate[],
    geocodeLoading: false,
    geocodeError: null,
    geocodingMode: "fixture" as const,
    onSectionChange: vi.fn(),
    onClose: vi.fn(),
    onChange: vi.fn(),
    onSearchArtists: vi.fn().mockResolvedValue([]),
    onCreateArtist: vi.fn(),
    onPreview: vi.fn(),
    onPublish: vi.fn(),
    onArchive: vi.fn(),
    onRestore: vi.fn(),
    onDiscard: vi.fn(),
    onDelete: vi.fn(),
    onManageMedia: vi.fn(),
    onMediaUpload: vi.fn(),
    onMediaMetadataSave: vi.fn(),
    onMediaReorder: vi.fn(),
    onMediaDetach: vi.fn(),
    onMediaErrorClear: vi.fn(),
    onFindCoordinates: vi.fn(),
    onApplyGeocodeCandidate: vi.fn(),
    onApplyVenue: vi.fn(),
    onLocationChange: vi.fn(),
  };
}

describe("ExhibitionInspector geocoding results", () => {
  it("identifies fixture lookup and names the only supported sample address", () => {
    render(<ExhibitionInspector {...inspectorProps()} />);

    expect(screen.getByText(/Fixture-only lookup/i)).toHaveTextContent(
      "서울 용산구 한남대로 28",
    );
    expect(screen.queryByText(/Searches NAVER Maps/i)).not.toBeInTheDocument();
  });

  it("identifies live lookup as a NAVER Maps search", () => {
    render(
      <ExhibitionInspector
        {...inspectorProps()}
        geocodingMode="naver-server"
      />,
    );

    expect(screen.getByText(/Searches NAVER Maps/i)).toBeInTheDocument();
    expect(screen.queryByText(/Fixture-only lookup/i)).not.toBeInTheDocument();
  });

  it("announces loading and the result count through one polite live region", () => {
    const props = inspectorProps();
    const { rerender } = render(
      <ExhibitionInspector {...props} geocodeLoading />,
    );

    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toHaveTextContent("Searching for address matches…");

    rerender(
      <ExhibitionInspector
        {...props}
        geocodeCandidates={candidates}
        geocodeLoading={false}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent(
      "2 address matches found.",
    );
  });

  it("gives every map review link an address-specific accessible name", () => {
    render(
      <ExhibitionInspector
        {...inspectorProps()}
        geocodeCandidates={candidates}
      />,
    );

    for (const candidate of candidates) {
      expect(
        screen.getByRole("link", {
          name: `Review ${candidate.roadAddress} on NAVER Maps`,
        }),
      ).toBeInTheDocument();
    }
  });
});

describe("ExhibitionInspector approved locations", () => {
  it("uses canonical selectors and fills bilingual labels together", async () => {
    const user = userEvent.setup();
    const props = inspectorProps();
    const onLocationChange = vi.fn();
    const { rerender } = render(
      <ExhibitionInspector
        {...props}
        onLocationChange={onLocationChange}
      />,
    );

    await user.selectOptions(
      screen.getByRole("combobox", { name: "City / province" }),
      "전북",
    );
    expect(onLocationChange).toHaveBeenLastCalledWith({
      cityKo: "전북",
      cityEn: "Jeonbuk",
      regionKo: "",
      regionEn: "",
    });

    rerender(
      <ExhibitionInspector
        {...props}
        exhibition={{
          ...props.exhibition,
          cityKo: "전북",
          cityEn: "Jeonbuk",
          regionKo: "",
          regionEn: "",
        }}
        onLocationChange={onLocationChange}
      />,
    );
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Region" }),
      "완주군",
    );
    expect(onLocationChange).toHaveBeenLastCalledWith({
      cityKo: "전북",
      cityEn: "Jeonbuk",
      regionKo: "완주군",
      regionEn: "Wanju-gun",
    });
  });
});

describe("ExhibitionInspector publish readiness", () => {
  it("explains that a processing image will unlock Publish automatically", () => {
    const props = inspectorProps();
    render(
      <ExhibitionInspector
        {...props}
        media={[processingCover]}
        readiness={getPublishReadiness(
          props.exhibition,
          [processingCover],
          true,
        )}
      />,
    );

    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    expect(
      screen.getByText(
        "Image processing is automatic. Publish will unlock when it finishes, usually within one minute.",
      ),
    ).toBeInTheDocument();
  });
});

describe("ExhibitionInspector art metadata", () => {
  it("blocks publication while an owner artist suggestion is unresolved", () => {
    const props = inspectorProps();
    render(
      <ExhibitionInspector
        {...props}
        section="Art"
        exhibition={{
          ...props.exhibition,
          artMetadata: {
            artists: [{ id: null, nameKo: "새 작가", nameEn: "New Artist" }],
            terms: [],
          },
        }}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Resolve every artist suggestion before publishing.",
    );
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    expect(screen.getByRole("list", { name: "Ordered artist credits" }))
      .toBeInTheDocument();
  });
});
