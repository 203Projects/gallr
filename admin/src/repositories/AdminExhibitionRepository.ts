import type {
  AdminExhibition,
  ArtistLookup,
  AdminGalleryClaim,
  AdminExhibitionLookups,
  AdminExhibitionSubmission,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaMutationResult,
  AdminMediaRole,
  AdminLocalPromotion,
  AdminSubmissionAcceptance,
  ExhibitionFilters,
  ExhibitionPatch,
  GalleryClaimFilters,
  LocalPromotionFilters,
  SubmissionFilters,
} from "../domain";

export class RevisionConflictError extends Error {
  constructor(readonly serverRevision: number) {
    super(`The server is at revision ${serverRevision}.`);
    this.name = "RevisionConflictError";
  }
}

/**
 * Reasons `admin_delete_exhibition_draft` refuses a permanent deletion. Each
 * value is the server-side exception message; the staff notice is chosen from
 * it so an operator learns which retained relationship blocked the command
 * instead of a single unactionable failure string.
 */
export const DRAFT_DELETE_BLOCKED_REASONS = [
  "only_never_published_drafts_can_be_deleted",
  "draft_delete_requires_media_detach",
  "imported_exhibitions_cannot_be_deleted",
  "draft_delete_has_submission_reference",
  "draft_delete_has_curation_reference",
  "draft_delete_has_launch_kit_reference",
  "draft_delete_has_promotion_reference",
  "draft_delete_has_pending_outbox_event",
] as const;

export type DraftDeleteBlockedReason =
  (typeof DRAFT_DELETE_BLOCKED_REASONS)[number];

export function isDraftDeleteBlockedReason(
  value: string,
): value is DraftDeleteBlockedReason {
  return (DRAFT_DELETE_BLOCKED_REASONS as readonly string[]).includes(value);
}

export class DraftDeleteBlockedError extends Error {
  constructor(readonly reason: DraftDeleteBlockedReason) {
    super(`Permanent deletion was refused: ${reason}.`);
    this.name = "DraftDeleteBlockedError";
  }
}

export interface AdminExhibitionRepository {
  list(filters: ExhibitionFilters): Promise<AdminExhibition[]>;
  getExhibitionLookups(): Promise<AdminExhibitionLookups>;
  searchArtists(query: string): Promise<ArtistLookup[]>;
  createArtist(
    nameKo: string,
    nameEn: string,
    requestId: string,
  ): Promise<ArtistLookup>;
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
  discardDraft(
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
  listSubmissions(
    filters: SubmissionFilters,
  ): Promise<AdminExhibitionSubmission[]>;
  startSubmissionReview(id: string): Promise<AdminExhibitionSubmission>;
  acceptSubmission(
    id: string,
    requestId: string,
  ): Promise<AdminSubmissionAcceptance>;
  rejectSubmission(
    id: string,
    reviewNotes: string,
    requestId: string,
  ): Promise<AdminExhibitionSubmission>;
  listGalleryClaims(filters: GalleryClaimFilters): Promise<AdminGalleryClaim[]>;
  approveGalleryClaim(
    galleryId: string,
    userId: string,
    requestId: string,
  ): Promise<AdminGalleryClaim>;
  rejectGalleryClaim(
    galleryId: string,
    userId: string,
    reviewNotes: string,
    requestId: string,
  ): Promise<AdminGalleryClaim>;
  listLocalPromotions(filters: LocalPromotionFilters): Promise<AdminLocalPromotion[]>;
  approveLocalPromotion(
    id: string,
    startsAt: string,
    endsAt: string,
    requestId: string,
  ): Promise<AdminLocalPromotion>;
  rejectLocalPromotion(
    id: string,
    reviewNotes: string,
    requestId: string,
  ): Promise<AdminLocalPromotion>;
}
