import { validateOpaqueToken } from "../_shared/opaque_token.ts";

type EnvironmentReader = (name: string) => string | undefined;
type ApplyStatus = "applied" | "unchanged";

interface MirrorPayload {
  p_snapshot: Record<string, unknown>;
  p_source_project_ref: string;
  p_reason: string;
}

export interface LegacyCatalogReceiverDependencies {
  env: EnvironmentReader;
  apply: (payload: MirrorPayload) => Promise<ApplyStatus>;
}

const MAX_BODY_BYTES = 16 * 1024 * 1024;

function empty(status: number, headers: HeadersInit = {}): Response {
  return new Response(null, { status, headers });
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

function parsePayload(value: unknown): MirrorPayload | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const payload = value as Record<string, unknown>;
  const snapshot = payload.p_snapshot;
  if (!snapshot || typeof snapshot !== "object" || Array.isArray(snapshot)) {
    return null;
  }
  if (payload.p_source_project_ref !== "oqrvbstopuppznxqoonp") return null;
  if (
    typeof payload.p_reason !== "string" ||
    !payload.p_reason.trim() ||
    payload.p_reason.length > 500
  ) return null;
  return payload as unknown as MirrorPayload;
}

export function createLegacyCatalogMirrorReceiverHandler(
  dependencies: LegacyCatalogReceiverDependencies,
): (request: Request) => Promise<Response> {
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return empty(405, { Allow: "POST" });
    const token = dependencies.env("LEGACY_CATALOG_RECEIVER_TOKEN")?.trim() ??
      "";
    if (!validateOpaqueToken(token).valid) return empty(500);
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
    const payload = parsePayload(decoded);
    if (!payload) return empty(400);

    try {
      const status = await dependencies.apply(payload);
      return new Response(JSON.stringify({ status }), {
        status: 200,
        headers: {
          "Cache-Control": "no-store",
          "Content-Type": "application/json; charset=utf-8",
        },
      });
    } catch {
      return empty(502);
    }
  };
}
