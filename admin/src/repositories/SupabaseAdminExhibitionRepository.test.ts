import type { SupabaseClient } from "@supabase/supabase-js";
import type { AdminExhibition, ExhibitionPatch } from "../domain";
import {
  DraftDeleteBlockedError,
  RevisionConflictError,
} from "./AdminExhibitionRepository";
import {
  MalformedAdminExhibitionPayloadError,
  SupabaseAdminExhibitionRepository,
} from "./SupabaseAdminExhibitionRepository";

interface RpcResponse {
  data: unknown;
  error: {
    code?: unknown;
    details?: unknown;
    hint?: unknown;
    message?: unknown;
  } | null;
}

const rawRecord = {
  id: "exhibition-123",
  working_version_id: "10000000-0000-0000-0000-000000000123",
  version_number: 4,
  published_version_id: "10000000-0000-0000-0000-000000000122",
  has_unpublished_changes: true,
  name_ko: "빛의 문법",
  name_en: "The Grammar of Light",
  venue_name_ko: "아트스페이스 오오",
  venue_name_en: "Artspace OOO",
  city_ko: "서울",
  city_en: "Seoul",
  region_ko: "용산구",
  region_en: "Yongsan-gu",
  address_ko: "서울 용산구 한남대로 28",
  address_en: "28 Hannam-daero, Yongsan-gu, Seoul",
  latitude: "37.5344",
  longitude: "127.0005",
  opening_date: "2026-07-01",
  closing_date: "2026-08-31",
  description_ko: "빛과 공간을 탐색합니다.",
  description_en: "An inquiry into light and space.",
  credits_ko: "자료 제공: 작가",
  credits_en: "Courtesy of the artist",
  hours: "화–일 11:00–18:00",
  contact: "02-000-0000",
  reception_date: "2026-07-03",
  reception_start_time: "18:00",
  reception_end_time: "20:00",
  event_id: "hannam-saturdays1",
  editor_id: "gallr-editors",
  ticket_url: "https://tickets.example.test/exhibition-123",
  cover_image_url: "https://images.example.test/cover.webp",
  cover_alt_ko: "전시장 설치 전경",
  cover_alt_en: "Installation view",
  image_credit: "Courtesy of the artist",
  is_featured: true,
  is_homepage_featured: false,
  has_open_owner_submission: false,
  created_at: "2026-07-01T09:00:00.000Z",
  published_at: "2026-07-02T09:00:00.000Z",
  status: "published",
  revision: 7,
  updated_at: "2026-07-21T12:34:56.000Z",
  updated_by: "Mina Kim",
} satisfies Record<string, unknown>;

const mappedRecord: AdminExhibition = {
  id: "exhibition-123",
  workingVersionId: "10000000-0000-0000-0000-000000000123",
  versionNumber: 4,
  publishedVersionId: "10000000-0000-0000-0000-000000000122",
  hasUnpublishedChanges: true,
  nameKo: "빛의 문법",
  nameEn: "The Grammar of Light",
  venueNameKo: "아트스페이스 오오",
  venueNameEn: "Artspace OOO",
  cityKo: "서울",
  cityEn: "Seoul",
  regionKo: "용산구",
  regionEn: "Yongsan-gu",
  addressKo: "서울 용산구 한남대로 28",
  addressEn: "28 Hannam-daero, Yongsan-gu, Seoul",
  latitude: "37.5344",
  longitude: "127.0005",
  openingDate: "2026-07-01",
  closingDate: "2026-08-31",
  descriptionKo: "빛과 공간을 탐색합니다.",
  descriptionEn: "An inquiry into light and space.",
  creditsKo: "자료 제공: 작가",
  creditsEn: "Courtesy of the artist",
  artMetadata: null,
  hours: "화–일 11:00–18:00",
  contact: "02-000-0000",
  receptionDate: "2026-07-03",
  receptionStartTime: "18:00",
  receptionEndTime: "20:00",
  eventId: "hannam-saturdays1",
  editorId: "gallr-editors",
  ticketUrl: "https://tickets.example.test/exhibition-123",
  coverImageUrl: "https://images.example.test/cover.webp",
  coverAltKo: "전시장 설치 전경",
  coverAltEn: "Installation view",
  imageCredit: "Courtesy of the artist",
  isFeatured: true,
  isHomepageFeatured: false,
  hasOpenOwnerSubmission: false,
  createdAt: "2026-07-01T09:00:00.000Z",
  publishedAt: "2026-07-02T09:00:00.000Z",
  status: "Published",
  revision: 7,
  updatedAt: "2026-07-21T12:34:56.000Z",
  updatedBy: "Mina Kim",
};

const checksum = "a8eb701c6f567b08661c2604364dd595455b811d2759d2029b465935b561c86b";

const rawMedia = {
  asset_id: "30000000-0000-0000-0000-000000000001",
  version_id: mappedRecord.workingVersionId,
  role: "cover",
  sort_order: 0,
  status: "published",
  bucket_id: "exhibition-media",
  object_path: "exhibition-123/cover.png",
  mime_type: "image/png",
  byte_size: 10,
  width: 1600,
  height: 1067,
  checksum_sha256: checksum,
  public_url: "https://images.example.test/published-cover.png",
  alt_ko: "전시장 설치 전경",
  alt_en: "Installation view",
  credit: "Courtesy of the artist",
  rights_url: "https://rights.example.test/cover",
  original_filename: "cover.png",
  created_at: "2026-07-21T12:00:00.000Z",
  updated_at: "2026-07-21T12:30:00.000Z",
} satisfies Record<string, unknown>;

const mappedMedia = {
  assetId: rawMedia.asset_id,
  versionId: rawMedia.version_id,
  role: "cover" as const,
  sortOrder: 0,
  status: "published" as const,
  bucketId: rawMedia.bucket_id,
  objectPath: rawMedia.object_path,
  mimeType: rawMedia.mime_type,
  byteSize: rawMedia.byte_size,
  width: rawMedia.width,
  height: rawMedia.height,
  checksumSha256: rawMedia.checksum_sha256,
  publicUrl: rawMedia.public_url,
  altKo: rawMedia.alt_ko,
  altEn: rawMedia.alt_en,
  credit: rawMedia.credit,
  rightsUrl: rawMedia.rights_url,
  originalFilename: rawMedia.original_filename,
  createdAt: rawMedia.created_at,
  updatedAt: rawMedia.updated_at,
  previewUrl: rawMedia.public_url,
};

const rawSubmission = {
  id: "40000000-0000-0000-0000-000000000001",
  status: "submitted",
  source: "public_form",
  owner_exhibition_id: null,
  gallery_name_ko: "",
  gallery_name_en: "",
  submitter_email: "gallery@example.com",
  payload: {
    name_ko: "기억의 층위",
    name_en: "Layers of Memory",
    venue_name_ko: "아트스페이스 이튼",
    venue_name_en: "Artspace Eaton",
    opening_date: "2026-08-15",
    closing_date: "2026-09-21",
    address_ko: "서울특별시 성동구 연무장길 68",
    address_en: "",
    hours: "화–금 11:00–19:00",
    description_ko: "기억과 장소를 다루는 전시입니다.",
    description_en: "",
    reception_date: "2026-08-15T18:00",
    reception_end: "2026-08-15T20:00",
  },
  accepted_exhibition_id: null,
  review_notes: "",
  submitted_at: "2026-07-30T02:30:00.000Z",
  reviewed_at: null,
  created_at: "2026-07-30T02:30:00.000Z",
  media: [
    {
      asset_id: "41000000-0000-0000-0000-000000000001",
      bucket_id: "exhibition-media",
      object_path:
        "submissions/40000000-0000-0000-0000-000000000001/41000000-0000-0000-0000-000000000001/original.jpg",
      public_url: null,
      mime_type: "image/jpeg",
      byte_size: 2048,
      original_filename: "installation.jpg",
    },
  ],
} satisfies Record<string, unknown>;

const rawGalleryClaim = {
  gallery_id: "42000000-0000-0000-0000-000000000001",
  gallery_name_ko: "갤러리 알파",
  gallery_name_en: "Gallery Alpha",
  gallery_status: "active",
  user_id: "43000000-0000-0000-0000-000000000001",
  owner_email: "owner@alpha.example",
  membership_status: "pending",
  website_url: "https://alpha.example",
  social_url: "",
  claim_note: "I manage gallery programming.",
  review_notes: "",
  created_at: "2026-07-31T08:00:00Z",
  reviewed_at: null,
} satisfies Record<string, unknown>;

const patch: ExhibitionPatch = {
  nameKo: mappedRecord.nameKo,
  nameEn: mappedRecord.nameEn,
  venueNameKo: mappedRecord.venueNameKo,
  venueNameEn: mappedRecord.venueNameEn,
  cityKo: mappedRecord.cityKo,
  cityEn: mappedRecord.cityEn,
  regionKo: mappedRecord.regionKo,
  regionEn: mappedRecord.regionEn,
  addressKo: mappedRecord.addressKo,
  addressEn: mappedRecord.addressEn,
  latitude: mappedRecord.latitude,
  longitude: mappedRecord.longitude,
  openingDate: mappedRecord.openingDate,
  closingDate: mappedRecord.closingDate,
  descriptionKo: mappedRecord.descriptionKo,
  descriptionEn: mappedRecord.descriptionEn,
  creditsKo: mappedRecord.creditsKo,
  creditsEn: mappedRecord.creditsEn,
  hours: mappedRecord.hours,
  contact: mappedRecord.contact,
  receptionDate: mappedRecord.receptionDate,
  receptionStartTime: mappedRecord.receptionStartTime,
  receptionEndTime: mappedRecord.receptionEndTime,
  eventId: mappedRecord.eventId,
  editorId: mappedRecord.editorId,
  ticketUrl: mappedRecord.ticketUrl,
  isFeatured: mappedRecord.isFeatured,
  isHomepageFeatured: mappedRecord.isHomepageFeatured,
};

const serializedPatch = {
  name_ko: rawRecord.name_ko,
  name_en: rawRecord.name_en,
  venue_name_ko: rawRecord.venue_name_ko,
  venue_name_en: rawRecord.venue_name_en,
  city_ko: rawRecord.city_ko,
  city_en: rawRecord.city_en,
  region_ko: rawRecord.region_ko,
  region_en: rawRecord.region_en,
  address_ko: rawRecord.address_ko,
  address_en: rawRecord.address_en,
  latitude: rawRecord.latitude,
  longitude: rawRecord.longitude,
  opening_date: rawRecord.opening_date,
  closing_date: rawRecord.closing_date,
  description_ko: rawRecord.description_ko,
  description_en: rawRecord.description_en,
  credits_ko: rawRecord.credits_ko,
  credits_en: rawRecord.credits_en,
  hours: rawRecord.hours,
  contact: rawRecord.contact,
  reception_date: rawRecord.reception_date,
  reception_start_time: rawRecord.reception_start_time,
  reception_end_time: rawRecord.reception_end_time,
  event_id: rawRecord.event_id,
  editor_id: rawRecord.editor_id,
  ticket_url: rawRecord.ticket_url,
  is_featured: rawRecord.is_featured,
  is_homepage_featured: rawRecord.is_homepage_featured,
};

const rawLookups = {
  locations: [
    {
      city_ko: "서울",
      city_en: "Seoul",
      region_ko: "용산구",
      region_en: "Yongsan-gu",
    },
  ],
  venues: [
    {
      id: "history:10000000-0000-0000-0000-000000000123",
      name_ko: "아트스페이스 오오",
      name_en: "Artspace OOO",
      city_ko: "서울",
      city_en: "Seoul",
      region_ko: "용산구",
      region_en: "Yongsan-gu",
      address_ko: "서울 용산구 이태원로 55",
      address_en: "55 Itaewon-ro, Yongsan-gu, Seoul",
      latitude: "37.5348",
      longitude: "127.0010",
    },
  ],
  events: [
    {
      id: "hannam-saturdays1",
      name_ko: "한남 새터데이즈",
      name_en: "Hannam Saturdays",
      location_label_ko: "한남동",
      location_label_en: "Hannam-dong",
      start_date: "2026-07-01",
      end_date: "2026-08-31",
      short_label: "HS26",
      is_active: true,
    },
    {
      id: "seoul-art-week-2025",
      name_ko: "서울 아트 위크 2025",
      name_en: "Seoul Art Week 2025",
      location_label_ko: "서울 전역",
      location_label_en: "Across Seoul",
      start_date: "2025-09-01",
      end_date: "2025-09-07",
      short_label: null,
      is_active: false,
    },
  ],
  editors: [
    {
      id: "gallr-editors",
      name_ko: "gallr 에디터즈",
      name_en: "gallr Editors",
      title_ko: "하우스 에디터",
      title_en: "House Editor",
      is_active: true,
      active_from: "2026-01-01",
      active_to: null,
    },
    {
      id: "minjung-kim",
      name_ko: "김민정",
      name_en: "Minjung Kim",
      title_ko: "게스트 에디터",
      title_en: "Guest Editor",
      is_active: false,
      active_from: null,
      active_to: "2025-08-31",
    },
  ],
};

const mappedLookups = {
  artTerms: null,
  locations: [
    {
      cityKo: "서울",
      cityEn: "Seoul",
      regionKo: "용산구",
      regionEn: "Yongsan-gu",
    },
  ],
  venues: [
    {
      id: "history:10000000-0000-0000-0000-000000000123",
      nameKo: "아트스페이스 오오",
      nameEn: "Artspace OOO",
      cityKo: "서울",
      cityEn: "Seoul",
      regionKo: "용산구",
      regionEn: "Yongsan-gu",
      addressKo: "서울 용산구 이태원로 55",
      addressEn: "55 Itaewon-ro, Yongsan-gu, Seoul",
      latitude: "37.5348",
      longitude: "127.0010",
    },
  ],
  events: [
    {
      id: "hannam-saturdays1",
      nameKo: "한남 새터데이즈",
      nameEn: "Hannam Saturdays",
      locationLabelKo: "한남동",
      locationLabelEn: "Hannam-dong",
      startDate: "2026-07-01",
      endDate: "2026-08-31",
      shortLabel: "HS26",
      isActive: true,
    },
    {
      id: "seoul-art-week-2025",
      nameKo: "서울 아트 위크 2025",
      nameEn: "Seoul Art Week 2025",
      locationLabelKo: "서울 전역",
      locationLabelEn: "Across Seoul",
      startDate: "2025-09-01",
      endDate: "2025-09-07",
      shortLabel: null,
      isActive: false,
    },
  ],
  editors: [
    {
      id: "gallr-editors",
      nameKo: "gallr 에디터즈",
      nameEn: "gallr Editors",
      titleKo: "하우스 에디터",
      titleEn: "House Editor",
      isActive: true,
      activeFrom: "2026-01-01",
      activeTo: null,
    },
    {
      id: "minjung-kim",
      nameKo: "김민정",
      nameEn: "Minjung Kim",
      titleKo: "게스트 에디터",
      titleEn: "Guest Editor",
      isActive: false,
      activeFrom: null,
      activeTo: "2025-08-31",
    },
  ],
};

function mockedClient(response: RpcResponse) {
  const rpc = vi.fn(
    async (_name: string, _args?: Record<string, unknown>) => response,
  );
  return {
    client: { rpc } as unknown as SupabaseClient,
    rpc,
  };
}

function scriptedClient(
  responses: Record<string, RpcResponse>,
  storageApi?: {
    createSignedUploadUrl: ReturnType<typeof vi.fn>;
    uploadToSignedUrl: ReturnType<typeof vi.fn>;
    createSignedUrl: ReturnType<typeof vi.fn>;
  },
) {
  const rpc = vi.fn(async (name: string) => {
    const response = responses[name];
    if (!response) throw new Error(`Unexpected RPC: ${name}`);
    return response;
  });
  const from = vi.fn(() => storageApi);
  return {
    client: { rpc, storage: { from } } as unknown as SupabaseClient,
    rpc,
    from,
    storageApi,
  };
}

describe("SupabaseAdminExhibitionRepository", () => {
  it("maps every snake-case record field and normalizes list filters", async () => {
    const { client, rpc } = mockedClient({ data: [rawRecord], error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(
      repository.list({
        search: "  빛  ",
        status: "Published",
        temporalStatus: "running",
        featuredOnly: true,
        missingCoverOnly: true,
        sort: "published_desc",
      }),
    ).resolves.toEqual([mappedRecord]);
    expect(rpc).toHaveBeenCalledWith("admin_list_exhibitions", {
      p_search: "빛",
      p_status: "published",
      p_temporal_status: "running",
      p_featured_only: true,
      p_missing_cover_only: true,
      p_sort: "published_desc",
    });
  });

  it("passes null for the All status filter", async () => {
    const { client, rpc } = mockedClient({ data: [], error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await repository.list({ search: "", status: "All" });

    expect(rpc).toHaveBeenCalledWith("admin_list_exhibitions", {
      p_search: "",
      p_status: null,
      p_temporal_status: null,
      p_featured_only: false,
      p_missing_cover_only: false,
      p_sort: "updated_desc",
    });
  });

  it("maps submission filters and signs private media previews", async () => {
    const storageApi = {
      createSignedUploadUrl: vi.fn(),
      uploadToSignedUrl: vi.fn(),
      createSignedUrl: vi.fn(async () => ({
        data: { signedUrl: "https://signed.example.test/submission" },
        error: null,
      })),
    };
    const { client, rpc, from } = scriptedClient(
      {
        admin_list_exhibition_submissions: {
          data: [rawSubmission],
          error: null,
        },
      },
      storageApi,
    );
    const repository = new SupabaseAdminExhibitionRepository(client);

    const result = await repository.listSubmissions({
      search: "  기억  ",
      status: "submitted",
    });

    expect(rpc).toHaveBeenCalledWith("admin_list_exhibition_submissions", {
      p_search: "기억",
      p_status: "submitted",
    });
    expect(result[0]).toMatchObject({
      id: rawSubmission.id,
      status: "submitted",
      submitterEmail: "gallery@example.com",
      nameKo: "기억의 층위",
      acceptedExhibitionId: null,
      media: [
        {
          assetId: rawSubmission.media[0].asset_id,
          previewUrl: "https://signed.example.test/submission",
        },
      ],
    });
    expect(from).toHaveBeenCalledWith("exhibition-media");
    expect(storageApi.createSignedUrl).toHaveBeenCalledWith(
      rawSubmission.media[0].object_path,
      900,
    );
  });

  it("uses a published submission media URL without signing the private original", async () => {
    const publicUrl = "https://images.example.test/published-submission.jpg";
    const storageApi = {
      createSignedUploadUrl: vi.fn(),
      uploadToSignedUrl: vi.fn(),
      createSignedUrl: vi.fn(),
    };
    const submission = {
      ...rawSubmission,
      media: [{ ...rawSubmission.media[0], public_url: publicUrl }],
    };
    const { client } = scriptedClient(
      {
        admin_list_exhibition_submissions: {
          data: [submission],
          error: null,
        },
      },
      storageApi,
    );

    const result = await new SupabaseAdminExhibitionRepository(client)
      .listSubmissions({ search: "", status: "all" });

    expect(result[0].media[0]).toMatchObject({
      publicUrl,
      previewUrl: publicUrl,
    });
    expect(storageApi.createSignedUrl).not.toHaveBeenCalled();
  });

  it("maps the gallery claim queue and sends an idempotent approval command", async () => {
    const approved = {
      ...rawGalleryClaim,
      membership_status: "active",
      reviewed_at: "2026-07-31T09:00:00Z",
    };
    const { client, rpc } = scriptedClient({
      admin_list_gallery_claims: { data: [rawGalleryClaim], error: null },
      admin_approve_gallery_claim: { data: approved, error: null },
    });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(repository.listGalleryClaims({ search: "  alpha ", status: "pending" }))
      .resolves.toEqual([expect.objectContaining({
        galleryId: rawGalleryClaim.gallery_id,
        ownerEmail: "owner@alpha.example",
        membershipStatus: "pending",
      })]);
    await expect(repository.approveGalleryClaim(
      rawGalleryClaim.gallery_id,
      rawGalleryClaim.user_id,
      "request-one",
    )).resolves.toEqual(expect.objectContaining({ membershipStatus: "active" }));
    expect(rpc).toHaveBeenNthCalledWith(1, "admin_list_gallery_claims", {
      p_search: "alpha",
      p_status: "pending",
    });
    expect(rpc).toHaveBeenNthCalledWith(2, "admin_approve_gallery_claim", {
      p_gallery_id: rawGalleryClaim.gallery_id,
      p_user_id: rawGalleryClaim.user_id,
      p_request_id: "request-one",
    });
  });

  it("keeps the submission queue usable when one preview cannot be signed", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const storageApi = {
      createSignedUploadUrl: vi.fn(),
      uploadToSignedUrl: vi.fn(),
      createSignedUrl: vi.fn(async () => ({
        data: null,
        error: { message: "private storage is temporarily unavailable" },
      })),
    };
    const { client } = scriptedClient(
      {
        admin_list_exhibition_submissions: {
          data: [rawSubmission],
          error: null,
        },
      },
      storageApi,
    );

    await expect(
      new SupabaseAdminExhibitionRepository(client).listSubmissions({
        search: "",
        status: "all",
      }),
    ).resolves.toMatchObject([
      {
        id: rawSubmission.id,
        media: [{ previewUrl: null }],
      },
    ]);
    expect(warn).toHaveBeenCalledWith(
      JSON.stringify({
        event: "admin_submission_preview_hydration_failed",
        submission_id: rawSubmission.id,
        asset_id: rawSubmission.media[0].asset_id,
      }),
    );
    expect(JSON.stringify(warn.mock.calls)).not.toContain(
      "private storage is temporarily unavailable",
    );
    warn.mockRestore();
  });

  it("maps active and inactive exhibition lookup catalogs in one RPC", async () => {
    const { client, rpc } = mockedClient({ data: rawLookups, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(repository.getExhibitionLookups()).resolves.toEqual(
      mappedLookups,
    );
    expect(rpc).toHaveBeenCalledWith("admin_get_exhibition_lookups");
  });

  it("keeps event and editor lookups usable when a legacy RPC omits venues and locations", async () => {
    const { venues: _venues, locations: _locations, ...legacyLookups } = rawLookups;
    const { client } = mockedClient({ data: legacyLookups, error: null });

    await expect(
      new SupabaseAdminExhibitionRepository(client).getExhibitionLookups(),
    ).resolves.toEqual({
      events: mappedLookups.events,
      editors: mappedLookups.editors,
      venues: [],
      locations: [],
      artTerms: null,
    });
  });

  it("rejects malformed nested lookup fields with the exact path", async () => {
    const { client } = mockedClient({
      data: {
        ...rawLookups,
        editors: [{ ...rawLookups.editors[0], is_active: "true" }],
      },
      error: null,
    });

    await expect(
      new SupabaseAdminExhibitionRepository(
        client,
      ).getExhibitionLookups(),
    ).rejects.toMatchObject({
      name: "MalformedAdminExhibitionPayloadError",
      rpcName: "admin_get_exhibition_lookups",
      path: "$.editors[0].is_active",
    } satisfies Partial<MalformedAdminExhibitionPayloadError>);
  });

  it("maps ordered media and uses a stable public URL for its preview", async () => {
    const { client } = mockedClient({ data: [rawMedia], error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(
      repository.listMedia(mappedRecord.id, mappedRecord.workingVersionId),
    ).resolves.toEqual([mappedMedia]);
  });

  it("hydrates ready media with a short-lived signed preview URL", async () => {
    const storageApi = {
      createSignedUploadUrl: vi.fn(),
      uploadToSignedUrl: vi.fn(),
      createSignedUrl: vi.fn(async () => ({
        data: { signedUrl: "https://signed.example.test/cover" },
        error: null,
      })),
    };
    const readyMedia = { ...rawMedia, status: "ready", public_url: null };
    const { client, from } = scriptedClient(
      {
        admin_list_exhibition_media: { data: [readyMedia], error: null },
      },
      storageApi,
    );
    const repository = new SupabaseAdminExhibitionRepository(client);

    const result = await repository.listMedia(
      mappedRecord.id,
      mappedRecord.workingVersionId,
    );

    expect(result[0].previewUrl).toBe("https://signed.example.test/cover");
    expect(from).toHaveBeenCalledWith("exhibition-media");
    expect(storageApi.createSignedUrl).toHaveBeenCalledWith(
      "exhibition-123/cover.png",
      900,
    );
  });

  it("reserves, signs, uploads, finalizes, and attaches an image without upsert", async () => {
    const file = new File(["fake-image"], "cover.png", { type: "image/png" });
    const target = {
      asset_id: rawMedia.asset_id,
      bucket_id: rawMedia.bucket_id,
      object_path: rawMedia.object_path,
      mime_type: file.type,
      byte_size: file.size,
      original_filename: file.name,
      status: "pending_upload",
    };
    const finalized = {
      ...target,
      status: "ready",
      width: null,
      height: null,
      checksum_sha256: checksum,
    };
    const attachedMedia = {
      ...rawMedia,
      status: "ready",
      public_url: null,
      width: null,
      height: null,
      checksum_sha256: checksum,
    };
    const mutation = {
      exhibition: { ...rawRecord, status: "draft", revision: 8 },
      media: [attachedMedia],
    };
    const storageApi = {
      createSignedUploadUrl: vi.fn(async () => ({
        data: {
          signedUrl: "https://storage.example.test/upload",
          path: rawMedia.object_path,
          token: "signed-upload-token",
        },
        error: null,
      })),
      uploadToSignedUrl: vi.fn(async () => ({
        data: { path: rawMedia.object_path, fullPath: rawMedia.object_path },
        error: null,
      })),
      createSignedUrl: vi.fn(async () => ({
        data: { signedUrl: "https://signed.example.test/cover" },
        error: null,
      })),
    };
    const { client, rpc, from } = scriptedClient(
      {
        admin_request_media_upload: { data: target, error: null },
        admin_finalize_media_upload: { data: finalized, error: null },
        admin_attach_exhibition_media: { data: mutation, error: null },
      },
      storageApi,
    );
    const repository = new SupabaseAdminExhibitionRepository(client);

    const result = await repository.uploadAndAttachMedia(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      file,
      "cover",
    );

    expect(result.exhibition.revision).toBe(8);
    expect(result.media[0].previewUrl).toBe("https://signed.example.test/cover");
    expect(rpc).toHaveBeenNthCalledWith(1, "admin_request_media_upload", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_filename: file.name,
      p_mime_type: file.type,
      p_byte_size: file.size,
    });
    expect(rpc).toHaveBeenNthCalledWith(2, "admin_finalize_media_upload", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_asset_id: rawMedia.asset_id,
      p_width: null,
      p_height: null,
      p_checksum_sha256: checksum,
    });
    expect(rpc).toHaveBeenNthCalledWith(3, "admin_attach_exhibition_media", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_asset_id: rawMedia.asset_id,
      p_role: "cover",
    });
    expect(from).toHaveBeenCalledWith("exhibition-media");
    expect(storageApi.createSignedUploadUrl).toHaveBeenCalledWith(
      rawMedia.object_path,
      { upsert: false },
    );
    expect(storageApi.uploadToSignedUrl).toHaveBeenCalledWith(
      rawMedia.object_path,
      "signed-upload-token",
      file,
      { contentType: "image/png", upsert: false },
    );
  });

  it("rejects unsupported or oversized image files before reserving storage", async () => {
    const { client, rpc } = mockedClient({ data: null, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);
    const unsupported = new File(["image"], "cover.gif", {
      type: "image/gif",
    });
    const oversized = new File(
      [new Uint8Array(10 * 1024 * 1024 + 1)],
      "cover.png",
      { type: "image/png" },
    );

    await expect(
      repository.uploadAndAttachMedia(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        unsupported,
        "cover",
      ),
    ).rejects.toThrow("Choose a JPEG, PNG, or WebP image.");
    await expect(
      repository.uploadAndAttachMedia(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        oversized,
        "cover",
      ),
    ).rejects.toThrow("The selected image exceeds the 10 MiB limit.");
    expect(rpc).not.toHaveBeenCalled();
  });

  it("returns a committed media mutation when signed-preview hydration fails", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const readyMedia = { ...rawMedia, status: "ready", public_url: null };
    const committedMutation = {
      exhibition: { ...rawRecord, revision: 8 },
      media: [readyMedia],
    };
    const storageApi = {
      createSignedUploadUrl: vi.fn(),
      uploadToSignedUrl: vi.fn(),
      createSignedUrl: vi.fn(async () => ({
        data: null,
        error: { message: "preview signing is temporarily unavailable" },
      })),
    };
    const { client, rpc } = scriptedClient(
      {
        admin_update_exhibition_media_metadata: {
          data: committedMutation,
          error: null,
        },
      },
      storageApi,
    );
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(
      repository.updateMediaMetadata(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        rawMedia.asset_id,
        {
          altKo: "새 대체 텍스트",
          altEn: "New alternative text",
          credit: "New credit",
          rightsUrl: "https://rights.example.test/new",
        },
      ),
    ).resolves.toEqual({
      exhibition: { ...mappedRecord, revision: 8 },
      media: [
        {
          ...mappedMedia,
          status: "ready",
          publicUrl: null,
          previewUrl: null,
        },
      ],
    });
    expect(rpc).toHaveBeenCalledTimes(1);
    expect(storageApi.createSignedUrl).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledOnce();
    expect(warn).toHaveBeenCalledWith(
      JSON.stringify({
        event: "admin_media_preview_hydration_failed",
        rpc_name: "admin_update_exhibition_media_metadata",
        exhibition_id: mappedRecord.id,
        working_version_id: mappedRecord.workingVersionId,
      }),
    );
    expect(JSON.stringify(warn.mock.calls)).not.toContain(
      "preview signing is temporarily unavailable",
    );
    warn.mockRestore();
  });

  it("still reports media mutation RPC failures", async () => {
    const { client } = scriptedClient({
      admin_update_exhibition_media_metadata: {
        data: null,
        error: {
          code: "40001",
          message: "revision_conflict",
          details: "8",
        },
      },
    });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(
      repository.updateMediaMetadata(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        rawMedia.asset_id,
        {
          altKo: "새 대체 텍스트",
          altEn: "New alternative text",
          credit: "New credit",
          rightsUrl: "https://rights.example.test/new",
        },
      ),
    ).rejects.toMatchObject({
      name: "RevisionConflictError",
      serverRevision: 8,
    });
  });

  it("maps metadata, gallery reorder, and detach mutation commands exactly", async () => {
    const mutation = { exhibition: rawRecord, media: [rawMedia] };
    const { client, rpc } = mockedClient({ data: mutation, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);
    const metadata = {
      altKo: "새 대체 텍스트",
      altEn: "New alternative text",
      credit: "New credit",
      rightsUrl: "https://rights.example.test/new",
    };

    await repository.updateMediaMetadata(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      rawMedia.asset_id,
      metadata,
    );
    await repository.reorderMedia(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      [rawMedia.asset_id],
    );
    await repository.detachMedia(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      rawMedia.asset_id,
    );

    expect(rpc).toHaveBeenNthCalledWith(
      1,
      "admin_update_exhibition_media_metadata",
      {
        p_exhibition_id: mappedRecord.id,
        p_expected_version_id: mappedRecord.workingVersionId,
        p_expected_revision: mappedRecord.revision,
        p_asset_id: rawMedia.asset_id,
        p_alt_ko: metadata.altKo,
        p_alt_en: metadata.altEn,
        p_credit: metadata.credit,
        p_rights_url: metadata.rightsUrl,
      },
    );
    expect(rpc).toHaveBeenNthCalledWith(2, "admin_reorder_exhibition_media", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_ordered_asset_ids: [rawMedia.asset_id],
    });
    expect(rpc).toHaveBeenNthCalledWith(3, "admin_detach_exhibition_media", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_asset_id: rawMedia.asset_id,
    });
  });

  it("rejects malformed media DTOs with the exact field path", async () => {
    const { client } = mockedClient({
      data: [{ ...rawMedia, byte_size: "10" }],
      error: null,
    });

    await expect(
      new SupabaseAdminExhibitionRepository(client).listMedia(
        mappedRecord.id,
        mappedRecord.workingVersionId,
      ),
    ).rejects.toMatchObject({
      name: "MalformedAdminExhibitionPayloadError",
      path: "$[0].byte_size",
    });
  });

  it("creates a draft through the parameterless RPC", async () => {
    const { client, rpc } = mockedClient({ data: rawRecord, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(repository.createDraft()).resolves.toEqual(mappedRecord);
    expect(rpc).toHaveBeenCalledWith("admin_create_exhibition_draft");
  });

  it("serializes a save patch and includes both concurrency tokens", async () => {
    const { client, rpc } = mockedClient({ data: rawRecord, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(
      repository.saveDraft(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        patch,
      ),
    ).resolves.toEqual(mappedRecord);
    expect(rpc).toHaveBeenCalledWith("admin_save_exhibition_draft", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_patch: serializedPatch,
    });
    expect(rpc.mock.calls[0][1]?.p_patch).not.toHaveProperty(
      "working_version_id",
    );
    expect(rpc.mock.calls[0][1]?.p_patch).not.toHaveProperty(
      "cover_image_url",
    );
    expect(rpc.mock.calls[0][1]?.p_patch).not.toHaveProperty("cover_alt_ko");
    expect(rpc.mock.calls[0][1]?.p_patch).not.toHaveProperty("cover_alt_en");
    expect(rpc.mock.calls[0][1]?.p_patch).not.toHaveProperty("image_credit");
  });

  it("sends an empty patch when creating an unchanged media working draft", async () => {
    const { client, rpc } = mockedClient({ data: rawRecord, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await repository.saveDraft(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      {},
    );

    expect(rpc).toHaveBeenCalledWith("admin_save_exhibition_draft", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_patch: {},
    });
  });

  it("publishes the exact working version and revision", async () => {
    const publishedRaw = {
      ...rawRecord,
      published_version_id: rawRecord.working_version_id,
      has_unpublished_changes: false,
    };
    const { client, rpc } = mockedClient({ data: publishedRaw, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await repository.publish(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      "20000000-0000-0000-0000-000000000001",
    );

    expect(rpc).toHaveBeenCalledWith("admin_publish_exhibition", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_request_id: "20000000-0000-0000-0000-000000000001",
    });
  });

  it("archives the exact working version and revision", async () => {
    const { client, rpc } = mockedClient({
      data: { ...rawRecord, status: "archived" },
      error: null,
    });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await repository.archive(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      "20000000-0000-0000-0000-000000000002",
    );

    expect(rpc).toHaveBeenCalledWith("admin_archive_exhibition", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_request_id: "20000000-0000-0000-0000-000000000002",
    });
  });

  it("restores the exact working version and revision", async () => {
    const { client, rpc } = mockedClient({ data: rawRecord, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await repository.restore(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      "20000000-0000-0000-0000-000000000003",
    );

    expect(rpc).toHaveBeenCalledWith("admin_restore_exhibition", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_request_id: "20000000-0000-0000-0000-000000000003",
    });
  });

  it("discards the exact unpublished working version and revision", async () => {
    const publishedRaw = {
      ...rawRecord,
      working_version_id: rawRecord.published_version_id,
      version_number: 3,
      has_unpublished_changes: false,
      status: "published",
    };
    const { client, rpc } = mockedClient({ data: publishedRaw, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await repository.discardDraft(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      "20000000-0000-0000-0000-000000000009",
    );

    expect(rpc).toHaveBeenCalledWith("admin_discard_exhibition_draft", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_request_id: "20000000-0000-0000-0000-000000000009",
    });
  });

  it("deletes the exact never-published draft version and revision", async () => {
    const { client, rpc } = mockedClient({
      data: { id: mappedRecord.id, status: "deleted" },
      error: null,
    });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await expect(
      repository.deleteDraft(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        "20000000-0000-0000-0000-000000000004",
      ),
    ).resolves.toBeUndefined();

    expect(rpc).toHaveBeenCalledWith("admin_delete_exhibition_draft", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_request_id: "20000000-0000-0000-0000-000000000004",
    });
  });

  it.each([
    "only_never_published_drafts_can_be_deleted",
    "draft_delete_requires_media_detach",
    "imported_exhibitions_cannot_be_deleted",
    "draft_delete_has_submission_reference",
    "draft_delete_has_curation_reference",
    "draft_delete_has_launch_kit_reference",
    "draft_delete_has_promotion_reference",
    "draft_delete_has_pending_outbox_event",
  ])("reports %s as a typed deletion refusal", async (reason) => {
    const { client } = mockedClient({
      data: null,
      error: { code: "23503", message: reason },
    });
    const repository = new SupabaseAdminExhibitionRepository(client);

    const error = await repository
      .deleteDraft(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        "20000000-0000-0000-0000-000000000010",
      )
      .catch((thrown: unknown) => thrown);

    expect(error).toBeInstanceOf(DraftDeleteBlockedError);
    expect((error as DraftDeleteBlockedError).reason).toBe(reason);
  });

  it("leaves an unrecognized deletion failure as a generic error", async () => {
    const { client } = mockedClient({
      data: null,
      error: { code: "P0002", message: "exhibition_not_found" },
    });
    const repository = new SupabaseAdminExhibitionRepository(client);

    const error = await repository
      .deleteDraft(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        "20000000-0000-0000-0000-000000000011",
      )
      .catch((thrown: unknown) => thrown);

    expect(error).toBeInstanceOf(Error);
    expect(error).not.toBeInstanceOf(DraftDeleteBlockedError);
  });

  it("maps the promotion queue and sends only the staff schedule command", async () => {
    const rawPromotion = {
      id: "promotion-one",
      launch_kit_id: "launch-one",
      exhibition_id: "between-seasons",
      gallery_id: "gallery-one",
      status: "submitted",
      revision: 1,
      city_ko: "서울",
      city_en: "Seoul",
      region_ko: "용산구",
      region_en: "Yongsan-gu",
      starts_at: null,
      ends_at: null,
      review_notes: "",
      requested_at: "2026-07-31T10:00:00Z",
      reviewed_at: null,
      name_ko: "계절 사이",
      name_en: "Between Seasons",
      venue_name_ko: "아틀리에 한남",
      venue_name_en: "Atelier Hannam",
      closing_date: "2026-09-14",
      gallery_name_ko: "아틀리에 한남",
      gallery_name_en: "Atelier Hannam",
    };
    const listed = mockedClient({ data: [rawPromotion], error: null });
    await expect(new SupabaseAdminExhibitionRepository(listed.client).listLocalPromotions({
      search: "  Between  ",
      status: "submitted",
    })).resolves.toEqual([
      expect.objectContaining({ id: "promotion-one", status: "submitted", cityKo: "서울" }),
    ]);
    expect(listed.rpc).toHaveBeenCalledWith("admin_list_local_promotions", {
      p_search: "Between",
      p_status: "submitted",
    });

    const approved = mockedClient({ data: { ...rawPromotion, status: "approved" }, error: null });
    await new SupabaseAdminExhibitionRepository(approved.client).approveLocalPromotion(
      "promotion-one",
      "2026-08-08T09:00:00.000Z",
      "2026-08-15T09:00:00.000Z",
      "request-one",
    );
    expect(approved.rpc).toHaveBeenCalledWith("admin_approve_local_promotion", {
      p_promotion_id: "promotion-one",
      p_starts_at: "2026-08-08T09:00:00.000Z",
      p_ends_at: "2026-08-15T09:00:00.000Z",
      p_request_id: "request-one",
    });
  });

  it("rejects malformed RPC collection and record payloads clearly", async () => {
    const malformedList = mockedClient({ data: rawRecord, error: null });
    const malformedRecord = mockedClient({
      data: { ...rawRecord, revision: "7" },
      error: null,
    });

    await expect(
      new SupabaseAdminExhibitionRepository(malformedList.client).list({
        search: "",
        status: "All",
      }),
    ).rejects.toThrow(
      "admin_list_exhibitions returned malformed data at $: expected an array, received object.",
    );
    await expect(
      new SupabaseAdminExhibitionRepository(
        malformedRecord.client,
      ).createDraft(),
    ).rejects.toMatchObject({
      name: "MalformedAdminExhibitionPayloadError",
      rpcName: "admin_create_exhibition_draft",
      path: "$.revision",
    } satisfies Partial<MalformedAdminExhibitionPayloadError>);
  });

  it("preserves ordinary Supabase error diagnostics", async () => {
    const { client } = mockedClient({
      data: null,
      error: {
        code: "42501",
        message: "permission denied",
        details: "staff role required",
        hint: "Sign in with an active staff account.",
      },
    });

    await expect(
      new SupabaseAdminExhibitionRepository(client).createDraft(),
    ).rejects.toThrow(
      "admin_create_exhibition_draft failed [42501]: permission denied Details: staff role required Hint: Sign in with an active staff account.",
    );
  });

  it("maps a serialization revision conflict to RevisionConflictError", async () => {
    const { client } = mockedClient({
      data: null,
      error: {
        code: "40001",
        message: "revision_conflict",
        details: "12",
      },
    });

    const error = await new SupabaseAdminExhibitionRepository(client)
      .saveDraft(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        patch,
      )
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(RevisionConflictError);
    expect(error).toMatchObject({
      name: "RevisionConflictError",
      serverRevision: 12,
    });
  });

  it("maps a PostgREST PT409 revision conflict to RevisionConflictError", async () => {
    const { client } = mockedClient({
      data: null,
      error: {
        code: "PT409",
        message: "revision_conflict",
        details: "12",
      },
    });

    await expect(
      new SupabaseAdminExhibitionRepository(client).saveDraft(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        patch,
      ),
    ).rejects.toMatchObject({
      name: "RevisionConflictError",
      serverRevision: 12,
    });
  });

  it("does not invent a revision when conflict details are malformed", async () => {
    const { client } = mockedClient({
      data: null,
      error: {
        code: "40001",
        message: "revision_conflict",
        details: "working version changed",
      },
    });

    await expect(
      new SupabaseAdminExhibitionRepository(client).publish(
        mappedRecord.id,
        mappedRecord.workingVersionId,
        mappedRecord.revision,
        "20000000-0000-0000-0000-000000000004",
      ),
    ).rejects.toThrow(
      "admin_publish_exhibition reported revision_conflict without a valid positive integer server revision in error.details.",
    );
  });
});

describe("SupabaseAdminExhibitionRepository art metadata", () => {
  const canonicalArtistId = "71000000-0000-4000-8000-000000000001";
  const metadataRecord = {
    ...rawRecord,
    artists: [
      {
        id: canonicalArtistId,
        name_ko: "김민정",
        name_en: "Minjung Kim",
      },
      {
        id: null,
        name_ko: "새 작가",
        name_en: "New Artist",
      },
    ],
    art_terms: [
      {
        id: "photography",
        category: "medium",
        name_ko: "사진",
        name_en: "Photography",
      },
    ],
  };

  it("distinguishes unsupported metadata from supported ordered metadata", async () => {
    const rpc = vi.fn()
      .mockResolvedValueOnce({ data: [rawRecord], error: null })
      .mockResolvedValueOnce({ data: [metadataRecord], error: null });
    const repository = new SupabaseAdminExhibitionRepository({ rpc } as unknown as SupabaseClient);

    await expect(repository.list({ search: "", status: "All" }))
      .resolves.toEqual([expect.objectContaining({ artMetadata: null })]);
    await expect(repository.list({ search: "", status: "All" }))
      .resolves.toEqual([expect.objectContaining({
        artMetadata: {
          artists: [
            { id: canonicalArtistId, nameKo: "김민정", nameEn: "Minjung Kim" },
            { id: null, nameKo: "새 작가", nameEn: "New Artist" },
          ],
          terms: [
            {
              id: "photography",
              category: "medium",
              nameKo: "사진",
              nameEn: "Photography",
            },
          ],
        },
      })]);
  });

  it("preserves supported-empty metadata instead of treating it as legacy", async () => {
    const { client } = mockedClient({
      data: [{ ...rawRecord, artists: [], art_terms: [] }],
      error: null,
    });
    await expect(new SupabaseAdminExhibitionRepository(client).list({
      search: "",
      status: "All",
    })).resolves.toEqual([
      expect.objectContaining({ artMetadata: { artists: [], terms: [] } }),
    ]);
  });

  it("serializes metadata only when the supported patch is present", async () => {
    const { client, rpc } = mockedClient({ data: metadataRecord, error: null });
    const repository = new SupabaseAdminExhibitionRepository(client);

    await repository.saveDraft(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      {
        artMetadata: {
          artists: [
            { id: canonicalArtistId, nameKo: "ignored", nameEn: "ignored" },
            { id: null, nameKo: "새 작가", nameEn: "New Artist" },
          ],
          terms: [{
            id: "photography",
            category: "medium",
            nameKo: "사진",
            nameEn: "Photography",
          }],
        },
      },
    );

    expect(rpc).toHaveBeenCalledWith("admin_save_exhibition_draft", {
      p_exhibition_id: mappedRecord.id,
      p_expected_version_id: mappedRecord.workingVersionId,
      p_expected_revision: mappedRecord.revision,
      p_patch: {
        artists: [
          { id: canonicalArtistId, name_ko: "ignored", name_en: "ignored" },
          { id: null, name_ko: "새 작가", name_en: "New Artist" },
        ],
        art_term_ids: ["photography"],
      },
    });

    rpc.mockClear();
    await repository.saveDraft(
      mappedRecord.id,
      mappedRecord.workingVersionId,
      mappedRecord.revision,
      {},
    );
    expect(rpc.mock.calls[0][1]?.p_patch).toEqual({});
  });

  it("uses bounded artist search and idempotent creation RPCs", async () => {
    const rpc = vi.fn()
      .mockResolvedValueOnce({
        data: [{ id: canonicalArtistId, name_ko: "김민정", name_en: "Minjung Kim" }],
        error: null,
      })
      .mockResolvedValueOnce({
        data: { id: canonicalArtistId, name_ko: "김민정", name_en: "Minjung Kim" },
        error: null,
      });
    const repository = new SupabaseAdminExhibitionRepository({ rpc } as unknown as SupabaseClient);

    await expect(repository.searchArtists("  Kim  ")).resolves.toEqual([
      { id: canonicalArtistId, nameKo: "김민정", nameEn: "Minjung Kim" },
    ]);
    await expect(repository.createArtist("김민정", "Minjung Kim", "request-one"))
      .resolves.toEqual({ id: canonicalArtistId, nameKo: "김민정", nameEn: "Minjung Kim" });
    expect(rpc).toHaveBeenNthCalledWith(1, "admin_search_artists", {
      p_query: "Kim",
      p_limit: 20,
    });
    expect(rpc).toHaveBeenNthCalledWith(2, "admin_create_artist", {
      p_name_ko: "김민정",
      p_name_en: "Minjung Kim",
      p_request_id: "request-one",
    });
  });

  it("maps the controlled term catalogue from Admin lookups", async () => {
    const { client } = mockedClient({
      data: {
        ...rawLookups,
        art_terms: metadataRecord.art_terms,
      },
      error: null,
    });
    await expect(new SupabaseAdminExhibitionRepository(client).getExhibitionLookups())
      .resolves.toEqual(expect.objectContaining({
        artTerms: [{
          id: "photography",
          category: "medium",
          nameKo: "사진",
          nameEn: "Photography",
        }],
      }));
  });

  it("rejects duplicate or malformed metadata responses", async () => {
    const { client } = mockedClient({
      data: [{
        ...metadataRecord,
        artists: [metadataRecord.artists[0], metadataRecord.artists[0]],
      }],
      error: null,
    });

    await expect(new SupabaseAdminExhibitionRepository(client).list({
      search: "",
      status: "All",
    })).rejects.toMatchObject({
      name: "MalformedAdminExhibitionPayloadError",
      path: "$[0].artists",
    });
  });
});
