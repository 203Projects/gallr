import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import { PrimaryNavigation } from "./PrimaryNavigation";

describe("PrimaryNavigation", () => {
  it("keeps Promotions absent by default", () => {
    render(
      <PrimaryNavigation
        activeItem="Exhibitions"
        staffRole="admin"
        onNavigate={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "Promotions" }))
      .not.toBeInTheDocument();
  });

  it("exposes Promotions only when its independent capability is enabled", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    render(
      <PrimaryNavigation
        activeItem="Exhibitions"
        staffRole="admin"
        onNavigate={onNavigate}
        promotionsEnabled
      />,
    );

    await user.click(screen.getByRole("button", { name: "Promotions" }));
    expect(onNavigate).toHaveBeenCalledWith("Promotions");
  });

  it("enables Editors for admins", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    render(
      <PrimaryNavigation
        activeItem="Exhibitions"
        staffRole="admin"
        onNavigate={onNavigate}
      />,
    );
    const editors = screen.getByRole("button", { name: "Editors" });
    expect(editors).toBeEnabled();
    await user.click(editors);
    expect(onNavigate).toHaveBeenCalledWith("Editors");
  });

  it.each(["contributor", "publisher"] as const)(
    "hides Editors from %s staff",
    (staffRole) => {
      render(
        <PrimaryNavigation
          activeItem="Exhibitions"
          staffRole={staffRole}
          onNavigate={vi.fn()}
        />,
      );
      expect(screen.queryByRole("button", { name: "Editors" }))
        .not.toBeInTheDocument();
    },
  );

  it("does not render placeholder destinations", () => {
    render(
      <PrimaryNavigation
        activeItem="Exhibitions"
        staffRole="admin"
        onNavigate={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "Venues" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Events" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Audit" }))
      .not.toBeInTheDocument();
  });

  it("renders the staff navigation and accessible sign-out control in Korean", () => {
    render(
      <LocaleProvider initialLocale="ko">
        <PrimaryNavigation
          activeItem="Exhibitions"
          staffRole="admin"
          onNavigate={vi.fn()}
          onSignOut={vi.fn()}
        />
      </LocaleProvider>,
    );

    expect(screen.getByRole("button", { name: "전시" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "제출 검토" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
  });
});
