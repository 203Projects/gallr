import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseEditorSelfOnboardingRepository } from "./EditorSelfOnboardingRepository";

describe("SupabaseEditorSelfOnboardingRepository", () => {
  it("completes the invited editor profile through the scoped RPC", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: {
        editor_id: "mina-kim",
        name_ko: "김미나",
        name_en: "Mina Kim",
        is_active: false,
      },
      error: null,
    });
    const repository = new SupabaseEditorSelfOnboardingRepository(
      { rpc } as unknown as SupabaseClient,
    );

    await expect(repository.complete({
      editorId: " mina-kim ",
      nameKo: " 김미나 ",
      nameEn: " Mina Kim ",
      titleKo: " 객원 에디터 ",
      titleEn: " Guest Editor ",
      bioKo: " 소개 ",
      bioEn: " Bio ",
      curationDescriptionKo: " 큐레이션 소개 ",
      curationDescriptionEn: " Curation statement ",
    })).resolves.toEqual({
      editorId: "mina-kim",
      nameKo: "김미나",
      nameEn: "Mina Kim",
      active: false,
    });
    expect(rpc).toHaveBeenCalledWith("editor_complete_onboarding", {
      p_editor_id: "mina-kim",
      p_name_ko: "김미나",
      p_name_en: "Mina Kim",
      p_title_ko: "객원 에디터",
      p_title_en: "Guest Editor",
      p_bio_ko: "소개",
      p_bio_en: "Bio",
      p_curation_description_ko: "큐레이션 소개",
      p_curation_description_en: "Curation statement",
    });
  });
});
