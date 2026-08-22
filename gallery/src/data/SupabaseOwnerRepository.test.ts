import { SupabaseOwnerRepository } from "./SupabaseOwnerRepository";

function clientWith(
  rpc: (name: string, args?: Record<string, unknown>) => Promise<{
    data: unknown;
    error: { message?: string } | null;
  }>,
) {
  return { rpc };
}

const exhibitionDto = {
  id: "exhibition-one",
  working_version_id: "version-one",
  version_number: 1,
  revision: 3,
  owner_status: "draft",
  review_notes: "",
  name_ko: "작은 방의 기록",
  name_en: "Notes from a Small Room",
  venue_name_ko: "갤러리 알파",
  venue_name_en: "Gallery Alpha",
  city_ko: "서울",
  city_en: "Seoul",
  region_ko: "종로구",
  region_en: "Jongno-gu",
  address_ko: "서울특별시 종로구 삼청로 12",
  address_en: "",
  latitude: 37.582,
  longitude: 126.981,
  opening_date: "2026-09-02",
  closing_date: "2026-11-08",
  description_ko: "작은 방에서 시작된 기록입니다.",
  description_en: "",
  hours: "Tue-Sun 11:00-18:00",
  contact: "",
  reception_date: "",
  reception_start_time: "",
  ticket_url: "",
  updated_at: "2026-07-31T10:00:00Z",
  page_loads_30d: 0,
  page_loads_all_time: 0,
  cover: null,
};

const galleryInfoDto = {
  gallery_id: "gallery-alpha",
  revision: 3,
  name_ko: "알파 갤러리",
  name_en: "Gallery Alpha",
  venue_name_ko: "알파 갤러리",
  venue_name_en: "Gallery Alpha",
  city_ko: "서울",
  city_en: "Seoul",
  region_ko: "종로구",
  region_en: "Jongno-gu",
  address_ko: "서울특별시 종로구 삼청로 12",
  address_en: "12 Samcheong-ro, Jongno-gu, Seoul",
  latitude: 37.582,
  longitude: 126.981,
  hours: "Tue-Sun 11:00-18:00",
  contact: "hello@alpha.example",
  updated_at: "2026-08-05T10:00:00Z",
};

const launchKitDto = {
  id: "launch-one",
  exhibition_id: "exhibition-one",
  status: "active",
  entitlement_source: "free_beta",
  revision: 2,
  public_token: "00000000-0000-4000-8000-000000000001",
  name_ko: "작은 방의 기록",
  name_en: "Notes from a Small Room",
  reception_date: "2026-09-02",
  reception_start_time: "19:00",
  rsvp_count: 1,
  guest_count: 2,
  checked_in_count: 0,
  updated_at: "2026-07-31T10:00:00Z",
};

describe("SupabaseOwnerRepository", () => {
  it("maps the owner access DTO without exposing claim evidence", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: {
        membership: { role: "owner", status: "pending" },
        gallery: {
          id: "gallery-alpha",
          name_ko: "알파 갤러리",
          name_en: "Gallery Alpha",
          status: "active",
          address_ko: "서울",
          address_en: "Seoul",
        },
      },
      error: null,
    });
    const repository = new SupabaseOwnerRepository(clientWith(rpc));

    await expect(repository.currentAccess()).resolves.toEqual({
      membership: { role: "owner", status: "pending" },
      gallery: {
        id: "gallery-alpha",
        nameKo: "알파 갤러리",
        nameEn: "Gallery Alpha",
        status: "active",
        addressKo: "서울",
        addressEn: "Seoul",
      },
    });
    expect(rpc).toHaveBeenCalledWith("owner_current_access");
  });

  it("sends only the typed claim fields and a generated request ID", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: {
        membership: { role: "owner", status: "pending" },
        gallery: {
          id: "gallery-alpha",
          name_ko: "알파 갤러리",
          name_en: "",
          status: "active",
          address_ko: "",
          address_en: "",
        },
      },
      error: null,
    });
    const repository = new SupabaseOwnerRepository(clientWith(rpc), () => "request-1");

    await repository.claimExistingGallery({
      galleryId: "gallery-alpha",
      websiteUrl: "https://alpha.example.test",
      socialUrl: "",
      claimNote: "",
    });

    expect(rpc).toHaveBeenCalledWith("owner_claim_existing_gallery", {
      p_gallery_id: "gallery-alpha",
      p_website_url: "https://alpha.example.test",
      p_social_url: "",
      p_claim_note: "",
      p_request_id: "request-1",
    });
  });

  it("rejects malformed access payloads instead of guessing", async () => {
    const repository = new SupabaseOwnerRepository(
      clientWith(vi.fn().mockResolvedValue({ data: { role: "owner" }, error: null })),
    );

    await expect(repository.currentAccess()).rejects.toThrow(
      "Owner access response was invalid.",
    );
  });

  it("maps and saves the revisioned Gallery Info allowlist", async () => {
    const rpc = vi.fn()
      .mockResolvedValueOnce({ data: galleryInfoDto, error: null })
      .mockResolvedValueOnce({ data: { ...galleryInfoDto, revision: 4 }, error: null });
    const repository = new SupabaseOwnerRepository(clientWith(rpc));

    const info = await repository.getGalleryInfo();
    expect(info).toEqual(expect.objectContaining({
      galleryId: "gallery-alpha",
      revision: 3,
      venueNameEn: "Gallery Alpha",
      latitude: 37.582,
      longitude: 126.981,
    }));
    await expect(repository.saveGalleryInfo(3, {
      nameKo: info.nameKo,
      nameEn: info.nameEn,
      venueNameKo: info.venueNameKo,
      venueNameEn: info.venueNameEn,
      cityKo: info.cityKo,
      cityEn: info.cityEn,
      regionKo: info.regionKo,
      regionEn: info.regionEn,
      addressKo: info.addressKo,
      addressEn: info.addressEn,
      latitude: info.latitude,
      longitude: info.longitude,
      hours: "Wed-Mon 12:00-19:00",
      contact: info.contact,
    })).resolves.toEqual(expect.objectContaining({ revision: 4 }));

    expect(rpc).toHaveBeenNthCalledWith(1, "owner_get_gallery_info");
    expect(rpc).toHaveBeenNthCalledWith(2, "owner_save_gallery_info", {
      p_expected_revision: 3,
      p_patch: expect.objectContaining({
        name_ko: "알파 갤러리",
        address_ko: "서울특별시 종로구 삼청로 12",
        latitude: 37.582,
        longitude: 126.981,
        hours: "Wed-Mon 12:00-19:00",
      }),
    });
  });

  it("rejects malformed Gallery Info and geocoder payloads", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: { ...galleryInfoDto, latitude: "37.582" },
      error: null,
    });
    const invoke = vi.fn().mockResolvedValue({
      data: { candidates: [{
        road_address: "서울특별시 종로구 삼청로 12",
        jibun_address: "",
        english_address: "12 Samcheong-ro, Jongno-gu, Seoul",
        city_ko: "서울",
        city_en: "Seoul",
        region_ko: "종로구",
        region_en: "Jongno-gu",
        latitude: "not-a-coordinate",
        longitude: "126.981",
      }] },
      error: null,
    });
    const repository = new SupabaseOwnerRepository({ rpc, functions: { invoke } });

    await expect(repository.getGalleryInfo()).rejects.toThrow(
      "Gallery Info response was invalid.",
    );
    await expect(repository.searchGalleryAddress("서울 종로구 삼청로 12"))
      .rejects.toThrow("Geocoding response was invalid.");
  });

  it("returns at most three bounded server geocoding candidates", async () => {
    const invoke = vi.fn().mockResolvedValue({
      data: { candidates: [{
        road_address: "서울특별시 종로구 삼청로 12",
        jibun_address: "서울특별시 종로구 삼청동 1-1",
        english_address: "12 Samcheong-ro, Jongno-gu, Seoul",
        city_ko: "서울",
        city_en: "Seoul",
        region_ko: "종로구",
        region_en: "Jongno-gu",
        latitude: "37.582",
        longitude: "126.981",
      }] },
      error: null,
    });
    const repository = new SupabaseOwnerRepository({
      rpc: vi.fn(),
      functions: { invoke },
    });

    await expect(repository.searchGalleryAddress(" 서울 종로구 삼청로 12 "))
      .resolves.toEqual([expect.objectContaining({
        roadAddress: "서울특별시 종로구 삼청로 12",
        latitude: 37.582,
        longitude: 126.981,
      })]);
    expect(invoke).toHaveBeenCalledWith("geocode-address", {
      body: { address: "서울 종로구 삼청로 12" },
      signal: expect.any(AbortSignal),
    });
  });

  it("maps canonical owner exhibition rows and rejects malformed lifecycle values", async () => {
    const rpc = vi.fn()
      .mockResolvedValueOnce({ data: [exhibitionDto], error: null })
      .mockResolvedValueOnce({ data: [{ ...exhibitionDto, owner_status: "reviewing" }], error: null });
    const repository = new SupabaseOwnerRepository(clientWith(rpc));

    await expect(repository.listExhibitions()).resolves.toEqual([
      expect.objectContaining({
        id: "exhibition-one",
        workingVersionId: "version-one",
        ownerStatus: "draft",
        nameEn: "Notes from a Small Room",
        pageLoads30d: 0,
        pageLoadsAllTime: 0,
      }),
    ]);
    await expect(repository.listExhibitions()).rejects.toThrow(
      "Owner exhibition response was invalid.",
    );
  });

  it("hides an exhibition with its displayed version and optimistic revision", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: { id: "exhibition-one", hidden: true },
      error: null,
    });
    const repository = new SupabaseOwnerRepository(clientWith(rpc));

    await expect(repository.hideExhibition("exhibition-one", "version-one", 3))
      .resolves.toBeUndefined();
    expect(rpc).toHaveBeenCalledWith("owner_hide_exhibition", {
      p_exhibition_id: "exhibition-one",
      p_expected_version_id: "version-one",
      p_expected_revision: 3,
    });
  });

  it("maps nonnegative impact totals and rejects incoherent counts", async () => {
    const rpc = vi.fn()
      .mockResolvedValueOnce({
        data: [{ ...exhibitionDto, owner_status: "published", page_loads_30d: 12, page_loads_all_time: 41 }],
        error: null,
      })
      .mockResolvedValueOnce({
        data: [{ ...exhibitionDto, page_loads_30d: 5, page_loads_all_time: 4 }],
        error: null,
      });
    const repository = new SupabaseOwnerRepository(clientWith(rpc));

    await expect(repository.listExhibitions()).resolves.toEqual([
      expect.objectContaining({ pageLoads30d: 12, pageLoadsAllTime: 41 }),
    ]);
    await expect(repository.listExhibitions()).rejects.toThrow(
      "Owner exhibition response was invalid.",
    );
  });

  it("maps editable fields to the allowlisted save patch", async () => {
    const rpc = vi.fn().mockResolvedValue({ data: { ...exhibitionDto, revision: 4 }, error: null });
    const repository = new SupabaseOwnerRepository(clientWith(rpc));

    await repository.saveExhibitionDraft("exhibition-one", "version-one", 3, {
      nameKo: "작은 방의 기록",
      nameEn: "Notes, Revised",
      venueNameKo: "갤러리 알파",
      venueNameEn: "Gallery Alpha",
      cityKo: "서울",
      cityEn: "Seoul",
      regionKo: "종로구",
      regionEn: "Jongno-gu",
      addressKo: "서울특별시 종로구 삼청로 12",
      addressEn: "",
      latitude: 37.582,
      longitude: 126.981,
      openingDate: "2026-09-02",
      closingDate: "2026-11-08",
      descriptionKo: "작은 방에서 시작된 기록입니다.",
      descriptionEn: "",
      hours: "Tue-Sun 11:00-18:00",
      contact: "",
      receptionDate: "",
      receptionStartTime: "",
      ticketUrl: "",
    });

    expect(rpc).toHaveBeenCalledWith("owner_save_exhibition_draft", {
      p_exhibition_id: "exhibition-one",
      p_expected_version_id: "version-one",
      p_expected_revision: 3,
      p_patch: expect.objectContaining({
        name_en: "Notes, Revised",
        opening_date: "2026-09-02",
        latitude: 37.582,
        longitude: 126.981,
      }),
    });
  });

  it("reserves, uploads, and completes a private owner cover", async () => {
    const rpc = vi.fn()
      .mockResolvedValueOnce({
        data: {
          asset_id: "asset-one",
          bucket_id: "exhibition-media",
          object_path: "owner-drafts/user/asset-one/original.jpg",
          mime_type: "image/jpeg",
          byte_size: 5,
        },
        error: null,
      })
      .mockResolvedValueOnce({
        data: {
          ...exhibitionDto,
          revision: 4,
          cover: {
            asset_id: "asset-one",
            status: "ready",
            bucket_id: "exhibition-media",
            object_path: "owner-drafts/user/asset-one/original.jpg",
            public_url: null,
            mime_type: "image/jpeg",
            byte_size: 5,
            original_filename: "cover.jpg",
          },
        },
        error: null,
      });
    const upload = vi.fn().mockResolvedValue({ data: {}, error: null });
    const createSignedUrl = vi.fn().mockResolvedValue({
      data: { signedUrl: "https://signed.example.test/cover" },
      error: null,
    });
    const client = {
      rpc,
      storage: { from: vi.fn().mockReturnValue({ upload, createSignedUrl }) },
    };
    const repository = new SupabaseOwnerRepository(client);
    const file = new File(["cover"], "cover.jpg", { type: "image/jpeg" });

    await expect(repository.uploadCover("exhibition-one", "version-one", 3, file))
      .resolves.toEqual(expect.objectContaining({
        revision: 4,
        cover: expect.objectContaining({ previewUrl: "https://signed.example.test/cover" }),
      }));
    expect(upload).toHaveBeenCalledWith(
      "owner-drafts/user/asset-one/original.jpg",
      file,
      { contentType: "image/jpeg", upsert: false },
    );
    expect(rpc).toHaveBeenLastCalledWith("owner_complete_cover_upload", {
      p_exhibition_id: "exhibition-one",
      p_expected_version_id: "version-one",
      p_expected_revision: 3,
      p_asset_id: "asset-one",
    });
  });

  it("activates a free Launch Kit through the authenticated owner RPC", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: launchKitDto,
      error: null,
    });
    const repository = new SupabaseOwnerRepository(clientWith(rpc), () => "request-activate");

    await expect(repository.activateLaunchKit("exhibition-one")).resolves.toEqual(
      expect.objectContaining({
        id: "launch-one",
        exhibitionId: "exhibition-one",
        status: "active",
        entitlementSource: "free_beta",
        publicToken: "00000000-0000-4000-8000-000000000001",
      }),
    );
    expect(rpc).toHaveBeenCalledWith("owner_activate_launch_kit", {
      p_exhibition_id: "exhibition-one",
      p_request_id: "request-activate",
    });
  });

  it("rejects a malformed free Launch Kit activation response", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: { ...launchKitDto, entitlement_source: "complimentary" },
      error: null,
    });
    const repository = new SupabaseOwnerRepository(clientWith(rpc), () => "request-invalid");

    await expect(repository.activateLaunchKit("exhibition-one"))
      .rejects.toThrow("Launch Kit response was invalid.");
  });

  it("preserves a validated paid entitlement source for later R4 gating", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: [{
        ...launchKitDto,
        id: "launch-paid",
        entitlement_source: "paid",
      }],
      error: null,
    });
    const repository = new SupabaseOwnerRepository(clientWith(rpc));

    await expect(repository.listLaunchKits()).resolves.toEqual([
      expect.objectContaining({ id: "launch-paid", entitlementSource: "paid" }),
    ]);
  });

  it("maps Launch Kit and guest RPCs and sends bounded guest-list arguments", async () => {
    const guestDto = {
      id: "guest-one", launch_kit_id: "launch-one", name: "Maya Chen",
      email: "maya@example.test", party_size: 2, status: "going",
      checked_in_at: null, created_at: "2026-07-31T10:00:00Z",
    };
    const rpc = vi.fn()
      .mockResolvedValueOnce({ data: [launchKitDto], error: null })
      .mockResolvedValueOnce({ data: [guestDto], error: null });
    const repository = new SupabaseOwnerRepository(clientWith(rpc));

    await expect(repository.listLaunchKits()).resolves.toEqual([
      expect.objectContaining({ id: "launch-one", guestCount: 2 }),
    ]);
    await expect(repository.listLaunchGuests("launch-one")).resolves.toEqual({
      records: [expect.objectContaining({ id: "guest-one", partySize: 2, status: "going" })],
      nextCursor: null,
    });
    expect(rpc).toHaveBeenLastCalledWith("owner_list_launch_guests", {
      p_launch_kit_id: "launch-one",
      p_query: "",
      p_status: "all",
      p_after_created_at: null,
      p_after_id: null,
      p_limit: 50,
    });
  });

  it("rotates an RSVP token through an idempotent owner command", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: {
        id: "launch-one", exhibition_id: "exhibition-one", status: "active",
        entitlement_source: "free_beta", revision: 3,
        public_token: "public-two", name_ko: "작은 방의 기록", name_en: "",
        reception_date: "2026-09-02", reception_start_time: "19:00",
        rsvp_count: 0, guest_count: 0, checked_in_count: 0,
        updated_at: "2026-07-31T10:00:00Z",
      },
      error: null,
    });
    const repository = new SupabaseOwnerRepository(clientWith(rpc), () => "request-rotate");

    await expect(repository.rotateLaunchRsvpToken("launch-one")).resolves.toEqual(
      expect.objectContaining({ publicToken: "public-two", revision: 3 }),
    );
    expect(rpc).toHaveBeenCalledWith("owner_rotate_launch_rsvp_token", {
      p_launch_kit_id: "launch-one",
      p_request_id: "request-rotate",
    });
  });

  it("maps and requests the owner promotion without client targeting fields", async () => {
    const dto = {
      id: "promotion-one", launch_kit_id: "launch-one", exhibition_id: "exhibition-one",
      status: "submitted", revision: 1, city_ko: "서울", city_en: "Seoul",
      region_ko: "용산구", region_en: "Yongsan-gu", starts_at: null, ends_at: null,
      review_notes: "", requested_at: "2026-07-31T10:00:00Z",
    };
    const rpc = vi.fn().mockResolvedValue({ data: [dto], error: null });
    const repository = new SupabaseOwnerRepository(clientWith(rpc), () => "request-promotion");

    await expect(repository.listLocalPromotions()).resolves.toEqual([
      expect.objectContaining({ id: "promotion-one", status: "submitted", regionKo: "용산구" }),
    ]);
    rpc.mockResolvedValueOnce({ data: dto, error: null });
    await expect(repository.requestLocalPromotion("launch-one")).resolves.toEqual(
      expect.objectContaining({ launchKitId: "launch-one" }),
    );
    expect(rpc).toHaveBeenLastCalledWith("owner_request_local_promotion", {
      p_launch_kit_id: "launch-one",
      p_request_id: "request-promotion",
    });
  });
});
