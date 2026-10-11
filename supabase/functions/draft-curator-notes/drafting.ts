import type { NoteDraft, NoteLanguage } from "./schema.ts";

/** Bump whenever prompt/system.md, prompt/examples.json or the request shape changes (R13). */
export const PROMPT_VERSION = "2026-10-11.1";

export type Packet = Record<string, unknown>;

export interface PromptAssets {
  system: string;
  examples: unknown[];
}

export interface DraftMessages {
  system: string;
  user: string;
}

export type RejectionCode =
  | "grounding_unknown_key"
  | "number_not_in_sources"
  | "language_mismatch"
  | "length_exceeded"
  | "insight_count"
  | "provider_refused"
  | "parse_failed"
  | "source_changed";

export type Validation = { ok: true } | { ok: false; code: RejectionCode };

const HANGUL = /[ᄀ-ᇿ㄰-㆏가-힯]/;
const NUMBER = /\d+/g;
const HOOK_MAX = 120;
const INSIGHT_MAX = 420;
const TIP_MAX = 240;

/** The packet keys a draft may cite, sorted so provenance is stable. */
export function sourceFieldsOf(packet: Packet): string[] {
  return Object.keys(packet).sort();
}

/** The system prompt (cached across exhibitions) and the per-exhibition user turn. */
export function buildMessages(
  packet: Packet,
  assets: PromptAssets,
): DraftMessages {
  const wantsEnglish = typeof packet.description_en === "string" &&
    packet.description_en.trim() !== "";
  const system = [
    assets.system.trim(),
    "",
    "## Reference notes",
    "",
    "These are approved notes in the voice this house expects. Match their stance and density, not their facts.",
    "",
    "```json",
    JSON.stringify(assets.examples, null, 2),
    "```",
  ].join("\n");
  const user = [
    "Write the curator's note for the exhibition in the packet below.",
    "",
    "Rules for this packet:",
    "- Every statement must come from the packet. Cite the packet keys you used in each insight's `grounding`.",
    "- Korean (`ko`) is required, written as Korean prose, not as a translation.",
    wantsEnglish
      ? "- English (`en`) is required because the packet has `description_en`; write it independently, not as a translation of the Korean."
      : '- The packet has no `description_en`, so set `"en": null`.',
    "- Do not state years, prices, counts or names that are not in the packet.",
    "",
    "## Packet",
    "",
    "```json",
    JSON.stringify(packet, null, 2),
    "```",
  ].join("\n");
  return { system, user };
}

function textsOf(language: NoteLanguage): string[] {
  return [
    language.hook,
    ...language.insights.map((insight) => insight.text),
    language.tip,
  ];
}

function validateLanguage(
  language: NoteLanguage,
  sourceFields: ReadonlySet<string>,
  packetText: string,
  expectHangul: boolean,
): Validation {
  if (language.insights.length < 2 || language.insights.length > 4) {
    return { ok: false, code: "insight_count" };
  }
  for (const insight of language.insights) {
    if (
      insight.grounding.length === 0 ||
      insight.grounding.some((key) => !sourceFields.has(key))
    ) {
      return { ok: false, code: "grounding_unknown_key" };
    }
  }
  if (
    language.hook.length > HOOK_MAX || language.tip.length > TIP_MAX ||
    language.insights.some((insight) =>
      insight.text.length > INSIGHT_MAX || insight.text.trim() === ""
    )
  ) {
    return { ok: false, code: "length_exceeded" };
  }
  for (const text of textsOf(language)) {
    if (text.trim() === "") return { ok: false, code: "length_exceeded" };
    if (HANGUL.test(text) !== expectHangul) {
      return { ok: false, code: "language_mismatch" };
    }
  }
  for (const text of textsOf(language)) {
    for (const number of text.match(NUMBER) ?? []) {
      if (!packetText.includes(number)) {
        return { ok: false, code: "number_not_in_sources" };
      }
    }
  }
  return { ok: true };
}

/**
 * The deterministic grounding guard (R3): grounding keys must be packet keys, numbers and years must
 * appear in the packet, Korean must be Korean and English must be English, and sizes must fit.
 */
export function validateDraft(draft: NoteDraft, packet: Packet): Validation {
  const sourceFields = new Set(sourceFieldsOf(packet));
  const packetText = JSON.stringify(packet);
  const korean = validateLanguage(draft.ko, sourceFields, packetText, true);
  if (!korean.ok) return korean;
  const hasEnglishSource = typeof packet.description_en === "string" &&
    packet.description_en.trim() !== "";
  if (draft.en === null) return { ok: true };
  if (!hasEnglishSource) return { ok: false, code: "language_mismatch" };
  return validateLanguage(draft.en, sourceFields, packetText, false);
}

export interface StorePayloadInput {
  exhibitionId: string;
  packetHash: string;
  sourceFields: string[];
  sourceVersionId: string | null;
  draft: NoteDraft | null;
  rejectionCode?: RejectionCode;
  model: string;
  requestKind: "scheduled" | "single" | "regenerate";
  requestedBy: string | null;
  generatedAt: string;
}

export interface StorePayload {
  exhibition_id: string;
  status: "draft" | "unusable";
  rejection_code: RejectionCode | null;
  hook_ko: string;
  hook_en: string;
  insights_ko: { text: string; grounding: string[] }[];
  insights_en: { text: string; grounding: string[] }[];
  tip_ko: string;
  tip_en: string;
  producer: "claude";
  model: string;
  prompt_version: string;
  source_fields: string[];
  source_hash: string;
  source_version_id: string | null;
  request_kind: "scheduled" | "single" | "regenerate";
  requested_by: string | null;
  generated_at: string;
}

/** The row store_curator_note_draft receives; an unusable draft keeps its provenance and empty text. */
export function toStorePayload(input: StorePayloadInput): StorePayload {
  const draft = input.draft;
  const english = draft?.en ?? null;
  return {
    exhibition_id: input.exhibitionId,
    status: draft ? "draft" : "unusable",
    rejection_code: draft ? null : (input.rejectionCode ?? "parse_failed"),
    hook_ko: draft?.ko.hook ?? "",
    hook_en: english?.hook ?? "",
    insights_ko: draft?.ko.insights ?? [],
    insights_en: english?.insights ?? [],
    tip_ko: draft?.ko.tip ?? "",
    tip_en: english?.tip ?? "",
    producer: "claude",
    model: input.model,
    prompt_version: PROMPT_VERSION,
    source_fields: input.sourceFields,
    source_hash: input.packetHash,
    source_version_id: input.sourceVersionId,
    request_kind: input.requestKind,
    requested_by: input.requestedBy,
    generated_at: input.generatedAt,
  };
}
