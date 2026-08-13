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

export interface AdminManagedEditor {
  editorId: string;
  email: string | null;
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
  revision: number;
  hasAccess: boolean;
  accessActive: boolean;
}

export type AdminEditorUpdateInput = Pick<
  AdminManagedEditor,
  | "nameKo"
  | "nameEn"
  | "titleKo"
  | "titleEn"
  | "bioKo"
  | "bioEn"
  | "curationDescriptionKo"
  | "curationDescriptionEn"
  | "isActive"
  | "activeFrom"
  | "activeTo"
>;

export class EditorRevisionConflictError extends Error {
  constructor(readonly serverRevision: number) {
    super("A newer editor revision exists.");
    this.name = "EditorRevisionConflictError";
  }
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
  listEditors(): Promise<AdminManagedEditor[]>;
  updateEditor(
    editorId: string,
    expectedRevision: number,
    input: AdminEditorUpdateInput,
  ): Promise<AdminManagedEditor>;
  setAccess(
    editorId: string,
    expectedRevision: number,
    active: boolean,
  ): Promise<AdminManagedEditor>;
  listRequests(status?: AdminEditorRequestStatus): Promise<AdminEditorRequest[]>;
  reviewRequest(
    requestId: string,
    approve: boolean,
    reviewNotes: string,
  ): Promise<AdminEditorRequest>;
}
