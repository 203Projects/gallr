import type {
  EditorCurationChange,
  EditorCurationHistoryItem,
  EditorCurationSubmission,
  EditorExhibitionSuggestion,
  EditorPickCandidate,
  EditorProfile,
} from "../domain";

/** Least-privilege persistence contract for an editor's own collection. */
export interface EditorPickRepository {
  list(search: string): Promise<EditorPickCandidate[]>;
  listCurationHistory(): Promise<EditorCurationHistoryItem[]>;
  getProfile(): Promise<EditorProfile>;
  submitCuration(
    changes: EditorCurationChange[],
    curationDescriptionKo: string,
    curationDescriptionEn: string,
  ): Promise<EditorCurationSubmission>;
  submitProfile(
    bioKo: string,
    bioEn: string,
  ): Promise<{ requestId: string; status: "submitted" }>;
  submitExhibition(
    suggestion: EditorExhibitionSuggestion,
  ): Promise<{ submissionId: string; status: "submitted" }>;
  setSelected(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    selected: boolean,
  ): Promise<EditorPickCandidate>;
}
