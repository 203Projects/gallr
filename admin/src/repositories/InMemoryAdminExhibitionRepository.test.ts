import { describe, expect, it } from "vitest";
import {
  getAdminExhibitionValidation,
  getPublishReadiness,
} from "../domain";
import { InMemoryAdminExhibitionRepository } from "./InMemoryAdminExhibitionRepository";

describe("InMemoryAdminExhibitionRepository exhibition references", () => {
  it("returns deterministic active and inactive catalogs as defensive copies", async () => {
    const repository = new InMemoryAdminExhibitionRepository();

    const first = await repository.getExhibitionLookups();
    first.events[0].nameKo = "mutated";
    const second = await repository.getExhibitionLookups();

    expect(second.events.map((event) => event.isActive)).toEqual([true, false]);
    expect(second.events[0].nameKo).toBe("한남 새터데이즈");
    expect(second.editors.map((editor) => editor.isActive)).toEqual([
      true,
      false,
    ]);
  });

  it("keeps incomplete draft locations saveable but not publishable", async () => {
    const draft = await new InMemoryAdminExhibitionRepository().createDraft();

    expect(draft).toMatchObject({
      latitude: "",
      longitude: "",
      eventId: "",
      editorId: "",
      ticketUrl: "",
      receptionEndTime: "",
      isHomepageFeatured: true,
    });
    expect(getAdminExhibitionValidation(draft)).toEqual({
      coordinateError: null,
      ticketUrlError: null,
      isValid: true,
    });
    expect(getPublishReadiness(draft)).toMatchObject({
      locationComplete: false,
    });

    expect(
      getPublishReadiness({
        ...draft,
        addressKo: "서울 용산구 한남대로 28",
        latitude: "37.5344",
        longitude: "127.0005",
      }),
    ).toMatchObject({ locationComplete: true });
  });

  it("requires bilingual canonical location labels before publication", async () => {
    const draft = await new InMemoryAdminExhibitionRepository().createDraft();

    expect(
      getPublishReadiness({
        ...draft,
        venueNameKo: "아트스페이스",
        cityKo: "서울",
        cityEn: "",
        regionKo: "용산구",
        regionEn: "",
      }),
    ).toMatchObject({ venueComplete: false });
    expect(
      getPublishReadiness({
        ...draft,
        venueNameKo: "아트스페이스",
        cityKo: "서울",
        cityEn: "Seoul",
        regionKo: "용산구",
        regionEn: "Yongsan-gu",
      }),
    ).toMatchObject({ venueComplete: true });
  });

  it("permanently deletes only a never-published draft", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    const draft = (await repository.list({ search: "", status: "Draft" }))[0];

    await repository.deleteDraft(
      draft.id,
      draft.workingVersionId,
      draft.revision,
      "90000000-0000-0000-0000-000000000001",
    );

    expect(
      await repository.list({ search: draft.id, status: "All" }),
    ).toEqual([]);

    const published = (
      await repository.list({ search: "", status: "Published" })
    )[0];
    await expect(
      repository.deleteDraft(
        published.id,
        published.workingVersionId,
        published.revision,
        "90000000-0000-0000-0000-000000000002",
      ),
    ).rejects.toThrow("Only never-published drafts can be deleted permanently.");
  });

  it("discards an unpublished working version and restores the published snapshot", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    const draft = (
      await repository.list({ search: "기억의 표면", status: "Draft" })
    )[0];

    expect(draft).toMatchObject({
      status: "Draft",
      hasUnpublishedChanges: true,
      publishedVersionId: "10000000-0000-0000-0000-000000000013",
    });

    const restored = await repository.discardDraft(
      draft.id,
      draft.workingVersionId,
      draft.revision,
      "90000000-0000-0000-0000-000000000010",
    );

    expect(restored).toMatchObject({
      status: "Published",
      workingVersionId: draft.publishedVersionId,
      publishedVersionId: draft.publishedVersionId,
      hasUnpublishedChanges: false,
    });
    expect(restored.versionNumber).toBe(draft.versionNumber - 1);
    expect(
      await repository.list({ search: draft.id, status: "Published" }),
    ).toEqual([restored]);
  });

  it("accepts an owner submission into its existing canonical draft", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    const before = await repository.list({ search: "", status: "All" });
    const ownerSubmission = (await repository.listSubmissions({ search: "owner@whiteroom", status: "all" }))[0];

    const accepted = await repository.acceptSubmission(
      ownerSubmission.id,
      "90000000-0000-0000-0000-000000000003",
    );

    expect(accepted.exhibition.id).toBe(ownerSubmission.ownerExhibitionId);
    expect(accepted.submission.acceptedExhibitionId).toBe(ownerSubmission.ownerExhibitionId);
    expect(await repository.list({ search: "", status: "All" })).toHaveLength(before.length);
  });
});

describe("admin exhibition reference-field validation", () => {
  it("requires a complete in-range coordinate pair", async () => {
    const draft = await new InMemoryAdminExhibitionRepository().createDraft();

    expect(
      getAdminExhibitionValidation({ ...draft, latitude: "37.5344" }),
    ).toMatchObject({
      coordinateError:
        "Add both latitude and longitude, or leave both blank.",
      isValid: false,
    });
    expect(
      getAdminExhibitionValidation({
        ...draft,
        latitude: "91",
        longitude: "181",
      }),
    ).toMatchObject({
      coordinateError:
        "Latitude must be between -90 and 90, and longitude between -180 and 180.",
      isValid: false,
    });
    expect(
      getAdminExhibitionValidation({
        ...draft,
        latitude: "37.5344",
        longitude: "127.0005",
      }),
    ).toMatchObject({ coordinateError: null, isValid: true });
  });

  it("accepts only optional absolute HTTP(S) ticket URLs", async () => {
    const draft = await new InMemoryAdminExhibitionRepository().createDraft();

    expect(
      getAdminExhibitionValidation({
        ...draft,
        ticketUrl: "tickets.example.test/exhibition",
      }),
    ).toMatchObject({
      ticketUrlError: "Enter a complete http:// or https:// URL.",
      isValid: false,
    });
    expect(
      getAdminExhibitionValidation({
        ...draft,
        ticketUrl: "https://tickets.example.test/exhibition",
      }),
    ).toMatchObject({ ticketUrlError: null, isValid: true });
  });
});

describe("InMemoryAdminExhibitionRepository media ordering", () => {
  it("appends new gallery images in upload order", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    const draft = (await repository.list({ search: "", status: "Draft" }))[0];

    const first = await repository.uploadAndAttachMedia(
      draft.id,
      draft.workingVersionId,
      draft.revision,
      new File(["first"], "first.png", { type: "image/png" }),
      "gallery",
    );
    const second = await repository.uploadAndAttachMedia(
      first.exhibition.id,
      first.exhibition.workingVersionId,
      first.exhibition.revision,
      new File(["second"], "second.png", { type: "image/png" }),
      "gallery",
    );

    expect(second.media.map((asset) => asset.originalFilename)).toEqual([
      "first.png",
      "second.png",
    ]);
    expect(second.media.map((asset) => asset.sortOrder)).toEqual([1, 2]);
  });
});

describe("InMemoryAdminExhibitionRepository list filters", () => {
  it("narrows the list to exhibitions without a cover image", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    const everything = await repository.list({ search: "", status: "All" });

    const missingCover = await repository.list({
      search: "",
      status: "All",
      missingCoverOnly: true,
    });

    expect(everything.some((record) => record.coverImageUrl)).toBe(true);
    expect(missingCover.length).toBeGreaterThan(0);
    expect(missingCover.length).toBeLessThan(everything.length);
    expect(missingCover.every((record) => !record.coverImageUrl)).toBe(true);
  });

  it("combines the missing-cover filter with the publish-state filter", async () => {
    const repository = new InMemoryAdminExhibitionRepository();

    const published = await repository.list({
      search: "",
      status: "Published",
      missingCoverOnly: true,
    });

    expect(published.length).toBeGreaterThan(0);
    expect(
      published.every(
        (record) => record.status === "Published" && !record.coverImageUrl,
      ),
    ).toBe(true);
    expect(published.map((record) => record.nameKo)).not.toContain("빛의 문법");
  });
});

describe("InMemoryAdminExhibitionRepository catalogue reliability (spec 101)", () => {
  const weekly = {
    MONDAY: null,
    TUESDAY: { opens: "10:00", closes: "18:00" },
    WEDNESDAY: { opens: "10:00", closes: "18:00" },
    THURSDAY: { opens: "10:00", closes: "18:00" },
    FRIDAY: { opens: "10:00", closes: "18:00" },
    SATURDAY: { opens: "10:00", closes: "18:00" },
    SUNDAY: { opens: "10:00", closes: "18:00" },
  };

  it("confirms terms, then flags them stale when the description changes", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    const draft = await repository.createDraft();
    const confirmed = await repository.confirmExhibitionMetadata(draft.id, draft.workingVersionId, draft.revision);
    expect(confirmed.metadataReview).toMatchObject({ stale: false });
    expect(confirmed.revision).toBe(draft.revision + 1);

    const edited = await repository.saveDraft(confirmed.id, confirmed.workingVersionId, confirmed.revision, {
      descriptionKo: "바뀐 설명",
    });
    expect(edited.metadataReview?.stale).toBe(true);
    await expect(repository.listStaleExhibitions()).resolves.toEqual([
      expect.objectContaining({ exhibitionId: draft.id, metadataStale: true, hoursStale: false }),
    ]);
    await expect(repository.list({ search: "", status: "All", staleOnly: true }))
      .resolves.toEqual([expect.objectContaining({ id: draft.id })]);
  });

  it("confirms weekly hours and refuses a day that closes before it opens", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    const draft = await repository.createDraft();
    await expect(repository.confirmExhibitionHours(draft.id, draft.workingVersionId, draft.revision, {
      ...weekly,
      FRIDAY: { opens: "18:00", closes: "10:00" },
    })).rejects.toMatchObject({ name: "HoursWeeklyInvalidError", day: "FRIDAY" });

    const confirmed = await repository.confirmExhibitionHours(
      draft.id,
      draft.workingVersionId,
      draft.revision,
      weekly,
    );
    expect(confirmed.confirmedHours).toMatchObject({ weekly, stale: false });
    const edited = await repository.saveDraft(confirmed.id, confirmed.workingVersionId, confirmed.revision, {
      hours: "화-일 11:00-19:00",
    });
    expect(edited.confirmedHours?.stale).toBe(true);
  });

  it("refuses a duplicate artist with the existing id, reports duplicates and merges them", async () => {
    const repository = new InMemoryAdminExhibitionRepository();
    await expect(repository.createArtist(" 김민정 ", "Someone Else", "request-one"))
      .rejects.toMatchObject({ name: "ArtistDuplicateError", existingArtistId: "71000000-0000-4000-8000-000000000001" });

    const [group] = await repository.findDuplicateArtists();
    expect(group.artists.map(({ id }) => id)).toEqual([
      "71000000-0000-4000-8000-000000000001",
      "71000000-0000-4000-8000-000000000003",
    ]);
    await expect(repository.mergeArtists(group.artists[0].id, group.artists[0].id, "같은 작가"))
      .rejects.toMatchObject({ name: "ArtistMergeInvalidError", reason: "same_artist" });
    await expect(repository.mergeArtists(group.artists[0].id, group.artists[1].id, "같은 작가"))
      .resolves.toMatchObject({ survivorId: group.artists[0].id, mergedId: group.artists[1].id });
    await expect(repository.findDuplicateArtists()).resolves.toEqual([]);
    await expect(repository.mergeArtists(group.artists[0].id, group.artists[1].id, "다시"))
      .rejects.toMatchObject({ reason: "merged_archived" });
  });
});
