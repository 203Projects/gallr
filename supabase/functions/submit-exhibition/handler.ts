import {
  type SubmissionBackend,
  SubmissionBackendError,
  type SubmissionMediaInput,
} from "./backend.ts";

const MAX_REQUEST_BYTES = 32 * 1024 * 1024;
const MAX_IMAGE_BYTES = 6 * 1024 * 1024;
const MAX_IMAGES = 5;
const MAX_USER_AGENT_LENGTH = 512;
const DEFAULT_ALLOWED_ORIGINS = [
  "https://gallrmap.com",
  "https://www.gallrmap.com",
  "http://127.0.0.1:8080",
  "http://127.0.0.1:8081",
] as const;

const REQUIRED_FIELDS = [
  "name_ko",
  "venue_name_ko",
  "opening_date",
  "closing_date",
  "address_ko",
  "hours",
] as const;

const OPTIONAL_FIELDS = [
  "name_en",
  "venue_name_en",
  "address_en",
  "description_ko",
  "description_en",
  "reception_date",
  "reception_end",
] as const;

type EnvironmentReader = (name: string) => string | undefined;

export interface SubmitHandlerDependencies {
  env?: EnvironmentReader;
  createBackend: (environment: Record<string, string>) => SubmissionBackend;
  requestId?: () => string;
  digest?: (value: string) => Promise<string>;
  log?: (event: Record<string, unknown>) => void;
}

class RequestError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "RequestError";
  }
}

function jsonResponse(
  body: unknown,
  status: number,
  origin: string | null,
  requestId: string,
  extraHeaders?: HeadersInit,
): Response {
  const headers = new Headers({
    "Cache-Control": "no-store",
    "Content-Type": "application/json; charset=utf-8",
    "X-Content-Type-Options": "nosniff",
    "X-Request-Id": requestId,
    ...extraHeaders,
  });
  if (origin) {
    headers.set("Access-Control-Allow-Origin", origin);
    headers.set("Vary", "Origin");
  }
  return new Response(JSON.stringify(body), { status, headers });
}

function allowedOrigins(env: EnvironmentReader): Set<string> {
  const configured = env("SUBMISSION_ALLOWED_ORIGINS")
    ?.split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  return new Set(configured?.length ? configured : DEFAULT_ALLOWED_ORIGINS);
}

function validatedOrigin(
  request: Request,
  allowed: Set<string>,
): string | null {
  const origin = request.headers.get("origin");
  if (origin === null) return null;
  if (!allowed.has(origin)) {
    throw new RequestError(
      "origin_not_allowed",
      "This origin is not allowed.",
      403,
    );
  }
  return origin;
}

function contentLengthWithinLimit(request: Request): void {
  const raw = request.headers.get("content-length");
  if (raw === null) return;
  if (!/^\d+$/u.test(raw) || Number(raw) > MAX_REQUEST_BYTES) {
    throw new RequestError(
      "submission_too_large",
      "The submission exceeds the size limit.",
      413,
    );
  }
}

function text(
  form: FormData,
  name: string,
  maximum: number,
  required = false,
): string {
  const value = form.get(name);
  if (typeof value !== "string") {
    if (!required) return "";
    throw new RequestError(
      "submission_invalid",
      `${name} is required.`,
      400,
    );
  }
  const normalized = value.trim().normalize("NFC");
  if ((required && !normalized) || normalized.length > maximum) {
    throw new RequestError(
      "submission_invalid",
      `${name} is invalid.`,
      400,
    );
  }
  for (let index = 0; index < normalized.length; index += 1) {
    const code = normalized.charCodeAt(index);
    if (
      (code < 32 && code !== 10 && code !== 13 && code !== 9) || code === 127
    ) {
      throw new RequestError(
        "submission_invalid",
        `${name} contains invalid characters.`,
        400,
      );
    }
  }
  return normalized;
}

function validEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(value) && value.length <= 320;
}

function validIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) &&
    date.toISOString().slice(0, 10) === value;
}

function validLocalDateTime(value: string): boolean {
  if (
    !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d$/u.test(value)
  ) {
    return false;
  }
  const date = new Date(`${value}:00Z`);
  return !Number.isNaN(date.getTime()) &&
    date.toISOString().slice(0, 16) === value;
}

function payload(form: FormData): {
  submitterEmail: string;
  fields: Record<string, string>;
} {
  if (text(form, "website", 200) !== "") {
    throw new RequestError("submission_rejected", "Submission rejected.", 400);
  }
  const submitterEmail = text(form, "contact", 320, true).toLowerCase();
  if (!validEmail(submitterEmail)) {
    throw new RequestError(
      "submission_invalid",
      "contact is invalid.",
      400,
    );
  }
  const fields: Record<string, string> = {};
  for (const name of REQUIRED_FIELDS) {
    fields[name] = text(
      form,
      name,
      name === "hours" ? 1000 : name === "address_ko" ? 500 : 300,
      true,
    );
  }
  const optionalLimits: Record<(typeof OPTIONAL_FIELDS)[number], number> = {
    name_en: 300,
    venue_name_en: 300,
    address_en: 500,
    description_ko: 20_000,
    description_en: 20_000,
    reception_date: 32,
    reception_end: 32,
  };
  for (const name of OPTIONAL_FIELDS) {
    fields[name] = text(form, name, optionalLimits[name]);
  }
  if (
    !validIsoDate(fields.opening_date) ||
    !validIsoDate(fields.closing_date) ||
    fields.closing_date < fields.opening_date
  ) {
    throw new RequestError(
      "submission_invalid",
      "The exhibition dates are invalid.",
      400,
    );
  }
  if (
    (fields.reception_date !== "" &&
      !validLocalDateTime(fields.reception_date)) ||
    (fields.reception_end !== "" &&
      (!validLocalDateTime(fields.reception_end) ||
        fields.reception_date === "" ||
        fields.reception_end < fields.reception_date))
  ) {
    throw new RequestError(
      "submission_invalid",
      "The reception time is invalid.",
      400,
    );
  }
  return { submitterEmail, fields };
}

function images(form: FormData): File[] {
  const files = form.getAll("images").filter(
    (value): value is File => value instanceof File && value.size > 0,
  );
  if (files.length > MAX_IMAGES) {
    throw new RequestError(
      "submission_invalid",
      "Up to five images may be attached.",
      400,
    );
  }
  for (const file of files) {
    if (
      (file.type !== "image/jpeg" && file.type !== "image/png") ||
      file.size > MAX_IMAGE_BYTES
    ) {
      throw new RequestError(
        "submission_invalid",
        "Images must be JPEG or PNG files no larger than 6 MB.",
        400,
      );
    }
  }
  return files;
}

async function hasValidMagicBytes(file: File): Promise<boolean> {
  const bytes = new Uint8Array(await file.slice(0, 8).arrayBuffer());
  if (file.type === "image/jpeg") {
    return bytes.length >= 3 &&
      bytes[0] === 0xff &&
      bytes[1] === 0xd8 &&
      bytes[2] === 0xff;
  }
  return bytes.length >= 8 &&
    bytes[0] === 0x89 &&
    bytes[1] === 0x50 &&
    bytes[2] === 0x4e &&
    bytes[3] === 0x47 &&
    bytes[4] === 0x0d &&
    bytes[5] === 0x0a &&
    bytes[6] === 0x1a &&
    bytes[7] === 0x0a;
}

async function sha256(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const result = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(result))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function environment(env: EnvironmentReader): Record<string, string> {
  const names = [
    "SUPABASE_URL",
    "SUPABASE_SECRET_KEY",
    "SUPABASE_SERVICE_ROLE_KEY",
    "SUPABASE_SECRET_KEYS",
  ];
  return Object.fromEntries(
    names.map((name) => [name, env(name) ?? ""]),
  );
}

function requestIp(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
    "unknown";
}

function extension(file: File): "jpg" | "png" {
  return file.type === "image/png" ? "png" : "jpg";
}

export function createSubmitExhibitionHandler(
  dependencies: SubmitHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const requestId = dependencies.requestId ?? (() => crypto.randomUUID());
  const digest = dependencies.digest ?? sha256;
  const log = dependencies.log ??
    ((event) => console.info(JSON.stringify(event)));
  const origins = allowedOrigins(env);

  return async (request: Request): Promise<Response> => {
    const id = requestId();
    let origin: string | null = null;
    let uploadedPaths: string[] = [];
    let backend: SubmissionBackend | null = null;
    try {
      origin = validatedOrigin(request, origins);
      if (request.method === "OPTIONS") {
        return new Response(null, {
          status: 204,
          headers: {
            "Access-Control-Allow-Headers": "content-type",
            "Access-Control-Allow-Methods": "POST, OPTIONS",
            "Access-Control-Allow-Origin": origin ?? "",
            "Access-Control-Max-Age": "86400",
            "Vary": "Origin",
          },
        });
      }
      if (request.method !== "POST") {
        throw new RequestError(
          "method_not_allowed",
          "Only POST is supported.",
          405,
        );
      }
      if (
        !request.headers.get("content-type")?.startsWith("multipart/form-data")
      ) {
        throw new RequestError(
          "content_type_invalid",
          "multipart/form-data is required.",
          415,
        );
      }
      contentLengthWithinLimit(request);
      const form = await request.formData();
      const submissionPayload = payload(form);
      const submissionImages = images(form);
      for (const file of submissionImages) {
        if (!(await hasValidMagicBytes(file))) {
          throw new RequestError(
            "submission_invalid",
            "An attached image is invalid.",
            400,
          );
        }
      }

      const hashSecret = env("SUBMISSION_HASH_SECRET")?.trim();
      if (!hashSecret || hashSecret.length < 32) {
        throw new RequestError(
          "server_configuration_missing",
          "The submission service is not configured.",
          500,
        );
      }
      const sourceIpHash = await digest(
        `${hashSecret}:${requestIp(request)}`,
      );
      const submissionId = crypto.randomUUID();
      const media: SubmissionMediaInput[] = submissionImages.map((file) => {
        const assetId = crypto.randomUUID();
        return {
          asset_id: assetId,
          object_path: `submissions/${submissionId}/${assetId}/original.${
            extension(file)
          }`,
          mime_type: file.type as "image/jpeg" | "image/png",
          byte_size: file.size,
          original_filename: file.name.slice(0, 255),
        };
      });
      backend = dependencies.createBackend(environment(env));
      await backend.checkRateLimit(
        submissionPayload.submitterEmail,
        sourceIpHash,
      );
      for (let index = 0; index < submissionImages.length; index += 1) {
        await backend.upload(media[index].object_path, submissionImages[index]);
        uploadedPaths.push(media[index].object_path);
      }
      await backend.create({
        submissionId,
        submitterEmail: submissionPayload.submitterEmail,
        payload: submissionPayload.fields,
        sourceIpHash,
        userAgent: (request.headers.get("user-agent") ?? "")
          .slice(0, MAX_USER_AGENT_LENGTH),
        media,
      });
      uploadedPaths = [];
      log({
        event: "submission_created",
        request_id: id,
        submission_id: submissionId,
      });
      return jsonResponse(
        { success: true, submissionId },
        201,
        origin,
        id,
      );
    } catch (error) {
      if (backend && uploadedPaths.length > 0) {
        await backend.remove(uploadedPaths);
      }
      if (error instanceof RequestError) {
        log({ event: "submission_rejected", request_id: id, code: error.code });
        return jsonResponse(
          {
            success: false,
            error: { code: error.code, message: error.message },
          },
          error.status,
          origin,
          id,
          error.status === 405 ? { Allow: "POST, OPTIONS" } : undefined,
        );
      }
      if (error instanceof SubmissionBackendError) {
        const status = error.code === "submission_rate_limited" ? 429 : 502;
        log({
          event: "submission_backend_error",
          request_id: id,
          code: error.code,
        });
        return jsonResponse(
          {
            success: false,
            error: {
              code: error.code,
              message: status === 429
                ? "Too many submissions. Please try again later."
                : "The submission could not be completed.",
            },
          },
          status,
          origin,
          id,
          status === 429 ? { "Retry-After": "3600" } : undefined,
        );
      }
      log({ event: "submission_unexpected_error", request_id: id });
      return jsonResponse(
        {
          success: false,
          error: {
            code: "submission_failed",
            message: "The submission could not be completed.",
          },
        },
        500,
        origin,
        id,
      );
    }
  };
}
