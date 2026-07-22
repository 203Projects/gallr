export type PayloadReadErrorCode =
  | "payload_empty"
  | "payload_invalid_encoding"
  | "payload_invalid_json"
  | "payload_too_large";

export class PayloadReadError extends Error {
  constructor(
    readonly code: PayloadReadErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "PayloadReadError";
  }
}

interface BodySource {
  readonly body: ReadableStream<Uint8Array> | null;
  readonly headers: Headers;
}

export function containsAsciiControlCharacters(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const codePoint = value.charCodeAt(index);
    if (codePoint <= 31 || codePoint === 127) return true;
  }
  return false;
}

export function isAbortError(error: unknown): boolean {
  if (error === null || typeof error !== "object") return false;
  const name = (error as { name?: unknown }).name;
  return name === "AbortError" || name === "TimeoutError";
}

export async function readBoundedJson(
  source: BodySource,
  maximumBytes: number,
): Promise<unknown> {
  const declaredLength = source.headers.get("content-length");
  if (declaredLength !== null) {
    if (!/^\d+$/u.test(declaredLength)) {
      throw new PayloadReadError(
        "payload_too_large",
        "Payload length is invalid.",
      );
    }
    const numericLength = Number(declaredLength);
    if (!Number.isSafeInteger(numericLength) || numericLength > maximumBytes) {
      throw new PayloadReadError(
        "payload_too_large",
        "Payload exceeds the size limit.",
      );
    }
  }

  if (source.body === null) {
    throw new PayloadReadError("payload_empty", "Payload is required.");
  }

  const chunks: Uint8Array[] = [];
  let byteLength = 0;
  const reader = source.body.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      byteLength += value.byteLength;
      if (byteLength > maximumBytes) {
        await reader.cancel();
        throw new PayloadReadError(
          "payload_too_large",
          "Payload exceeds the size limit.",
        );
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  if (byteLength === 0) {
    throw new PayloadReadError("payload_empty", "Payload is required.");
  }

  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new PayloadReadError(
      "payload_invalid_encoding",
      "Payload must be valid UTF-8.",
    );
  }

  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new PayloadReadError(
      "payload_invalid_json",
      "Payload must be valid JSON.",
    );
  }
}
