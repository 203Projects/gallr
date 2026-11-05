import { MAX_GROUNDING_LENGTH } from "../domain";
import { ART_TERM_WORDS, type ArtTermWords } from "./artTermWords";

/** A vocabulary term the description implies, with the description's own sentence that implies it. */
export interface SuggestedArtTerm {
  termId: string;
  /** Verbatim from the description, at most [MAX_GROUNDING_LENGTH] characters. */
  sentence: string;
}

export interface ArtTermSuggestionText {
  descriptionKo: string;
  descriptionEn: string;
}

/**
 * Spec 101 FR-019a: the terms an exhibition's description implies, in vocabulary order, each with the first sentence
 * that implies it, for a person to accept or decline. The rule is the app's own detection (Korean words as
 * substrings, English words as whole words regardless of case), read from the description only, so every
 * suggestion comes with a sentence. Nothing here decides a term: Admin only pre-fills what staff confirm.
 */
export function suggestArtTerms(text: ArtTermSuggestionText): SuggestedArtTerm[] {
  const koreanSentences = sentencesOf(text.descriptionKo);
  const englishSentences = sentencesOf(text.descriptionEn);
  const suggestions: SuggestedArtTerm[] = [];
  for (const words of ART_TERM_WORDS) {
    const sentence = koreanSentenceFor(words, koreanSentences) ?? englishSentenceFor(words, englishSentences);
    if (sentence) suggestions.push({ termId: words.id, sentence });
  }
  return suggestions;
}

function koreanSentenceFor(words: ArtTermWords, sentences: string[]): string | null {
  for (const sentence of sentences) {
    const index = firstIndex(words.korean.map((word) => sentence.indexOf(word)));
    if (index >= 0) return clip(sentence, index);
  }
  return null;
}

function englishSentenceFor(words: ArtTermWords, sentences: string[]): string | null {
  if (words.english.length === 0) return null;
  const pattern = new RegExp(`(^|[^a-z])(${words.english.map(escapeRegExp).join("|")})([^a-z]|$)`);
  for (const sentence of sentences) {
    const match = pattern.exec(sentence.toLowerCase());
    if (match) return clip(sentence, match.index + match[1].length);
  }
  return null;
}

/**
 * Sentences as written: split after sentence-ending punctuation followed by a space, and at line breaks. A split
 * never changes the characters inside a sentence, so the result is a substring of the description.
 */
function sentencesOf(description: string): string[] {
  return description
    .split(/(?<=[.!?。？！])\s+|\n+/u)
    .map((sentence) => sentence.trim())
    .filter((sentence) => sentence.length > 0);
}

/** A sentence longer than the grounding limit keeps the stretch around the matched word, still verbatim. */
function clip(sentence: string, matchIndex: number): string {
  const characters = Array.from(sentence);
  if (characters.length <= MAX_GROUNDING_LENGTH) return sentence;
  const matchCharacter = Array.from(sentence.slice(0, matchIndex)).length;
  const start = Math.max(0, Math.min(matchCharacter - MAX_GROUNDING_LENGTH / 4, characters.length - MAX_GROUNDING_LENGTH));
  return characters.slice(start, start + MAX_GROUNDING_LENGTH).join("").trim();
}

function firstIndex(indexes: number[]): number {
  const found = indexes.filter((index) => index >= 0);
  return found.length === 0 ? -1 : Math.min(...found);
}

function escapeRegExp(word: string): string {
  return word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
