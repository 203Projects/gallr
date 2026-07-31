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
      p_patch: expect.objectContaining({ name_en: "Notes, Revised", opening_date: "2026-09-02" }),
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

  it("starts Checkout through the authenticated Edge function without accepting a client price", async () => {
    const invoke = vi.fn().mockResolvedValue({
      data: { active: false, url: "https://checkout.stripe.com/c/pay/test" },
      error: null,
    });
    const repository = new SupabaseOwnerRepository({ rpc: vi.fn(), functions: { invoke } });

    await expect(repository.startLaunchCheckout("exhibition-one")).resolves.toEqual({
      active: false,
      url: "https://checkout.stripe.com/c/pay/test",
      launchKitId: undefined,
    });
    expect(invoke).toHaveBeenCalledWith("create-launch-checkout", {
      body: { exhibition_id: "exhibition-one" },
    });
  });

  it("maps Launch Kit and guest RPCs and sends bounded guest-list arguments", async () => {
    const kitDto = {
      id: "launch-one", exhibition_id: "exhibition-one", status: "active", revision: 2,
      public_token: "public-one", name_ko: "작은 방의 기록", name_en: "Notes from a Small Room",
      reception_date: "2026-09-02", reception_start_time: "19:00",
      rsvp_count: 1, guest_count: 2, checked_in_count: 0,
      updated_at: "2026-07-31T10:00:00Z",
    };
    const guestDto = {
      id: "guest-one", launch_kit_id: "launch-one", name: "Maya Chen",
      email: "maya@example.test", party_size: 2, status: "going",
      checked_in_at: null, created_at: "2026-07-31T10:00:00Z",
    };
    const rpc = vi.fn()
      .mockResolvedValueOnce({ data: [kitDto], error: null })
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
        id: "launch-one", exhibition_id: "exhibition-one", status: "active", revision: 3,
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
