import type { SupabaseClient } from "@supabase/supabase-js";
import { SupabaseAdminEditorRepository } from "./SupabaseAdminEditorRepository";

const rawEditor = {
  editor_id: "mina-kim",
  email: "mina@example.com",
  name_ko: "김미나",
  name_en: "Mina Kim",
  title_ko: "객원 에디터",
  title_en: "Guest Editor",
  bio_ko: "서울의 동시대 미술을 씁니다.",
  bio_en: "Writes about contemporary art in Seoul.",
  curation_description_ko: "서울의 새로운 전시를 연결합니다.",
  curation_description_en: "Connecting new exhibitions across Seoul.",
  is_active: true,
  active_from: "2026-08-10",
  active_to: null,
  revision: 3,
  has_access: true,
  access_active: true,
};

const updateInput = {
  nameKo: "김미나",
  nameEn: "Mina Kim",
  titleKo: "수석 에디터",
  titleEn: "Senior Editor",
  bioKo: "새 소개",
  bioEn: "New bio",
  curationDescriptionKo: "새 큐레이션 문장",
  curationDescriptionEn: "New curation statement",
  isActive: true,
  activeFrom: "2026-08-10",
  activeTo: null,
};

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

  it("lists and validates managed editors through the admin RPC", async () => {
    const rpc = vi.fn().mockResolvedValue({ data: [rawEditor], error: null });
    const client = { rpc } as unknown as SupabaseClient;

    await expect(
      new SupabaseAdminEditorRepository(client).listEditors(),
    ).resolves.toEqual([{
      editorId: "mina-kim",
      email: "mina@example.com",
      nameKo: "김미나",
      nameEn: "Mina Kim",
      titleKo: "객원 에디터",
      titleEn: "Guest Editor",
      bioKo: "서울의 동시대 미술을 씁니다.",
      bioEn: "Writes about contemporary art in Seoul.",
      curationDescriptionKo: "서울의 새로운 전시를 연결합니다.",
      curationDescriptionEn: "Connecting new exhibitions across Seoul.",
      isActive: true,
      activeFrom: "2026-08-10",
      activeTo: null,
      revision: 3,
      hasAccess: true,
      accessActive: true,
    }]);
    expect(rpc).toHaveBeenCalledWith("admin_list_editors");
  });

  it("fails closed when the managed editor response is malformed", async () => {
    const client = {
      rpc: vi.fn().mockResolvedValue({
        data: [{ ...rawEditor, revision: "3" }],
        error: null,
      }),
    } as unknown as SupabaseClient;

    await expect(
      new SupabaseAdminEditorRepository(client).listEditors(),
    ).rejects.toThrow(/invalid response/i);
  });

  it("updates an editor with normalized fields and an expected revision", async () => {
    const rpc = vi.fn().mockResolvedValue({
      data: { ...rawEditor, title_ko: "수석 에디터", revision: 4 },
      error: null,
    });
    const repository = new SupabaseAdminEditorRepository(
      { rpc } as unknown as SupabaseClient,
    );

    await repository.updateEditor("mina-kim", 3, {
      ...updateInput,
      nameKo: " 김미나 ",
    });

    expect(rpc).toHaveBeenCalledWith("admin_update_editor", {
      p_editor_id: "mina-kim",
      p_expected_revision: 3,
      p_name_ko: "김미나",
      p_name_en: "Mina Kim",
      p_title_ko: "수석 에디터",
      p_title_en: "Senior Editor",
      p_bio_ko: "새 소개",
      p_bio_en: "New bio",
      p_curation_description_ko: "새 큐레이션 문장",
      p_curation_description_en: "New curation statement",
      p_is_active: true,
      p_active_from: "2026-08-10",
      p_active_to: null,
    });
  });

  it("maps a stale editor mutation to a revision conflict", async () => {
    const client = {
      rpc: vi.fn().mockResolvedValue({
        data: null,
        error: { code: "40001", message: "revision_conflict", details: "5" },
      }),
    } as unknown as SupabaseClient;

    await expect(
      new SupabaseAdminEditorRepository(client).updateEditor(
        "mina-kim",
        3,
        updateInput,
      ),
    ).rejects.toMatchObject({ name: "EditorRevisionConflictError", serverRevision: 5 });
  });

  it("deactivates and restores the linked editor membership", async () => {
    const rpc = vi.fn()
      .mockResolvedValueOnce({
        data: { ...rawEditor, is_active: false, access_active: false, revision: 4 },
        error: null,
      })
      .mockResolvedValueOnce({
        data: { ...rawEditor, is_active: false, access_active: true, revision: 5 },
        error: null,
      });
    const repository = new SupabaseAdminEditorRepository(
      { rpc } as unknown as SupabaseClient,
    );

    await repository.setAccess("mina-kim", 3, false);
    await repository.setAccess("mina-kim", 4, true);

    expect(rpc).toHaveBeenNthCalledWith(1, "admin_set_editor_access", {
      p_editor_id: "mina-kim",
      p_expected_revision: 3,
      p_active: false,
    });
    expect(rpc).toHaveBeenNthCalledWith(2, "admin_set_editor_access", {
      p_editor_id: "mina-kim",
      p_expected_revision: 4,
      p_active: true,
    });
  });
});
