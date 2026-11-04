function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

/** A finite source proves rejection happens before consuming the remaining body. */
export async function assertBoundedBody(
  handler: (request: Request) => Promise<Response>,
  template: Request,
  maximumBytes: number,
  successStatus: number,
  overflowStatus: number,
  overflowError?: string,
): Promise<void> {
  const valid = await template.text();
  const validBytes = new TextEncoder().encode(valid);
  for (const declaredLength of [undefined, "1"]) {
    let pulls = 0;
    let cancelled = false;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulls++;
        if (pulls === 1) {
          controller.enqueue(new Uint8Array(maximumBytes).fill(32));
        } else if (pulls <= 3) controller.enqueue(new Uint8Array([32]));
        else controller.close();
      },
      cancel() {
        cancelled = true;
      },
    }, { highWaterMark: 0 });
    const headers = new Headers(template.headers);
    headers.delete("content-length");
    if (declaredLength) headers.set("content-length", declaredLength);
    const response = await handler(
      new Request(template.url, {
        method: "POST",
        headers,
        body: stream,
      }),
    );
    assert(response.status === overflowStatus, "overflow status changed");
    if (overflowError) {
      assert(
        (await response.json()).error === overflowError,
        "overflow code changed",
      );
    } else {
      assert(await response.text() === "", "overflow response must stay empty");
    }
    assert(cancelled, "oversized stream must be cancelled before reaching EOF");
    assert(pulls === 2, `read beyond the byte limit: ${pulls} pulls`);
    assert(!stream.locked, "overflow reader lock was not released");
  }

  // Whitespace pads valid JSON to exactly the byte limit; one-byte chunks also
  // split Korean UTF-8 code points without changing Request.text() decoding.
  const bytes = new Uint8Array(maximumBytes).fill(32);
  bytes.set(validBytes);
  let offset = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset < bytes.length) {
        controller.enqueue(bytes.slice(offset, ++offset));
      } else controller.close();
    },
  }, { highWaterMark: 0 });
  const headers = new Headers(template.headers);
  headers.delete("content-length");
  const response = await handler(
    new Request(template.url, {
      method: "POST",
      headers,
      body: stream,
    }),
  );
  assert(
    response.status === successStatus,
    "valid body at byte limit rejected",
  );
  assert(!stream.locked, "successful reader lock was not released");
}
