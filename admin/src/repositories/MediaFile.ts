export const ADMIN_MEDIA_MAX_BYTES = 10 * 1024 * 1024;

export const ADMIN_MEDIA_MIME_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
] as const;

export function assertValidAdminMediaFile(file: File): void {
  if (!(ADMIN_MEDIA_MIME_TYPES as readonly string[]).includes(file.type)) {
    throw new Error("Choose a JPEG, PNG, or WebP image.");
  }
  if (file.size < 1) {
    throw new Error("The selected image is empty.");
  }
  if (file.size > ADMIN_MEDIA_MAX_BYTES) {
    throw new Error("The selected image exceeds the 10 MiB limit.");
  }
}

async function readFileBuffer(file: File): Promise<ArrayBuffer> {
  if (typeof file.arrayBuffer === "function") return file.arrayBuffer();
  if (typeof FileReader === "undefined") {
    throw new Error("This browser cannot read the selected image.");
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("The selected image could not be read."));
    reader.onload = () => {
      if (reader.result instanceof ArrayBuffer) resolve(reader.result);
      else reject(new Error("The selected image could not be read."));
    };
    reader.readAsArrayBuffer(file);
  });
}

export async function sha256File(file: File): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new Error("Secure image checksums are not available in this browser.");
  }
  const digest = await globalThis.crypto.subtle.digest(
    "SHA-256",
    await readFileBuffer(file),
  );
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

export async function readImageDimensions(
  file: File,
): Promise<{ width: number; height: number } | null> {
  if (typeof globalThis.createImageBitmap !== "function") return null;
  const bitmap = await globalThis.createImageBitmap(file);
  try {
    if (bitmap.width < 1 || bitmap.height < 1) return null;
    return { width: bitmap.width, height: bitmap.height };
  } finally {
    bitmap.close();
  }
}

export async function readFileDataUrl(file: File): Promise<string | null> {
  if (typeof FileReader === "undefined") return null;
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("The selected image could not be read."));
    reader.onload = () =>
      resolve(typeof reader.result === "string" ? reader.result : null);
    reader.readAsDataURL(file);
  });
}
