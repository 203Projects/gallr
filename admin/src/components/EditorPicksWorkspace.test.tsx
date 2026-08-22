import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { EditorPickCandidate } from "../domain";
import type { EditorPickRepository } from "../repositories/EditorPickRepository";
import { EditorPicksWorkspace } from "./EditorPicksWorkspace";

const rows: EditorPickCandidate[] = [
  {
    id: "live-pick",
    workingVersionId: "10000000-0000-0000-0000-000000000001",
    publishedVersionId: "10000000-0000-0000-0000-000000000001",
    revision: 2,
    nameKo: "현재의 선",
    nameEn: "Present Line",
    venueNameKo: "갤러리 하나",
    venueNameEn: "Gallery One",
    openingDate: "2026-08-01",
    closingDate: "2026-09-01",
    selected: true,
    live: true,
    available: true,
    assignedEditorName: "",
  },
  {
    id: "available",
    workingVersionId: "20000000-0000-0000-0000-000000000002",
    publishedVersionId: "20000000-0000-0000-0000-000000000002",
    revision: 4,
    nameKo: "새로운 면",
    nameEn: "New Plane",
    venueNameKo: "갤러리 둘",
    venueNameEn: "Gallery Two",
    openingDate: "2026-08-10",
    closingDate: "2026-10-01",
    selected: false,
    live: false,
    available: true,
    assignedEditorName: "",
  },
];

const history = [{
  id: "request-accepted",
  status: "accepted" as const,
  submittedAt: "2026-08-10T09:00:00Z",
  reviewedAt: "2026-08-11T09:00:00Z",
  reviewNotes: "",
  curationDescriptionKo: "승인된 큐레이션 문장",
  curationDescriptionEn: "Approved curation statement",
  changes: [{
    exhibitionId: "live-pick",
    nameKo: "현재의 선",
    nameEn: "Present Line",
    venueNameKo: "갤러리 하나",
    venueNameEn: "Gallery One",
    openingDate: "2026-08-01",
    closingDate: "2026-09-01",
    selected: true,
  }],
}];

function createRepository() {
  return {
    list: vi.fn().mockResolvedValue(rows),
    listCurationHistory: vi.fn().mockResolvedValue(history),
    getProfile: vi.fn().mockResolvedValue({
      editorId: "minjung-kim",
      nameKo: "김민정",
      nameEn: "Minjung Kim",
      bioKo: "기존 소개",
      bioEn: "Current bio",
      curationDescriptionKo: "기존 큐레이션 문장",
      curationDescriptionEn: "Current curation statement",
      pendingProfile: false,
      pendingCuration: false,
    }),
    submitCuration: vi.fn().mockResolvedValue({
      requestId: "request-one",
      status: "submitted",
      candidates: [{ ...rows[1], selected: true, live: false, revision: 5 }],
    }),
    submitProfile: vi.fn().mockResolvedValue({
      requestId: "request-profile",
      status: "submitted",
    }),
    submitExhibition: vi.fn().mockResolvedValue({
      submissionId: "submission-one",
      status: "submitted",
    }),
  };
}

describe("EditorPicksWorkspace", () => {
  it("opens My curation as submission history and keeps creation separate", async () => {
    const user = userEvent.setup();
    const repository = createRepository();
    render(
      <EditorPicksWorkspace
        repository={repository as unknown as EditorPickRepository}
        editorName="Minjung Kim"
      />,
    );

    expect(await screen.findByRole("heading", { name: "My curation" }))
      .toBeInTheDocument();
    expect(screen.getByText("Approved")).toBeInTheDocument();
    expect(screen.getByText("승인된 큐레이션 문장")).toBeInTheDocument();
    expect(screen.queryByLabelText("Curatorial statement (Korean)"))
      .not.toBeInTheDocument();
    expect(repository.list).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Add curation" }));
    expect(await screen.findByRole("heading", { name: "Add curation" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Curatorial statement (Korean)"))
      .toBeInTheDocument();
    await waitFor(() => expect(repository.list).toHaveBeenCalledWith(""));
  });

  it("stages curation choices locally and sends one grouped request", async () => {
    const user = userEvent.setup();
    const repository = createRepository();
    render(
      <EditorPicksWorkspace
        repository={repository as unknown as EditorPickRepository}
        editorName="Minjung Kim"
      />,
    );

    await user.click(screen.getByRole("button", { name: "Add curation" }));
    expect(await screen.findByRole("heading", { name: "Add curation" }))
      .toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add New Plane to my curation" }));
    expect(repository.submitCuration).not.toHaveBeenCalled();
    expect(screen.getByText("1 unsent change")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Send for approval" }));
    await waitFor(() => expect(repository.submitCuration).toHaveBeenCalledWith(
      [{
        exhibitionId: "available",
        expectedVersionId: "20000000-0000-0000-0000-000000000002",
        expectedRevision: 4,
        selected: true,
      }],
      "기존 큐레이션 문장",
      "Current curation statement",
    ));
    expect(await screen.findByText(/curation request was sent/i)).toBeInTheDocument();
    expect(screen.getByText(/curation request is awaiting review/i)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "My curation" })).toBeInTheDocument();
    expect(repository.listCurationHistory).toHaveBeenCalledTimes(2);
  });

  it("edits the curation statement separately and allows a statement-only request", async () => {
    const user = userEvent.setup();
    const repository = createRepository();
    render(
      <EditorPicksWorkspace
        repository={repository as unknown as EditorPickRepository}
        editorName="Minjung Kim"
      />,
    );

    await user.click(screen.getByRole("button", { name: "Add curation" }));
    expect(await screen.findByDisplayValue("기존 큐레이션 문장")).toBeInTheDocument();
    expect(screen.getByText(/different from your personal biography/i)).toBeInTheDocument();
    await user.clear(screen.getByLabelText("Curatorial statement (Korean)"));
    await user.type(screen.getByLabelText("Curatorial statement (Korean)"), "새 큐레이션 문장");
    expect(screen.getByText("1 unsent change")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Send for approval" }));

    await waitFor(() => expect(repository.submitCuration).toHaveBeenCalledWith(
      [],
      "새 큐레이션 문장",
      "Current curation statement",
    ));
    expect(repository.submitProfile).not.toHaveBeenCalled();
  });

  it("edits only the authenticated editor bio and sends it for review", async () => {
    const user = userEvent.setup();
    const repository = createRepository();
    render(
      <EditorPicksWorkspace
        repository={repository as unknown as EditorPickRepository}
        editorName="Minjung Kim"
      />,
    );

    await user.click(screen.getByRole("button", { name: "My profile" }));
    expect(await screen.findByDisplayValue("기존 소개")).toBeInTheDocument();
    await user.clear(screen.getByLabelText("Bio (Korean)"));
    await user.type(screen.getByLabelText("Bio (Korean)"), "새로운 소개");
    await user.click(screen.getByRole("button", { name: "Send bio for approval" }));

    await waitFor(() => expect(repository.submitProfile).toHaveBeenCalledWith(
      "새로운 소개",
      "Current bio",
    ));
    expect(screen.getByLabelText("Bio (Korean)")).toHaveValue("새로운 소개");
  });

  it("submits a missing exhibition to the admin queue", async () => {
    const user = userEvent.setup();
    const repository = createRepository();
    render(
      <EditorPicksWorkspace
        repository={repository as unknown as EditorPickRepository}
        editorName="Minjung Kim"
      />,
    );

    await user.click(screen.getByRole("button", { name: "Add curation" }));
    await screen.findByText("새로운 면");
    await user.click(screen.getByRole("button", { name: "Suggest missing exhibition" }));
    await user.type(screen.getByLabelText("Exhibition name (Korean)"), "누락 전시");
    await user.type(screen.getByLabelText("Venue name (Korean)"), "새 갤러리");
    await user.type(screen.getByLabelText("Opening date"), "2026-08-01");
    await user.type(screen.getByLabelText("Closing date"), "2026-09-01");
    await user.type(screen.getByLabelText("Address (Korean)"), "서울 용산구");
    await user.type(screen.getByLabelText("Hours"), "10:00–18:00");
    await user.click(screen.getByRole("button", { name: "Send exhibition for review" }));

    await waitFor(() => expect(repository.submitExhibition).toHaveBeenCalledWith(
      expect.objectContaining({
        nameKo: "누락 전시",
        venueNameKo: "새 갤러리",
        openingDate: "2026-08-01",
        closingDate: "2026-09-01",
      }),
    ));
    expect(await screen.findByText(/exhibition suggestion was sent/i)).toBeInTheDocument();
  });

  it("filters candidates without requesting unrelated admin data", async () => {
    const user = userEvent.setup();
    const repository = createRepository();
    render(
      <EditorPicksWorkspace
        repository={repository as unknown as EditorPickRepository}
        editorName="Minjung Kim"
      />,
    );
    await user.click(screen.getByRole("button", { name: "Add curation" }));
    await screen.findByText("현재의 선");
    await user.type(screen.getByRole("searchbox", { name: "Search exhibitions" }), "new");
    expect(repository.list).toHaveBeenLastCalledWith("new");
  });

  it("shows exhibitions curated by another editor without allowing selection", async () => {
    const user = userEvent.setup();
    const repository = createRepository();
    repository.list.mockResolvedValue([...rows, {
      ...rows[1],
      id: "assigned",
      nameKo: "이미 큐레이션 중",
      selected: false,
      live: false,
      available: false,
      assignedEditorName: "Sora Lee",
    }]);
    render(
      <EditorPicksWorkspace
        repository={repository as unknown as EditorPickRepository}
        editorName="Minjung Kim"
      />,
    );

    await user.click(screen.getByRole("button", { name: "Add curation" }));
    expect(await screen.findByText("Curated by Sora Lee")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Unavailable New Plane" }))
      .toBeDisabled();
  });

  it("keeps staged changes in the grouped request when search results change", async () => {
    const user = userEvent.setup();
    const repository = createRepository();
    repository.list.mockImplementation((search: string) =>
      Promise.resolve(search ? [rows[0]] : rows),
    );
    render(
      <EditorPicksWorkspace
        repository={repository as unknown as EditorPickRepository}
        editorName="Minjung Kim"
      />,
    );

    await user.click(screen.getByRole("button", { name: "Add curation" }));
    await user.click(await screen.findByRole("button", { name: "Add New Plane to my curation" }));
    await user.type(screen.getByRole("searchbox", { name: "Search exhibitions" }), "present");
    await waitFor(() => expect(repository.list).toHaveBeenLastCalledWith("present"));
    expect(screen.queryByText("새로운 면")).not.toBeInTheDocument();
    expect(screen.getByText("1 unsent change")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Send for approval" }));
    await waitFor(() => expect(repository.submitCuration).toHaveBeenCalledWith(
      [{
        exhibitionId: "available",
        expectedVersionId: "20000000-0000-0000-0000-000000000002",
        expectedRevision: 4,
        selected: true,
      }],
      "기존 큐레이션 문장",
      "Current curation statement",
    ));
  });
});
