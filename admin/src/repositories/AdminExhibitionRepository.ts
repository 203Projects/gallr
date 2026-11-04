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
  ArtistMergeResult,
  DuplicateArtistGroup,
  ExhibitionFilters,
  ExhibitionPatch,
  GalleryClaimFilters,
  HoursWeekly,
  HoursWeeklyDay,
  LocalPromotionFilters,
  StaleExhibitionSummary,
  SubmissionFilters,
} from "../domain";

export class RevisionConflictError extends Error {
  constructor(readonly serverRevision: number) {
    super(`The server is at revision ${serverRevision}.`);
    this.name = "RevisionConflictError";
  }
}

/**
 * A confirm command (`exhibition_metadata_stale_revision`) found the exhibition at another version or revision.
 * Staff reload before confirming again, as with a save conflict; [serverRevision] is null when the server did
 * not report one.
 */
export class ExhibitionChangedError extends Error {
  constructor(readonly serverRevision: number | null) {
    super("The exhibition changed on the server before it could be confirmed.");
    this.name = "ExhibitionChangedError";
  }
}

/** `artist_duplicate`: an active artist already has this normalised Korean or English name. */
export class ArtistDuplicateError extends Error {
  constructor(readonly existingArtistId: string) {
    super("An artist with this name already exists.");
    this.name = "ArtistDuplicateError";
  }
}

/** `artist_merge_invalid`: the same artist twice, an archived artist, or one that does not exist. */
export class ArtistMergeInvalidError extends Error {
  constructor(readonly reason: string | null) {
    super(`The artists cannot be merged${reason ? `: ${reason}` : "."}`);
    this.name = "ArtistMergeInvalidError";
  }
}

/** `hours_weekly_invalid`; [day] names the refused weekday when the server reports one. */
export class HoursWeeklyInvalidError extends Error {
  constructor(readonly day: HoursWeeklyDay | null) {
    super("The weekly hours were refused.");
    this.name = "HoursWeeklyInvalidError";
  }
}

/** `art_metadata_term_grounding_invalid`; [termId] names the refused term when the server reports one. */
/** The server refused an artist credit's grounding (wrong type or longer than 400 characters). */
export class ArtistGroundingInvalidError extends Error {
  constructor() {
    super("An artist grounding was refused.");
    this.name = "ArtistGroundingInvalidError";
  }
}

export class ArtTermGroundingInvalidError extends Error {
  constructor(readonly termId: string | null) {
    super("A term grounding was refused.");
    this.name = "ArtTermGroundingInvalidError";
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
  /** Throws [ArtistDuplicateError] when an active artist already has the name. */
  createArtist(
    nameKo: string,
    nameEn: string,
    requestId: string,
  ): Promise<ArtistLookup>;
  /**
   * Marks the terms and credits as checked against the current description and dates, on the published version
   * and the open draft. Returns the working version.
   */
  confirmExhibitionMetadata(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
  ): Promise<AdminExhibition>;
  /** Stores staff-confirmed weekly hours on the open draft, else the published version. Returns the working version. */
  confirmExhibitionHours(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    hoursWeekly: HoursWeekly,
  ): Promise<AdminExhibition>;
  /** Exhibitions whose published version or open draft has stale terms, credits or weekly hours. */
  listStaleExhibitions(): Promise<StaleExhibitionSummary[]>;
  /** Admin role only. */
  findDuplicateArtists(): Promise<DuplicateArtistGroup[]>;
  /** Admin role only; moves the merged artist's credits to the survivor and archives it. */
  mergeArtists(
    survivorId: string,
    mergedId: string,
    reason: string,
  ): Promise<ArtistMergeResult>;
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
