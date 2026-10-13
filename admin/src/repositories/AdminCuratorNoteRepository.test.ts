import type { SupabaseClient } from "@supabase/supabase-js";
import {
  CuratorNoteDraftingUnavailableError,
  CuratorNoteNotQualifyingError,
  CuratorNoteNotStaffError,
  CuratorNoteSourceChangedError,
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
  stale: false,
};

const wireQueueItem = {
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
  stale: false,
};

function clientReturning(result: { data: unknown; error: unknown }) {
  const rpc = vi.fn().mockResolvedValue(result);
  const invoke = vi.fn();
  return { client: { rpc, functions: { invoke } } as unknown as SupabaseClient, rpc, invoke };
}

describe("SupabaseAdminCuratorNoteRepository", () => {
  it("reads the queue as the server orders it", async () => {
    const { client, rpc } = clientReturning({ data: [wireQueueItem], error: null });

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
      stale: false,
    }]);
    expect(rpc).toHaveBeenCalledWith("list_curator_note_queue");
  });

  it("carries the queue's stale flag for drafts whose exhibition text changed", async () => {
    const { client } = clientReturning({ data: [{ ...wireQueueItem, stale: true }], error: null });
    const [item] = await new SupabaseAdminCuratorNoteRepository(client).listQueue();
    expect(item.stale).toBe(true);
  });

  it("refuses a queue row or version without the stale flag", async () => {
    const { stale: _queueStale, ...queueWithoutStale } = wireQueueItem;
    const queue = clientReturning({ data: [queueWithoutStale], error: null });
    await expect(new SupabaseAdminCuratorNoteRepository(queue.client).listQueue())
      .rejects.toBeInstanceOf(MalformedCuratorNotePayloadError);
    const { stale: _versionStale, ...versionWithoutStale } = wireVersion;
    const version = clientReturning({ data: versionWithoutStale, error: null });
    await expect(new SupabaseAdminCuratorNoteRepository(version.client).approve(VERSION_ID, REVISION))
      .rejects.toBeInstanceOf(MalformedCuratorNotePayloadError);
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
        versions: [wireVersion],
      },
      error: null,
    });

    const facts = await new SupabaseAdminCuratorNoteRepository(client).get("who-lived-happily");
    expect(rpc).toHaveBeenCalledWith("get_curator_note", { p_exhibition_id: "who-lived-happily" });
    expect(facts.packet).toEqual({ name_ko: "후지와라", description_ko: "설명" });
    expect(facts.versions[0]).toMatchObject({
      id: VERSION_ID,
      status: "draft",
      hookKo: "곰 한 마리가 동화 속으로 들어간 이유",
      insightsKo: [{ text: "첫 번째 통찰.", grounding: ["description_ko"] }, { text: "두 번째 통찰.", grounding: ["artists"] }],
      sourceFields: ["artists", "description_ko", "name_ko"],
      revision: REVISION,
      stale: false,
    });
  });

  it("reads a version's stale flag from the facts", async () => {
    const { client } = clientReturning({
      data: {
        exhibition_id: "who-lived-happily",
        auto_draft_blocked: false,
        approved_version_id: null,
        qualifies: true,
        packet: { name_ko: "후지와라" },
        packet_hash: "b".repeat(64),
        versions: [{ ...wireVersion, stale: true }],
      },
      error: null,
    });
    const facts = await new SupabaseAdminCuratorNoteRepository(client).get("who-lived-happily");
    expect(facts.versions[0].stale).toBe(true);
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

  it("maps the database's refusals to typed errors", async () => {
    const stale = clientReturning({ data: null, error: { code: "PT409", message: "curator_note_stale" } });
    await expect(new SupabaseAdminCuratorNoteRepository(stale.client).approve(VERSION_ID, REVISION))
      .rejects.toBeInstanceOf(CuratorNoteStaleError);
    const unsupported = clientReturning({ data: null, error: { code: "PT409", message: "curator_note_unsupported" } });
    await expect(new SupabaseAdminCuratorNoteRepository(unsupported.client).approve(VERSION_ID, REVISION))
      .rejects.toBeInstanceOf(CuratorNoteUnsupportedError);
    const sourceChanged = clientReturning({ data: null, error: { code: "PT409", message: "curator_note_source_changed" } });
    await expect(new SupabaseAdminCuratorNoteRepository(sourceChanged.client).approve(VERSION_ID, REVISION))
      .rejects.toBeInstanceOf(CuratorNoteSourceChangedError);
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

  it("marks a draft stale once the exhibition's text changes and refuses only its approval", async () => {
    const repository = new InMemoryAdminCuratorNoteRepository();
    await repository.requestDraft("who-lived-happily", "single");
    const [fresh] = await repository.listQueue();
    expect(fresh.stale).toBe(false);

    repository.changeExhibitionText("who-lived-happily", { description_ko: "새로 쓴 설명" });

    const [item] = await repository.listQueue();
    expect(item.stale).toBe(true);
    const facts = await repository.get("who-lived-happily");
    expect(facts.versions[0].stale).toBe(true);
    expect(facts.packet?.description_ko).toBe("새로 쓴 설명");
    expect(facts.packetHash).not.toBe(facts.versions[0].sourceHash);

    await expect(repository.approve(item.versionId, item.revision)).rejects.toBeInstanceOf(CuratorNoteSourceChangedError);
    const edited = await repository.saveEdit(item.versionId, { hookKo: "바뀐 훅" }, item.revision);
    expect(edited.stale).toBe(true);
    const declined = await repository.decline(item.versionId, "wrong_facts", null, edited.revision);
    expect(declined.status).toBe("declined");

    await repository.requestDraft("who-lived-happily", "regenerate");
    const [next] = await repository.listQueue();
    expect(next.stale).toBe(false);
    await expect(repository.approve(next.versionId, next.revision)).resolves.toMatchObject({ status: "approved", stale: false });
  });
});
