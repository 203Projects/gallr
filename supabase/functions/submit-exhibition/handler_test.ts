import type { SubmissionBackend, SubmissionCreateInput } from "./backend.ts";
import { createSubmitExhibitionHandler } from "./handler.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

class RecordingBackend implements SubmissionBackend {
  readonly operations: string[] = [];
  readonly uploads: string[] = [];
  readonly removals: string[][] = [];
  createInput: SubmissionCreateInput | null = null;
  createError: Error | null = null;

  checkRateLimit(): Promise<void> {
    this.operations.push("rate-limit");
    return Promise.resolve();
  }

  upload(path: string): Promise<void> {
    this.operations.push("upload");
    this.uploads.push(path);
    return Promise.resolve();
  }

  create(input: SubmissionCreateInput): Promise<void> {
    this.operations.push("create");
    if (this.createError) return Promise.reject(this.createError);
    this.createInput = input;
    return Promise.resolve();
  }

  remove(paths: string[]): Promise<void> {
    this.removals.push(paths);
    return Promise.resolve();
  }
}

const environment: Record<string, string> = {
  SUPABASE_URL: "https://project.supabase.co",
  SUPABASE_SECRET_KEY: "sb_secret_test",
  SUBMISSION_HASH_SECRET: "x".repeat(32),
  SUBMISSION_ALLOWED_ORIGINS: "https://gallrmap.com",
};

function validForm(image?: File): FormData {
  const form = new FormData();
  form.set("name_ko", "기억의 층위");
  form.set("name_en", "Layers of Memory");
  form.set("venue_name_ko", "아트스페이스 이튼");
  form.set("venue_name_en", "Artspace Eaton");
  form.set("opening_date", "2026-08-15");
  form.set("closing_date", "2026-09-21");
  form.set("address_ko", "서울특별시 성동구 연무장길 68");
  form.set("hours", "화–금 11:00–19:00");
  form.set("contact", "gallery@example.com");
  if (image) form.append("images", image);
  return form;
}

function request(form: FormData): Request {
  return new Request(
    "https://project.supabase.co/functions/v1/submit-exhibition",
    {
      method: "POST",
      headers: {
        Origin: "https://gallrmap.com",
        "User-Agent": "test-agent",
        "X-Forwarded-For": "203.0.113.4",
      },
      body: form,
    },
  );
}

function handler(backend: RecordingBackend) {
  return createSubmitExhibitionHandler({
    env: (name) => environment[name],
    requestId: () => "request-1",
    digest: () => Promise.resolve("a".repeat(64)),
    log: () => undefined,
    createBackend: () => backend,
  });
}

Deno.test("accepts a valid metadata-only public submission", async () => {
  const backend = new RecordingBackend();
  const response = await handler(backend)(request(validForm()));
  const body = await response.json() as Record<string, unknown>;

  assert(response.status === 201, "expected created response");
  assert(body.success === true, "expected success body");
  assert(backend.createInput !== null, "submission was not persisted");
  assert(
    backend.createInput.submitterEmail === "gallery@example.com",
    "email was not normalized",
  );
  assert(
    backend.createInput.sourceIpHash === "a".repeat(64),
    "IP was not hashed",
  );
  assert(backend.createInput.media.length === 0, "unexpected media");
  assert(
    response.headers.get("access-control-allow-origin") ===
      "https://gallrmap.com",
    "CORS origin missing",
  );
});

Deno.test("uploads valid image bytes to an immutable private path", async () => {
  const jpeg = new File(
    [new Uint8Array([0xff, 0xd8, 0xff, 0xdb, 0x00, 0x01])],
    "installation.jpg",
    { type: "image/jpeg" },
  );
  const backend = new RecordingBackend();
  const response = await handler(backend)(request(validForm(jpeg)));

  assert(response.status === 201, "expected image submission success");
  assert(backend.uploads.length === 1, "image was not uploaded");
  assert(
    backend.operations.join(",") === "rate-limit,upload,create",
    "rate limiting did not run before storage",
  );
  assert(
    /^submissions\/[0-9a-f-]+\/[0-9a-f-]+\/original\.jpg$/u.test(
      backend.uploads[0],
    ),
    "image path is not immutable and submission-scoped",
  );
  assert(
    backend.createInput?.media[0].original_filename === "installation.jpg",
    "filename metadata missing",
  );
});

Deno.test("rejects invalid origins and honeypot traffic before storage", async () => {
  const backend = new RecordingBackend();
  const wrongOrigin = request(validForm());
  wrongOrigin.headers.set("Origin", "https://attacker.invalid");
  const originResponse = await handler(backend)(wrongOrigin);
  assert(originResponse.status === 403, "invalid origin was accepted");

  const botForm = validForm();
  botForm.set("website", "https://spam.invalid");
  const botResponse = await handler(backend)(request(botForm));
  assert(botResponse.status === 400, "honeypot was accepted");
  assert(backend.uploads.length === 0, "rejected input touched storage");
});

Deno.test("rejects spoofed image MIME types", async () => {
  const fakePng = new File(
    [new TextEncoder().encode("not a png")],
    "fake.png",
    { type: "image/png" },
  );
  const backend = new RecordingBackend();
  const response = await handler(backend)(request(validForm(fakePng)));
  assert(response.status === 400, "spoofed image was accepted");
  assert(backend.uploads.length === 0, "invalid image touched storage");
});

Deno.test("rejects malformed or reversed reception times before storage", async () => {
  const malformed = validForm();
  malformed.set("reception_date", "2026-02-30T18:00");
  const malformedBackend = new RecordingBackend();
  const malformedResponse = await handler(malformedBackend)(
    request(malformed),
  );
  assert(
    malformedResponse.status === 400,
    "invalid calendar date was accepted",
  );
  assert(
    malformedBackend.operations.length === 0,
    "invalid reception touched the backend",
  );

  const reversed = validForm();
  reversed.set("reception_date", "2026-08-15T20:00");
  reversed.set("reception_end", "2026-08-15T18:00");
  const reversedBackend = new RecordingBackend();
  const reversedResponse = await handler(reversedBackend)(request(reversed));
  assert(reversedResponse.status === 400, "reversed reception was accepted");
  assert(
    reversedBackend.operations.length === 0,
    "reversed reception touched the backend",
  );
});
