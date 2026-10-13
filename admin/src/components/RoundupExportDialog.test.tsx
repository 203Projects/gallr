import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import type { AdminCuratorNoteRepository, ApprovedCuratorNote } from "../repositories/AdminCuratorNoteRepository";
import { exportRoundup, RoundupPosterUnavailableError } from "../export/roundupCanvas";
import { RoundupExportDialog, seoulToday } from "./RoundupExportDialog";

vi.mock("../export/roundupCanvas", () => ({
  exportRoundup: vi.fn(),
  RoundupPosterUnavailableError: class extends Error {
    constructor(readonly exhibitionId: string) {
      super("no cover");
    }
  },
}));
vi.mock("../export/carouselCanvas", () => ({ CarouselExportTimeoutError: class extends Error {} }));

function note(id: string, closingDate: string, overrides: Partial<ApprovedCuratorNote> = {}): ApprovedCuratorNote {
  return {
    versionId: `version-${id}`,
    exhibitionId: id,
    versionNumber: 1,
    hookKo: `${id} 훅`,
    hookEn: `${id} hook`,
    approvedAt: "2026-10-12T03:00:00Z",
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

const notes = [
  note("a", "2026-10-18"),
  note("b", "2026-10-31"),
  note("no-cover", "2026-10-31", { coverImageUrl: null }),
  note("c", "2026-11-29"),
];

function renderDialog(overrides: Partial<Pick<AdminCuratorNoteRepository, "listApproved" | "recordRoundupExport">> = {}) {
  const repository = {
    listApproved: vi.fn().mockResolvedValue(notes),
    recordRoundupExport: vi.fn().mockResolvedValue(2),
    ...overrides,
  } as unknown as AdminCuratorNoteRepository;
  const save = vi.fn();
  render(
    <LocaleProvider initialLocale="en">
      <RoundupExportDialog repository={repository} onClose={() => {}} save={save} today="2026-10-13" />
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
});

describe("RoundupExportDialog", () => {
  it("waits for two exhibitions and a cover line before it can export", async () => {
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole("dialog", { name: "Export a roundup carousel" });
    const download = within(dialog).getByRole("button", { name: "Download archive" });

    expect(await within(dialog).findByText("Show a")).toBeInTheDocument();
    expect(download).toBeDisabled();
    await user.click(within(dialog).getByRole("checkbox", { name: /Show a/ }));
    await user.type(within(dialog).getByLabelText("Cover line"), "Two to see");
    expect(download).toBeDisabled();
    await user.click(within(dialog).getByRole("checkbox", { name: /Show c/ }));
    expect(download).toBeEnabled();
    expect(within(dialog).getByRole("checkbox", { name: /Show no-cover/ })).toBeDisabled();
    expect(within(dialog).getByText("Exhibitions (2 of up to 10)")).toBeInTheDocument();
  });

  it("exports the picked exhibitions in list order, saves the archive and records the roundup", async () => {
    const user = userEvent.setup();
    const { repository, save } = renderDialog();
    const dialog = await screen.findByRole("dialog", { name: "Export a roundup carousel" });
    await within(dialog).findByText("Show a");

    await user.click(within(dialog).getByRole("checkbox", { name: /Show c/ }));
    await user.click(within(dialog).getByRole("checkbox", { name: /Show a/ }));
    await user.type(within(dialog).getByLabelText("Cover line"), "Two to see");
    await user.click(within(dialog).getByLabelText("English draft"));
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    await waitFor(() => expect(repository.recordRoundupExport).toHaveBeenCalledWith(["version-a", "version-c"], "en", 4));
    expect(vi.mocked(exportRoundup)).toHaveBeenCalledWith({ title: "Two to see", entries: [notes[0], notes[3]], language: "en" });
    expect(save).toHaveBeenCalledWith(new Uint8Array([1, 2, 3]), "curator-roundup-2026-10-13-2-en.zip");
    expect(await within(dialog).findByText("The archive downloaded and the roundup was recorded.")).toBeInTheDocument();
  });

  it("picks the exhibitions closing this month and suggests a plain cover line", async () => {
    const user = userEvent.setup();
    renderDialog();
    const dialog = await screen.findByRole("dialog", { name: "Export a roundup carousel" });
    await within(dialog).findByText("Show a");

    await user.click(within(dialog).getByRole("button", { name: "Pick those closing this month" }));

    expect(within(dialog).getByRole("checkbox", { name: /Show a/ })).toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: /Show b/ })).toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: /Show c/ })).not.toBeChecked();
    expect(within(dialog).getByLabelText("Cover line")).toHaveValue("10월에 끝나는 전시 2");
  });

  it("names the exhibition whose cover could not be used and records nothing", async () => {
    const user = userEvent.setup();
    vi.mocked(exportRoundup).mockRejectedValue(new RoundupPosterUnavailableError("a"));
    const { repository, save } = renderDialog();
    const dialog = await screen.findByRole("dialog", { name: "Export a roundup carousel" });
    await within(dialog).findByText("Show a");

    await user.click(within(dialog).getByRole("button", { name: "Pick those closing this month" }));
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The cover image of Show a could not be used");
    expect(save).not.toHaveBeenCalled();
    expect(repository.recordRoundupExport).not.toHaveBeenCalled();
  });

  it("says so when the archive downloaded but the record was refused", async () => {
    const user = userEvent.setup();
    const { save } = renderDialog({ recordRoundupExport: vi.fn().mockRejectedValue(new Error("refused")) });
    const dialog = await screen.findByRole("dialog", { name: "Export a roundup carousel" });
    await within(dialog).findByText("Show a");

    await user.click(within(dialog).getByRole("button", { name: "Pick those closing this month" }));
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    expect(await within(dialog).findByText("The archive downloaded, but the roundup could not be recorded.")).toBeInTheDocument();
    expect(save).toHaveBeenCalled();
  });

  it("explains an empty list and a failed load", async () => {
    renderDialog({ listApproved: vi.fn().mockResolvedValue([notes[0]]) });
    expect(await screen.findByText("A roundup needs at least two approved notes of exhibitions that are still on.")).toBeInTheDocument();
  });

  it("reads today's date in Seoul", () => {
    expect(seoulToday(new Date("2026-10-13T16:30:00Z"))).toBe("2026-10-14");
    expect(seoulToday(new Date("2026-10-13T14:59:00Z"))).toBe("2026-10-13");
  });
});
