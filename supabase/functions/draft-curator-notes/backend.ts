import Anthropic from "@anthropic-ai/sdk";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import {
  resolveSupabasePublishableKey,
  resolveSupabaseSecretKey,
} from "../_shared/supabase_keys.ts";
import {
  type DraftMessages,
  type Packet,
  PACKET_HASH_PATTERN,
  type StorePayload,
} from "./drafting.ts";
import { type NoteDraft, NoteDraftSchema } from "./schema.ts";

export interface Candidate {
  exhibitionId: string;
  isFeatured: boolean;
  packet: Packet;
  packetHash: string;
  sourceVersionId: string | null;
}

export interface StoredVersion {
  id: string;
  versionNumber: number;
  status: string;
}

export interface DraftAnswer {
  parsed: NoteDraft | null;
  model: string;
  stopReason: string;
  usage: {
    inputTokens: number;
    cacheReadInputTokens: number;
    outputTokens: number;
  };
}

/** Everything the handler needs from the outside world; tests substitute a fake. */
export interface DraftingBackend {
  listCandidates(limit: number): Promise<Candidate[]>;
  requestPacket(exhibitionId: string, jwt: string): Promise<Candidate | null>;
  isPublisher(jwt: string): Promise<boolean>;
  storeDraft(payload: StorePayload): Promise<StoredVersion>;
  draft(messages: DraftMessages): Promise<DraftAnswer>;
}

/** A database error surfaced with its SQLSTATE so the handler can name the outcome. */
export class StoreError extends Error {
  constructor(message: string, readonly code: string) {
    super(message);
    this.name = "StoreError";
  }
}

const DEFAULT_MODEL = "claude-opus-5-5";
const COMPONENT = "draft-curator-notes";

function required(
  environment: Record<string, string | undefined>,
  name: string,
): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

function asString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function toCandidate(row: unknown): Candidate | null {
  if (!row || typeof row !== "object") return null;
  const record = row as Record<string, unknown>;
  const packet = record.packet;
  const hash = asString(record.packet_hash);
  const exhibitionId = asString(record.exhibition_id);
  if (
    !packet || typeof packet !== "object" || Array.isArray(packet) ||
    !PACKET_HASH_PATTERN.test(hash) || !exhibitionId
  ) {
    return null;
  }
  return {
    exhibitionId,
    isFeatured: record.is_featured === true,
    packet: packet as Packet,
    packetHash: hash,
    sourceVersionId: typeof record.source_version_id === "string"
      ? record.source_version_id
      : null,
  };
}

class SupabaseDraftingBackend implements DraftingBackend {
  private readonly service: SupabaseClient;
  private readonly url: string;
  private readonly publishableKey: string;
  private readonly anthropic: Anthropic;
  private readonly model: string;
  private readonly systemPrompt: string;

  constructor(
    environment: Record<string, string | undefined>,
    systemPrompt: string,
  ) {
    this.url = required(environment, "SUPABASE_URL");
    this.publishableKey = resolveSupabasePublishableKey(environment, COMPONENT);
    this.service = createClient(
      this.url,
      resolveSupabaseSecretKey(environment, COMPONENT),
      {
        auth: { autoRefreshToken: false, persistSession: false },
      },
    );
    this.anthropic = new Anthropic({
      apiKey: required(environment, "ANTHROPIC_API_KEY"),
      timeout: 90_000,
      maxRetries: 2,
    });
    this.model = environment.CURATOR_NOTES_MODEL?.trim() || DEFAULT_MODEL;
    this.systemPrompt = systemPrompt;
  }

  private sessionClient(jwt: string): SupabaseClient {
    return createClient(this.url, this.publishableKey, {
      auth: { autoRefreshToken: false, persistSession: false },
      global: { headers: { Authorization: `Bearer ${jwt}` } },
    });
  }

  async listCandidates(limit: number): Promise<Candidate[]> {
    const { data, error } = await this.service.rpc(
      "service_list_curator_note_candidates",
      { p_limit: limit },
    );
    if (error) throw new StoreError(error.message, error.code ?? "");
    if (!Array.isArray(data)) return [];
    return data.map(toCandidate).filter((candidate): candidate is Candidate =>
      candidate !== null
    );
  }

  async requestPacket(
    exhibitionId: string,
    jwt: string,
  ): Promise<Candidate | null> {
    const { data, error } = await this.sessionClient(jwt).rpc(
      "request_curator_note_draft",
      {
        p_exhibition_id: exhibitionId,
      },
    );
    if (error) {
      if (error.code === "P0002") return null;
      throw new StoreError(error.message, error.code ?? "");
    }
    const candidate = toCandidate(data);
    if (!candidate) return null;
    const qualifies = (data as Record<string, unknown>).qualifies === true;
    return qualifies ? candidate : null;
  }

  async isPublisher(jwt: string): Promise<boolean> {
    const { data, error } = await this.sessionClient(jwt).rpc(
      "admin_current_staff",
    );
    if (error || !data || typeof data !== "object") return false;
    const staff = data as Record<string, unknown>;
    return staff.active === true &&
      (staff.role === "publisher" || staff.role === "admin");
  }

  async storeDraft(payload: StorePayload): Promise<StoredVersion> {
    const { data, error } = await this.service.rpc(
      "service_store_curator_note_draft",
      { p_payload: payload },
    );
    if (error) throw new StoreError(error.message, error.code ?? "");
    const row = (data ?? {}) as Record<string, unknown>;
    return {
      id: asString(row.id),
      versionNumber: typeof row.version_number === "number"
        ? row.version_number
        : 0,
      status: asString(row.status),
    };
  }

  async draft(messages: DraftMessages): Promise<DraftAnswer> {
    const response = await this.anthropic.messages.parse(
      {
        model: this.model,
        max_tokens: 8000,
        system: [{
          type: "text",
          text: messages.system,
          cache_control: { type: "ephemeral" },
        }],
        messages: [{ role: "user", content: messages.user }],
        output_config: {
          effort: "high",
          format: zodOutputFormat(NoteDraftSchema),
        },
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
      } as Parameters<typeof this.anthropic.messages.parse>[0],
    );
    const usage = response.usage as unknown as Record<
      string,
      number | null | undefined
    >;
    return {
      parsed: (response.parsed_output as NoteDraft | null | undefined) ?? null,
      model: response.model,
      stopReason: response.stop_reason ?? "",
      usage: {
        inputTokens: usage.input_tokens ?? 0,
        cacheReadInputTokens: usage.cache_read_input_tokens ?? 0,
        outputTokens: usage.output_tokens ?? 0,
      },
    };
  }
}

export function createDraftingBackend(
  environment: Record<string, string | undefined>,
  systemPrompt: string,
): DraftingBackend {
  return new SupabaseDraftingBackend(environment, systemPrompt);
}
