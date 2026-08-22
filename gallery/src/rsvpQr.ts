interface RsvpQrDownload {
  rsvpUrl: string;
  launchKitId: string;
}

export function rsvpQrFilename(launchKitId: string): string {
  const safeId = launchKitId
    .normalize("NFKD")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80)
    .replace(/-+$/g, "");
  return `gallr-rsvp-${safeId || "launch-kit"}.svg`;
}

export async function downloadRsvpQr({
  rsvpUrl,
  launchKitId,
}: RsvpQrDownload): Promise<void> {
  const { renderSVG } = await import("uqr");
  const svg = renderSVG(rsvpUrl, {
    ecc: "M",
    border: 4,
    blackColor: "#000000",
    whiteColor: "#ffffff",
  });
  const blob = new Blob([svg], { type: "image/svg+xml;charset=utf-8" });
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = rsvpQrFilename(launchKitId);
  let downloadStarted = false;

  try {
    document.body.append(anchor);
    anchor.click();
    downloadStarted = true;
  } finally {
    anchor.remove();
    if (downloadStarted) {
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1_000);
    } else {
      URL.revokeObjectURL(objectUrl);
    }
  }
}
