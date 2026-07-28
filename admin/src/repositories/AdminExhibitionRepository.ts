import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaMutationResult,
  AdminMediaRole,
  ExhibitionFilters,
  ExhibitionPatch,
} from "../domain";

export class RevisionConflictError extends Error {
  constructor(readonly serverRevision: number) {
    super(`The server is at revision ${serverRevision}.`);
    this.name = "RevisionConflictError";
  }
}

export interface AdminExhibitionRepository {
  list(filters: ExhibitionFilters): Promise<AdminExhibition[]>;
  getExhibitionLookups(): Promise<AdminExhibitionLookups>;
  createDraft(): Promise<AdminExhibition>;
  saveDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    patch: Partial<ExhibitionPatch>,
  ): Promise<AdminExhibition>;
  publish(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition>;
  archive(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition>;
  restore(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition>;
  deleteDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<void>;
  listMedia(exhibitionId: string, versionId: string): Promise<AdminMediaAsset[]>;
  uploadAndAttachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    file: File,
    role: AdminMediaRole,
  ): Promise<AdminMediaMutationResult>;
  updateMediaMetadata(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ): Promise<AdminMediaMutationResult>;
  reorderMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    orderedAssetIds: string[],
  ): Promise<AdminMediaMutationResult>;
  detachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
  ): Promise<AdminMediaMutationResult>;
}
