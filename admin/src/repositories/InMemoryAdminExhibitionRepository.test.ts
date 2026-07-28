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
