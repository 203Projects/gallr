import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
import { ArtistMergeInvalidError } from "../repositories/AdminExhibitionRepository";
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

describe("ExhibitionInspector catalogue reliability (spec 101)", () => {
  const unconfirmed = { weekly: null, confirmedAt: null, stale: false };
  const scheduleProps = (overrides: Record<string, unknown> = {}) => {
    const props = inspectorProps();
    return {
      ...props,
      section: "Schedule" as const,
      exhibition: { ...props.exhibition, hours: "화–일 11:00–18:00", confirmedHours: unconfirmed },
      onConfirmHours: vi.fn(),
      ...overrides,
    };
  };

  it("prefills the weekly editor from the hours text and confirms the seven days", async () => {
    const user = userEvent.setup();
    const props = scheduleProps();
    render(<ExhibitionInspector {...props} />);

    expect(screen.getByRole("checkbox", { name: "Monday closed" })).toBeChecked();
    expect(screen.getByLabelText("Tuesday opens")).toHaveValue("11:00");
    expect(screen.getByLabelText("Sunday closes")).toHaveValue("18:00");
    expect(screen.getByText(/Filled in from the hours text/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Saturday closes"), { target: { value: "20:00" } });
    await user.click(screen.getByRole("button", { name: "Confirm hours" }));
    expect(props.onConfirmHours).toHaveBeenCalledWith({
      MONDAY: null,
      TUESDAY: { opens: "11:00", closes: "18:00" },
      WEDNESDAY: { opens: "11:00", closes: "18:00" },
      THURSDAY: { opens: "11:00", closes: "18:00" },
      FRIDAY: { opens: "11:00", closes: "18:00" },
      SATURDAY: { opens: "11:00", closes: "20:00" },
      SUNDAY: { opens: "11:00", closes: "18:00" },
    });
  });

  it("blocks Confirm hours on an invalid day, unsaved edits or a non-publisher", () => {
    const { rerender } = render(<ExhibitionInspector {...scheduleProps()} />);
    fireEvent.change(screen.getByLabelText("Friday opens"), { target: { value: "19:00" } });
    expect(screen.getByRole("group", { name: "Friday" })).toHaveTextContent(
      "Enter an opening time before the closing time.",
    );
    expect(screen.getByRole("button", { name: "Confirm hours" })).toBeDisabled();

    rerender(<ExhibitionInspector {...scheduleProps({ saveState: "dirty" })} />);
    expect(screen.getByRole("button", { name: "Confirm hours" })).toBeDisabled();
    rerender(<ExhibitionInspector {...scheduleProps({ publishAllowed: false })} />);
    expect(screen.getByRole("button", { name: "Confirm hours" })).toBeDisabled();
  });

  it("shows confirmed hours, the stale badge and the day the server refused", () => {
    const props = scheduleProps();
    const confirmed = {
      weekly: {
        MONDAY: null,
        TUESDAY: null,
        WEDNESDAY: { opens: "12:00", closes: "19:00" },
        THURSDAY: { opens: "12:00", closes: "19:00" },
        FRIDAY: { opens: "12:00", closes: "19:00" },
        SATURDAY: { opens: "12:00", closes: "19:00" },
        SUNDAY: { opens: "12:00", closes: "19:00" },
      },
      confirmedAt: "2026-10-21T02:00:00.000Z",
      stale: false,
    };
    const { rerender } = render(
      <ExhibitionInspector {...props} exhibition={{ ...props.exhibition, confirmedHours: confirmed }} />,
    );
    expect(screen.getByRole("checkbox", { name: "Tuesday closed" })).toBeChecked();
    expect(screen.getByText(/Showing the confirmed hours/)).toBeInTheDocument();
    expect(screen.queryByText("STALE")).not.toBeInTheDocument();

    rerender(
      <ExhibitionInspector
        {...props}
        exhibition={{ ...props.exhibition, confirmedHours: { ...confirmed, stale: true } }}
        hoursRefusal={{ day: "SATURDAY" }}
      />,
    );
    expect(screen.getByText("STALE")).toBeInTheDocument();
    expect(screen.getByText(/The hours text changed after these hours were confirmed/)).toBeInTheDocument();
    // A stale confirmation no longer prefills: the rows are read from the current text again.
    expect(screen.getByRole("checkbox", { name: "Tuesday closed" })).not.toBeChecked();
    const saturday = screen.getByRole("group", { name: "Saturday" });
    expect(within(saturday).getByLabelText("Saturday opens")).toHaveAttribute("aria-invalid", "true");
    expect(saturday).toHaveTextContent("The server refused Saturday. Check its times.");
  });

  it("hides the weekly editor on a server without confirmed hours", () => {
    const props = scheduleProps();
    render(<ExhibitionInspector {...props} exhibition={{ ...props.exhibition, confirmedHours: undefined }} />);
    expect(screen.queryByRole("button", { name: "Confirm hours" })).not.toBeInTheDocument();
  });

  it("offers Confirm terms on a stale exhibition", async () => {
    const user = userEvent.setup();
    const props = inspectorProps();
    const onConfirmTerms = vi.fn();
    render(
      <ExhibitionInspector
        {...props}
        section="Art"
        exhibition={{ ...props.exhibition, metadataReview: { stale: true, reviewedAt: null } }}
        onConfirmTerms={onConfirmTerms}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Confirm terms" }));
    expect(onConfirmTerms).toHaveBeenCalledTimes(1);
  });

  it("opens the duplicate report and merges for the admin role only", async () => {
    const user = userEvent.setup();
    const keep = { id: "71000000-0000-4000-8000-000000000001", nameKo: "김민정", nameEn: "Minjung Kim", creditCount: 4 };
    const drop = { id: "71000000-0000-4000-8000-000000000003", nameKo: "김 민정", nameEn: "Kim Min-jung", creditCount: 1 };
    const onFindDuplicates = vi.fn()
      .mockResolvedValueOnce([{ key: "김민정", artists: [drop, keep] }])
      .mockResolvedValue([]);
    const onMerge = vi.fn().mockResolvedValue({
      survivorId: keep.id,
      mergedId: drop.id,
      movedCredits: 1,
      removedDuplicateCredits: 0,
    });
    const { rerender } = render(<ExhibitionInspector {...inspectorProps()} section="Art" />);
    expect(screen.queryByRole("button", { name: "Duplicate artists" })).not.toBeInTheDocument();

    rerender(<ExhibitionInspector {...inspectorProps()} section="Art" artistMerge={{ onFindDuplicates, onMerge }} />);
    await user.click(screen.getByRole("button", { name: "Duplicate artists" }));
    const dialog = await screen.findByRole("dialog", { name: "Duplicate artists" });
    const group = await within(dialog).findByRole("group", { name: "Same name: 김민정" });
    // The artist with more credits is kept by default.
    expect(within(group).getByRole("combobox", { name: "Keep" })).toHaveValue(keep.id);
    expect(within(group).getByRole("combobox", { name: "Merge away" })).toHaveValue(drop.id);
    const merge = within(group).getByRole("button", { name: "Merge" });
    expect(merge).toBeDisabled();

    await user.type(within(group).getByRole("textbox", { name: "Reason" }), " 같은 작가 ");
    await user.click(merge);
    expect(onMerge).toHaveBeenCalledWith(keep.id, drop.id, "같은 작가");
    expect(await within(dialog).findByText(/Merged\. 1 credits moved/)).toBeInTheDocument();
    await waitFor(() => expect(within(dialog).getByText("No duplicate artists.")).toBeInTheDocument());
  });

  it("explains a refused merge", async () => {
    const user = userEvent.setup();
    const artists = [
      { id: "71000000-0000-4000-8000-000000000001", nameKo: "김민정", nameEn: "Minjung Kim", creditCount: 2 },
      { id: "71000000-0000-4000-8000-000000000003", nameKo: "김 민정", nameEn: "Kim Min-jung", creditCount: 1 },
    ];
    render(
      <ExhibitionInspector
        {...inspectorProps()}
        section="Art"
        artistMerge={{
          onFindDuplicates: vi.fn().mockResolvedValue([{ key: "김민정", artists }]),
          onMerge: vi.fn().mockRejectedValue(new ArtistMergeInvalidError("merged_archived")),
        }}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Duplicate artists" }));
    const group = await screen.findByRole("group", { name: "Same name: 김민정" });
    fireEvent.change(within(group).getByRole("combobox", { name: "Merge away" }), { target: { value: artists[0].id } });
    expect(within(group).getByText("Choose two different artists.")).toBeInTheDocument();
    fireEvent.change(within(group).getByRole("combobox", { name: "Merge away" }), { target: { value: artists[1].id } });
    await user.type(within(group).getByRole("textbox", { name: "Reason" }), "같은 작가");
    await user.click(within(group).getByRole("button", { name: "Merge" }));
    expect(await screen.findByText("! The merge was refused: one of the artists is already archived."))
      .toBeInTheDocument();
  });
});
