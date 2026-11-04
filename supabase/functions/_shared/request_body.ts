/** Read at most maximumBytes, returning null for overflow. HTTP errors and JSON
 * parsing remain the caller's responsibility. Content-Length is not trusted. */
export async function readBoundedText(
  request: Pick<Request, "body">,
  maximumBytes: number,
): Promise<string | null> {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maximumBytes) {
        // Cancellation is best effort; its failure must not replace the size
        // response or keep the request waiting for an untrusted source.
        void reader.cancel().catch(() => {});
        return null;
      }
      if (value.byteLength) chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  // Match Request.text(): strip a UTF-8 BOM and replace malformed sequences.
  const text = new TextDecoder().decode(bytes);
  // Preserve the callers' existing bound on the decoded representation too.
  return new TextEncoder().encode(text).byteLength > maximumBytes ? null : text;
}
