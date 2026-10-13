import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import {
  CuratorNoteNotStaffError,
  CuratorNoteStaleError,
  type AdminCuratorNoteRepository,
  type CuratorNoteFacts,
  type CuratorNoteQueueItem,
  type CuratorNoteVersion,
} from "../repositories/AdminCuratorNoteRepository";
import { CuratorNotesWorkspace } from "./CuratorNotesWorkspace";
import { exportCarousel } from "../export/carouselCanvas";

const VERSION_ID = "7a1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";
const REVISION = "2026-10-11T18:00:00Z";

const version: CuratorNoteVersion = {
  id: VERSION_ID,
  exhibitionId: "who-lived-happily",
  versionNumber: 1,
  status: "draft",
  hookKo: "곰 한 마리가 동화 속으로 들어간 이유",
  hookEn: "Why a bear walked into a fairy tale",
  insightsKo: [
    { text: "서울 첫 개인전이라는 사실보다 중요한 것은 네 번째 전시라는 숫자다.", grounding: ["description_ko"] },
    { text: "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역한다.", grounding: ["description_ko", "artists"] },
  ],
  insightsEn: [
    { text: "A first solo show in Seoul matters less than the number four.", grounding: ["description_en"] },
    { text: "Each room changes colour to stage the next disguise.", grounding: ["description_en"] },
  ],
  tipKo: "복도 끝의 가장 어두운 방부터 보라.",
  tipEn: "Start with the darkest room.",
  unsupportedRefs: [],
  edited: false,
  producer: "claude",
  model: "claude-opus-5-5",
  promptVersion: "2026-10-11.1",
  sourceFields: ["artists", "description_en", "description_ko", "name_ko"],
  sourceHash: "a".repeat(64),
  requestKind: "scheduled",
  generatedAt: "2026-10-11T18:00:00Z",
  rejectionCode: null,
  reviewerNameKo: null,
  reviewerNameEn: null,
  decidedAt: null,
  declineReason: null,
  declineNote: null,
  approvedAt: null,
  revision: REVISION,
};

const queueItem: CuratorNoteQueueItem = {
  versionId: VERSION_ID,
  exhibitionId: "who-lived-happily",
  nameKo: "Who Lived Happily Ever After? | 사이먼 후지와라",
  nameEn: "Who Lived Happily Ever After?",
  isFeatured: true,
  closingDate: "2026-10-31",
  versionNumber: 1,
  promptVersion: "2026-10-11.1",
  generatedAt: "2026-10-11T18:00:00Z",
  requestKind: "scheduled",
  unsupportedCount: 0,
  edited: false,
  revision: REVISION,
};

const facts: CuratorNoteFacts = {
  exhibitionId: "who-lived-happily",
  autoDraftBlocked: false,
  approvedVersionId: null,
  qualifies: true,
  packet: {
    name_ko: "Who Lived Happily Ever After? | 사이먼 후지와라",
    description_ko: "에스더쉬퍼 서울은 사이먼 후지와라의 서울 첫 개인전을 선보인다.",
    description_en: "The gallery presents Simon Fujiwara's first solo exhibition in Seoul.",
    venue_name_ko: "에스더쉬퍼",
    artists: [{ id: "a1", name_ko: "사이먼 후지와라", name_en: "Simon Fujiwara" }],
  },
  packetHash: "a".repeat(64),
  coverImageUrl: "https://images.example.test/who-lived.jpg",
  images: [
    { url: "https://images.example.test/who-lived.jpg", role: "cover", credit: "ⓒ 에스더쉬퍼", altKo: "", altEn: "" },
    { url: "https://images.example.test/room-one.jpg", role: "gallery", credit: "사진 김지원", altKo: "전시 전경", altEn: "Installation view" },
  ],
  versions: [version],
};

function repositoryWith(overrides: Partial<AdminCuratorNoteRepository> = {}): AdminCuratorNoteRepository {
  return {
    listQueue: vi.fn().mockResolvedValue([queueItem]),
    get: vi.fn().mockResolvedValue(facts),
    saveEdit: vi.fn().mockResolvedValue({ ...version, hookKo: "바뀐 훅", edited: true, revision: "r2" }),
    markSentence: vi.fn().mockResolvedValue({ ...version, unsupportedRefs: ["ko:insight:0"], revision: "r2" }),
    approve: vi.fn().mockResolvedValue({ ...version, status: "approved", reviewerNameKo: "하신", revision: "r3" }),
    decline: vi.fn().mockResolvedValue({ ...version, status: "declined", declineReason: "tone", revision: "r3" }),
    requestDraft: vi.fn().mockResolvedValue({ outcome: "drafted", code: null }),
    recordExport: vi.fn().mockResolvedValue(version),
    listApproved: vi.fn().mockResolvedValue([]),
    recordRoundupExport: vi.fn().mockResolvedValue(0),
    ...overrides,
  };
}

function renderWorkspace(repository: AdminCuratorNoteRepository) {
  return render(
    <LocaleProvider initialLocale="en">
      <CuratorNotesWorkspace repository={repository} now={() => new Date("2026-10-11T20:00:00Z")} />
    </LocaleProvider>,
  );
}

async function openFirst(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: /Who Lived Happily Ever After\?/ }));
  await screen.findByRole("heading", { name: "Facts" });
}

describe("CuratorNotesWorkspace", () => {
  it("lists the queue with the featured flag and prompt version, and opens a draft beside its facts", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);

    const row = await screen.findByRole("button", { name: /Who Lived Happily Ever After\?/ });
    expect(within(row).getByText("Featured")).toBeInTheDocument();
    expect(within(row).getByText(/prompt 2026-10-11\.1/)).toBeInTheDocument();

    await openFirst(user);
    expect(repository.get).toHaveBeenCalledWith("who-lived-happily");
    expect(screen.getByText(/에스더쉬퍼 서울은 사이먼 후지와라의/)).toBeInTheDocument();
    expect(screen.getByDisplayValue("곰 한 마리가 동화 속으로 들어간 이유")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Why a bear walked into a fairy tale")).toBeInTheDocument();
    expect(screen.getAllByText("description_ko").length).toBeGreaterThan(0);
  });

  it("saves edited text against the revision staff are looking at", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);
    await openFirst(user);

    const hook = screen.getByLabelText("Hook", { selector: "textarea#note-ko-hook" });
    await user.clear(hook);
    await user.type(hook, "바뀐 훅");
    await user.click(screen.getByRole("button", { name: "Save edits" }));

    await waitFor(() => expect(repository.saveEdit).toHaveBeenCalledWith(VERSION_ID, { hookKo: "바뀐 훅" }, REVISION));
    expect(await screen.findByText("Edits saved.")).toBeInTheDocument();
    expect(screen.getByText("Edited")).toBeInTheDocument();
  });

  it("blocks approval while a sentence is marked unsupported", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);
    await openFirst(user);

    const approve = screen.getByRole("button", { name: "Approve" });
    expect(approve).toBeEnabled();
    const insightField = screen.getByLabelText("Insight 1", { selector: "#note-ko-insight-0" }).closest(".field") as HTMLElement;
    await user.click(within(insightField).getByRole("button", { name: "Mark unsupported" }));

    await waitFor(() => expect(repository.markSentence).toHaveBeenCalledWith(VERSION_ID, "ko:insight:0", true, REVISION));
    expect(await screen.findByText("Approval is blocked while a sentence is marked unsupported.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Clear unsupported" })).toBeInTheDocument();
  });

  it("approves with the shown revision and takes the draft out of the queue", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    (repository.listQueue as ReturnType<typeof vi.fn>).mockResolvedValueOnce([queueItem]).mockResolvedValue([]);
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(repository.approve).toHaveBeenCalledWith(VERSION_ID, REVISION));
    expect(await screen.findByText("Approved and published.")).toBeInTheDocument();
    expect(screen.getByText("Nothing to review")).toBeInTheDocument();
  });

  it("declines only with a reason and sends the optional note", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getByRole("button", { name: "Decline" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Decline this draft?" });
    const confirm = within(dialog).getByRole("button", { name: "Decline" });
    expect(confirm).toBeDisabled();
    await user.click(within(dialog).getByLabelText("Tone or voice"));
    await user.type(within(dialog).getByLabelText("Note (optional)"), "너무 홍보 같아요");
    await user.click(confirm);

    await waitFor(() =>
      expect(repository.decline).toHaveBeenCalledWith(VERSION_ID, "tone", "너무 홍보 같아요", REVISION)
    );
    expect(await screen.findByText("Declined.")).toBeInTheDocument();
  });

  it("asks the function for a fresh draft and reloads", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getByRole("button", { name: "Regenerate" }));

    await waitFor(() => expect(repository.requestDraft).toHaveBeenCalledWith("who-lived-happily", "regenerate"));
    expect(await screen.findByText("A draft is ready in the queue.")).toBeInTheDocument();
    expect(repository.listQueue).toHaveBeenCalledTimes(2);
  });

  it("drafts an exhibition on demand from its id", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);

    await user.type(await screen.findByLabelText("Exhibition ID"), " loop-lab ");
    await user.click(screen.getByRole("button", { name: "Draft now" }));

    await waitFor(() => expect(repository.requestDraft).toHaveBeenCalledWith("loop-lab", "single"));
  });

  it("reloads and says so when the note changed under the editor", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({ approve: vi.fn().mockRejectedValue(new CuratorNoteStaleError()) });
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByText("The note changed since you opened it. It was reloaded; check it again.")).toBeInTheDocument();
    await waitFor(() => expect(repository.get).toHaveBeenCalledTimes(2));
  });

  it("explains a denial instead of an empty page", async () => {
    const repository = repositoryWith({ listQueue: vi.fn().mockRejectedValue(new CuratorNoteNotStaffError()) });
    renderWorkspace(repository);

    expect(await screen.findByText("Only active publishers can review curator notes.")).toBeInTheDocument();
  });
});

vi.mock("../export/carouselCanvas", () => ({
  exportCarousel: vi.fn().mockResolvedValue({
    archive: new Uint8Array([1, 2, 3]),
    slideCount: 5,
    layout: { omitted: [] },
  }),
  CarouselExportTimeoutError: class extends Error {},
}));

describe("CuratorNotesWorkspace roundup", () => {
  it("opens the roundup dialog from the header and loads the approved notes", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);

    await user.click(await screen.findByRole("button", { name: "Roundup carousel" }));

    expect(await screen.findByRole("dialog", { name: "Export a roundup carousel" })).toBeInTheDocument();
    await waitFor(() => expect(repository.listApproved).toHaveBeenCalledTimes(1));
  });
});

describe("CuratorNotesWorkspace export", () => {
  it("exports an approved version as a carousel and records it", async () => {
    const user = userEvent.setup();
    const approved: CuratorNoteVersion = { ...version, id: "approved-1", status: "approved", reviewerNameKo: "김지원", approvedAt: "2026-10-11T19:00:00Z" };
    const repository = repositoryWith({
      get: vi.fn().mockResolvedValue({ ...facts, approvedVersionId: "approved-1", versions: [version, approved] }),
    });
    const createObjectURL = vi.fn().mockReturnValue("blob:carousel");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL }));
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getByRole("button", { name: "Export carousel" }));
    const dialog = await screen.findByRole("dialog", { name: "Export as an Instagram carousel" });
    expect(within(dialog).getByLabelText("English draft")).toBeEnabled();
    expect(within(dialog).getByText(/The exhibition has 1 more images under Media/)).toBeInTheDocument();
    await user.click(within(dialog).getByLabelText("English draft"));
    await user.click(within(dialog).getByRole("button", { name: "Download archive" }));

    await waitFor(() => expect(repository.recordExport).toHaveBeenCalledWith("approved-1", "en", 5));
    expect(vi.mocked(exportCarousel)).toHaveBeenCalledWith(
      expect.objectContaining({
        coverUrl: "https://images.example.test/who-lived.jpg",
        coverCredit: "ⓒ 에스더쉬퍼",
        gallery: [{ url: "https://images.example.test/room-one.jpg", credit: "사진 김지원" }],
        language: "en",
      }),
    );
    expect(createObjectURL).toHaveBeenCalled();
    expect(await within(dialog).findByText(/The archive downloaded/)).toBeInTheDocument();
  });
});
