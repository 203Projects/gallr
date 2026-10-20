import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LocaleProvider } from "../i18n";
import {
  AppVersionPolicyNotStaffError,
  AppVersionPolicyRejectedError,
  type AppVersionPolicy,
  type AppVersionPolicyRepository,
} from "../repositories/AppVersionPolicyRepository";
import { InMemoryAppVersionPolicyRepository } from "../repositories/InMemoryAppVersionPolicyRepository";
import { AppVersionsWorkspace } from "./AppVersionsWorkspace";

const rows: AppVersionPolicy[] = [
  {
    platform: "android",
    minimumVersion: "1.13.1",
    recommendedVersion: "1.14.0",
    latestReleasedVersion: "1.14.0",
    updatedAt: "2026-10-20T03:00:00Z",
    updatedByEmail: "staff@gallrmap.com",
  },
  {
    platform: "ios",
    minimumVersion: "1.13.1",
    recommendedVersion: "1.13.1",
    latestReleasedVersion: null,
    updatedAt: "2026-10-20T00:00:00Z",
    updatedByEmail: null,
  },
];

function renderWorkspace(repository: AppVersionPolicyRepository, locale: "ko" | "en" = "en") {
  render(
    <LocaleProvider initialLocale={locale} storage={null}>
      <AppVersionsWorkspace repository={repository} />
    </LocaleProvider>,
  );
}

async function androidForm() {
  return within(await screen.findByRole("form", { name: "Android" }));
}

describe("AppVersionsWorkspace", () => {
  it("shows one row per platform with the stored values and who changed them", async () => {
    renderWorkspace(new InMemoryAppVersionPolicyRepository(rows));

    const android = await androidForm();
    expect(android.getByLabelText("Minimum version")).toHaveValue("1.13.1");
    expect(android.getByLabelText("Recommended version")).toHaveValue("1.14.0");
    expect(android.getByLabelText("Last released version")).toHaveValue("1.14.0");
    expect(android.getByText(/staff@gallrmap\.com/)).toBeInTheDocument();
    expect(android.getByText(/Oct 20, 2026/)).toBeInTheDocument();

    const ios = within(screen.getByRole("form", { name: "iOS" }));
    expect(ios.getByLabelText("Recommended version")).toHaveValue("1.13.1");
    expect(ios.getByLabelText("Last released version")).toHaveValue("");
    expect(ios.getByText("Set with the release")).toBeInTheDocument();
  });

  it("refuses a version that is not three numbers and disables Save", async () => {
    const user = userEvent.setup();
    renderWorkspace(new InMemoryAppVersionPolicyRepository(rows));
    const android = await androidForm();

    await user.clear(android.getByLabelText("Minimum version"));
    await user.type(android.getByLabelText("Minimum version"), "1.14");

    expect(android.getByRole("alert")).toHaveTextContent("Use three numbers, like 1.14.0.");
    expect(android.getByLabelText("Minimum version")).toHaveAttribute("aria-invalid", "true");
    expect(android.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("refuses a recommended version below the minimum", async () => {
    const user = userEvent.setup();
    renderWorkspace(new InMemoryAppVersionPolicyRepository(rows));
    const android = await androidForm();

    await user.clear(android.getByLabelText("Minimum version"));
    await user.type(android.getByLabelText("Minimum version"), "1.15.0");

    expect(android.getByRole("alert")).toHaveTextContent("The recommended version cannot be below the minimum.");
    expect(android.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("warns when the minimum is newer than the last released version", async () => {
    const user = userEvent.setup();
    renderWorkspace(new InMemoryAppVersionPolicyRepository(rows));
    const android = await androidForm();
    expect(android.queryByRole("note")).not.toBeInTheDocument();

    await user.clear(android.getByLabelText("Minimum version"));
    await user.type(android.getByLabelText("Minimum version"), "1.14.1");
    await user.clear(android.getByLabelText("Recommended version"));
    await user.type(android.getByLabelText("Recommended version"), "1.14.1");

    expect(android.getByRole("note")).toHaveTextContent(
      "The minimum is newer than the last released version: every current install would be locked out.",
    );
    expect(android.getByRole("button", { name: "Save" })).toBeEnabled();
  });

  it("saves the row's values and shows who saved them", async () => {
    const user = userEvent.setup();
    const repository = new InMemoryAppVersionPolicyRepository(rows, "hello@gallrmap.com", () => "2026-10-21T09:30:00Z");
    const save = vi.spyOn(repository, "save");
    renderWorkspace(repository);
    const android = await androidForm();

    expect(android.getByRole("button", { name: "Save" })).toBeDisabled();
    await user.clear(android.getByLabelText("Recommended version"));
    await user.type(android.getByLabelText("Recommended version"), "1.15.0");
    await user.clear(android.getByLabelText("Last released version"));
    await user.click(android.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(save).toHaveBeenCalledWith("android", {
      minimumVersion: "1.13.1",
      recommendedVersion: "1.15.0",
      latestReleasedVersion: null,
    }));
    expect(await android.findByRole("status")).toHaveTextContent("Saved.");
    expect(android.getByText(/hello@gallrmap\.com/)).toBeInTheDocument();
    expect(android.getByText(/Oct 21, 2026/)).toBeInTheDocument();
    expect(android.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("shows the server's refusal and keeps the typed values", async () => {
    const user = userEvent.setup();
    const repository: AppVersionPolicyRepository = {
      list: vi.fn().mockResolvedValue(rows),
      save: vi.fn().mockRejectedValue(new AppVersionPolicyRejectedError("recommended_below_minimum")),
    };
    renderWorkspace(repository);
    const android = await androidForm();

    await user.clear(android.getByLabelText("Recommended version"));
    await user.type(android.getByLabelText("Recommended version"), "1.16.0");
    await user.click(android.getByRole("button", { name: "Save" }));

    expect(await android.findByRole("alert")).toHaveTextContent(
      "The server refused the versions. Check the format and order.",
    );
    expect(android.getByLabelText("Recommended version")).toHaveValue("1.16.0");
  });

  it("tells a non-admin why the panel is empty", async () => {
    renderWorkspace({
      list: vi.fn().mockRejectedValue(new AppVersionPolicyNotStaffError()),
      save: vi.fn(),
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Only admins can change app versions.");
    expect(screen.queryByRole("form")).not.toBeInTheDocument();
  });

  it("reads in Korean", async () => {
    renderWorkspace(new InMemoryAppVersionPolicyRepository(rows), "ko");

    expect(await screen.findByRole("heading", { name: "앱 버전" })).toBeInTheDocument();
    const android = within(screen.getByRole("form", { name: "Android" }));
    expect(android.getByLabelText("최소 버전")).toHaveValue("1.13.1");
    expect(android.getByRole("button", { name: "저장" })).toBeDisabled();
  });
});
