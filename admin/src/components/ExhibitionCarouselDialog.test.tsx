import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import type {
  AdminCuratorNoteRepository,
  CarouselExhibition,
  CuratorNoteFacts,
  CuratorNoteVersion,
} from "../repositories/AdminCuratorNoteRepository";
import { exportCarousel } from "../export/carouselCanvas";
import { exportRoundup, RoundupPosterUnavailableError } from "../export/roundupCanvas";
import { ExhibitionCarouselDialog, seoulToday } from "./ExhibitionCarouselDialog";

vi.mock("../export/roundupCanvas", () => ({
  exportRoundup: vi.fn(),
  RoundupPosterUnavailableError: class extends Error {
    constructor(readonly exhibitionId: string) {
      super("no cover");
    }
  },
}));
vi.mock("../export/carouselCanvas", () => ({ exportCarousel: vi.fn(), CarouselExportTimeoutError: class extends Error {} }));

function exhibition(id: string, closingDate: string, overrides: Partial<CarouselExhibition> = {}): CarouselExhibition {
  return {
    exhibitionId: id,
    versionId: `version-${id}`,
    hookKo: `${id} 훅`,
    hookEn: `${id} hook`,
    nameKo: `${id} 전시`,
    nameEn: `Show ${id}`,
    venueNameKo: "갤러리",
    venueNameEn: "Gallery",
    regionKo: "종로구",
    regionEn: "Jongno-gu",
    openingDate: "2026-09-02",
    closingDate,
    coverImageUrl: `https://example.invalid/${id}.jpg`,
    ...overrides,
  };
}

const plain = (id: string, closingDate: string) => exhibition(id, closingDate, { versionId: null, hookKo: "", hookEn: "" });

const exhibitions = [
  exhibition("a", "2026-10-18"),
  plain("p", "2026-10-25"),
  exhibition("b", "2026-10-31"),
  exhibition("no-cover", "2026-10-31", { coverImageUrl: null }),
  exhibition("c", "2026-11-29"),
];

function facts(id: string, versions: CuratorNoteVersion[] = []): CuratorNoteFacts {
  return {
    exhibitionId: id,
    autoDraftBlocked: false,
    approvedVersionId: versions[0]?.id ?? null,
    qualifies: true,
    packet: { name_ko: `${id} 전시`, name_en: `Show ${id}` },
    packetHash: null,
    coverImageUrl: `https://example.invalid/${id}.jpg`,
    images: [{ url: `https://example.invalid/${id}-1.jpg`, role: "gallery", credit: "Photo", altKo: "전경", altEn: "View" }],
    versions,
  };
}

const approvedVersion = { id: "version-a", exhibitionId: "a", versionNumber: 3, status: "approved" } as CuratorNoteVersion;

function renderDialog(overrides: Partial<Record<keyof AdminCuratorNoteRepository, unknown>> = {}) {
  const repository = {
    listCarouselExhibitions: vi.fn().mockResolvedValue(exhibitions),
    get: vi.fn((id: string) => Promise.resolve(id === "a" ? facts("a", [approvedVersion]) : facts(id))),
    recordExport: vi.fn().mockResolvedValue(approvedVersion),
    recordRoundupExport: vi.fn().mockResolvedValue(2),
    ...overrides,
  } as unknown as AdminCuratorNoteRepository;
  const save = vi.fn();
  render(
    <LocaleProvider initialLocale="en">
      <ExhibitionCarouselDialog repository={repository} onClose={() => {}} save={save} today="2026-10-13" />
    </LocaleProvider>,
  );
  return { repository, save };
}

beforeEach(() => {
  vi.mocked(exportRoundup).mockReset();
  vi.mocked(exportRoundup).mockResolvedValue({
    archive: new Uint8Array([1, 2, 3]),
    slideCount: 4,
    layout: { language: "ko", slides: [], caption: "", omitted: [] },
  });
  vi.mocked(exportCarousel).mockReset();
  vi.mocked(exportCarousel).mockResolvedValue({
    archive: new Uint8Array([4, 5]),
    slideCount: 3,
    layout: { language: "ko", slides: [], caption: "", omitted: [] },
  });
});

async function openDialog() {
  const dialog = await screen.findByRole("dialog", { name: "Export an Instagram carousel" });
  await within(dialog).findByText("Show a");
  return dialog;
}

describe("ExhibitionCarouselDialog", () => {
  it("lists every exhibition still on and marks the ones with an approved note", async () => {
    renderDialog();
    const dialog = await openDialog();

    expect(within(dialog).getByRole("checkbox", { name: /Show p/ })).toBeEnabled();
    expect(within(dialog).getByRole("checkbox", { name: /Show a.*approved note/ })).toBeInTheDocument();
    expect(within(dialog).getByRole("checkbox", { name: /Show p/ })).not.toHaveAccessibleName(/approved note/);
    expect(within(dialog).getByRole("checkbox", { name: /Show no-cover/ })).toBeDisabled();
  });

  it("exports one exhibition without a note as its own carousel and records nothing", async () => {
    const user = userEvent.setup();
    const { repository, save } = renderDialog();
    const dialog = await openDialog();

    await user.click(within(dialog).getByRole("checkbox", { name: /Show p/ }));
    expect(within(dialog).getByRole("button", { name: "Download archive" })).toBeEnabled();
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    await waitFor(() => expect(save).toHaveBeenCalledWith(new Uint8Array([4, 5]), "exhibition-carousel-p-ko.zip"));
    expect(repository.get).toHaveBeenCalledWith("p");
    expect(vi.mocked(exportCarousel)).toHaveBeenCalledWith(expect.objectContaining({
      note: null,
      language: "ko",
      coverUrl: "https://example.invalid/p.jpg",
      gallery: [{ url: "https://example.invalid/p-1.jpg", credit: "Photo", altKo: "전경", altEn: "View" }],
    }));
    expect(repository.recordExport).not.toHaveBeenCalled();
    expect(await within(dialog).findByText("The archive downloaded.")).toBeInTheDocument();
  });

  it("exports one exhibition with an approved note as the note's carousel and records it", async () => {
    const user = userEvent.setup();
    const { repository, save } = renderDialog();
    const dialog = await openDialog();

    await user.click(within(dialog).getByRole("checkbox", { name: /Show a/ }));
    await user.click(within(dialog).getByLabelText("English draft"));
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    await waitFor(() => expect(repository.recordExport).toHaveBeenCalledWith("version-a", "en", 3));
    expect(vi.mocked(exportCarousel)).toHaveBeenCalledWith(expect.objectContaining({ note: approvedVersion, language: "en" }));
    expect(save).toHaveBeenCalledWith(new Uint8Array([4, 5]), "curator-note-a-v3-en.zip");
    expect(await within(dialog).findByText("The archive downloaded and the export was recorded.")).toBeInTheDocument();
  });

  it("needs a cover line for two or more, exports them as a roundup in list order and records it when every one has a note", async () => {
    const user = userEvent.setup();
    const { repository, save } = renderDialog();
    const dialog = await openDialog();
    const download = within(dialog).getByRole("button", { name: "Download archive" });

    await user.click(within(dialog).getByRole("checkbox", { name: /Show c/ }));
    await user.click(within(dialog).getByRole("checkbox", { name: /Show a/ }));
    expect(download).toBeDisabled();
    await user.type(within(dialog).getByLabelText("Cover line"), "Two to see");
    expect(within(dialog).getByText("Exhibitions (2 of up to 10)")).toBeInTheDocument();
    await user.click(download);

    await waitFor(() => expect(repository.recordRoundupExport).toHaveBeenCalledWith(["version-a", "version-c"], "ko", 4));
    expect(vi.mocked(exportRoundup)).toHaveBeenCalledWith({ title: "Two to see", entries: [exhibitions[0], exhibitions[4]], language: "ko" });
    expect(save).toHaveBeenCalledWith(new Uint8Array([1, 2, 3]), "curator-roundup-2026-10-13-2-ko.zip");
    expect(await within(dialog).findByText("The archive downloaded and the roundup was recorded.")).toBeInTheDocument();
  });

  it("exports a roundup with exhibitions that have no note, without recording it against the notes", async () => {
    const user = userEvent.setup();
    const { repository, save } = renderDialog();
    const dialog = await openDialog();

    await user.click(within(dialog).getByRole("checkbox", { name: /Show a/ }));
    await user.click(within(dialog).getByRole("checkbox", { name: /Show p/ }));
    await user.type(within(dialog).getByLabelText("Cover line"), "This week");
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(vi.mocked(exportRoundup)).toHaveBeenCalledWith({ title: "This week", entries: [exhibitions[0], exhibitions[1]], language: "ko" });
    expect(repository.recordRoundupExport).not.toHaveBeenCalled();
    expect(
      await within(dialog).findByText("The archive downloaded. It is not recorded against the notes because some exhibitions in it have no approved note."),
    ).toBeInTheDocument();
  });

  it("picks every exhibition closing this month that has a cover and suggests a plain cover line", async () => {
    const user = userEvent.setup();
    renderDialog();
    const dialog = await openDialog();

    await user.click(within(dialog).getByRole("button", { name: "Pick those closing this month" }));

    for (const id of ["a", "p", "b"]) expect(within(dialog).getByRole("checkbox", { name: new RegExp(`Show ${id}`) })).toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: /Show c/ })).not.toBeChecked();
    expect(within(dialog).getByLabelText("Cover line")).toHaveValue("10월에 끝나는 전시 3");
  });

  it("names the exhibition whose cover could not be used and records nothing", async () => {
    const user = userEvent.setup();
    vi.mocked(exportRoundup).mockRejectedValue(new RoundupPosterUnavailableError("a"));
    const { repository, save } = renderDialog();
    const dialog = await openDialog();

    await user.click(within(dialog).getByRole("button", { name: "Pick those closing this month" }));
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The cover image of Show a could not be used");
    expect(save).not.toHaveBeenCalled();
    expect(repository.recordRoundupExport).not.toHaveBeenCalled();
  });

  it("says so when the archive downloaded but the record was refused", async () => {
    const user = userEvent.setup();
    const { save } = renderDialog({ recordRoundupExport: vi.fn().mockRejectedValue(new Error("refused")) });
    const dialog = await openDialog();

    await user.click(within(dialog).getByRole("checkbox", { name: /Show a/ }));
    await user.click(within(dialog).getByRole("checkbox", { name: /Show b/ }));
    await user.type(within(dialog).getByLabelText("Cover line"), "Two");
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    expect(await within(dialog).findByText("The archive downloaded, but the export could not be recorded.")).toBeInTheDocument();
    expect(save).toHaveBeenCalled();
  });

  it("explains an empty list and a failed load", async () => {
    renderDialog({ listCarouselExhibitions: vi.fn().mockResolvedValue([]) });
    expect(await screen.findByText("No published exhibition is on right now.")).toBeInTheDocument();
  });

  it("reads today's date in Seoul", () => {
    expect(seoulToday(new Date("2026-10-13T16:30:00Z"))).toBe("2026-10-14");
    expect(seoulToday(new Date("2026-10-13T14:59:00Z"))).toBe("2026-10-13");
  });
});
