import type { SupabaseClient } from "@supabase/supabase-js";
import {
  CuratorNoteDraftingUnavailableError,
  CuratorNoteInvalidFieldsError,
  CuratorNoteInvalidTransitionError,
  CuratorNoteNotQualifyingError,
  CuratorNoteNotStaffError,
  CuratorNoteStaleError,
  CuratorNoteUnsupportedError,
  MalformedCuratorNotePayloadError,
} from "./AdminCuratorNoteRepository";
import { InMemoryAdminCuratorNoteRepository } from "./InMemoryAdminCuratorNoteRepository";
import { SupabaseAdminCuratorNoteRepository } from "./SupabaseAdminCuratorNoteRepository";

const VERSION_ID = "7a1c2a7e-8d34-4b8e-9a51-2f0c7d1e9b10";
const REVISION = "2026-10-11T18:00:00.123456+00:00";

const wireVersion = {
  id: VERSION_ID,
  exhibition_id: "who-lived-happily",
  version_number: 1,
  status: "draft",
  hook_ko: "곰 한 마리가 동화 속으로 들어간 이유",
  hook_en: "",
  insights_ko: [
    { text: "첫 번째 통찰.", grounding: ["description_ko"] },
    { text: "두 번째 통찰.", grounding: ["artists"] },
  ],
  insights_en: [],
  tip_ko: "복도 끝의 방부터 보라.",
  tip_en: "",
  unsupported_refs: [],
  edited: false,
  producer: "claude",
  model: "claude-opus-5-5",
  prompt_version: "2026-10-11.1",
  source_fields: ["artists", "description_ko", "name_ko"],
  source_hash: "a".repeat(64),
  source_version_id: null,
  request_kind: "scheduled",
  generated_at: "2026-10-11T18:00:00Z",
  rejection_code: null,
  reviewer_name_ko: null,
  reviewer_name_en: null,
  decided_at: null,
  decline_reason: null,
  decline_note: null,
  approved_at: null,
  created_at: "2026-10-11T18:00:00Z",
  revision: REVISION,
};

function clientReturning(result: { data: unknown; error: unknown }) {
  const rpc = vi.fn().mockResolvedValue(result);
  const invoke = vi.fn();
  return { client: { rpc, functions: { invoke } } as unknown as SupabaseClient, rpc, invoke };
}

describe("SupabaseAdminCuratorNoteRepository", () => {
  it("reads the queue as the server orders it", async () => {
    const { client, rpc } = clientReturning({
      data: [{
        version_id: VERSION_ID,
        exhibition_id: "who-lived-happily",
        name_ko: "후지와라",
        name_en: "Fujiwara",
        is_featured: true,
        closing_date: "2026-10-31",
        version_number: 1,
        prompt_version: "2026-10-11.1",
        generated_at: "2026-10-11T18:00:00Z",
        request_kind: "scheduled",
        unsupported_count: 0,
        edited: false,
        revision: REVISION,
      }],
      error: null,
    });

    await expect(new SupabaseAdminCuratorNoteRepository(client).listQueue()).resolves.toEqual([{
      versionId: VERSION_ID,
      exhibitionId: "who-lived-happily",
      nameKo: "후지와라",
      nameEn: "Fujiwara",
      isFeatured: true,
      closingDate: "2026-10-31",
      versionNumber: 1,
      promptVersion: "2026-10-11.1",
      generatedAt: "2026-10-11T18:00:00Z",
      requestKind: "scheduled",
      unsupportedCount: 0,
      edited: false,
      revision: REVISION,
    }]);
    expect(rpc).toHaveBeenCalledWith("list_curator_note_queue");
  });

  it("reads the facts and versions of one exhibition", async () => {
    const { client, rpc } = clientReturning({
      data: {
        exhibition_id: "who-lived-happily",
        auto_draft_blocked: false,
        approved_version_id: null,
        qualifies: true,
        packet: { name_ko: "후지와라", description_ko: "설명" },
        packet_hash: "a".repeat(64),
        cover_image_url: "https://images.example.test/who-lived.jpg",
        images: [{ url: "https://images.example.test/room-one.jpg", role: "gallery", credit: "사진 김지원", alt_ko: "전시 전경", alt_en: "Installation view" }],
        versions: [wireVersion],
      },
      error: null,
    });

    const facts = await new SupabaseAdminCuratorNoteRepository(client).get("who-lived-happily");
    expect(rpc).toHaveBeenCalledWith("get_curator_note", { p_exhibition_id: "who-lived-happily" });
    expect(facts.packet).toEqual({ name_ko: "후지와라", description_ko: "설명" });
    expect(facts.coverImageUrl).toBe("https://images.example.test/who-lived.jpg");
    expect(facts.images).toEqual([
      { url: "https://images.example.test/room-one.jpg", role: "gallery", credit: "사진 김지원", altKo: "전시 전경", altEn: "Installation view" },
    ]);
    expect(facts.versions[0]).toMatchObject({
      id: VERSION_ID,
      status: "draft",
      hookKo: "곰 한 마리가 동화 속으로 들어간 이유",
      insightsKo: [{ text: "첫 번째 통찰.", grounding: ["description_ko"] }, { text: "두 번째 통찰.", grounding: ["artists"] }],
      sourceFields: ["artists", "description_ko", "name_ko"],
      revision: REVISION,
    });
  });

  it("sends an edit with the revision staff reviewed and snake_case fields", async () => {
    const { client, rpc } = clientReturning({ data: { ...wireVersion, hook_ko: "바뀐 훅", edited: true }, error: null });
    const saved = await new SupabaseAdminCuratorNoteRepository(client).saveEdit(VERSION_ID, { hookKo: "바뀐 훅" }, REVISION);
    expect(rpc).toHaveBeenCalledWith("save_curator_note_edit", {
      p_version_id: VERSION_ID,
      p_fields: { hook_ko: "바뀐 훅" },
      p_expected_revision: REVISION,
    });
    expect(saved.hookKo).toBe("바뀐 훅");
    expect(saved.edited).toBe(true);
  });

  it("marks and clears an unsupported sentence", async () => {
    const { client, rpc } = clientReturning({ data: { ...wireVersion, unsupported_refs: ["ko:insight:0"] }, error: null });
    const marked = await new SupabaseAdminCuratorNoteRepository(client).markSentence(VERSION_ID, "ko:insight:0", true, REVISION);
    expect(rpc).toHaveBeenCalledWith("mark_curator_note_sentence", {
      p_version_id: VERSION_ID,
      p_ref: "ko:insight:0",
      p_unsupported: true,
      p_expected_revision: REVISION,
    });
    expect(marked.unsupportedRefs).toEqual(["ko:insight:0"]);
  });

  it("approves, declines and records exports through the staff functions", async () => {
    const { client, rpc } = clientReturning({ data: { ...wireVersion, status: "approved" }, error: null });
    const repository = new SupabaseAdminCuratorNoteRepository(client);
    await repository.approve(VERSION_ID, REVISION);
    expect(rpc).toHaveBeenCalledWith("approve_curator_note", { p_version_id: VERSION_ID, p_expected_revision: REVISION });
    await repository.decline(VERSION_ID, "tone", "너무 홍보 같아요", REVISION);
    expect(rpc).toHaveBeenCalledWith("decline_curator_note", {
      p_version_id: VERSION_ID,
      p_reason: "tone",
      p_note: "너무 홍보 같아요",
      p_expected_revision: REVISION,
    });
    await repository.recordExport(VERSION_ID, "en", 5);
    expect(rpc).toHaveBeenCalledWith("record_curator_note_export", {
      p_version_id: VERSION_ID,
      p_language: "en",
      p_slide_count: 5,
    });
  });

  it("lists approved notes with the facts a roundup prints", async () => {
    const wire = {
      version_id: VERSION_ID,
      exhibition_id: "who-lived-happily",
      version_number: 2,
      hook_ko: "훅",
      hook_en: "Hook",
      approved_at: "2026-10-12T03:00:00+00:00",
      name_ko: "전시",
      name_en: "Show",
      venue_name_ko: "에스더쉬퍼",
      venue_name_en: "Esther Schipper Seoul",
      region_ko: "용산구",
      region_en: "Yongsan-gu",
      opening_date: "2026-09-02",
      closing_date: "2026-10-31",
      cover_image_url: null,
    };
    const { client, rpc } = clientReturning({ data: [wire], error: null });

    const [approved] = await new SupabaseAdminCuratorNoteRepository(client).listApproved();

    expect(rpc).toHaveBeenCalledWith("list_approved_curator_notes");
    expect(approved).toEqual({
      versionId: VERSION_ID,
      exhibitionId: "who-lived-happily",
      versionNumber: 2,
      hookKo: "훅",
      hookEn: "Hook",
      approvedAt: "2026-10-12T03:00:00+00:00",
      nameKo: "전시",
      nameEn: "Show",
      venueNameKo: "에스더쉬퍼",
      venueNameEn: "Esther Schipper Seoul",
      regionKo: "용산구",
      regionEn: "Yongsan-gu",
      openingDate: "2026-09-02",
      closingDate: "2026-10-31",
      coverImageUrl: null,
    });
    const malformed = clientReturning({ data: [{ ...wire, hook_ko: 7 }], error: null });
    await expect(new SupabaseAdminCuratorNoteRepository(malformed.client).listApproved())
      .rejects.toBeInstanceOf(MalformedCuratorNotePayloadError);
  });

  it("records a roundup export with the versions in slide order", async () => {
    const { client, rpc } = clientReturning({ data: 2, error: null });
    await expect(new SupabaseAdminCuratorNoteRepository(client).recordRoundupExport([VERSION_ID, "other"], "ko", 4)).resolves.toBe(2);
    expect(rpc).toHaveBeenCalledWith("record_curator_note_roundup_export", {
      p_version_ids: [VERSION_ID, "other"],
      p_language: "ko",
      p_slide_count: 4,
    });
    const refused = clientReturning({ data: null, error: { code: "22023", message: "curator_note_invalid_fields" } });
    await expect(new SupabaseAdminCuratorNoteRepository(refused.client).recordRoundupExport([VERSION_ID], "ko", 3))
      .rejects.toBeInstanceOf(CuratorNoteInvalidFieldsError);
    const odd = clientReturning({ data: "2", error: null });
    await expect(new SupabaseAdminCuratorNoteRepository(odd.client).recordRoundupExport([VERSION_ID, "other"], "ko", 4))
      .rejects.toBeInstanceOf(MalformedCuratorNotePayloadError);
  });

  it("maps the database's refusals to typed errors", async () => {
    const stale = clientReturning({ data: null, error: { code: "PT409", message: "curator_note_stale" } });
    await expect(new SupabaseAdminCuratorNoteRepository(stale.client).approve(VERSION_ID, REVISION))
      .rejects.toBeInstanceOf(CuratorNoteStaleError);
    const unsupported = clientReturning({ data: null, error: { code: "PT409", message: "curator_note_unsupported" } });
    await expect(new SupabaseAdminCuratorNoteRepository(unsupported.client).approve(VERSION_ID, REVISION))
      .rejects.toBeInstanceOf(CuratorNoteUnsupportedError);
    const denied = clientReturning({ data: null, error: { code: "42501", message: "curator_note_not_staff" } });
    await expect(new SupabaseAdminCuratorNoteRepository(denied.client).listQueue())
      .rejects.toBeInstanceOf(CuratorNoteNotStaffError);
  });

  it("rejects a malformed version instead of rendering it", async () => {
    const { client } = clientReturning({ data: { ...wireVersion, insights_ko: "not an array" }, error: null });
    await expect(new SupabaseAdminCuratorNoteRepository(client).approve(VERSION_ID, REVISION))
      .rejects.toBeInstanceOf(MalformedCuratorNotePayloadError);
  });

  it("requests a draft through the Edge Function with the staff session", async () => {
    const { client, invoke } = clientReturning({ data: null, error: null });
    invoke.mockResolvedValue({
      data: { drafted: 1, unusable: 0, failed: 0, remaining: 0, results: [{ exhibition_id: "who-lived-happily", outcome: "drafted", code: null }] },
      error: null,
    });
    const result = await new SupabaseAdminCuratorNoteRepository(client).requestDraft("who-lived-happily", "regenerate");
    expect(invoke).toHaveBeenCalledWith("draft-curator-notes", {
      body: { mode: "single", exhibition_id: "who-lived-happily", request_kind: "regenerate" },
    });
    expect(result).toEqual({ outcome: "drafted", code: null });
  });

  it("maps the function's refusals", async () => {
    const notStaff = clientReturning({ data: null, error: null });
    notStaff.invoke.mockResolvedValue({ data: null, error: { context: { status: 401 } } });
    await expect(new SupabaseAdminCuratorNoteRepository(notStaff.client).requestDraft("x", "single"))
      .rejects.toBeInstanceOf(CuratorNoteNotStaffError);
    const notQualifying = clientReturning({ data: null, error: null });
    notQualifying.invoke.mockResolvedValue({ data: null, error: { context: { status: 404 } } });
    await expect(new SupabaseAdminCuratorNoteRepository(notQualifying.client).requestDraft("x", "single"))
      .rejects.toBeInstanceOf(CuratorNoteNotQualifyingError);
    const down = clientReturning({ data: null, error: null });
    down.invoke.mockResolvedValue({ data: null, error: { context: { status: 503 } } });
    await expect(new SupabaseAdminCuratorNoteRepository(down.client).requestDraft("x", "single"))
      .rejects.toBeInstanceOf(CuratorNoteDraftingUnavailableError);
  });
});

describe("InMemoryAdminCuratorNoteRepository", () => {
  it("walks a draft through edit, unsupported marking, approval and supersession", async () => {
    const repository = new InMemoryAdminCuratorNoteRepository();
    await repository.requestDraft("who-lived-happily", "single");
    const [item] = await repository.listQueue();
    expect(item.exhibitionId).toBe("who-lived-happily");

    const marked = await repository.markSentence(item.versionId, "ko:insight:0", true, item.revision);
    await expect(repository.approve(item.versionId, marked.revision)).rejects.toBeInstanceOf(CuratorNoteUnsupportedError);
    await expect(repository.approve(item.versionId, item.revision)).rejects.toBeInstanceOf(CuratorNoteStaleError);

    const cleared = await repository.markSentence(item.versionId, "ko:insight:0", false, marked.revision);
    const edited = await repository.saveEdit(item.versionId, { hookKo: "바뀐 훅" }, cleared.revision);
    const approved = await repository.approve(item.versionId, edited.revision);
    expect(approved.status).toBe("approved");
    expect(approved.reviewerNameKo).toBe("하신");
    expect((await repository.get("who-lived-happily")).approvedVersionId).toBe(item.versionId);

    await repository.requestDraft("who-lived-happily", "regenerate");
    const [next] = await repository.listQueue();
    const second = await repository.approve(next.versionId, next.revision);
    const facts = await repository.get("who-lived-happily");
    expect(facts.approvedVersionId).toBe(second.id);
    expect(facts.versions.find((version) => version.id === item.versionId)?.status).toBe("superseded");
    await expect(repository.listQueue()).resolves.toEqual([]);
  });

  it("lists approved notes of running exhibitions soonest closing first and checks a roundup before recording it", async () => {
    const exhibition = (id: string, closingDate: string) => ({
      id,
      nameKo: `${id} 전시`,
      nameEn: `${id} show`,
      isFeatured: false,
      closingDate,
      coverImageUrl: `https://example.invalid/${id}.jpg`,
      packet: { venue_name_ko: "갤러리", region_ko: "종로구", opening_date: "2026-09-02" },
    });
    const repository = new InMemoryAdminCuratorNoteRepository(
      [exhibition("later", "2026-11-29"), exhibition("soon", "2026-10-18"), exhibition("ended", "2026-10-01"), exhibition("draft", "2026-10-20")],
      { ko: "하신", en: "Hanshin" },
      () => "2026-10-13T03:00:00.000Z",
    );
    for (const id of ["later", "soon", "ended", "draft"]) await repository.requestDraft(id, "single");
    for (const item of await repository.listQueue()) {
      if (item.exhibitionId !== "draft") await repository.approve(item.versionId, item.revision);
    }

    const approved = await repository.listApproved();
    expect(approved.map((note) => note.exhibitionId)).toEqual(["soon", "later"]);
    expect(approved[0]).toMatchObject({ nameKo: "soon 전시", venueNameKo: "갤러리", regionKo: "종로구", closingDate: "2026-10-18", coverImageUrl: "https://example.invalid/soon.jpg" });

    const ids = approved.map((note) => note.versionId);
    await expect(repository.recordRoundupExport(ids, "ko", 4)).resolves.toBe(2);
    await expect(repository.recordRoundupExport([ids[0]], "ko", 3)).rejects.toBeInstanceOf(CuratorNoteInvalidFieldsError);
    await expect(repository.recordRoundupExport([ids[0], ids[0]], "ko", 4)).rejects.toBeInstanceOf(CuratorNoteInvalidFieldsError);
    const [waiting] = await repository.listQueue();
    await expect(repository.recordRoundupExport([ids[0], waiting.versionId], "ko", 4)).rejects.toBeInstanceOf(CuratorNoteInvalidTransitionError);
  });
});
