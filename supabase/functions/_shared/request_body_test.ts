import { readBoundedText } from "./request_body.ts";

function assert(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}

Deno.test("bounded text matches Request.text UTF-8 decoding", async () => {
  const cases = [
    new TextEncoder().encode(' {"name":"서울"} '),
    new Uint8Array([0xef, 0xbb, 0xbf, 123, 125]),
    new Uint8Array([34, 0xff, 34]),
    new Uint8Array(),
  ];
  for (const bytes of cases) {
    const expected = await new Response(bytes).text();
    let offset = 0;
    const body = new ReadableStream<Uint8Array<ArrayBuffer>>({
      pull(controller) {
        if (offset < bytes.length) {
          controller.enqueue(bytes.slice(offset, ++offset));
        } else controller.close();
      },
    });
    assert(
      await readBoundedText({ body }, 128) === expected,
      "decoding changed",
    );
    assert(!body.locked, "reader lock leaked");
  }
  assert(
    await readBoundedText({ body: null }, 128) === "",
    "null body changed",
  );
});

Deno.test("bounded text rejects a giant chunk even when cancellation fails or stalls", async () => {
  for (
    const cancel of [
      () => Promise.reject(new Error("cancel failed")),
      () => new Promise<void>(() => {}),
    ]
  ) {
    let pulls = 0;
    let cancelled = false;
    const body = new ReadableStream<Uint8Array<ArrayBuffer>>({
      pull(controller) {
        pulls++;
        controller.enqueue(new Uint8Array(1024));
      },
      cancel() {
        cancelled = true;
        return cancel();
      },
    }, { highWaterMark: 0 });
    assert(await readBoundedText({ body }, 16) === null, "overflow accepted");
    assert(pulls === 1 && cancelled, "overflow read or cancellation changed");
    assert(!body.locked, "overflow reader lock leaked");
  }
});

Deno.test("bounded text releases the lock and preserves stream read errors", async () => {
  const error = new Error("read failed");
  const body = new ReadableStream<Uint8Array<ArrayBuffer>>({
    pull(controller) {
      controller.error(error);
    },
  });
  let caught: unknown;
  try {
    await readBoundedText({ body }, 16);
  } catch (value) {
    caught = value;
  }
  assert(caught === error, "stream failure was remapped");
  assert(!body.locked, "failed reader lock leaked");
});

Deno.test("bounded text preserves decoded byte limits for malformed UTF-8", async () => {
  const body = new Response(new Uint8Array([0xff])).body;
  assert(await readBoundedText({ body }, 1) === null, "decoded limit bypassed");
});
