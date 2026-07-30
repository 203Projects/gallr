export class MediaSourcePathError extends Error {
  constructor() {
    super("Private media path is not canonical.");
    this.name = "MediaSourcePathError";
  }
}

export function validateSourcePath(
  assetId: string,
  objectPath: string,
): string {
  const parts = objectPath.split("/");
  const supportedScope = parts[0] === "drafts" || parts[0] === "submissions";
  if (
    parts.length !== 4 ||
    !supportedScope ||
    !/^[A-Za-z0-9_-]+$/u.test(parts[1]) ||
    parts[2] !== assetId ||
    !/^original\.(jpg|png|webp)$/u.test(parts[3])
  ) {
    throw new MediaSourcePathError();
  }
  return parts[3].slice("original.".length);
}
