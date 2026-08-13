import type {
  AdminEditorRepository,
  AdminEditorRequest,
  AdminEditorRequestStatus,
  AdminEditorUpdateInput,
  AdminManagedEditor,
  EditorOnboardingInput,
  EditorOnboardingResult,
} from "./AdminEditorRepository";
import { EditorRevisionConflictError as RevisionConflict } from "./AdminEditorRepository";

export class InMemoryAdminEditorRepository implements AdminEditorRepository {
  private readonly requests: AdminEditorRequest[] = [];
  private editors: AdminManagedEditor[] = [
    {
      editorId: "gallr-editors",
      email: null,
      nameKo: "gallr 에디터즈",
      nameEn: "gallr Editors",
      titleKo: "하우스 에디터",
      titleEn: "House Editor",
      bioKo: "gallr 팀이 선정한 상시 큐레이션.",
      bioEn: "Always-on selection by the gallr team.",
      curationDescriptionKo: "gallr 팀이 선정한 상시 큐레이션.",
      curationDescriptionEn: "Always-on selection by the gallr team.",
      isActive: true,
      activeFrom: "2026-01-01",
      activeTo: null,
      revision: 1,
      hasAccess: false,
      accessActive: false,
    },
  ];
  async invite(input: EditorOnboardingInput): Promise<EditorOnboardingResult> {
    await Promise.resolve();
    this.editors = [...this.editors, {
      editorId: input.editorId,
      email: input.email,
      nameKo: input.nameKo,
      nameEn: input.nameEn,
      titleKo: input.titleKo,
      titleEn: input.titleEn,
      bioKo: input.bioKo,
      bioEn: input.bioEn,
      curationDescriptionKo: input.curationDescriptionKo,
      curationDescriptionEn: input.curationDescriptionEn,
      isActive: input.isActive,
      activeFrom: input.activeFrom,
      activeTo: input.activeTo,
      revision: 1,
      hasAccess: true,
      accessActive: true,
    }];
    return {
      editorId: input.editorId,
      email: input.email,
      nameKo: input.nameKo,
      nameEn: input.nameEn,
      active: input.isActive,
    };
  }

  async listEditors(): Promise<AdminManagedEditor[]> {
    await Promise.resolve();
    return this.editors.map((editor) => ({ ...editor }));
  }

  async updateEditor(
    editorId: string,
    expectedRevision: number,
    input: AdminEditorUpdateInput,
  ): Promise<AdminManagedEditor> {
    await Promise.resolve();
    const editor = this.findEditor(editorId, expectedRevision);
    const updated = { ...editor, ...input, revision: editor.revision + 1 };
    this.replaceEditor(updated);
    return { ...updated };
  }

  async setAccess(
    editorId: string,
    expectedRevision: number,
    active: boolean,
  ): Promise<AdminManagedEditor> {
    await Promise.resolve();
    const editor = this.findEditor(editorId, expectedRevision);
    if (!editor.hasAccess) throw new Error("Editor workspace account not found.");
    const updated = {
      ...editor,
      isActive: active ? editor.isActive : false,
      accessActive: active,
      revision: editor.revision + 1,
    };
    this.replaceEditor(updated);
    return { ...updated };
  }

  private findEditor(
    editorId: string,
    expectedRevision: number,
  ): AdminManagedEditor {
    const editor = this.editors.find((item) => item.editorId === editorId);
    if (!editor) throw new Error("Editor not found.");
    if (editor.revision !== expectedRevision) {
      throw new RevisionConflict(editor.revision);
    }
    return editor;
  }

  private replaceEditor(editor: AdminManagedEditor) {
    this.editors = this.editors.map((item) =>
      item.editorId === editor.editorId ? editor : item,
    );
  }

  async listRequests(
    status: AdminEditorRequestStatus = "submitted",
  ): Promise<AdminEditorRequest[]> {
    await Promise.resolve();
    return this.requests.filter((request) => request.status === status);
  }

  async reviewRequest(
    requestId: string,
    approve: boolean,
    reviewNotes: string,
  ): Promise<AdminEditorRequest> {
    await Promise.resolve();
    const request = this.requests.find((item) => item.id === requestId);
    if (!request) throw new Error("Editor request not found.");
    request.status = approve ? "accepted" : "rejected";
    request.reviewNotes = reviewNotes;
    return { ...request };
  }
}
