import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EditorSelfOnboardingWorkspace } from "./EditorSelfOnboardingWorkspace";

describe("EditorSelfOnboardingWorkspace", () => {
  it("lets an invited editor create an unpublished profile", async () => {
    const user = userEvent.setup();
    const complete = vi.fn().mockResolvedValue({
      editorId: "mina-kim",
      nameKo: "김미나",
      nameEn: "Mina Kim",
      active: false,
    });
    const onCompleted = vi.fn();
    render(
      <EditorSelfOnboardingWorkspace
        repository={{ complete }}
        onCompleted={onCompleted}
      />,
    );

    await user.type(screen.getByLabelText("Editor slug"), "mina-kim");
    await user.type(screen.getByLabelText("Name (Korean)"), "김미나");
    await user.type(screen.getByLabelText("Name (English)"), "Mina Kim");
    await user.type(screen.getByLabelText("Title (Korean)"), "객원 에디터");
    await user.type(screen.getByLabelText("Bio (Korean)"), "서울의 동시대 미술을 씁니다.");
    await user.type(
      screen.getByLabelText("Curatorial statement (Korean)"),
      "서울의 새로운 전시를 연결합니다.",
    );
    await user.click(screen.getByRole("button", { name: "Create editor profile" }));

    await waitFor(() => expect(complete).toHaveBeenCalledWith(
      expect.objectContaining({
        editorId: "mina-kim",
        nameKo: "김미나",
        titleKo: "객원 에디터",
      }),
    ));
    expect(onCompleted).toHaveBeenCalledWith("Mina Kim");
    expect(screen.queryByLabelText("Active from")).not.toBeInTheDocument();
    expect(screen.getByText(/starts unpublished/i)).toBeInTheDocument();
  });
});
