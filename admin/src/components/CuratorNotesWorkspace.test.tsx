import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import {
  CuratorNoteNotStaffError,
  CuratorNoteSourceChangedError,
  CuratorNoteStaleError,
  type AdminCuratorNoteRepository,
  type ApprovedCuratorNote,
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
  stale: false,
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
  stale: false,
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

function renderWorkspace(
  repository: AdminCuratorNoteRepository,
  {
    draftingEnabled = false,
    onOpenExhibition,
  }: { draftingEnabled?: boolean; onOpenExhibition?: (exhibitionId: string) => void } = {},
) {
  return render(
    <LocaleProvider initialLocale="en">
      <CuratorNotesWorkspace
        repository={repository}
        draftingEnabled={draftingEnabled}
        onOpenExhibition={onOpenExhibition}
        now={() => new Date("2026-10-11T20:00:00Z")}
      />
    </LocaleProvider>,
  );
}

const DESK_ONLY_LINE = "Drafts are written at the desk; see the curator-notes skill.";

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

  it("labels an API draft by producer in the version meta line", async () => {
    const user = userEvent.setup();
    renderWorkspace(repositoryWith());
    await openFirst(user);
    expect(screen.getByText(/v1 · API · claude-opus-5-5 · prompt 2026-10-11\.1 · scheduled/)).toBeInTheDocument();
  });

  it("labels a desk draft by producer in the version meta line", async () => {
    const user = userEvent.setup();
    renderWorkspace(repositoryWith({
      get: vi.fn().mockResolvedValue({ ...facts, versions: [{ ...version, producer: "claude-desk" }] }),
    }));
    await openFirst(user);
    expect(screen.getByText(/v1 · Desk · claude-opus-5-5 · prompt 2026-10-11\.1 · scheduled/)).toBeInTheDocument();
  });

  it("shows an unknown producer verbatim", async () => {
    const user = userEvent.setup();
    renderWorkspace(repositoryWith({
      get: vi.fn().mockResolvedValue({ ...facts, versions: [{ ...version, producer: "hand" }] }),
    }));
    await openFirst(user);
    expect(screen.getByText(/v1 · hand · claude-opus-5-5 · prompt/)).toBeInTheDocument();
  });

  it("hides Draft now and Regenerate and points to the desk when drafting is not configured", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);

    await screen.findByRole("button", { name: /Who Lived Happily Ever After\?/ });
    expect(screen.getByText(DESK_ONLY_LINE)).toBeInTheDocument();
    expect(screen.queryByLabelText("Exhibition ID")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Draft now" })).not.toBeInTheDocument();

    await openFirst(user);
    expect(screen.queryByRole("button", { name: "Regenerate" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save edits" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Decline" })).toBeEnabled();
    expect(repository.requestDraft).not.toHaveBeenCalled();
  });

  it("asks the function for a fresh draft and reloads", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository, { draftingEnabled: true });
    await openFirst(user);
    expect(screen.queryByText(DESK_ONLY_LINE)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Regenerate" }));

    await waitFor(() => expect(repository.requestDraft).toHaveBeenCalledWith("who-lived-happily", "regenerate"));
    expect(await screen.findByText("A draft is ready in the queue.")).toBeInTheDocument();
    expect(repository.listQueue).toHaveBeenCalledTimes(2);
  });

  it("drafts an exhibition on demand from its id", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository, { draftingEnabled: true });

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

  const SOURCE_CHANGED_LINE = "The exhibition's text changed after this draft was written; it cannot be approved. Write a new draft.";

  it("marks a queue row whose exhibition text changed since the draft", async () => {
    renderWorkspace(repositoryWith({ listQueue: vi.fn().mockResolvedValue([{ ...queueItem, stale: true }]) }));
    const row = await screen.findByRole("button", { name: /Who Lived Happily Ever After\?/ });
    expect(within(row).getByText("Exhibition changed")).toBeInTheDocument();
  });

  it("does not mark a fresh queue row", async () => {
    renderWorkspace(repositoryWith());
    const row = await screen.findByRole("button", { name: /Who Lived Happily Ever After\?/ });
    expect(within(row).queryByText("Exhibition changed")).not.toBeInTheDocument();
  });

  it("explains a stale draft and keeps everything but Approve available", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({
      listQueue: vi.fn().mockResolvedValue([{ ...queueItem, stale: true }]),
      get: vi.fn().mockResolvedValue({ ...facts, packetHash: "b".repeat(64), versions: [{ ...version, stale: true }] }),
    });
    renderWorkspace(repository);
    await openFirst(user);

    expect(screen.getByText(SOURCE_CHANGED_LINE)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Decline" })).toBeEnabled();
    expect(screen.getAllByRole("button", { name: "Mark unsupported" })[0]).toBeEnabled();
    const hook = screen.getByLabelText("Hook", { selector: "textarea#note-ko-hook" });
    expect(hook).toBeEnabled();
    await user.type(hook, "!");
    expect(screen.getByRole("button", { name: "Save edits" })).toBeEnabled();
  });

  it("explains and reloads when the database refuses approval because the exhibition changed", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({
      approve: vi.fn().mockRejectedValue(new CuratorNoteSourceChangedError()),
      get: vi.fn()
        .mockResolvedValueOnce(facts)
        .mockResolvedValue({ ...facts, packetHash: "b".repeat(64), versions: [{ ...version, stale: true }] }),
    });
    renderWorkspace(repository);
    await openFirst(user);
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(SOURCE_CHANGED_LINE);
    await waitFor(() => expect(repository.get).toHaveBeenCalledTimes(2));
    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
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

describe("CuratorNotesWorkspace review layout", () => {
  const secondItem: CuratorNoteQueueItem = {
    ...queueItem,
    versionId: "second-version",
    exhibitionId: "second-show",
    nameKo: "두 번째 전시",
    nameEn: "Second show",
    isFeatured: false,
  };
  const approvedVersion: CuratorNoteVersion = {
    ...version,
    id: "approved-1",
    status: "approved",
    reviewerNameKo: "김지원",
    approvedAt: "2026-10-11T19:00:00Z",
  };
  const approvedNote: ApprovedCuratorNote = {
    versionId: "approved-1",
    exhibitionId: "who-lived-happily",
    versionNumber: 1,
    hookKo: version.hookKo,
    hookEn: version.hookEn,
    approvedAt: "2026-10-11T19:00:00Z",
    nameKo: queueItem.nameKo,
    nameEn: queueItem.nameEn,
    venueNameKo: "에스더쉬퍼",
    venueNameEn: "Esther Schipper",
    regionKo: "용산구",
    regionEn: "Yongsan-gu",
    openingDate: "2026-09-02",
    closingDate: "2026-10-31",
    coverImageUrl: "https://images.example.test/who-lived.jpg",
  };

  it("asks staff to pick a note before one is open, in the review pane beside the queue", async () => {
    renderWorkspace(repositoryWith());

    await screen.findByRole("button", { name: /Who Lived Happily Ever After\?/ });
    const pane = screen.getByRole("region", { name: "Review" });
    expect(within(pane).getByText("Select a note to review it here.")).toBeInTheDocument();
  });

  it("marks the open row and keeps the decisions in a bar at the top of the review", async () => {
    const user = userEvent.setup();
    renderWorkspace(repositoryWith());
    await openFirst(user);

    expect(screen.getByRole("button", { name: /Who Lived Happily Ever After\?/ })).toHaveAttribute("aria-current", "true");
    const pane = screen.getByRole("region", { name: "Review" });
    expect(within(pane).getByRole("heading", { name: "Who Lived Happily Ever After?" })).toBeInTheDocument();
    const bar = within(pane).getByRole("group", { name: "Review actions" });
    expect(within(bar).getByRole("button", { name: "Approve" })).toBeEnabled();
    expect(within(bar).getByRole("button", { name: "Decline" })).toBeEnabled();
    expect(within(bar).getByRole("button", { name: "Save edits" })).toBeDisabled();
  });

  it("opens the next waiting draft from the review bar", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({ listQueue: vi.fn().mockResolvedValue([queueItem, secondItem]) });
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getByRole("button", { name: "Next draft" }));

    await waitFor(() => expect(repository.get).toHaveBeenLastCalledWith("second-show"));
  });

  it("offers no next draft when the open one is the only one waiting", async () => {
    const user = userEvent.setup();
    renderWorkspace(repositoryWith());
    await openFirst(user);

    expect(screen.queryByRole("button", { name: "Next draft" })).not.toBeInTheDocument();
  });

  it("asks before leaving a draft with unsaved edits and keeps them unless staff discard", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({ listQueue: vi.fn().mockResolvedValue([queueItem, secondItem]) });
    renderWorkspace(repository);
    await openFirst(user);
    await user.type(screen.getByLabelText("Hook", { selector: "#note-ko-hook" }), " 추가");

    await user.click(screen.getByRole("button", { name: /Second show/ }));

    const dialog = await screen.findByRole("alertdialog", { name: "Discard your edits?" });
    expect(repository.get).toHaveBeenCalledTimes(1);
    await user.click(within(dialog).getByRole("button", { name: "Keep editing" }));
    expect(screen.getByLabelText("Hook", { selector: "#note-ko-hook" })).toHaveValue(`${version.hookKo} 추가`);

    await user.click(screen.getByRole("button", { name: /Second show/ }));
    await user.click(within(await screen.findByRole("alertdialog", { name: "Discard your edits?" })).getByRole("button", { name: "Discard edits" }));

    await waitFor(() => expect(repository.get).toHaveBeenLastCalledWith("second-show"));
  });

  it("lists approved notes on their own tab and opens one for export", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({
      listQueue: vi.fn().mockResolvedValue([]),
      listApproved: vi.fn().mockResolvedValue([approvedNote]),
      get: vi.fn().mockResolvedValue({ ...facts, approvedVersionId: "approved-1", versions: [approvedVersion] }),
    });
    renderWorkspace(repository);

    expect(await screen.findByRole("tab", { name: "Waiting 0" })).toHaveAttribute("aria-selected", "true");
    await user.click(await screen.findByRole("tab", { name: "Approved 1" }));
    await user.click(await screen.findByRole("button", { name: /Who Lived Happily Ever After\?/ }));

    await waitFor(() => expect(repository.get).toHaveBeenCalledWith("who-lived-happily"));
    expect(await screen.findByRole("button", { name: "Export carousel" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("offers no next draft on an approved note while drafts wait", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({
      listQueue: vi.fn().mockResolvedValue([secondItem]),
      listApproved: vi.fn().mockResolvedValue([approvedNote]),
      get: vi.fn().mockResolvedValue({ ...facts, approvedVersionId: "approved-1", versions: [approvedVersion] }),
    });
    renderWorkspace(repository);

    await user.click(await screen.findByRole("tab", { name: "Approved 1" }));
    await user.click(await screen.findByRole("button", { name: /Who Lived Happily Ever After\?/ }));

    expect(await screen.findByRole("button", { name: "Export carousel" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Next draft" })).not.toBeInTheDocument();
  });

  it("says when no approved note is listed", async () => {
    const user = userEvent.setup();
    renderWorkspace(repositoryWith());

    await user.click(await screen.findByRole("tab", { name: "Approved 0" }));

    expect(screen.getByText("No approved notes for exhibitions that are still on")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Waiting for review" })).not.toBeInTheDocument();
  });

  it("goes round from the last waiting draft to the first", async () => {
    const user = userEvent.setup();
    const secondVersion: CuratorNoteVersion = { ...version, id: secondItem.versionId, exhibitionId: secondItem.exhibitionId };
    const repository = repositoryWith({
      listQueue: vi.fn().mockResolvedValue([queueItem, secondItem]),
      get: vi.fn().mockImplementation(async (exhibitionId: string) =>
        exhibitionId === secondItem.exhibitionId
          ? { ...facts, exhibitionId: secondItem.exhibitionId, versions: [secondVersion] }
          : facts
      ),
    });
    renderWorkspace(repository);
    await user.click(await screen.findByRole("button", { name: /Second show/ }));
    const pane = screen.getByRole("region", { name: "Review" });
    await within(pane).findByRole("heading", { name: "Second show" });

    await user.click(within(pane).getByRole("button", { name: "Next draft" }));

    expect(await within(pane).findByRole("heading", { name: "Who Lived Happily Ever After?" })).toBeInTheDocument();
  });

  it("moves an approved draft to the approved tab", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({
      listQueue: vi.fn().mockResolvedValueOnce([queueItem]).mockResolvedValue([]),
      listApproved: vi.fn().mockResolvedValueOnce([]).mockResolvedValue([approvedNote]),
    });
    renderWorkspace(repository);
    await openFirst(user);
    expect(screen.getByRole("tab", { name: "Approved 0" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByRole("tab", { name: "Approved 1" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Waiting 0" })).toBeInTheDocument();
  });

  it("opens no other note while a write is in flight, so its result lands on the note it was for", async () => {
    const user = userEvent.setup();
    let finishMark: (value: CuratorNoteVersion) => void = () => {};
    const repository = repositoryWith({
      listQueue: vi.fn().mockResolvedValue([queueItem, secondItem]),
      markSentence: vi.fn().mockReturnValue(new Promise<CuratorNoteVersion>((resolve) => { finishMark = resolve; })),
    });
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getAllByRole("button", { name: "Mark unsupported" })[0]);
    await user.click(screen.getByRole("button", { name: /Second show/ }));
    finishMark({ ...version, unsupportedRefs: ["ko:hook:0"], revision: "r2" });

    expect(await screen.findByRole("button", { name: "Clear unsupported" })).toBeInTheDocument();
    expect(repository.get).toHaveBeenCalledTimes(1);
    const pane = screen.getByRole("region", { name: "Review" });
    expect(within(pane).getByRole("heading", { name: "Who Lived Happily Ever After?" })).toBeInTheDocument();
    expect(screen.getByLabelText("Hook", { selector: "#note-ko-hook" })).toHaveValue(version.hookKo);
  });

  it("keeps the open note and reads the lists again when the chosen draft is no longer there", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({
      listQueue: vi.fn().mockResolvedValueOnce([queueItem, secondItem]).mockResolvedValue([queueItem]),
      get: vi.fn().mockResolvedValue(facts),
    });
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getByRole("button", { name: /Second show/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The note changed since you opened it. It was reloaded; check it again.");
    await waitFor(() => expect(screen.queryByRole("button", { name: /Second show/ })).not.toBeInTheDocument());
    const pane = screen.getByRole("region", { name: "Review" });
    expect(within(pane).getByRole("heading", { name: "Who Lived Happily Ever After?" })).toBeInTheDocument();
  });

  it("holds approval until edits are saved, so what is approved is what is on screen", async () => {
    const user = userEvent.setup();
    renderWorkspace(repositoryWith());
    await openFirst(user);

    await user.type(screen.getByLabelText("Hook", { selector: "#note-ko-hook" }), " 추가");

    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save edits" })).toBeEnabled();
  });

  it("opens the draft that followed once the open one is approved", async () => {
    const user = userEvent.setup();
    const secondVersion: CuratorNoteVersion = {
      ...version,
      id: secondItem.versionId,
      exhibitionId: secondItem.exhibitionId,
      hookKo: "두 번째 전시의 훅",
    };
    const repository = repositoryWith({
      listQueue: vi.fn().mockResolvedValueOnce([queueItem, secondItem]).mockResolvedValue([secondItem]),
      get: vi.fn().mockImplementation(async (exhibitionId: string) =>
        exhibitionId === secondItem.exhibitionId
          ? { ...facts, exhibitionId: secondItem.exhibitionId, versions: [secondVersion] }
          : facts
      ),
    });
    renderWorkspace(repository);
    await openFirst(user);

    await user.click(screen.getByRole("button", { name: "Approve" }));
    await screen.findByText("Approved and published.");
    await user.click(await screen.findByRole("button", { name: "Next draft" }));

    const pane = screen.getByRole("region", { name: "Review" });
    expect(await within(pane).findByRole("heading", { name: "Second show" })).toBeInTheDocument();
    expect(within(pane).getByDisplayValue("두 번째 전시의 훅")).toBeInTheDocument();
    expect(within(pane).getByRole("button", { name: "Approve" })).toBeEnabled();
    expect(within(pane).queryByRole("button", { name: "Next draft" })).not.toBeInTheDocument();
  });

  it("leaves the open draft and its unsaved edits alone when its own row is clicked again", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);
    await openFirst(user);
    await user.type(screen.getByLabelText("Hook", { selector: "#note-ko-hook" }), " 추가");

    await user.click(screen.getByRole("button", { name: /Who Lived Happily Ever After\?/ }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Hook", { selector: "#note-ko-hook" })).toHaveValue(`${version.hookKo} 추가`);
    expect(repository.get).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Save edits" })).toBeEnabled();
  });

  it("says so when the approved notes fail to load and keeps the waiting drafts reviewable", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({ listApproved: vi.fn().mockRejectedValue(new Error("offline")) });
    renderWorkspace(repository);

    expect(await screen.findByRole("alert")).toHaveTextContent("The notes could not be loaded.");
    expect(screen.getByRole("tab", { name: "Approved 0" })).toBeInTheDocument();
    await openFirst(user);
    expect(screen.getByRole("button", { name: "Approve" })).toBeEnabled();
  });
});

describe("CuratorNotesWorkspace pictures", () => {
  it("shows the cover and each gallery picture with the insight it sits above", async () => {
    const user = userEvent.setup();
    renderWorkspace(repositoryWith());
    await openFirst(user);

    const pictures = screen.getByRole("region", { name: "Pictures" });
    expect(within(pictures).getByText("Cover")).toBeInTheDocument();
    const view = within(pictures).getByRole("img", { name: "Installation view" });
    expect(view).toHaveAttribute("src", "https://images.example.test/room-one.jpg");
    expect(within(pictures).getByText("Above insight 1")).toBeInTheDocument();
    expect(within(pictures).getByText("사진 김지원")).toBeInTheDocument();
    expect(within(pictures).getByText("Insights without a picture: 2.")).toBeInTheDocument();
    expect(within(pictures).getByRole("img", { name: "Cover" })).toHaveAttribute("src", "https://images.example.test/who-lived.jpg");
  });

  it("says when readers get the note without pictures and opens the exhibition to add them", async () => {
    const user = userEvent.setup();
    const onOpenExhibition = vi.fn();
    const repository = repositoryWith({
      get: vi.fn().mockResolvedValue({ ...facts, images: facts.images.filter((image) => image.role === "cover") }),
    });
    renderWorkspace(repository, { onOpenExhibition });
    await openFirst(user);

    const pictures = screen.getByRole("region", { name: "Pictures" });
    expect(within(pictures).getByText("No gallery pictures yet. Readers see this note without pictures.")).toBeInTheDocument();
    await user.click(within(pictures).getByRole("button", { name: "Add pictures in Exhibitions" }));

    expect(onOpenExhibition).toHaveBeenCalledWith("who-lived-happily");
  });

  it("asks before leaving unsaved edits for the exhibition's pictures", async () => {
    const user = userEvent.setup();
    const onOpenExhibition = vi.fn();
    renderWorkspace(repositoryWith(), { onOpenExhibition });
    await openFirst(user);
    await user.type(screen.getByLabelText("Hook", { selector: "#note-ko-hook" }), " 추가");

    await user.click(screen.getByRole("button", { name: "Add pictures in Exhibitions" }));

    const dialog = await screen.findByRole("alertdialog", { name: "Discard your edits?" });
    expect(onOpenExhibition).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "Discard edits" }));
    expect(onOpenExhibition).toHaveBeenCalledWith("who-lived-happily");
  });

  it("still opens the exhibition's pictures when every insight has one", async () => {
    const user = userEvent.setup();
    const onOpenExhibition = vi.fn();
    const repository = repositoryWith({
      get: vi.fn().mockResolvedValue({
        ...facts,
        images: [...facts.images, { ...facts.images[1], url: "https://images.example.test/room-two.jpg" }],
      }),
    });
    renderWorkspace(repository, { onOpenExhibition });
    await openFirst(user);

    const pictures = screen.getByRole("region", { name: "Pictures" });
    expect(within(pictures).queryByText(/Insights without a picture/)).not.toBeInTheDocument();
    await user.click(within(pictures).getByRole("button", { name: "Edit pictures in Exhibitions" }));

    expect(onOpenExhibition).toHaveBeenCalledWith("who-lived-happily");
  });

  it("labels a picture the note does not use and says when there is no cover", async () => {
    const user = userEvent.setup();
    const room = (number: number) => ({ ...facts.images[1], url: `https://images.example.test/room-${number}.jpg` });
    const repository = repositoryWith({
      get: vi.fn().mockResolvedValue({ ...facts, coverImageUrl: null, images: [room(1), room(2), room(3)] }),
    });
    renderWorkspace(repository);
    await openFirst(user);

    const pictures = screen.getByRole("region", { name: "Pictures" });
    expect(within(pictures).getByText("No cover image")).toBeInTheDocument();
    expect(within(pictures).getByText("Above insight 2")).toBeInTheDocument();
    const unused = within(pictures).getByText("Not used in the note").closest("figure")!;
    expect(within(unused).getByRole("img")).toHaveAttribute("src", "https://images.example.test/room-3.jpg");
    expect(within(pictures).queryByRole("button")).not.toBeInTheDocument();
  });

  it("flags a gallery picture that has no alt text", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith({
      get: vi.fn().mockResolvedValue({
        ...facts,
        images: [facts.images[0], { ...facts.images[1], altKo: "", altEn: "" }],
      }),
    });
    renderWorkspace(repository);
    await openFirst(user);

    expect(within(screen.getByRole("region", { name: "Pictures" })).getByText("No alt text")).toBeInTheDocument();
  });
});

describe("CuratorNotesWorkspace roundup", () => {
  it("opens the roundup dialog from the header and loads the approved notes", async () => {
    const user = userEvent.setup();
    const repository = repositoryWith();
    renderWorkspace(repository);

    await waitFor(() => expect(repository.listApproved).toHaveBeenCalledTimes(1));

    await user.click(await screen.findByRole("button", { name: "Roundup carousel" }));

    expect(await screen.findByRole("dialog", { name: "Export a roundup carousel" })).toBeInTheDocument();
    await waitFor(() => expect(repository.listApproved).toHaveBeenCalledTimes(2));
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
