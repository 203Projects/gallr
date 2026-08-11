import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { EditorOnboardingInput } from "../repositories/AdminEditorRepository";
import { EditorOnboardingWorkspace } from "./EditorOnboardingWorkspace";

describe("EditorOnboardingWorkspace", () => {
  it("invites an editor with a complete profile", async () => {
    const user = userEvent.setup();
    const invite = vi.fn().mockResolvedValue({
      editorId: "mina-kim",
      email: "mina@example.com",
      nameKo: "김미나",
      nameEn: "Mina Kim",
      active: false,
    });

    render(<EditorOnboardingWorkspace repository={{ invite, listRequests: vi.fn().mockResolvedValue([]), reviewRequest: vi.fn() }} />);

    await user.type(screen.getByLabelText("Invitation email"), "mina@example.com");
    await user.type(screen.getByLabelText("Editor slug"), "mina-kim");
    await user.type(screen.getByLabelText("Name (Korean)"), "김미나");
    await user.type(screen.getByLabelText("Name (English)"), "Mina Kim");
    await user.type(screen.getByLabelText("Title (Korean)"), "객원 에디터");
    await user.type(screen.getByLabelText("Title (English)"), "Guest Editor");
    await user.type(screen.getByLabelText("Bio (Korean)"), "서울의 동시대 미술을 씁니다.");
    await user.type(screen.getByLabelText("Bio (English)"), "Writes about contemporary art in Seoul.");
    await user.type(screen.getByLabelText("Curatorial statement (Korean)"), "서울의 새로운 전시를 연결합니다.");
    await user.type(screen.getByLabelText("Curatorial statement (English)"), "Connecting new exhibitions across Seoul.");
    await user.type(screen.getByLabelText("Active from"), "2026-08-10");
    await user.click(screen.getByRole("button", { name: "Invite editor" }));

    await waitFor(() => expect(invite).toHaveBeenCalledTimes(1));
    const input = invite.mock.calls[0][0] as EditorOnboardingInput;
    expect(input).toMatchObject({
      email: "mina@example.com",
      editorId: "mina-kim",
      nameKo: "김미나",
      nameEn: "Mina Kim",
      titleKo: "객원 에디터",
      titleEn: "Guest Editor",
      bioKo: "서울의 동시대 미술을 씁니다.",
      bioEn: "Writes about contemporary art in Seoul.",
      curationDescriptionKo: "서울의 새로운 전시를 연결합니다.",
      curationDescriptionEn: "Connecting new exhibitions across Seoul.",
      isActive: false,
      activeFrom: "2026-08-10",
      activeTo: null,
    });
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Invitation sent to mina@example.com",
    );
  });

  it("keeps invalid slugs client-side", async () => {
    const user = userEvent.setup();
    const invite = vi.fn();
    render(<EditorOnboardingWorkspace repository={{ invite, listRequests: vi.fn().mockResolvedValue([]), reviewRequest: vi.fn() }} />);

    await user.type(screen.getByLabelText("Editor slug"), "Mina Kim");
    await user.click(screen.getByRole("button", { name: "Invite editor" }));

    expect(invite).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(/lowercase letters/i);
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
});
