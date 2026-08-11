import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseAdminEditorRepository } from "./SupabaseAdminEditorRepository";

describe("SupabaseAdminEditorRepository", () => {
  it("invokes the server-side invitation boundary with normalized fields", async () => {
    const invoke = vi.fn().mockResolvedValue({
      data: {
        editor_id: "mina-kim",
        email: "mina@example.com",
        name_ko: "김미나",
        name_en: "Mina Kim",
        is_active: false,
      },
      error: null,
    });
    const client = { functions: { invoke } } as unknown as SupabaseClient;
    const repository = new SupabaseAdminEditorRepository(client);

    await expect(repository.invite({
      email: " mina@example.com ",
      editorId: " mina-kim ",
      nameKo: " 김미나 ",
      nameEn: " Mina Kim ",
      titleKo: " 객원 에디터 ",
      titleEn: " Guest Editor ",
      bioKo: " 소개 ",
      bioEn: " Bio ",
      curationDescriptionKo: " 큐레이션 소개 ",
      curationDescriptionEn: " Curation statement ",
      isActive: false,
      activeFrom: "2026-08-10",
      activeTo: null,
    })).resolves.toMatchObject({ editorId: "mina-kim", active: false });

    expect(invoke).toHaveBeenCalledWith("invite-editor", {
      body: {
        email: "mina@example.com",
        editor_id: "mina-kim",
        name_ko: "김미나",
        name_en: "Mina Kim",
        title_ko: "객원 에디터",
        title_en: "Guest Editor",
        bio_ko: "소개",
        bio_en: "Bio",
        curation_description_ko: "큐레이션 소개",
        curation_description_en: "Curation statement",
        is_active: false,
        active_from: "2026-08-10",
        active_to: null,
      },
    });
  });

  it("does not expose server error details", async () => {
    const client = {
      functions: {
        invoke: vi.fn().mockResolvedValue({
          data: null,
          error: { message: "secret backend detail" },
        }),
      },
    } as unknown as SupabaseClient;
    const repository = new SupabaseAdminEditorRepository(client);

    await expect(repository.invite({
      email: "mina@example.com",
      editorId: "mina-kim",
      nameKo: "김미나",
      nameEn: "Mina Kim",
      titleKo: "객원 에디터",
      titleEn: "Guest Editor",
      bioKo: "소개",
      bioEn: "Bio",
      curationDescriptionKo: "큐레이션 소개",
      curationDescriptionEn: "Curation statement",
      isActive: false,
      activeFrom: "2026-08-10",
      activeTo: null,
    })).rejects.not.toThrow("secret backend detail");
  });
});
