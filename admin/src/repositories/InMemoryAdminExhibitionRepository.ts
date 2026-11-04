import type {
  AdminExhibition,
  AdminExhibitionLookups,
  AdminExhibitionSubmission,
  AdminGalleryClaim,
  AdminMediaAsset,
  AdminMediaMetadataPatch,
  AdminMediaMutationResult,
  AdminMediaRole,
  AdminLocalPromotion,
  AdminSubmissionAcceptance,
  ArtistLookup,
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
import {
  exhibitionFixtures,
  exhibitionLookupFixtures,
  galleryClaimFixtures,
  submissionFixtures,
} from "../data/fixtures";
import {
  HOURS_WEEKLY_DAYS,
  isExhibitionStale,
  isPublishReady,
  matchesExhibitionFilters,
  seoulCalendarDate,
  sortAdminExhibitions,
} from "../domain";
import {
  type AdminExhibitionRepository,
  ArtistDuplicateError,
  ArtistMergeInvalidError,
  HoursWeeklyInvalidError,
  RevisionConflictError,
} from "./AdminExhibitionRepository";
import {
  assertValidAdminMediaFile,
  readFileDataUrl,
  readImageDimensions,
  sha256File,
} from "./MediaFile";

type LifecycleAction = "publish" | "archive" | "restore" | "discard";

interface LifecycleResult {
  action: LifecycleAction;
  exhibitionId: string;
  versionId: string;
  revision: number;
  result: AdminExhibition;
}

function copy<T>(value: T): T {
  return structuredClone(value);
}

interface FixtureArtist extends ArtistLookup {
  archived: boolean;
}

const MAX_MERGE_REASON_LENGTH = 400;
const HHMM = /^(?:[01]\d|2[0-3]):[0-5]\d$/u;

/** Folds case, width, spaces and punctuation, like the database's `normalize_artist_name`. */
function normalizeArtistName(name: string): string {
  return name.normalize("NFKC").toLocaleLowerCase().replace(/[\s\p{P}\p{S}]/gu, "");
}

/** The first weekday that breaks the `hours_weekly` contract, or null when every day is valid. */
function invalidHoursWeeklyDay(hoursWeekly: HoursWeekly): HoursWeeklyDay | "keys" | null {
  const keys = Object.keys(hoursWeekly);
  if (keys.length !== HOURS_WEEKLY_DAYS.length || !HOURS_WEEKLY_DAYS.every((day) => keys.includes(day))) {
    return "keys";
  }
  for (const day of HOURS_WEEKLY_DAYS) {
    const opening = hoursWeekly[day];
    if (opening === null) continue;
    if (!HHMM.test(opening.opens) || !HHMM.test(opening.closes) || opening.opens >= opening.closes) return day;
  }
  return null;
}

function publishedFixtureSnapshot(
  exhibition: AdminExhibition,
): AdminExhibition | null {
  if (exhibition.publishedVersionId === null) return null;
  if (
    exhibition.workingVersionId === exhibition.publishedVersionId &&
    !exhibition.hasUnpublishedChanges
  ) {
    return copy(exhibition);
  }
  return {
    ...copy(exhibition),
    workingVersionId: exhibition.publishedVersionId,
    versionNumber: Math.max(1, exhibition.versionNumber - 1),
    hasUnpublishedChanges: false,
    status: "Published",
    revision: Math.max(1, exhibition.revision - 1),
  };
}

export class InMemoryAdminExhibitionRepository
  implements AdminExhibitionRepository
{
  private records = copy(exhibitionFixtures);
  private submissions = copy(submissionFixtures);
  private galleryClaims = copy(galleryClaimFixtures);
  private localPromotions: AdminLocalPromotion[] = [];
  private mediaByVersion = new Map<string, AdminMediaAsset[]>();
  private publishedSnapshots = new Map<string, AdminExhibition>(
    exhibitionFixtures.flatMap((exhibition) => {
      const snapshot = publishedFixtureSnapshot(exhibition);
      return snapshot === null ? [] : [[exhibition.id, snapshot]];
    }),
  );
  private lifecycleResults = new Map<string, LifecycleResult>();
  private deletedDraftRequests = new Map<
    string,
    {
      exhibitionId: string;
      versionId: string;
      revision: number;
    }
  >();
  private submissionResults = new Map<
    string,
    AdminSubmissionAcceptance | AdminExhibitionSubmission
  >();
  // The third artist duplicates the first by normalised Korean name, so fixture mode shows a merge group.
  private artists: FixtureArtist[] = [
    { id: "71000000-0000-4000-8000-000000000001", nameKo: "김민정", nameEn: "Minjung Kim", archived: false },
    { id: "71000000-0000-4000-8000-000000000002", nameKo: "이수진", nameEn: "Sujin Lee", archived: false },
    { id: "71000000-0000-4000-8000-000000000003", nameKo: "김 민정", nameEn: "Kim Min-jung", archived: false },
  ];

  async list(filters: ExhibitionFilters): Promise<AdminExhibition[]> {
    const today = seoulCalendarDate();
    const records = this.records.filter((record) =>
      matchesExhibitionFilters(record, filters, today),
    );
    return copy(sortAdminExhibitions(records, filters.sort));
  }

  async getExhibitionLookups(): Promise<AdminExhibitionLookups> {
    return copy(exhibitionLookupFixtures);
  }

  async searchArtists(query: string): Promise<ArtistLookup[]> {
    const normalized = query.trim().toLocaleLowerCase();
    if (normalized.length < 2) return [];
    return this.artists
      .filter((artist) =>
        !artist.archived &&
        `${artist.nameKo} ${artist.nameEn}`.toLocaleLowerCase().includes(normalized),
      )
      .map(({ id, nameKo, nameEn }) => ({ id, nameKo, nameEn }));
  }

  async createArtist(nameKo: string, nameEn: string, _requestId: string): Promise<ArtistLookup> {
    const keys = [nameKo, nameEn].map(normalizeArtistName).filter((key) => key.length > 0);
    const existing = this.artists.find((artist) =>
      !artist.archived &&
      [artist.nameKo, artist.nameEn].some((name) => keys.includes(normalizeArtistName(name))),
    );
    if (existing) throw new ArtistDuplicateError(existing.id);
    const artist = { id: crypto.randomUUID(), nameKo: nameKo.trim(), nameEn: nameEn.trim() };
    this.artists.push({ ...artist, archived: false });
    return { ...artist };
  }

  async confirmExhibitionMetadata(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
  ): Promise<AdminExhibition> {
    const current = this.requireCurrent(id, expectedVersionId, expectedRevision);
    const now = new Date().toISOString();
    const confirmed: AdminExhibition = {
      ...current.record,
      metadataReview: { stale: false, reviewedAt: now },
      revision: current.record.revision + 1,
      updatedAt: now,
      updatedBy: "Current editor",
    };
    this.records[current.index] = confirmed;
    return copy(confirmed);
  }

  async confirmExhibitionHours(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    hoursWeekly: HoursWeekly,
  ): Promise<AdminExhibition> {
    const current = this.requireCurrent(id, expectedVersionId, expectedRevision);
    const invalid = invalidHoursWeeklyDay(hoursWeekly);
    if (invalid !== null) throw new HoursWeeklyInvalidError(invalid === "keys" ? null : invalid);
    const now = new Date().toISOString();
    const confirmed: AdminExhibition = {
      ...current.record,
      confirmedHours: { weekly: copy(hoursWeekly), confirmedAt: now, stale: false },
      revision: current.record.revision + 1,
      updatedAt: now,
      updatedBy: "Current editor",
    };
    this.records[current.index] = confirmed;
    return copy(confirmed);
  }

  async listStaleExhibitions(): Promise<StaleExhibitionSummary[]> {
    return sortAdminExhibitions(this.records.filter(isExhibitionStale), "updated_desc").map((record) => ({
      exhibitionId: record.id,
      nameKo: record.nameKo,
      nameEn: record.nameEn,
      metadataStale: record.metadataReview?.stale ?? false,
      hoursStale: record.confirmedHours?.stale ?? false,
    }));
  }

  async findDuplicateArtists(): Promise<DuplicateArtistGroup[]> {
    const active = this.artists.filter(({ archived }) => !archived);
    const groups = new Map<string, FixtureArtist[]>();
    for (const artist of active) {
      for (const name of [artist.nameKo, artist.nameEn]) {
        const key = normalizeArtistName(name);
        if (!key) continue;
        const members = groups.get(key) ?? [];
        if (!members.includes(artist)) members.push(artist);
        groups.set(key, members);
      }
    }
    return [...groups.entries()]
      .filter(([, members]) => members.length > 1)
      .map(([key, members]) => ({
        key,
        artists: members.map(({ id, nameKo, nameEn }) => ({
          id,
          nameKo,
          nameEn,
          creditCount: this.creditCount(id),
        })),
      }));
  }

  async mergeArtists(
    survivorId: string,
    mergedId: string,
    reason: string,
  ): Promise<ArtistMergeResult> {
    const reasonLength = Array.from(reason.trim()).length;
    if (reasonLength < 1 || reasonLength > MAX_MERGE_REASON_LENGTH) {
      throw new ArtistMergeInvalidError("reason_invalid");
    }
    if (survivorId === mergedId) throw new ArtistMergeInvalidError("same_artist");
    const survivor = this.artists.find(({ id }) => id === survivorId);
    const merged = this.artists.find(({ id }) => id === mergedId);
    if (!survivor) throw new ArtistMergeInvalidError("survivor_not_found");
    if (!merged) throw new ArtistMergeInvalidError("merged_not_found");
    if (survivor.archived) throw new ArtistMergeInvalidError("survivor_archived");
    if (merged.archived) throw new ArtistMergeInvalidError("merged_archived");

    let movedCredits = 0;
    let removedDuplicateCredits = 0;
    this.records = this.records.map((record) => {
      const metadata = record.artMetadata;
      if (!metadata?.artists.some(({ id }) => id === mergedId)) return record;
      const hasSurvivor = metadata.artists.some(({ id }) => id === survivorId);
      const artists = metadata.artists.flatMap((artist) => {
        if (artist.id !== mergedId) return [artist];
        if (hasSurvivor) {
          removedDuplicateCredits += 1;
          return [];
        }
        movedCredits += 1;
        return [{ ...artist, id: survivorId }];
      });
      return { ...record, artMetadata: { ...metadata, artists } };
    });
    merged.archived = true;
    return { survivorId, mergedId, movedCredits, removedDuplicateCredits };
  }

  /** A description or date edit makes reviewed terms stale; an hours edit makes confirmed hours stale. */
  private stalenessAfter(
    current: AdminExhibition,
    patch: Partial<ExhibitionPatch>,
  ): Pick<AdminExhibition, "metadataReview" | "confirmedHours"> {
    const changed = (field: keyof ExhibitionPatch) =>
      Object.prototype.hasOwnProperty.call(patch, field) && patch[field] !== current[field];
    const staleness: Pick<AdminExhibition, "metadataReview" | "confirmedHours"> = {};
    if (
      current.metadataReview?.reviewedAt &&
      (["descriptionKo", "descriptionEn", "openingDate", "closingDate"] as const).some(changed)
    ) {
      staleness.metadataReview = { ...current.metadataReview, stale: true };
    }
    if (current.confirmedHours?.weekly && changed("hours")) {
      staleness.confirmedHours = { ...current.confirmedHours, stale: true };
    }
    return staleness;
  }

  private creditCount(artistId: string): number {
    return this.records.filter((record) =>
      record.artMetadata?.artists.some(({ id }) => id === artistId),
    ).length;
  }

  async createDraft(): Promise<AdminExhibition> {
    const now = new Date().toISOString();
    const record: AdminExhibition = {
      id: crypto.randomUUID(),
      workingVersionId: crypto.randomUUID(),
      versionNumber: 1,
      publishedVersionId: null,
      hasUnpublishedChanges: true,
      nameKo: "",
      nameEn: "",
      venueNameKo: "",
      venueNameEn: "",
      cityKo: "서울",
      cityEn: "Seoul",
      regionKo: "",
      regionEn: "",
      addressKo: "",
      addressEn: "",
      latitude: "",
      longitude: "",
      openingDate: "",
      closingDate: "",
      descriptionKo: "",
      descriptionEn: "",
      creditsKo: "",
      creditsEn: "",
      artMetadata: { artists: [], terms: [] },
      hours: "",
      contact: "",
      receptionDate: "",
      receptionStartTime: "",
      receptionEndTime: "",
      eventId: "",
      editorId: "",
      ticketUrl: "",
      coverImageUrl: null,
      coverAltKo: "",
      coverAltEn: "",
      imageCredit: "",
      isFeatured: false,
      isHomepageFeatured: true,
      hasOpenOwnerSubmission: false,
      status: "Draft",
      revision: 1,
      createdAt: now,
      publishedAt: null,
      updatedAt: now,
      updatedBy: "Current editor",
    };
    this.records.unshift(record);
    return copy(record);
  }

  async saveDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    patch: Partial<ExhibitionPatch>,
  ): Promise<AdminExhibition> {
    const index = this.records.findIndex((record) => record.id === id);
    if (index < 0) throw new Error("Exhibition not found.");
    const current = this.records[index];
    if (
      current.workingVersionId !== expectedVersionId ||
      current.revision !== expectedRevision
    ) {
      throw new RevisionConflictError(current.revision);
    }

    const cloningPublished =
      current.status === "Published" && !current.hasUnpublishedChanges;
    if (cloningPublished) {
      this.publishedSnapshots.set(current.id, copy(current));
    }
    const workingDraft: AdminExhibition =
      cloningPublished
        ? {
            ...current,
            workingVersionId: crypto.randomUUID(),
            versionNumber: current.versionNumber + 1,
            hasUnpublishedChanges: true,
            status: "Draft",
            revision: current.revision,
          }
        : current;

    const saved: AdminExhibition = {
      ...workingDraft,
      ...copy(patch),
      ...this.stalenessAfter(workingDraft, patch),
      hasUnpublishedChanges: true,
      status: "Draft",
      revision: workingDraft.revision + 1,
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[index] = saved;
    return copy(saved);
  }

  async publish(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const repeated = this.readLifecycleResult(
      "publish",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (repeated) return repeated;
    const index = this.records.findIndex((record) => record.id === id);
    if (index < 0) throw new Error("Exhibition not found.");
    const current = this.records[index];
    if (
      current.workingVersionId !== expectedVersionId ||
      current.revision !== expectedRevision
    ) {
      throw new RevisionConflictError(current.revision);
    }
    if (!isPublishReady(current)) {
      throw new Error("Complete every required field before publishing.");
    }
    if (
      (this.mediaByVersion.get(current.workingVersionId) ?? []).some(
        (asset) => asset.status !== "published",
      )
    ) {
      throw new Error("Wait for every attached image to finish processing before publishing.");
    }

    const publishedAt = new Date().toISOString();
    const published: AdminExhibition = {
      ...current,
      publishedVersionId: current.workingVersionId,
      hasUnpublishedChanges: false,
      status: "Published",
      revision: current.revision + 1,
      publishedAt,
      updatedAt: publishedAt,
      updatedBy: "Current editor",
    };
    this.records[index] = published;
    this.publishedSnapshots.set(id, copy(published));
    return this.storeLifecycleResult(
      "publish",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
      published,
    );
  }

  async archive(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const repeated = this.readLifecycleResult(
      "archive",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (repeated) return repeated;
    const current = this.requireCurrent(
      id,
      expectedVersionId,
      expectedRevision,
    );
    const archived: AdminExhibition = {
      ...current.record,
      status: "Archived",
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[current.index] = archived;
    return this.storeLifecycleResult(
      "archive",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
      archived,
    );
  }

  async restore(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const repeated = this.readLifecycleResult(
      "restore",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (repeated) return repeated;
    const current = this.requireCurrent(
      id,
      expectedVersionId,
      expectedRevision,
    );
    const restored: AdminExhibition = {
      ...current.record,
      status:
        current.record.publishedVersionId !== null &&
        !current.record.hasUnpublishedChanges
          ? "Published"
          : "Draft",
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[current.index] = restored;
    return this.storeLifecycleResult(
      "restore",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
      restored,
    );
  }

  async discardDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<AdminExhibition> {
    const repeated = this.readLifecycleResult(
      "discard",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (repeated) return repeated;
    const current = this.requireCurrent(
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (
      current.record.status !== "Draft" ||
      current.record.publishedVersionId === null ||
      current.record.workingVersionId === current.record.publishedVersionId ||
      !current.record.hasUnpublishedChanges
    ) {
      throw new Error("Only an unpublished working draft can be discarded.");
    }
    const published = this.publishedSnapshots.get(id);
    if (!published) {
      throw new Error("The last published version could not be found.");
    }

    const restored: AdminExhibition = {
      ...copy(published),
      workingVersionId: current.record.publishedVersionId,
      publishedVersionId: current.record.publishedVersionId,
      hasUnpublishedChanges: false,
      status: "Published",
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[current.index] = restored;
    this.mediaByVersion.delete(expectedVersionId);
    return this.storeLifecycleResult(
      "discard",
      requestId,
      id,
      expectedVersionId,
      expectedRevision,
      restored,
    );
  }

  async deleteDraft(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
    requestId: string,
  ): Promise<void> {
    const repeated = this.deletedDraftRequests.get(requestId);
    if (repeated) {
      if (
        repeated.exhibitionId !== id ||
        repeated.versionId !== expectedVersionId ||
        repeated.revision !== expectedRevision
      ) {
        throw new Error("The deletion request ID was reused with different details.");
      }
      return;
    }

    const current = this.requireCurrent(
      id,
      expectedVersionId,
      expectedRevision,
    );
    if (
      current.record.status !== "Draft" ||
      current.record.publishedVersionId !== null
    ) {
      throw new Error("Only never-published drafts can be deleted permanently.");
    }
    if ((this.mediaByVersion.get(expectedVersionId) ?? []).length > 0) {
      throw new Error("Remove every attached image before deleting this draft.");
    }

    this.records.splice(current.index, 1);
    this.mediaByVersion.delete(expectedVersionId);
    this.deletedDraftRequests.set(requestId, {
      exhibitionId: id,
      versionId: expectedVersionId,
      revision: expectedRevision,
    });
  }

  async listSubmissions(
    filters: SubmissionFilters,
  ): Promise<AdminExhibitionSubmission[]> {
    const query = filters.search.trim().toLocaleLowerCase();
    return copy(
      this.submissions.filter((submission) => {
        const matchesStatus =
          filters.status === "all" || submission.status === filters.status;
        const matchesSearch =
          query.length === 0 ||
          [
            submission.nameKo,
            submission.nameEn,
            submission.venueNameKo,
            submission.venueNameEn,
            submission.submitterEmail,
          ].some((value) => value.toLocaleLowerCase().includes(query));
        return matchesStatus && matchesSearch;
      }),
    );
  }

  async startSubmissionReview(id: string): Promise<AdminExhibitionSubmission> {
    const current = this.requireSubmission(id);
    if (current.submission.status === "submitted") {
      current.submission.status = "in_review";
    } else if (current.submission.status !== "in_review") {
      throw new Error("This submission can no longer be reviewed.");
    }
    return copy(current.submission);
  }

  async acceptSubmission(
    id: string,
    requestId: string,
  ): Promise<AdminSubmissionAcceptance> {
    const replay = this.submissionResults.get(requestId);
    if (replay) return copy(replay as AdminSubmissionAcceptance);
    const current = this.requireSubmission(id);
    if (
      current.submission.status !== "submitted" &&
      current.submission.status !== "in_review"
    ) {
      throw new Error("This submission can no longer be accepted.");
    }
    if (current.submission.source === "owner_workspace") {
      const exhibition = this.records.find(
        (record) => record.id === current.submission.ownerExhibitionId,
      );
      if (!exhibition) throw new Error("Owner exhibition draft not found.");
      current.submission.status = "accepted";
      current.submission.acceptedExhibitionId = exhibition.id;
      current.submission.reviewedAt = new Date().toISOString();
      const result = {
        submission: copy(current.submission),
        exhibition: copy(exhibition),
      };
      this.submissionResults.set(requestId, copy(result));
      return result;
    }
    const exhibition = await this.createDraft();
    const acceptedDraft = await this.saveDraft(
      exhibition.id,
      exhibition.workingVersionId,
      exhibition.revision,
      {
        nameKo: current.submission.nameKo,
        nameEn: current.submission.nameEn,
        venueNameKo: current.submission.venueNameKo,
        venueNameEn: current.submission.venueNameEn,
        addressKo: current.submission.addressKo,
        addressEn: current.submission.addressEn,
        openingDate: current.submission.openingDate,
        closingDate: current.submission.closingDate,
        descriptionKo: current.submission.descriptionKo,
        descriptionEn: current.submission.descriptionEn,
        hours: current.submission.hours,
        receptionDate: current.submission.receptionDate.slice(0, 10),
        receptionStartTime: current.submission.receptionDate.slice(11, 16),
        receptionEndTime: current.submission.receptionEnd.slice(11, 16),
      },
    );
    current.submission.status = "accepted";
    current.submission.acceptedExhibitionId = acceptedDraft.id;
    current.submission.reviewedAt = new Date().toISOString();
    const result = {
      submission: copy(current.submission),
      exhibition: acceptedDraft,
    };
    this.submissionResults.set(requestId, copy(result));
    return result;
  }

  async rejectSubmission(
    id: string,
    reviewNotes: string,
    requestId: string,
  ): Promise<AdminExhibitionSubmission> {
    const replay = this.submissionResults.get(requestId);
    if (replay) return copy(replay as AdminExhibitionSubmission);
    if (!reviewNotes.trim()) throw new Error("Add a reason before rejecting.");
    const current = this.requireSubmission(id);
    if (
      current.submission.status !== "submitted" &&
      current.submission.status !== "in_review"
    ) {
      throw new Error("This submission can no longer be rejected.");
    }
    current.submission.status = "rejected";
    current.submission.reviewNotes = reviewNotes.trim();
    current.submission.reviewedAt = new Date().toISOString();
    this.submissionResults.set(requestId, copy(current.submission));
    return copy(current.submission);
  }

  async listGalleryClaims(filters: GalleryClaimFilters): Promise<AdminGalleryClaim[]> {
    const query = filters.search.trim().toLocaleLowerCase();
    return copy(this.galleryClaims.filter((claim) => {
      const matchesStatus = filters.status === "all" || claim.membershipStatus === filters.status;
      const matchesSearch = query.length === 0 || [
        claim.galleryNameKo,
        claim.galleryNameEn,
        claim.ownerEmail,
      ].some((value) => value.toLocaleLowerCase().includes(query));
      return matchesStatus && matchesSearch;
    }));
  }

  async approveGalleryClaim(
    galleryId: string,
    userId: string,
    _requestId: string,
  ): Promise<AdminGalleryClaim> {
    const claim = this.requireGalleryClaim(galleryId, userId);
    if (claim.membershipStatus !== "pending") throw new Error("This gallery claim is no longer pending.");
    claim.membershipStatus = "active";
    claim.reviewedAt = new Date().toISOString();
    claim.reviewNotes = "";
    return copy(claim);
  }

  async rejectGalleryClaim(
    galleryId: string,
    userId: string,
    reviewNotes: string,
    _requestId: string,
  ): Promise<AdminGalleryClaim> {
    if (!reviewNotes.trim()) throw new Error("Add a reason before rejecting.");
    const claim = this.requireGalleryClaim(galleryId, userId);
    if (claim.membershipStatus !== "pending") throw new Error("This gallery claim is no longer pending.");
    claim.membershipStatus = "rejected";
    claim.reviewNotes = reviewNotes.trim();
    claim.reviewedAt = new Date().toISOString();
    return copy(claim);
  }

  async listLocalPromotions(filters: LocalPromotionFilters): Promise<AdminLocalPromotion[]> {
    const query = filters.search.trim().toLocaleLowerCase();
    return copy(this.localPromotions.filter((promotion) =>
      (filters.status === "all" || promotion.status === filters.status) &&
      (!query || [promotion.nameKo, promotion.nameEn, promotion.galleryNameKo, promotion.galleryNameEn]
        .some((value) => value.toLocaleLowerCase().includes(query)))
    ));
  }

  async approveLocalPromotion(
    id: string, startsAt: string, endsAt: string, _requestId: string,
  ): Promise<AdminLocalPromotion> {
    const promotion = this.requireLocalPromotion(id);
    if (promotion.status !== "submitted") throw new Error("Promotion is no longer submitted.");
    promotion.startsAt = startsAt;
    promotion.endsAt = endsAt;
    promotion.status = new Date(startsAt) <= new Date() ? "active" : "approved";
    promotion.reviewedAt = new Date().toISOString();
    return copy(promotion);
  }

  async rejectLocalPromotion(
    id: string, reviewNotes: string, _requestId: string,
  ): Promise<AdminLocalPromotion> {
    if (!reviewNotes.trim()) throw new Error("Add a reason before rejecting.");
    const promotion = this.requireLocalPromotion(id);
    promotion.status = "rejected";
    promotion.reviewNotes = reviewNotes.trim();
    promotion.reviewedAt = new Date().toISOString();
    return copy(promotion);
  }

  private requireLocalPromotion(id: string): AdminLocalPromotion {
    const promotion = this.localPromotions.find((item) => item.id === id);
    if (!promotion) throw new Error("Promotion not found.");
    return promotion;
  }

  private requireGalleryClaim(galleryId: string, userId: string): AdminGalleryClaim {
    const claim = this.galleryClaims.find((item) => item.galleryId === galleryId && item.userId === userId);
    if (!claim) throw new Error("Gallery claim not found.");
    return claim;
  }

  private requireSubmission(id: string): {
    index: number;
    submission: AdminExhibitionSubmission;
  } {
    const index = this.submissions.findIndex((submission) => submission.id === id);
    if (index < 0) throw new Error("Submission not found.");
    return { index, submission: this.submissions[index] };
  }

  async listMedia(
    exhibitionId: string,
    versionId: string,
  ): Promise<AdminMediaAsset[]> {
    const exhibition = this.records.find((record) => record.id === exhibitionId);
    if (!exhibition) throw new Error("Exhibition not found.");
    if (
      exhibition.workingVersionId !== versionId &&
      exhibition.publishedVersionId !== versionId
    ) {
      throw new Error("Exhibition version not found.");
    }
    return copy(this.mediaByVersion.get(versionId) ?? []);
  }

  async uploadAndAttachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    file: File,
    role: AdminMediaRole,
  ): Promise<AdminMediaMutationResult> {
    assertValidAdminMediaFile(file);
    const current = this.requireDraftCurrent(
      exhibitionId,
      expectedVersionId,
      expectedRevision,
    );
    const [dimensions, previewUrl, checksumSha256] = await Promise.all([
      readImageDimensions(file).catch(() => null),
      readFileDataUrl(file),
      globalThis.crypto?.subtle ? sha256File(file) : Promise.resolve(null),
    ]);
    const now = new Date().toISOString();
    const existing = this.mediaByVersion.get(expectedVersionId) ?? [];
    const asset: AdminMediaAsset = {
      assetId: crypto.randomUUID(),
      versionId: current.record.workingVersionId,
      role,
      sortOrder:
        role === "cover"
          ? 0
          : existing.filter((item) => item.role === "gallery").length + 1,
      status: "ready",
      bucketId: "exhibition-media",
      objectPath: `${exhibitionId}/${crypto.randomUUID()}-${file.name}`,
      mimeType: file.type,
      byteSize: file.size,
      width: dimensions?.width ?? null,
      height: dimensions?.height ?? null,
      checksumSha256,
      publicUrl: null,
      altKo: "",
      altEn: "",
      credit: "",
      rightsUrl: "",
      originalFilename: file.name,
      createdAt: now,
      updatedAt: now,
      previewUrl,
    };

    const next =
      role === "cover"
        ? [
            ...existing.map((item) =>
              item.role === "cover" ? { ...item, role: "gallery" as const } : item,
            ),
            asset,
          ]
        : [...existing, asset];
    return this.commitMediaMutation(current.index, this.normalizeMedia(next));
  }

  async updateMediaMetadata(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
    patch: AdminMediaMetadataPatch,
  ): Promise<AdminMediaMutationResult> {
    const current = this.requireDraftCurrent(
      exhibitionId,
      expectedVersionId,
      expectedRevision,
    );
    const existing = this.mediaByVersion.get(expectedVersionId) ?? [];
    if (!existing.some((asset) => asset.assetId === assetId)) {
      throw new Error("Image attachment not found.");
    }
    const now = new Date().toISOString();
    const next = existing.map((asset) =>
      asset.assetId === assetId
        ? {
            ...asset,
            altKo: patch.altKo,
            altEn: patch.altEn,
            credit: patch.credit,
            rightsUrl: patch.rightsUrl,
            updatedAt: now,
          }
        : asset,
    );
    return this.commitMediaMutation(current.index, this.normalizeMedia(next));
  }

  async reorderMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    orderedAssetIds: string[],
  ): Promise<AdminMediaMutationResult> {
    const current = this.requireDraftCurrent(
      exhibitionId,
      expectedVersionId,
      expectedRevision,
    );
    const existing = this.mediaByVersion.get(expectedVersionId) ?? [];
    const gallery = existing.filter((asset) => asset.role === "gallery");
    const expectedIds = new Set(gallery.map((asset) => asset.assetId));
    const suppliedIds = new Set(orderedAssetIds);
    if (
      suppliedIds.size !== orderedAssetIds.length ||
      suppliedIds.size !== expectedIds.size ||
      orderedAssetIds.some((assetId) => !expectedIds.has(assetId))
    ) {
      throw new Error("Gallery order must include every gallery image exactly once.");
    }
    const byId = new Map(existing.map((asset) => [asset.assetId, asset]));
    const cover = existing.filter((asset) => asset.role === "cover");
    const reordered = [
      ...cover,
      ...orderedAssetIds.map((assetId) => byId.get(assetId) as AdminMediaAsset),
    ];
    return this.commitMediaMutation(
      current.index,
      this.normalizeMedia(reordered),
    );
  }

  async detachMedia(
    exhibitionId: string,
    expectedVersionId: string,
    expectedRevision: number,
    assetId: string,
  ): Promise<AdminMediaMutationResult> {
    const current = this.requireDraftCurrent(
      exhibitionId,
      expectedVersionId,
      expectedRevision,
    );
    const existing = this.mediaByVersion.get(expectedVersionId) ?? [];
    if (!existing.some((asset) => asset.assetId === assetId)) {
      throw new Error("Image attachment not found.");
    }
    return this.commitMediaMutation(
      current.index,
      this.normalizeMedia(
        existing.filter((asset) => asset.assetId !== assetId),
      ),
    );
  }

  private normalizeMedia(media: AdminMediaAsset[]): AdminMediaAsset[] {
    const cover = media.find((asset) => asset.role === "cover");
    const gallery = media
      .filter((asset) => asset.role === "gallery")
      .sort((left, right) => left.sortOrder - right.sortOrder);
    return [
      ...(cover ? [{ ...cover, sortOrder: 0 }] : []),
      ...gallery.map((asset, index) => ({ ...asset, sortOrder: index + 1 })),
    ];
  }

  private commitMediaMutation(
    recordIndex: number,
    media: AdminMediaAsset[],
  ): AdminMediaMutationResult {
    const current = this.records[recordIndex];
    const cover = media.find((asset) => asset.role === "cover");
    const exhibition: AdminExhibition = {
      ...current,
      coverImageUrl: cover?.publicUrl ?? cover?.previewUrl ?? null,
      coverAltKo: cover?.altKo ?? "",
      coverAltEn: cover?.altEn ?? "",
      imageCredit: cover?.credit ?? "",
      hasUnpublishedChanges: true,
      status: "Draft",
      revision: current.revision + 1,
      updatedAt: new Date().toISOString(),
      updatedBy: "Current editor",
    };
    this.records[recordIndex] = exhibition;
    this.mediaByVersion.set(current.workingVersionId, copy(media));
    return { exhibition: copy(exhibition), media: copy(media) };
  }

  private requireDraftCurrent(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
  ): { index: number; record: AdminExhibition } {
    const current = this.requireCurrent(id, expectedVersionId, expectedRevision);
    if (current.record.status !== "Draft") {
      throw new Error("Media can only be changed on a draft exhibition.");
    }
    return current;
  }

  private readLifecycleResult(
    action: LifecycleAction,
    requestId: string,
    exhibitionId: string,
    versionId: string,
    revision: number,
  ): AdminExhibition | null {
    const stored = this.lifecycleResults.get(requestId);
    if (!stored) return null;
    if (
      stored.action !== action ||
      stored.exhibitionId !== exhibitionId ||
      stored.versionId !== versionId ||
      stored.revision !== revision
    ) {
      throw new Error("The lifecycle request ID was already used for another command.");
    }
    return copy(stored.result);
  }

  private storeLifecycleResult(
    action: LifecycleAction,
    requestId: string,
    exhibitionId: string,
    versionId: string,
    revision: number,
    result: AdminExhibition,
  ): AdminExhibition {
    this.lifecycleResults.set(requestId, {
      action,
      exhibitionId,
      versionId,
      revision,
      result: copy(result),
    });
    return copy(result);
  }

  private requireCurrent(
    id: string,
    expectedVersionId: string,
    expectedRevision: number,
  ): { index: number; record: AdminExhibition } {
    const index = this.records.findIndex((record) => record.id === id);
    if (index < 0) throw new Error("Exhibition not found.");
    const record = this.records[index];
    if (
      record.workingVersionId !== expectedVersionId ||
      record.revision !== expectedRevision
    ) {
      throw new RevisionConflictError(record.revision);
    }
    return { index, record };
  }
}
