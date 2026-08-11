import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseEditorPickRepository } from "./SupabaseEditorPickRepository";

const candidate = {
  id: "quiet-lines",
  working_version_id: "10000000-0000-0000-0000-000000000001",
  published_version_id: "10000000-0000-0000-0000-000000000001",
  revision: 2,
  name_ko: "고요한 선",
  name_en: "Quiet Lines",
  venue_name_ko: "갤러리 선",
  venue_name_en: "Line Gallery",
  opening_date: "2026-08-01",
  closing_date: "2026-09-01",
  selected: false,
  live: false,
};

function createClient(data: unknown) {
  const rpc = vi.fn().mockResolvedValue({ data, error: null });
  return { client: { rpc } as unknown as SupabaseClient, rpc };
}

describe("SupabaseEditorPickRepository", () => {
  it("maps the editor-scoped candidate list", async () => {
    const { client, rpc } = createClient([candidate]);
    const repository = new SupabaseEditorPickRepository(client);

    await expect(repository.list(" quiet ")).resolves.toEqual([
      {
        id: "quiet-lines",
        workingVersionId: "10000000-0000-0000-0000-000000000001",
        publishedVersionId: "10000000-0000-0000-0000-000000000001",
        revision: 2,
        nameKo: "고요한 선",
        nameEn: "Quiet Lines",
        venueNameKo: "갤러리 선",
        venueNameEn: "Line Gallery",
        openingDate: "2026-08-01",
        closingDate: "2026-09-01",
        selected: false,
        live: false,
      },
    ]);
    expect(rpc).toHaveBeenCalledWith("editor_list_pick_candidates", {
      p_search: "quiet",
    });
  });

  it("sends the optimistic identity when changing a pick", async () => {
    const { client, rpc } = createClient({
      ...candidate,
      working_version_id: "20000000-0000-0000-0000-000000000002",
      revision: 3,
      selected: true,
    });
    const repository = new SupabaseEditorPickRepository(client);

    const result = await repository.setSelected(
      "quiet-lines",
      candidate.working_version_id,
      2,
      true,
    );

    expect(rpc).toHaveBeenCalledWith("editor_set_pick", {
      p_exhibition_id: "quiet-lines",
      p_expected_version_id: candidate.working_version_id,
      p_expected_revision: 2,
      p_selected: true,
    });
    expect(result.selected).toBe(true);
    expect(result.live).toBe(false);
    expect(result.revision).toBe(3);
  });

  it("submits grouped curation changes for approval", async () => {
    const { client, rpc } = createClient({
      request_id: "request-one",
      status: "submitted",
      candidates: [{ ...candidate, selected: true, revision: 3 }],
    });
    const repository = new SupabaseEditorPickRepository(client);

    await repository.submitCuration([{
      exhibitionId: "quiet-lines",
      expectedVersionId: candidate.working_version_id,
      expectedRevision: 2,
      selected: true,
    }], "새 큐레이션", "New curation");

    expect(rpc).toHaveBeenCalledWith("editor_submit_curation", {
      p_changes: [{
        exhibition_id: "quiet-lines",
        expected_version_id: candidate.working_version_id,
        expected_revision: 2,
        selected: true,
      }],
      p_curation_description_ko: "새 큐레이션",
      p_curation_description_en: "New curation",
    });
  });

  it("loads and submits the authenticated editor profile without an editor ID argument", async () => {
    const { client, rpc } = createClient({
      editor_id: "mina-kim",
      name_ko: "김미나",
      name_en: "Mina Kim",
      bio_ko: "소개",
      bio_en: "Bio",
      curation_description_ko: "큐레이션 소개",
      curation_description_en: "Curation statement",
      pending_profile: false,
      pending_curation: true,
    });
    const repository = new SupabaseEditorPickRepository(client);

    await expect(repository.getProfile()).resolves.toMatchObject({
      bioKo: "소개",
      curationDescriptionKo: "큐레이션 소개",
      pendingCuration: true,
    });
    expect(rpc).toHaveBeenCalledWith("editor_get_profile");

    rpc.mockResolvedValueOnce({
      data: { request_id: "request-profile", status: "submitted" },
      error: null,
    });
    await repository.submitProfile("새 소개", "New bio");
    expect(rpc).toHaveBeenCalledWith("editor_submit_profile", {
      p_bio_ko: "새 소개",
      p_bio_en: "New bio",
    });
  });

  it("rejects malformed payloads instead of guessing access state", async () => {
    const { client } = createClient([{ ...candidate, selected: "yes" }]);
    const repository = new SupabaseEditorPickRepository(client);

    await expect(repository.list("")).rejects.toThrow(
      /editor_list_pick_candidates returned malformed data/i,
    );
  });
});
