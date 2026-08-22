import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SubmissionWorkspace, submissionSourceLabel } from "./SubmissionWorkspace";

describe("submissionSourceLabel", () => {
  it("identifies editor workspace suggestions in the admin queue", () => {
    expect(submissionSourceLabel("editor_workspace" as never)).toBe("Editor");
    expect(submissionSourceLabel("owner_workspace")).toBe("Owner workspace");
    expect(submissionSourceLabel("public_form")).toBe("Public form");
    expect(submissionSourceLabel("owner_workspace", "ko")).toBe("갤러리 작업공간");
  });
});

describe("SubmissionWorkspace", () => {
  it("lets a narrow-screen admin close an open submission inspector", async () => {
    const user = userEvent.setup();
    const repository = {
      listSubmissions: vi.fn().mockResolvedValue([{
        id: "submission-one",
        status: "submitted",
        source: "editor_workspace",
        submitterEmail: "editor@example.com",
        galleryNameKo: "",
        galleryNameEn: "",
        nameKo: "보이지 않는 정원",
        nameEn: "The Invisible Garden",
        venueNameKo: "스튜디오 남산",
        openingDate: "2026-08-01",
        closingDate: "2026-09-15",
        addressKo: "서울 중구",
        hours: "11:00–19:00",
        descriptionKo: "설명",
        submittedAt: "2026-08-10T00:00:00Z",
        reviewNotes: "",
        media: [],
      }]),
    };

    render(
      <SubmissionWorkspace
        repository={repository as never}
        onAccepted={vi.fn()}
      />,
    );

    expect(await screen.findByRole("heading", { name: "The Invisible Garden" }))
      .toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Back to submissions" }));
    expect(screen.getByText("Select a submission to review its details."))
      .toBeInTheDocument();
  });
});
