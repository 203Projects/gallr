import { z } from "zod";

/** One insight paragraph and the packet keys it draws on (spec 090 FR-003). */
const insightSchema = z.object({
  text: z.string().max(420),
  grounding: z.array(z.string()).min(1),
}).strict();

/** One language of a note: a hook sentence, two to four insights and one viewing tip. */
const languageSchema = z.object({
  hook: z.string().max(120),
  insights: z.array(insightSchema).min(2).max(4),
  tip: z.string().max(240),
}).strict();

/**
 * The structured output the model must produce. Korean is always present; English is null when the
 * exhibition has neither an English description nor an English name (see `englishSourceOf`). The Claude request uses this as its output format, so a
 * response that does not fit is a parse failure rather than a malformed draft.
 */
export const NoteDraftSchema = z.object({
  ko: languageSchema,
  en: languageSchema.nullable(),
}).strict();

export type NoteDraft = z.infer<typeof NoteDraftSchema>;
export type NoteLanguage = z.infer<typeof languageSchema>;
