import type {
  AdminEditorRepository,
  AdminEditorRequest,
  AdminEditorRequestStatus,
  EditorOnboardingInput,
  EditorOnboardingResult,
} from "./AdminEditorRepository";

export class InMemoryAdminEditorRepository implements AdminEditorRepository {
  private readonly requests: AdminEditorRequest[] = [];
  async invite(input: EditorOnboardingInput): Promise<EditorOnboardingResult> {
    await Promise.resolve();
    return {
      editorId: input.editorId,
      email: input.email,
      nameKo: input.nameKo,
      nameEn: input.nameEn,
      active: input.isActive,
    };
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
