import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { AdminMediaAsset } from "../domain";
import { LocaleProvider } from "../i18n";
import { MediaEditor } from "./MediaEditor";

function asset(
  assetId: string,
  role: "cover" | "gallery",
  sortOrder: number,
  overrides: Partial<AdminMediaAsset> = {},
): AdminMediaAsset {
  return {
    assetId,
    versionId: "10000000-0000-0000-0000-000000000001",
    role,
    sortOrder,
    status: "published",
    bucketId: "exhibition-media",
    objectPath: `fixture/${assetId}.jpg`,
    mimeType: "image/jpeg",
    byteSize: 1024,
    width: 1600,
    height: 1067,
    checksumSha256: null,
    publicUrl: `https://images.example.test/${assetId}.jpg`,
    altKo: "기존 한국어 설명",
    altEn: "Existing English description",
    credit: "Existing credit",
    rightsUrl: "https://rights.example.test/existing",
    originalFilename: `${assetId}.jpg`,
    createdAt: "2026-07-21T12:00:00.000Z",
    updatedAt: "2026-07-21T12:00:00.000Z",
    previewUrl: `https://images.example.test/${assetId}.jpg`,
    ...overrides,
  };
}

const handlers = () => ({
  onUpload: vi.fn(),
  onUpdateMetadata: vi.fn(),
  onReorder: vi.fn(),
  onDetach: vi.fn(),
  onClearError: vi.fn(),
});

describe("MediaEditor", () => {
  it("uses the active locale for image alternative text", () => {
    render(
      <LocaleProvider initialLocale="ko">
        <MediaEditor
          media={[asset("cover", "cover", 0)]}
          loading={false}
          busy={false}
          error={null}
          editable
          readOnlyReason={null}
          {...handlers()}
        />
      </LocaleProvider>,
    );

    expect(screen.getByRole("img", { name: "기존 한국어 설명" })).toBeInTheDocument();
  });

  it("keeps metadata local until the explicit save action", async () => {
    const user = userEvent.setup();
    const callbacks = handlers();
    render(
      <MediaEditor
        media={[
          asset("cover", "cover", 0, { status: "ready" }),
          asset("gallery-one", "gallery", 1),
        ]}
        loading={false}
        busy={false}
        error={null}
        editable
        readOnlyReason={null}
        {...callbacks}
      />,
    );

    expect(screen.getByText("Processing for publication")).toBeInTheDocument();
    const coverEditor = screen.getByText("cover.jpg").closest("article");
    expect(coverEditor).not.toBeNull();
    const altKo = within(coverEditor as HTMLElement).getByLabelText(
      "Alt text (Korean)",
    );
    await user.clear(altKo);
    await user.type(altKo, "새 한국어 설명");

    expect(callbacks.onUpdateMetadata).not.toHaveBeenCalled();
    await user.click(
      within(coverEditor as HTMLElement).getByRole("button", {
        name: "Save metadata",
      }),
    );
    expect(callbacks.onUpdateMetadata).toHaveBeenCalledWith("cover", {
      altKo: "새 한국어 설명",
      altEn: "Existing English description",
      credit: "Existing credit",
      rightsUrl: "https://rights.example.test/existing",
    });
  });

  it("exposes accessible gallery ordering and sends the exact gallery-only set", async () => {
    const user = userEvent.setup();
    const callbacks = handlers();
    render(
      <MediaEditor
        media={[
          asset("cover", "cover", 0),
          asset("gallery-one", "gallery", 1),
          asset("gallery-two", "gallery", 2),
        ]}
        loading={false}
        busy={false}
        error={null}
        editable
        readOnlyReason={null}
        {...callbacks}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: "Move gallery-one.jpg down" }),
    );
    expect(callbacks.onReorder).toHaveBeenCalledWith([
      "gallery-two",
      "gallery-one",
    ]);
    expect(callbacks.onReorder.mock.calls[0][0]).not.toContain("cover");
  });

  it("clears the file input while forwarding the selected upload role", async () => {
    const user = userEvent.setup();
    const callbacks = handlers();
    render(
      <MediaEditor
        media={[]}
        loading={false}
        busy={false}
        error={null}
        editable
        readOnlyReason={null}
        {...callbacks}
      />,
    );
    const chooser = screen.getByLabelText("Choose cover image") as HTMLInputElement;
    const file = new File(["image"], "new-cover.webp", { type: "image/webp" });

    await user.upload(chooser, file);

    expect(callbacks.onUpload).toHaveBeenCalledWith(file, "cover");
    expect(chooser.value).toBe("");
  });

  it("renders rejected media actionably and keeps archived controls read-only", () => {
    const callbacks = handlers();
    render(
      <MediaEditor
        media={[asset("rejected", "cover", 0, { status: "rejected" })]}
        loading={false}
        busy={false}
        error="Worker validation failed."
        editable={false}
        readOnlyReason="Archived exhibitions are read-only."
        {...callbacks}
      />,
    );

    expect(
      screen.getByText(/Remove it and upload another image/),
    ).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("! Worker validation failed.");
    expect(screen.getByLabelText("Replace cover")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Remove rejected.jpg" })).toBeDisabled();
  });
});
