import { render, screen } from "@testing-library/react";
import {
  getAdminExhibitionValidation,
  getPublishReadiness,
  type AdminGeocodeCandidate,
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
    latitude: "37.5344",
    longitude: "127.0005",
  },
  {
    roadAddress: "서울 용산구 이태원로 55",
    jibunAddress: "서울 용산구 한남동 2-2",
    englishAddress: "55 Itaewon-ro, Yongsan-gu, Seoul",
    latitude: "37.5348",
    longitude: "127.0010",
  },
];

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
    onPreview: vi.fn(),
    onPublish: vi.fn(),
    onArchive: vi.fn(),
    onRestore: vi.fn(),
    onMediaUpload: vi.fn(),
    onMediaMetadataSave: vi.fn(),
    onMediaReorder: vi.fn(),
    onMediaDetach: vi.fn(),
    onMediaErrorClear: vi.fn(),
    onFindCoordinates: vi.fn(),
    onApplyGeocodeCandidate: vi.fn(),
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
