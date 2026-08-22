import { downloadRsvpQr, rsvpQrFilename } from "./rsvpQr";

const qr = vi.hoisted(() => ({
  moduleLoads: 0,
  renderSVG: vi.fn(),
}));

vi.mock("uqr", () => {
  qr.moduleLoads += 1;
  return { renderSVG: qr.renderSVG };
});

describe("RSVP QR download", () => {
  const rsvpUrl =
    "https://public-preview.example.test/rsvp/?token=00000000-0000-4000-8000-000000000001";
  let originalCreateObjectUrl: typeof URL.createObjectURL | undefined;
  let originalRevokeObjectUrl: typeof URL.revokeObjectURL | undefined;

  beforeEach(() => {
    vi.useFakeTimers();
    qr.renderSVG.mockReset();
    qr.renderSVG.mockReturnValue(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 29 29"><path /></svg>',
    );
    originalCreateObjectUrl = URL.createObjectURL;
    originalRevokeObjectUrl = URL.revokeObjectURL;
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn().mockReturnValue("blob:rsvp-qr"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    if (originalCreateObjectUrl) {
      Object.defineProperty(URL, "createObjectURL", {
        configurable: true,
        value: originalCreateObjectUrl,
      });
    } else {
      Reflect.deleteProperty(URL, "createObjectURL");
    }
    if (originalRevokeObjectUrl) {
      Object.defineProperty(URL, "revokeObjectURL", {
        configurable: true,
        value: originalRevokeObjectUrl,
      });
    } else {
      Reflect.deleteProperty(URL, "revokeObjectURL");
    }
  });

  it("loads the QR renderer on demand with print-safe correction and quiet zone", async () => {
    expect(qr.moduleLoads).toBe(0);
    expect(qr.renderSVG).not.toHaveBeenCalled();

    await downloadRsvpQr({
      rsvpUrl,
      launchKitId: "launch-00000000-0000-4000-8000-000000000001",
    });

    expect(qr.moduleLoads).toBe(1);
    expect(qr.renderSVG).toHaveBeenCalledOnce();
    expect(qr.renderSVG).toHaveBeenCalledWith(
      rsvpUrl,
      expect.objectContaining({
        ecc: "M",
        border: 4,
        blackColor: "#000000",
        whiteColor: "#ffffff",
      }),
    );
    expect(URL.createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    const blob = vi.mocked(URL.createObjectURL).mock.calls[0][0] as Blob;
    expect(blob.type).toBe("image/svg+xml;charset=utf-8");
    expect(blob.size).toBeGreaterThan(0);
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();
    await vi.runAllTimersAsync();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:rsvp-qr");
  });

  it("uses a deterministic filesystem-safe SVG filename", () => {
    expect(rsvpQrFilename("launch-00000000-0000-4000-8000-000000000001"))
      .toBe("gallr-rsvp-launch-00000000-0000-4000-8000-000000000001.svg");
    expect(rsvpQrFilename("../../Launch Kit / 서울"))
      .toBe("gallr-rsvp-launch-kit.svg");
    expect(rsvpQrFilename("///"))
      .toBe("gallr-rsvp-launch-kit.svg");
  });

  it("revokes the temporary Blob URL even when the browser download fails", async () => {
    vi.mocked(HTMLAnchorElement.prototype.click)
      .mockImplementationOnce(() => { throw new Error("download blocked"); });

    await expect(downloadRsvpQr({
      rsvpUrl,
      launchKitId: "launch-one",
    })).rejects.toThrow("download blocked");
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:rsvp-qr");
  });
});
