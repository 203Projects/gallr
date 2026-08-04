import { validateOpaqueToken } from "../_shared/opaque_token.ts";

export type MirrorSource = "outbox" | "five-minute-reconciliation";

type EnvironmentReader = (name: string) => string | undefined;

export interface LegacyCatalogMirrorHandlerDependencies {
  env: EnvironmentReader;
  mirror: (source: MirrorSource) => Promise<void>;
}

const MAX_BODY_BYTES = 4 * 1024;

function empty(status: number, extraHeaders: HeadersInit = {}): Response {
  return new Response(null, { status, headers: extraHeaders });
}

function constantTimeEquals(left: string, right: string): boolean {
  const maxLength = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let index = 0; index < maxLength; index += 1) {
    difference |= (left.charCodeAt(index) || 0) ^
      (right.charCodeAt(index) || 0);
  }
  return difference === 0;
}

function configuredToken(env: EnvironmentReader): string | null {
  const token = env("LEGACY_CATALOG_MIRROR_TOKEN")?.trim() ?? "";
  return validateOpaqueToken(token).valid ? token : null;
}

function parseSource(value: unknown): MirrorSource | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const source = (value as Record<string, unknown>).source;
  return source === "outbox" || source === "five-minute-reconciliation"
    ? source
    : null;
}

export function createLegacyCatalogMirrorHandler(
  dependencies: LegacyCatalogMirrorHandlerDependencies,
): (request: Request) => Promise<Response> {
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return empty(405, { Allow: "POST" });

    const token = configuredToken(dependencies.env);
    if (!token) return empty(500);
    const match = (request.headers.get("authorization") ?? "").match(
      /^Bearer ([^\s]+)$/,
    );
    if (!match || !constantTimeEquals(match[1], token)) return empty(401);

    const contentLength = Number(request.headers.get("content-length") ?? "0");
    if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
      return empty(413);
    }
    const rawBody = await request.text();
    if (new TextEncoder().encode(rawBody).byteLength > MAX_BODY_BYTES) {
      return empty(413);
    }

    let decoded: unknown;
    try {
      decoded = JSON.parse(rawBody);
    } catch {
      return empty(400);
    }
    const source = parseSource(decoded);
    if (!source) return empty(400);

    try {
      await dependencies.mirror(source);
      return empty(204);
    } catch {
      return empty(502);
    }
  };
}
