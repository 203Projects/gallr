import type { Candidate, DraftAnswer, DraftingBackend } from "./backend.ts";
import {
  buildMessages,
  DEFAULT_LIMIT,
  EXHIBITION_ID_PATTERN,
  loadPromptAssets,
  MAX_LIMIT,
  PROMPT_VERSION,
  type PromptAssets,
  type RejectionCode,
  sourceFieldsOf,
  toStorePayload,
  validateDraft,
} from "./drafting.ts";

const MAX_BODY_BYTES = 16 * 1024;
const BUDGET_MS = 50_000;
const DEFAULT_ORIGINS = ["https://admin.gallrmap.com"] as const;

type EnvironmentReader = (name: string) => string | undefined;
type RequestKind = "scheduled" | "single" | "regenerate";
type Outcome = "drafted" | "unusable" | "failed";

export interface DraftHandlerDependencies {
  env?: EnvironmentReader;
  createBackend: (
    environment: Record<string, string | undefined>,
    systemPrompt: string,
  ) => DraftingBackend;
  assets?: PromptAssets;
  log?: (value: unknown) => void;
  now?: () => number;
  budgetMs?: number;
}

interface Result {
  exhibition_id: string;
  outcome: Outcome;
  code: string | null;
}

interface Summary {
  drafted: number;
  unusable: number;
  skipped: number;
  failed: number;
  remaining: number;
  results: Result[];
}

const ENV_NAMES = [
  "SUPABASE_URL",
  "SUPABASE_SECRET_KEY",
  "SUPABASE_SECRET_KEYS",
  "SUPABASE_SERVICE_ROLE_KEY",
  "SUPABASE_PUBLISHABLE_KEY",
  "SUPABASE_PUBLISHABLE_KEYS",
  "SUPABASE_ANON_KEY",
  "ANTHROPIC_API_KEY",
  "CURATOR_NOTES_MODEL",
] as const;

function environment(
  env: EnvironmentReader,
): Record<string, string | undefined> {
  return Object.fromEntries(ENV_NAMES.map((name) => [name, env(name)]));
}

function timingSafeEqual(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let index = 0; index < left.length; index += 1) {
    diff |= left[index] ^ right[index];
  }
  return diff === 0;
}

function bearer(request: Request): string | null {
  const header = request.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(\S+)$/.exec(header);
  return match ? match[1] : null;
}

function subjectOf(jwt: string): string | null {
  const parts = jwt.split(".");
  if (parts.length < 2) return null;
  try {
    const json = atob(parts[1].replace(/-/g, "+").replace(/_/g, "/"));
    const claims = JSON.parse(json) as Record<string, unknown>;
    return typeof claims.sub === "string" && /^[0-9a-f-]{36}$/i.test(claims.sub)
      ? claims.sub
      : null;
  } catch {
    return null;
  }
}

function summary(results: Result[], remaining: number): Summary {
  return {
    drafted: results.filter((result) => result.outcome === "drafted").length,
    unusable: results.filter((result) => result.outcome === "unusable").length,
    skipped: 0,
    failed: results.filter((result) => result.outcome === "failed").length,
    remaining,
    results,
  };
}

export function createDraftHandler(
  dependencies: DraftHandlerDependencies,
): (request: Request) => Promise<Response> {
  const env = dependencies.env ?? ((name) => Deno.env.get(name));
  const log = dependencies.log ??
    ((value: unknown) => console.log(JSON.stringify(value)));
  const now = dependencies.now ?? (() => Date.now());
  const budgetMs = dependencies.budgetMs ?? BUDGET_MS;
  const configuredOrigins = env("CURATOR_NOTES_ALLOWED_ORIGINS")?.split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const origins = new Set(
    configuredOrigins?.length ? configuredOrigins : DEFAULT_ORIGINS,
  );
  const scheduleToken = env("CURATOR_NOTES_SCHEDULE_TOKEN")?.trim() ?? "";
  let assetsPromise: Promise<PromptAssets> | null = dependencies.assets
    ? Promise.resolve(dependencies.assets)
    : null;
  let backend: DraftingBackend | null = null;

  const respond = (
    body: unknown,
    status: number,
    origin: string | null,
  ): Response => {
    const headers: Record<string, string> = {
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      Vary: "Origin",
    };
    if (origin) {
      headers["Access-Control-Allow-Origin"] = origin;
      headers["Access-Control-Allow-Headers"] = "authorization, content-type";
      headers["Access-Control-Allow-Methods"] = "POST, OPTIONS";
    }
    return new Response(body === null ? null : JSON.stringify(body), {
      status,
      headers,
    });
  };

  const assets = async (): Promise<PromptAssets> => {
    if (!assetsPromise) assetsPromise = loadPromptAssets();
    return await assetsPromise;
  };

  const backendFor = async (): Promise<DraftingBackend> => {
    if (!backend) {
      backend = dependencies.createBackend(
        environment(env),
        (await assets()).system,
      );
    }
    return backend;
  };

  const draftOne = async (
    candidate: Candidate,
    requestKind: RequestKind,
    requestedBy: string | null,
  ): Promise<Result> => {
    const api = await backendFor();
    const sourceFields = sourceFieldsOf(candidate.packet);
    const messages = buildMessages(candidate.packet, await assets());
    const base = {
      exhibition_id: candidate.exhibitionId,
      prompt_version: PROMPT_VERSION,
      request_kind: requestKind,
    };
    let answer: DraftAnswer;
    try {
      answer = await api.draft(messages);
    } catch (error) {
      log({
        operation: "curator_note_provider_failed",
        ...base,
        error: error instanceof Error ? error.name : "unknown",
      });
      return {
        exhibition_id: candidate.exhibitionId,
        outcome: "failed",
        code: "provider_failed",
      };
    }
    let rejection: RejectionCode | null = null;
    if (!answer.parsed) {
      rejection = answer.stopReason === "refusal"
        ? "provider_refused"
        : "parse_failed";
    } else {
      const validation = validateDraft(answer.parsed, candidate.packet);
      if (!validation.ok) rejection = validation.code;
    }
    const payload = toStorePayload({
      exhibitionId: candidate.exhibitionId,
      packetHash: candidate.packetHash,
      sourceFields,
      sourceVersionId: candidate.sourceVersionId,
      draft: rejection ? null : answer.parsed,
      rejectionCode: rejection ?? undefined,
      model: answer.model,
      requestKind,
      requestedBy,
      generatedAt: new Date(now()).toISOString(),
      // Regenerate is the explicit "write over what is there" action; Draft now is not.
      override: requestKind === "regenerate",
    });
    try {
      const stored = await api.storeDraft(payload);
      const outcome: Outcome = rejection ? "unusable" : "drafted";
      log({
        operation: rejection ? "curator_note_unusable" : "curator_note_drafted",
        ...base,
        version_id: stored.id,
        version_number: stored.versionNumber,
        model: answer.model,
        input_tokens: answer.usage.inputTokens,
        cache_read_input_tokens: answer.usage.cacheReadInputTokens,
        output_tokens: answer.usage.outputTokens,
        code: rejection,
      });
      return {
        exhibition_id: candidate.exhibitionId,
        outcome,
        code: rejection,
      };
    } catch (error) {
      const message = error instanceof Error ? error.message : "";
      const code = message === "curator_note_source_changed"
        ? "source_changed"
        : "store_failed";
      log({ operation: "curator_note_store_failed", ...base, code });
      return { exhibition_id: candidate.exhibitionId, outcome: "failed", code };
    }
  };

  return async (request: Request): Promise<Response> => {
    const originHeader = request.headers.get("origin");
    const origin = originHeader && origins.has(originHeader)
      ? originHeader
      : null;
    if (request.method === "OPTIONS") {
      return origin
        ? respond(null, 204, origin)
        : respond({ error: "forbidden" }, 403, null);
    }
    if (request.method !== "POST") {
      return respond({ error: "method_not_allowed" }, 405, origin);
    }
    if (
      !(request.headers.get("content-type") ?? "").toLowerCase().startsWith(
        "application/json",
      )
    ) {
      return respond({ error: "invalid_request" }, 400, origin);
    }
    const raw = await request.text();
    if (new TextEncoder().encode(raw).length > MAX_BODY_BYTES) {
      return respond({ error: "invalid_request" }, 400, origin);
    }
    let body: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(raw);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("shape");
      }
      body = parsed as Record<string, unknown>;
    } catch {
      return respond({ error: "invalid_request" }, 400, origin);
    }

    if (body.mode === "scheduled") {
      const token = bearer(request);
      if (!token || !scheduleToken || !timingSafeEqual(token, scheduleToken)) {
        return respond({ error: "unauthorized" }, 401, origin);
      }
      const requested =
        typeof body.limit === "number" && Number.isInteger(body.limit)
          ? body.limit
          : DEFAULT_LIMIT;
      const limit = Math.min(Math.max(requested, 1), MAX_LIMIT);
      const api = await backendFor();
      const candidates = await api.listCandidates(limit);
      const started = now();
      const results: Result[] = [];
      let index = 0;
      for (; index < candidates.length; index += 1) {
        if (now() - started >= budgetMs) break;
        results.push(await draftOne(candidates[index], "scheduled", null));
      }
      return respond(summary(results, candidates.length - index), 200, origin);
    }

    if (body.mode === "single") {
      const jwt = bearer(request);
      const exhibitionId = typeof body.exhibition_id === "string"
        ? body.exhibition_id.trim()
        : "";
      const requestKind: RequestKind = body.request_kind === "regenerate"
        ? "regenerate"
        : "single";
      if (!jwt) return respond({ error: "unauthorized" }, 401, origin);
      if (!EXHIBITION_ID_PATTERN.test(exhibitionId)) {
        return respond({ error: "invalid_request" }, 400, origin);
      }
      const api = await backendFor();
      if (!(await api.isPublisher(jwt))) {
        return respond({ error: "unauthorized" }, 401, origin);
      }
      const candidate = await api.requestPacket(exhibitionId, jwt);
      if (!candidate) {
        return respond({ error: "exhibition_not_qualifying" }, 404, origin);
      }
      const result = await draftOne(candidate, requestKind, subjectOf(jwt));
      if (result.outcome === "failed") {
        const status = result.code === "provider_failed" ? 503 : 500;
        return respond(
          {
            error: result.code === "provider_failed"
              ? "provider_unavailable"
              : "store_failed",
          },
          status,
          origin,
        );
      }
      return respond(summary([result], 0), 200, origin);
    }

    return respond({ error: "invalid_request" }, 400, origin);
  };
}
