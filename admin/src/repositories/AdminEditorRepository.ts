export interface EditorOnboardingInput {
  email: string;
  editorId: string;
  nameKo: string;
  nameEn: string;
  titleKo: string;
  titleEn: string;
  bioKo: string;
  bioEn: string;
  curationDescriptionKo: string;
  curationDescriptionEn: string;
  isActive: boolean;
  activeFrom: string;
  activeTo: string | null;
}

export interface EditorOnboardingResult {
  editorId: string;
  email: string;
  nameKo: string;
  nameEn: string;
  active: boolean;
}

export type AdminEditorRequestKind = "profile" | "curation";
export type AdminEditorRequestStatus = "submitted" | "accepted" | "rejected";

export interface AdminEditorRequest {
  id: string;
  editorId: string;
  editorName: string;
  kind: AdminEditorRequestKind;
  status: AdminEditorRequestStatus;
  payload: Record<string, unknown>;
  reviewNotes: string;
  createdAt: string;
}

/** Admin-only capability; authorization is enforced again by the server. */
export interface AdminEditorRepository {
  invite(input: EditorOnboardingInput): Promise<EditorOnboardingResult>;
  listRequests(status?: AdminEditorRequestStatus): Promise<AdminEditorRequest[]>;
  reviewRequest(
    requestId: string,
    approve: boolean,
    reviewNotes: string,
  ): Promise<AdminEditorRequest>;
}
