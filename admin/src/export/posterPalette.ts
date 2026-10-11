/**
 * Poster fetch and palette sampling for the carousel export (DESIGN.md exported-content exception). Mirrors the
 * gallery workspace's QR export: a public https poster, a bounded download, a 48×48 sample, five scan-safe tones
 * sorted dark to light. Admin and the gallery workspace are separate deployables, so the logic lives in both.
 */
const SAMPLE_SIZE = 48;
const PALETTE_SIZE = 5;
const MAX_POSTER_BYTES = 12_000_000;
const POSTER_FETCH_TIMEOUT_MS = 10_000;
/** Dark tones for type and QR modules keep at least 7:1 against white (luminance ≤ 0.09). */
const MAX_DARK_LUMINANCE = 0.09;

export const FALLBACK_PALETTE = ["#000000", "#181818", "#2C2C2C", "#404040", "#555555"] as const;

export interface Rgb {
  red: number;
  green: number;
  blue: number;
}

export interface LoadedPoster {
  bitmap: ImageBitmap;
  palette: string[];
}

export class PosterUnavailableError extends Error {
  constructor(readonly reason: string) {
    super(`The poster could not be used: ${reason}.`);
    this.name = "PosterUnavailableError";
  }
}

function channelToLinear(channel: number): number {
  const value = channel / 255;
  return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
}

export function luminance(color: Rgb): number {
  return 0.2126 * channelToLinear(color.red) + 0.7152 * channelToLinear(color.green) + 0.0722 * channelToLinear(color.blue);
}

export function parseHexColor(color: string): Rgb | null {
  const match = /^#([0-9a-f]{6})$/i.exec(color.trim());
  if (!match) return null;
  const value = Number.parseInt(match[1], 16);
  return { red: (value >> 16) & 255, green: (value >> 8) & 255, blue: value & 255 };
}

export function toHex(color: Rgb): string {
  const channel = (value: number) => Math.round(Math.max(0, Math.min(255, value))).toString(16).padStart(2, "0").toUpperCase();
  return `#${channel(color.red)}${channel(color.green)}${channel(color.blue)}`;
}

/** WCAG contrast ratio between two colours. */
export function contrastRatio(first: Rgb, second: Rgb): number {
  const [light, dark] = [luminance(first), luminance(second)].sort((a, b) => b - a);
  return (light + 0.05) / (dark + 0.05);
}

function scale(color: Rgb, factor: number): Rgb {
  return { red: color.red * factor, green: color.green * factor, blue: color.blue * factor };
}

function scanSafe(color: Rgb): Rgb {
  if (luminance(color) <= MAX_DARK_LUMINANCE) return color;
  let safe = 0;
  let unsafe = 1;
  for (let iteration = 0; iteration < 16; iteration += 1) {
    const candidate = (safe + unsafe) / 2;
    if (luminance(scale(color, candidate)) <= MAX_DARK_LUMINANCE) safe = candidate;
    else unsafe = candidate;
  }
  return scale(color, safe);
}

function saturation(color: Rgb): number {
  const maximum = Math.max(color.red, color.green, color.blue);
  const minimum = Math.min(color.red, color.green, color.blue);
  return maximum === 0 ? 0 : (maximum - minimum) / maximum;
}

/** Five tones from the sampled pixels: the dominant saturated colours first, darkened until scan-safe. */
export function derivePalette(pixels: Uint8ClampedArray): string[] {
  const buckets = new Map<number, { count: number; red: number; green: number; blue: number }>();
  for (let index = 0; index + 3 < pixels.length; index += 4) {
    if (pixels[index + 3] < 128) continue;
    const red = pixels[index];
    const green = pixels[index + 1];
    const blue = pixels[index + 2];
    const key = ((red >> 5) << 10) | ((green >> 5) << 5) | (blue >> 5);
    const bucket = buckets.get(key) ?? { count: 0, red: 0, green: 0, blue: 0 };
    bucket.count += 1;
    bucket.red += red;
    bucket.green += green;
    bucket.blue += blue;
    buckets.set(key, bucket);
  }
  const candidates = Array.from(buckets.values())
    .map((bucket) => ({
      color: { red: bucket.red / bucket.count, green: bucket.green / bucket.count, blue: bucket.blue / bucket.count },
      count: bucket.count,
    }))
    .sort((a, b) => b.count * (0.25 + saturation(b.color) * 1.75) - a.count * (0.25 + saturation(a.color) * 1.75))
    .reduce<Rgb[]>((selected, candidate) => {
      const far = selected.every((color) =>
        Math.hypot(color.red - candidate.color.red, color.green - candidate.color.green, color.blue - candidate.color.blue) >= 42
      );
      if (selected.length < PALETTE_SIZE && far) selected.push(candidate.color);
      return selected;
    }, []);
  if (candidates.length === 0) return [...FALLBACK_PALETTE];
  const palette = new Set(candidates.map(scanSafe).map(toHex));
  const dominant = scanSafe(candidates[0]);
  for (const factor of [0.88, 0.76, 0.64, 0.52, 0.4, 0.3]) {
    if (palette.size >= PALETTE_SIZE) break;
    palette.add(toHex(scale(dominant, factor)));
  }
  for (const fallback of FALLBACK_PALETTE) {
    if (palette.size >= PALETTE_SIZE) break;
    palette.add(fallback);
  }
  return Array.from(palette)
    .sort((a, b) => luminance(parseHexColor(a)!) - luminance(parseHexColor(b)!))
    .slice(0, PALETTE_SIZE);
}

async function decode(blob: Blob): Promise<ImageBitmap> {
  if (typeof createImageBitmap !== "function") throw new PosterUnavailableError("decoding unsupported");
  return await createImageBitmap(blob);
}

function samplePixels(bitmap: ImageBitmap): Uint8ClampedArray {
  const canvas = document.createElement("canvas");
  canvas.width = SAMPLE_SIZE;
  canvas.height = SAMPLE_SIZE;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) throw new PosterUnavailableError("canvas unavailable");
  context.drawImage(bitmap, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
  return context.getImageData(0, 0, SAMPLE_SIZE, SAMPLE_SIZE).data;
}

/** Fetches a public https poster, decodes it for drawing and samples its palette; refuses anything else. */
export async function loadPoster(posterUrl: string | null): Promise<LoadedPoster> {
  if (!posterUrl) throw new PosterUnavailableError("no cover image");
  let url: URL;
  try {
    url = new URL(posterUrl);
  } catch {
    throw new PosterUnavailableError("invalid cover url");
  }
  if (url.protocol !== "https:") throw new PosterUnavailableError("cover must be https");
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), POSTER_FETCH_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      cache: "force-cache",
      credentials: "omit",
      mode: "cors",
      referrerPolicy: "no-referrer",
      signal: controller.signal,
    });
    if (!response.ok) throw new PosterUnavailableError("cover fetch failed");
    const blob = await response.blob();
    const contentType = blob.type || response.headers.get("content-type") || "";
    if (!contentType.toLowerCase().startsWith("image/")) throw new PosterUnavailableError("cover is not an image");
    if (blob.size === 0 || blob.size > MAX_POSTER_BYTES) throw new PosterUnavailableError("cover size out of range");
    const bitmap = await decode(blob);
    return { bitmap, palette: derivePalette(samplePixels(bitmap)) };
  } catch (error) {
    if (error instanceof PosterUnavailableError) throw error;
    throw new PosterUnavailableError("cover fetch failed");
  } finally {
    window.clearTimeout(timeout);
  }
}
