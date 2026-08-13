import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { EditorOnboardingInput } from "../repositories/AdminEditorRepository";
import { EditorRevisionConflictError } from "../repositories/AdminEditorRepository";
import { EditorOnboardingWorkspace } from "./EditorOnboardingWorkspace";

describe("EditorOnboardingWorkspace", () => {
  const managedEditor = {
    editorId: "mina-kim",
    email: "mina@example.com",
    nameKo: "김미나",
    nameEn: "Mina Kim",
    titleKo: "객원 에디터",
    titleEn: "Guest Editor",
    bioKo: "서울의 동시대 미술을 씁니다.",
    bioEn: "Writes about contemporary art in Seoul.",
    curationDescriptionKo: "서울의 새로운 전시를 연결합니다.",
    curationDescriptionEn: "Connecting new exhibitions across Seoul.",
    isActive: true,
    activeFrom: "2026-08-10",
    activeTo: null,
    revision: 3,
    hasAccess: true,
    accessActive: true,
  };

  it("invites an editor by email without collecting their profile", async () => {
    const user = userEvent.setup();
    const invite = vi.fn().mockResolvedValue({
      email: "mina@example.com",
      status: "invited",
    });

    render(<EditorOnboardingWorkspace repository={{
      invite,
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors: vi.fn().mockResolvedValue([]),
      updateEditor: vi.fn(),
      setAccess: vi.fn(),
    }} />);

    await user.type(screen.getByLabelText("Invitation email"), "mina@example.com");
    await user.click(screen.getByRole("button", { name: "Invite editor" }));

    await waitFor(() => expect(invite).toHaveBeenCalledTimes(1));
    const input = invite.mock.calls[0][0] as EditorOnboardingInput;
    expect(input).toEqual({ email: "mina@example.com" });
    expect(screen.queryByLabelText("Editor slug")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Name (Korean)")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Active from")).not.toBeInTheDocument();
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Invitation sent to mina@example.com",
    );
  });

  it("keeps invalid invitation emails client-side", async () => {
    const user = userEvent.setup();
    const invite = vi.fn();
    render(<EditorOnboardingWorkspace repository={{
      invite,
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors: vi.fn().mockResolvedValue([]),
      updateEditor: vi.fn(),
      setAccess: vi.fn(),
    }} />);

    await user.type(screen.getByLabelText("Invitation email"), "not-an-email");
    await user.click(screen.getByRole("button", { name: "Invite editor" }));

    expect(invite).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(/valid invitation email/i);
  });

  it("focuses the first missing invitation field", async () => {
    const user = userEvent.setup();
    const invite = vi.fn();
    render(<EditorOnboardingWorkspace repository={{
      invite,
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors: vi.fn().mockResolvedValue([]),
      updateEditor: vi.fn(),
      setAccess: vi.fn(),
    }} />);

    await user.click(screen.getByRole("button", { name: "Invite editor" }));

    expect(invite).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Invitation email is required.",
    );
    expect(screen.getByLabelText("Invitation email"))
      .toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("Invitation email")).toHaveFocus();
  });

  it("lets an admin approve a pending editor bio request", async () => {
    const user = userEvent.setup();
    const reviewRequest = vi.fn().mockResolvedValue({
      id: "request-one",
      editorId: "mina-kim",
      editorName: "Mina Kim",
      kind: "profile",
      status: "accepted",
      payload: { bio_ko: "새 소개", bio_en: "New bio" },
      reviewNotes: "",
      createdAt: "2026-08-10T00:00:00Z",
    });
    const repository = {
      invite: vi.fn(),
      listRequests: vi.fn().mockResolvedValue([{
        id: "request-one",
        editorId: "mina-kim",
        editorName: "Mina Kim",
        kind: "profile",
        status: "submitted",
        payload: { bio_ko: "새 소개", bio_en: "New bio" },
        reviewNotes: "",
        createdAt: "2026-08-10T00:00:00Z",
      }]),
      reviewRequest,
    };

    render(<EditorOnboardingWorkspace repository={repository as never} />);
    expect(await screen.findByText("New bio")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Approve Mina Kim profile request" }));
    expect(reviewRequest).toHaveBeenCalledWith("request-one", true, "");
  });

  it("shows the exact exhibitions and decisions in a curation request", async () => {
    const repository = {
      invite: vi.fn(),
      listRequests: vi.fn().mockResolvedValue([{
        id: "request-curation",
        editorId: "mina-kim",
        editorName: "Mina Kim",
        kind: "curation",
        status: "submitted",
        payload: {
          curation_description_ko: "서울의 빛과 공간을 따라가는 큐레이션입니다.",
          curation_description_en: "A curation following light and space across Seoul.",
          changes: [{
            id: "light-lines",
            name_ko: "빛과 선의 문법",
            name_en: "Grammar of Light and Line",
            venue_name_ko: "아카이브 스페이스",
            selected: true,
          }, {
            id: "city-afterimage",
            name_ko: "도시의 잔상",
            name_en: "Afterimage of the City",
            venue_name_ko: "프로젝트 룸 한강",
            selected: false,
          }],
        },
        reviewNotes: "",
        createdAt: "2026-08-10T00:00:00Z",
      }]),
      reviewRequest: vi.fn(),
    };

    render(<EditorOnboardingWorkspace repository={repository as never} />);

    expect(await screen.findByText("빛과 선의 문법")).toBeInTheDocument();
    expect(screen.getByText("서울의 빛과 공간을 따라가는 큐레이션입니다.")).toBeInTheDocument();
    expect(screen.getByText("Curatorial statement")).toBeInTheDocument();
    expect(screen.getByText("Add to curation")).toBeInTheDocument();
    expect(screen.getByText("도시의 잔상")).toBeInTheDocument();
    expect(screen.getByText("Remove from curation")).toBeInTheDocument();
  });

  it("lists existing editors and their publication and access states", async () => {
    render(<EditorOnboardingWorkspace repository={{
      invite: vi.fn(),
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors: vi.fn().mockResolvedValue([managedEditor, {
        ...managedEditor,
        editorId: "gallr-editors",
        email: null,
        nameKo: "gallr 에디터즈",
        nameEn: "gallr Editors",
        hasAccess: false,
        accessActive: false,
      }]),
      updateEditor: vi.fn(),
      setAccess: vi.fn(),
    } as never} />);

    expect(await screen.findByRole("heading", { name: "Manage editors" }))
      .toBeInTheDocument();
    expect(screen.getByText("Mina Kim")).toBeInTheDocument();
    expect(screen.getAllByText("Published profile")).toHaveLength(2);
    expect(screen.getByText("Workspace active")).toBeInTheDocument();
    expect(screen.getByText("No linked workspace account")).toBeInTheDocument();
  });

  it("edits an existing editor without changing identity fields", async () => {
    const user = userEvent.setup();
    const updateEditor = vi.fn().mockResolvedValue({
      ...managedEditor,
      titleEn: "Senior Editor",
      revision: 4,
    });
    render(<EditorOnboardingWorkspace repository={{
      invite: vi.fn(),
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors: vi.fn().mockResolvedValue([managedEditor]),
      updateEditor,
      setAccess: vi.fn(),
    } as never} />);

    await user.click(await screen.findByRole("button", { name: "Edit Mina Kim" }));
    expect(screen.getByText("mina@example.com")).toBeInTheDocument();
    expect(screen.getByText("mina-kim")).toBeInTheDocument();
    const title = screen.getByLabelText("Edit title (English)");
    await user.clear(title);
    await user.type(title, "Senior Editor");
    await user.click(screen.getByRole("button", { name: "Save editor" }));

    await waitFor(() => expect(updateEditor).toHaveBeenCalledWith(
      "mina-kim",
      3,
      expect.objectContaining({ titleEn: "Senior Editor" }),
    ));
    expect(await screen.findByRole("status")).toHaveTextContent("Mina Kim was updated");
  });

  it("rejects an edit when a required Korean profile field is cleared", async () => {
    const user = userEvent.setup();
    const updateEditor = vi.fn();
    render(<EditorOnboardingWorkspace repository={{
      invite: vi.fn(),
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors: vi.fn().mockResolvedValue([managedEditor]),
      updateEditor,
      setAccess: vi.fn(),
    } as never} />);

    await user.click(await screen.findByRole("button", { name: "Edit Mina Kim" }));
    await user.clear(screen.getByLabelText("Edit title (Korean)"));
    await user.click(screen.getByRole("button", { name: "Save editor" }));

    expect(updateEditor).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Complete the required Korean profile fields.",
    );
  });

  it("deactivates an editor only after confirmation and can restore access", async () => {
    const user = userEvent.setup();
    const setAccess = vi.fn()
      .mockResolvedValueOnce({
        ...managedEditor,
        isActive: false,
        accessActive: false,
        revision: 4,
      })
      .mockResolvedValueOnce({
        ...managedEditor,
        isActive: false,
        accessActive: true,
        revision: 5,
      });
    render(<EditorOnboardingWorkspace repository={{
      invite: vi.fn(),
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors: vi.fn().mockResolvedValue([managedEditor]),
      updateEditor: vi.fn(),
      setAccess,
    } as never} />);

    await user.click(await screen.findByRole("button", { name: "Deactivate Mina Kim" }));
    expect(screen.getByRole("alertdialog")).toHaveTextContent(/preserved/i);
    expect(setAccess).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Confirm deactivate Mina Kim" }));
    await waitFor(() => expect(setAccess).toHaveBeenCalledWith("mina-kim", 3, false));
    expect(await screen.findByText("Access removed")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Restore Mina Kim access" }));
    await waitFor(() => expect(setAccess).toHaveBeenLastCalledWith("mina-kim", 4, true));
    expect(await screen.findByText("Workspace active")).toBeInTheDocument();
    expect(screen.getByText("Unpublished profile")).toBeInTheDocument();
  });

  it("reloads the directory after an edit revision conflict", async () => {
    const user = userEvent.setup();
    const newerEditor = { ...managedEditor, revision: 4 };
    const listEditors = vi.fn()
      .mockResolvedValueOnce([managedEditor])
      .mockResolvedValueOnce([newerEditor]);
    const updateEditor = vi.fn().mockRejectedValue(
      new EditorRevisionConflictError(4),
    );
    render(<EditorOnboardingWorkspace repository={{
      invite: vi.fn(),
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors,
      updateEditor,
      setAccess: vi.fn(),
    } as never} />);

    await user.click(await screen.findByRole("button", { name: "Edit Mina Kim" }));
    const title = screen.getByLabelText("Edit title (English)");
    await user.clear(title);
    await user.type(title, "Senior Editor");
    await user.click(screen.getByRole("button", { name: "Save editor" }));

    await waitFor(() => expect(listEditors).toHaveBeenCalledTimes(2));
    expect(updateEditor).toHaveBeenCalledWith(
      "mina-kim",
      3,
      expect.objectContaining({ titleEn: "Senior Editor" }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      /newer editor revision \(4\)/i,
    );
    expect(screen.queryByRole("button", { name: "Save editor" }))
      .not.toBeInTheDocument();
    expect(screen.getByText("REV 4")).toBeInTheDocument();
  });

  it("closes deactivation confirmation after an access revision conflict", async () => {
    const user = userEvent.setup();
    const listEditors = vi.fn()
      .mockResolvedValueOnce([managedEditor])
      .mockResolvedValueOnce([{ ...managedEditor, revision: 4 }]);
    const setAccess = vi.fn().mockRejectedValue(
      new EditorRevisionConflictError(4),
    );
    render(<EditorOnboardingWorkspace repository={{
      invite: vi.fn(),
      listRequests: vi.fn().mockResolvedValue([]),
      reviewRequest: vi.fn(),
      listEditors,
      updateEditor: vi.fn(),
      setAccess,
    } as never} />);

    await user.click(await screen.findByRole("button", { name: "Deactivate Mina Kim" }));
    await user.click(screen.getByRole("button", { name: "Confirm deactivate Mina Kim" }));

    await waitFor(() => expect(listEditors).toHaveBeenCalledTimes(2));
    expect(setAccess).toHaveBeenCalledWith("mina-kim", 3, false);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(
      /newer editor revision \(4\)/i,
    );
    expect(screen.getByText("REV 4")).toBeInTheDocument();
  });
});
