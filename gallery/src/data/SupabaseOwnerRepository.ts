import type {
  ExistingGalleryClaimInput,
  GalleryGeocodeCandidate,
  GalleryInfo,
  GalleryInfoPatch,
  GallerySearchResult,
  GalleryStatus,
  LaunchGuest,
  LaunchGuestCursor,
  LaunchGuestPage,
  LaunchGuestStatus,
  LaunchKit,
  LaunchKitStatus,
  LocalPromotion,
  LocalPromotionStatus,
  MembershipStatus,
  NewGalleryClaimInput,
  OwnerAccess,
  OwnerCover,
  OwnerExhibition,
  OwnerExhibitionPatch,
  OwnerExhibitionStatus,
  OwnerRepository,
} from "../domain";

interface RpcResult {
  data: unknown;
  error: { message?: string } | null;
}

interface RpcClient {
  rpc(name: string, args?: Record<string, unknown>): PromiseLike<RpcResult>;
  storage?: {
    from(bucket: string): {
      upload(
        path: string,
        file: File,
        options: { contentType: string; upsert: boolean },
      ): PromiseLike<RpcResult>;
      createSignedUrl(path: string, expiresIn: number): PromiseLike<RpcResult>;
    };
  };
  functions?: {
    invoke(
      name: string,
      options: { body: Record<string, unknown>; signal?: AbortSignal },
    ): PromiseLike<RpcResult>;
  };
}

type RecordValue = Record<string, unknown>;

const membershipStatuses = new Set<MembershipStatus>([
  "pending",
  "active",
  "rejected",
  "suspended",
  "revoked",
]);
const galleryStatuses = new Set<GalleryStatus>([
  "pending",
  "active",
  "merged",
  "disabled",
]);
const ownerExhibitionStatuses = new Set<OwnerExhibitionStatus>([
  "draft",
  "submitted",
  "needs_changes",
  "published",
  "archived",
]);
const ownerCoverStatuses = new Set<OwnerCover["status"]>([
  "pending_upload",
  "ready",
  "published",
  "orphaned",
  "rejected",
]);
const coverMimeTypes = new Set(["image/jpeg", "image/png", "image/webp"]);
const maximumCoverBytes = 10 * 1024 * 1024;
const launchKitStatuses = new Set<LaunchKitStatus>(["pending", "active", "cancelled", "refunded"]);
const launchKitEntitlementSources = new Set(["free_beta", "paid"]);
const launchGuestStatuses = new Set<LaunchGuestStatus>(["going", "checked_in"]);
const localPromotionStatuses = new Set<LocalPromotionStatus>([
  "submitted", "approved", "active", "rejected", "ended",
]);

function record(value: unknown): RecordValue | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as RecordValue
    : null;
}

function string(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function integer(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) ? value : null;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function nullableCoordinate(
  value: unknown,
  minimum: number,
  maximum: number,
): number | null | undefined {
  if (value === null) return null;
  const parsed = finiteNumber(value);
  return parsed !== null && parsed >= minimum && parsed <= maximum
    ? parsed
    : undefined;
}

function parseGalleryInfo(value: unknown): GalleryInfo {
  const item = record(value);
  const galleryId = string(item?.gallery_id);
  const revision = integer(item?.revision);
  const latitude = nullableCoordinate(item?.latitude, -90, 90);
  const longitude = nullableCoordinate(item?.longitude, -180, 180);
  const fields = [
    "name_ko", "name_en", "venue_name_ko", "venue_name_en",
    "city_ko", "city_en", "region_ko", "region_en", "address_ko",
    "address_en", "hours", "contact", "updated_at",
  ] as const;
  if (
    !item || !galleryId || revision === null || revision < 1 ||
    latitude === undefined || longitude === undefined ||
    (latitude === null) !== (longitude === null) ||
    fields.some((field) => string(item[field]) === null)
  ) {
    throw new Error("Gallery Info response was invalid.");
  }
  return {
    galleryId,
    revision,
    nameKo: item.name_ko as string,
    nameEn: item.name_en as string,
    venueNameKo: item.venue_name_ko as string,
    venueNameEn: item.venue_name_en as string,
    cityKo: item.city_ko as string,
    cityEn: item.city_en as string,
    regionKo: item.region_ko as string,
    regionEn: item.region_en as string,
    addressKo: item.address_ko as string,
    addressEn: item.address_en as string,
    latitude,
    longitude,
    hours: item.hours as string,
    contact: item.contact as string,
    updatedAt: item.updated_at as string,
  };
}

function boundedCandidateString(
  item: RecordValue,
  key: string,
  maximum: number,
  required = false,
): string | null {
  const value = string(item[key]);
  if (
    value === null || value.length > maximum ||
    /[\u0000-\u001f\u007f]/u.test(value) ||
    (required && value.trim().length === 0)
  ) return null;
  return value.trim();
}

function parseCoordinateString(
  value: unknown,
  minimum: number,
  maximum: number,
): number | null {
  if (typeof value !== "string" || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/u.test(value.trim())) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= minimum && parsed <= maximum
    ? parsed
    : null;
}

function parseGeocodeCandidate(value: unknown): GalleryGeocodeCandidate {
  const item = record(value);
  if (!item) throw new Error("Geocoding response was invalid.");
  const roadAddress = boundedCandidateString(item, "road_address", 500);
  const jibunAddress = boundedCandidateString(item, "jibun_address", 500);
  const englishAddress = boundedCandidateString(item, "english_address", 500);
  const cityKo = boundedCandidateString(item, "city_ko", 100, true);
  const cityEn = boundedCandidateString(item, "city_en", 100, true);
  const regionKo = boundedCandidateString(item, "region_ko", 100, true);
  const regionEn = boundedCandidateString(item, "region_en", 100, true);
  const latitude = parseCoordinateString(item.latitude, -90, 90);
  const longitude = parseCoordinateString(item.longitude, -180, 180);
  if (
    roadAddress === null || jibunAddress === null || englishAddress === null ||
    (!roadAddress && !jibunAddress) || !cityKo || !cityEn || !regionKo ||
    !regionEn || latitude === null || longitude === null
  ) throw new Error("Geocoding response was invalid.");
  return {
    roadAddress, jibunAddress, englishAddress, cityKo, cityEn,
    regionKo, regionEn, latitude, longitude,
  };
}

function parseAccess(value: unknown): OwnerAccess | null {
  if (value === null) return null;
  const root = record(value);
  const membership = record(root?.membership);
  const gallery = record(root?.gallery);
  const membershipStatus = string(membership?.status) as MembershipStatus | null;
  const galleryStatus = string(gallery?.status) as GalleryStatus | null;

  if (
    membership?.role !== "owner" ||
    !membershipStatus ||
    !membershipStatuses.has(membershipStatus) ||
    !galleryStatus ||
    !galleryStatuses.has(galleryStatus)
  ) {
    throw new Error("Owner access response was invalid.");
  }

  const id = string(gallery?.id);
  const nameKo = string(gallery?.name_ko);
  const nameEn = string(gallery?.name_en);
  const addressKo = string(gallery?.address_ko);
  const addressEn = string(gallery?.address_en);
  if (!id || nameKo === null || nameEn === null || addressKo === null || addressEn === null) {
    throw new Error("Owner access response was invalid.");
  }

  return {
    membership: { role: "owner", status: membershipStatus },
    gallery: { id, nameKo, nameEn, status: galleryStatus, addressKo, addressEn },
  };
}

function parseSearchResult(value: unknown): GallerySearchResult {
  const item = record(value);
  const galleryId = string(item?.gallery_id);
  const nameKo = string(item?.name_ko);
  const nameEn = string(item?.name_en);
  const addressKo = string(item?.address_ko);
  const addressEn = string(item?.address_en);
  const isClaimed = item?.is_claimed;
  if (
    !galleryId ||
    nameKo === null ||
    nameEn === null ||
    addressKo === null ||
    addressEn === null ||
    typeof isClaimed !== "boolean"
  ) {
    throw new Error("Gallery search response was invalid.");
  }
  return { galleryId, nameKo, nameEn, addressKo, addressEn, isClaimed };
}

function parseCover(value: unknown): OwnerCover | null {
  if (value === null) return null;
  const item = record(value);
  const status = string(item?.status) as OwnerCover["status"] | null;
  const assetId = string(item?.asset_id);
  const bucketId = string(item?.bucket_id);
  const objectPath = string(item?.object_path);
  const publicUrl = item?.public_url === null ? null : string(item?.public_url);
  const mimeType = string(item?.mime_type);
  const byteSize = integer(item?.byte_size);
  const originalFilename = string(item?.original_filename);
  if (
    !item || !status || !ownerCoverStatuses.has(status) || !assetId || !bucketId ||
    !objectPath || publicUrl === null && item.public_url !== null || mimeType === null ||
    byteSize === null || originalFilename === null
  ) {
    throw new Error("Owner exhibition response was invalid.");
  }
  return {
    assetId,
    status,
    bucketId,
    objectPath,
    publicUrl,
    mimeType,
    byteSize,
    originalFilename,
    previewUrl: publicUrl,
  };
}

function parseExhibition(value: unknown): OwnerExhibition {
  const item = record(value);
  const ownerStatus = string(item?.owner_status) as OwnerExhibitionStatus | null;
  const id = string(item?.id);
  const workingVersionId = string(item?.working_version_id);
  const versionNumber = integer(item?.version_number);
  const revision = integer(item?.revision);
  const pageLoads30d = integer(item?.page_loads_30d);
  const pageLoadsAllTime = integer(item?.page_loads_all_time);
  const latitude = nullableCoordinate(item?.latitude, -90, 90);
  const longitude = nullableCoordinate(item?.longitude, -180, 180);
  const fields = [
    "review_notes", "name_ko", "name_en", "venue_name_ko", "venue_name_en",
    "city_ko", "city_en", "region_ko", "region_en", "address_ko", "address_en",
    "opening_date", "closing_date", "description_ko", "description_en", "hours",
    "contact", "reception_date", "reception_start_time", "ticket_url", "updated_at",
  ] as const;
  if (
    !item || !id || !workingVersionId || versionNumber === null || revision === null ||
    latitude === undefined || longitude === undefined ||
    (latitude === null) !== (longitude === null) ||
    pageLoads30d === null || pageLoads30d < 0 ||
    pageLoadsAllTime === null || pageLoadsAllTime < pageLoads30d ||
    !ownerStatus || !ownerExhibitionStatuses.has(ownerStatus) ||
    fields.some((field) => string(item[field]) === null)
  ) {
    throw new Error("Owner exhibition response was invalid.");
  }
  return {
    id,
    workingVersionId,
    versionNumber,
    revision,
    ownerStatus,
    reviewNotes: item.review_notes as string,
    nameKo: item.name_ko as string,
    nameEn: item.name_en as string,
    venueNameKo: item.venue_name_ko as string,
    venueNameEn: item.venue_name_en as string,
    cityKo: item.city_ko as string,
    cityEn: item.city_en as string,
    regionKo: item.region_ko as string,
    regionEn: item.region_en as string,
    addressKo: item.address_ko as string,
    addressEn: item.address_en as string,
    latitude,
    longitude,
    openingDate: item.opening_date as string,
    closingDate: item.closing_date as string,
    descriptionKo: item.description_ko as string,
    descriptionEn: item.description_en as string,
    hours: item.hours as string,
    contact: item.contact as string,
    receptionDate: item.reception_date as string,
    receptionStartTime: item.reception_start_time as string,
    ticketUrl: item.ticket_url as string,
    updatedAt: item.updated_at as string,
    pageLoads30d,
    pageLoadsAllTime,
    cover: parseCover(item.cover),
  };
}

function nonnegative(value: unknown): number | null {
  const parsed = integer(value);
  return parsed !== null && parsed >= 0 ? parsed : null;
}

function parseLaunchKit(value: unknown): LaunchKit {
  const item = record(value);
  const status = string(item?.status) as LaunchKitStatus | null;
  const entitlementSource = item?.entitlement_source === null
    ? null
    : string(item?.entitlement_source);
  const id = string(item?.id);
  const exhibitionId = string(item?.exhibition_id);
  const revision = integer(item?.revision);
  const rsvpCount = nonnegative(item?.rsvp_count);
  const guestCount = nonnegative(item?.guest_count);
  const checkedInCount = nonnegative(item?.checked_in_count);
  const fields = ["public_token", "name_ko", "name_en", "reception_date", "reception_start_time", "updated_at"] as const;
  if (
    !item || !id || !exhibitionId || !status || !launchKitStatuses.has(status) ||
    (entitlementSource !== null && !launchKitEntitlementSources.has(entitlementSource)) ||
    (status === "active" && entitlementSource === null) ||
    revision === null || rsvpCount === null || guestCount === null ||
    checkedInCount === null || checkedInCount > guestCount ||
    fields.some((field) => string(item[field]) === null)
  ) throw new Error("Launch Kit response was invalid.");
  return {
    id,
    exhibitionId,
    status,
    entitlementSource: entitlementSource as LaunchKit["entitlementSource"],
    revision,
    publicToken: item.public_token as string,
    nameKo: item.name_ko as string, nameEn: item.name_en as string,
    receptionDate: item.reception_date as string,
    receptionStartTime: item.reception_start_time as string,
    rsvpCount, guestCount, checkedInCount, updatedAt: item.updated_at as string,
  };
}

function parseLaunchGuest(value: unknown): LaunchGuest {
  const item = record(value);
  const status = string(item?.status) as LaunchGuestStatus | null;
  const id = string(item?.id);
  const launchKitId = string(item?.launch_kit_id);
  const name = string(item?.name);
  const email = string(item?.email);
  const partySize = integer(item?.party_size);
  const createdAt = string(item?.created_at);
  const checkedInAt = item?.checked_in_at === null ? null : string(item?.checked_in_at);
  if (
    !item || !id || !launchKitId || !name || !email || !status ||
    !launchGuestStatuses.has(status) || partySize === null || partySize < 1 ||
    partySize > 6 || !createdAt || (checkedInAt === null) !== (status === "going")
  ) throw new Error("Launch guest response was invalid.");
  return { id, launchKitId, name, email, partySize, status, checkedInAt, createdAt };
}

function parseLocalPromotion(value: unknown): LocalPromotion {
  const item = record(value);
  const id = string(item?.id);
  const launchKitId = string(item?.launch_kit_id);
  const exhibitionId = string(item?.exhibition_id);
  const status = string(item?.status) as LocalPromotionStatus | null;
  const revision = integer(item?.revision);
  const startsAt = item?.starts_at === null ? null : string(item?.starts_at);
  const endsAt = item?.ends_at === null ? null : string(item?.ends_at);
  const fields = ["city_ko", "city_en", "region_ko", "region_en", "review_notes", "requested_at"] as const;
  if (
    !item || !id || !launchKitId || !exhibitionId || !status ||
    !localPromotionStatuses.has(status) || revision === null || revision < 1 ||
    fields.some((field) => string(item[field]) === null) ||
    (startsAt === null) !== (item.starts_at === null) ||
    (endsAt === null) !== (item.ends_at === null)
  ) throw new Error("Promotion response was invalid.");
  return {
    id, launchKitId, exhibitionId, status, revision,
    cityKo: item.city_ko as string, cityEn: item.city_en as string,
    regionKo: item.region_ko as string, regionEn: item.region_en as string,
    startsAt, endsAt, reviewNotes: item.review_notes as string,
    requestedAt: item.requested_at as string,
  };
}

function patchDto(patch: OwnerExhibitionPatch): Record<string, string | number | null> {
  return {
    name_ko: patch.nameKo,
    name_en: patch.nameEn,
    venue_name_ko: patch.venueNameKo,
    venue_name_en: patch.venueNameEn,
    city_ko: patch.cityKo,
    city_en: patch.cityEn,
    region_ko: patch.regionKo,
    region_en: patch.regionEn,
    address_ko: patch.addressKo,
    address_en: patch.addressEn,
    latitude: patch.latitude,
    longitude: patch.longitude,
    opening_date: patch.openingDate,
    closing_date: patch.closingDate,
    description_ko: patch.descriptionKo,
    description_en: patch.descriptionEn,
    hours: patch.hours,
    contact: patch.contact,
    reception_date: patch.receptionDate,
    reception_start_time: patch.receptionStartTime,
    ticket_url: patch.ticketUrl,
  };
}

function galleryInfoPatchDto(
  patch: GalleryInfoPatch,
): Record<string, string | number | null> {
  return {
    name_ko: patch.nameKo,
    name_en: patch.nameEn,
    venue_name_ko: patch.venueNameKo,
    venue_name_en: patch.venueNameEn,
    city_ko: patch.cityKo,
    city_en: patch.cityEn,
    region_ko: patch.regionKo,
    region_en: patch.regionEn,
    address_ko: patch.addressKo,
    address_en: patch.addressEn,
    latitude: patch.latitude,
    longitude: patch.longitude,
    hours: patch.hours,
    contact: patch.contact,
  };
}

function assertRpc(result: RpcResult): unknown {
  if (result.error) {
    throw new Error(result.error.message || "Gallery request failed.");
  }
  return result.data;
}

export class SupabaseOwnerRepository implements OwnerRepository {
  constructor(
    private readonly client: RpcClient,
    private readonly requestId: () => string = () => crypto.randomUUID(),
  ) {}

  async currentAccess(): Promise<OwnerAccess | null> {
    const result = await this.client.rpc("owner_current_access");
    return parseAccess(assertRpc(result));
  }

  async searchGalleries(query: string): Promise<GallerySearchResult[]> {
    const result = await this.client.rpc("owner_search_galleries", {
      p_query: query.trim(),
    });
    const data = assertRpc(result);
    if (!Array.isArray(data)) throw new Error("Gallery search response was invalid.");
    return data.map(parseSearchResult);
  }

  async claimExistingGallery(
    input: ExistingGalleryClaimInput,
  ): Promise<OwnerAccess> {
    const result = await this.client.rpc("owner_claim_existing_gallery", {
      p_gallery_id: input.galleryId,
      p_website_url: input.websiteUrl,
      p_social_url: input.socialUrl,
      p_claim_note: input.claimNote,
      p_request_id: this.requestId(),
    });
    const access = parseAccess(assertRpc(result));
    if (!access) throw new Error("Owner access response was invalid.");
    return access;
  }

  async createGalleryClaim(input: NewGalleryClaimInput): Promise<OwnerAccess> {
    const result = await this.client.rpc("owner_create_gallery_claim", {
      p_name_ko: input.nameKo,
      p_name_en: input.nameEn,
      p_website_url: input.websiteUrl,
      p_social_url: input.socialUrl,
      p_claim_note: input.claimNote,
      p_request_id: this.requestId(),
    });
    const access = parseAccess(assertRpc(result));
    if (!access) throw new Error("Owner access response was invalid.");
    return access;
  }

  async getGalleryInfo(): Promise<GalleryInfo> {
    return parseGalleryInfo(assertRpc(await this.client.rpc("owner_get_gallery_info")));
  }

  async saveGalleryInfo(
    revision: number,
    patch: GalleryInfoPatch,
  ): Promise<GalleryInfo> {
    return parseGalleryInfo(assertRpc(await this.client.rpc("owner_save_gallery_info", {
      p_expected_revision: revision,
      p_patch: galleryInfoPatchDto(patch),
    })));
  }

  async searchGalleryAddress(address: string): Promise<GalleryGeocodeCandidate[]> {
    if (!this.client.functions) throw new Error("Address search is unavailable.");
    const controller = new AbortController();
    const timeoutId = globalThis.setTimeout(() => controller.abort(), 20_000);
    let data: unknown;
    try {
      data = assertRpc(await this.client.functions.invoke("geocode-address", {
        body: { address: address.trim() },
        signal: controller.signal,
      }));
    } finally {
      globalThis.clearTimeout(timeoutId);
    }
    const payload = record(data);
    if (!payload || !Array.isArray(payload.candidates) || payload.candidates.length > 3) {
      throw new Error("Geocoding response was invalid.");
    }
    return payload.candidates.map(parseGeocodeCandidate);
  }

  private async withCoverPreview(exhibition: OwnerExhibition): Promise<OwnerExhibition> {
    const cover = exhibition.cover;
    if (!cover || cover.previewUrl || !this.client.storage) return exhibition;
    const result = await this.client.storage.from(cover.bucketId)
      .createSignedUrl(cover.objectPath, 3600);
    const data = record(assertRpc(result));
    const previewUrl = string(data?.signedUrl);
    if (!previewUrl) return exhibition;
    return { ...exhibition, cover: { ...cover, previewUrl } };
  }

  async listExhibitions(): Promise<OwnerExhibition[]> {
    const result = await this.client.rpc("owner_list_exhibitions");
    const data = assertRpc(result);
    if (!Array.isArray(data)) throw new Error("Owner exhibition response was invalid.");
    return Promise.all(data.map((item) => this.withCoverPreview(parseExhibition(item))));
  }

  async hideExhibition(id: string, versionId: string, revision: number): Promise<void> {
    const payload = record(assertRpc(await this.client.rpc("owner_hide_exhibition", {
      p_exhibition_id: id,
      p_expected_version_id: versionId,
      p_expected_revision: revision,
    })));
    if (string(payload?.id) !== id || payload?.hidden !== true) {
      throw new Error("Owner exhibition hide response was invalid.");
    }
  }

  async createExhibitionDraft(requestId: string): Promise<OwnerExhibition> {
    const result = await this.client.rpc("owner_create_exhibition_draft", {
      p_request_id: requestId,
    });
    return this.withCoverPreview(parseExhibition(assertRpc(result)));
  }

  async saveExhibitionDraft(
    id: string,
    versionId: string,
    revision: number,
    patch: OwnerExhibitionPatch,
  ): Promise<OwnerExhibition> {
    const result = await this.client.rpc("owner_save_exhibition_draft", {
      p_exhibition_id: id,
      p_expected_version_id: versionId,
      p_expected_revision: revision,
      p_patch: patchDto(patch),
    });
    return this.withCoverPreview(parseExhibition(assertRpc(result)));
  }

  async uploadCover(
    id: string,
    versionId: string,
    revision: number,
    file: File,
  ): Promise<OwnerExhibition> {
    if (!coverMimeTypes.has(file.type)) {
      throw new Error("Cover image must be a JPEG, PNG, or WebP file.");
    }
    if (file.size < 1 || file.size > maximumCoverBytes) {
      throw new Error("Cover image must be 10 MB or smaller.");
    }
    if (!this.client.storage) throw new Error("Cover upload is unavailable.");

    const reservationResult = await this.client.rpc("owner_reserve_cover_upload", {
      p_exhibition_id: id,
      p_expected_version_id: versionId,
      p_expected_revision: revision,
      p_mime_type: file.type,
      p_byte_size: file.size,
      p_original_filename: file.name,
    });
    const reservation = record(assertRpc(reservationResult));
    const assetId = string(reservation?.asset_id);
    const bucketId = string(reservation?.bucket_id);
    const objectPath = string(reservation?.object_path);
    if (!assetId || !bucketId || !objectPath) {
      throw new Error("Cover reservation response was invalid.");
    }
    const bucket = this.client.storage.from(bucketId);
    assertRpc(await bucket.upload(objectPath, file, {
      contentType: file.type,
      upsert: false,
    }));
    const completed = await this.client.rpc("owner_complete_cover_upload", {
      p_exhibition_id: id,
      p_expected_version_id: versionId,
      p_expected_revision: revision,
      p_asset_id: assetId,
    });
    return this.withCoverPreview(parseExhibition(assertRpc(completed)));
  }

  async submitExhibition(
    id: string,
    versionId: string,
    revision: number,
    requestId: string,
  ): Promise<OwnerExhibition> {
    const result = await this.client.rpc("owner_submit_exhibition", {
      p_exhibition_id: id,
      p_expected_version_id: versionId,
      p_expected_revision: revision,
      p_request_id: requestId,
    });
    return this.withCoverPreview(parseExhibition(assertRpc(result)));
  }

  async listLaunchKits(): Promise<LaunchKit[]> {
    const result = await this.client.rpc("owner_list_launch_kits");
    const data = assertRpc(result);
    if (!Array.isArray(data)) throw new Error("Launch Kit response was invalid.");
    return data.map(parseLaunchKit);
  }

  async activateLaunchKit(exhibitionId: string): Promise<LaunchKit> {
    return parseLaunchKit(assertRpc(await this.client.rpc("owner_activate_launch_kit", {
      p_exhibition_id: exhibitionId,
      p_request_id: this.requestId(),
    })));
  }

  async listLaunchGuests(
    launchKitId: string,
    query = "",
    status: "all" | LaunchGuestStatus = "all",
    cursor: LaunchGuestCursor | null = null,
  ): Promise<LaunchGuestPage> {
    const result = await this.client.rpc("owner_list_launch_guests", {
      p_launch_kit_id: launchKitId, p_query: query, p_status: status,
      p_after_created_at: cursor?.createdAt ?? null,
      p_after_id: cursor?.id ?? null,
      p_limit: 50,
    });
    const data = assertRpc(result);
    if (!Array.isArray(data)) throw new Error("Launch guest response was invalid.");
    const records = data.map(parseLaunchGuest);
    const last = records.at(-1);
    return {
      records,
      nextCursor: records.length === 50 && last
        ? { createdAt: last.createdAt, id: last.id }
        : null,
    };
  }

  async addLaunchGuest(
    launchKitId: string, name: string, email: string, partySize: number,
  ): Promise<LaunchGuest> {
    return parseLaunchGuest(assertRpc(await this.client.rpc("owner_add_launch_guest", {
      p_launch_kit_id: launchKitId, p_name: name, p_email: email,
      p_party_size: partySize, p_request_id: this.requestId(),
    })));
  }

  async checkInLaunchGuest(launchKitId: string, guestId: string): Promise<LaunchGuest> {
    return parseLaunchGuest(assertRpc(await this.client.rpc("owner_check_in_launch_guest", {
      p_launch_kit_id: launchKitId, p_guest_id: guestId,
      p_request_id: this.requestId(),
    })));
  }

  async rotateLaunchRsvpToken(launchKitId: string): Promise<LaunchKit> {
    return parseLaunchKit(assertRpc(await this.client.rpc("owner_rotate_launch_rsvp_token", {
      p_launch_kit_id: launchKitId,
      p_request_id: this.requestId(),
    })));
  }

  async listLocalPromotions(): Promise<LocalPromotion[]> {
    const data = assertRpc(await this.client.rpc("owner_list_local_promotions"));
    if (!Array.isArray(data)) throw new Error("Promotion response was invalid.");
    return data.map(parseLocalPromotion);
  }

  async requestLocalPromotion(launchKitId: string): Promise<LocalPromotion> {
    return parseLocalPromotion(assertRpc(await this.client.rpc(
      "owner_request_local_promotion",
      { p_launch_kit_id: launchKitId, p_request_id: this.requestId() },
    )));
  }
}
