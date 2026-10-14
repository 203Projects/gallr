import {
  CuratorNoteInvalidFieldsError,
  CuratorNoteInvalidTransitionError,
  CuratorNoteNotFoundError,
  CuratorNoteSourceChangedError,
  CuratorNoteStaleError,
  CuratorNoteUnsupportedError,
  type AdminCuratorNoteRepository,
  type ApprovedCuratorNote,
  type CuratorNoteDeclineReason,
  type CuratorNoteDraftRequestResult,
  type CuratorNoteEdit,
  type CuratorNoteFacts,
  type CuratorNoteImage,
  type CuratorNoteLanguage,
  type CuratorNoteQueueItem,
  type CuratorNoteVersion,
} from "./AdminCuratorNoteRepository";

/** A catalogue row the in-memory repository drafts from. */
export interface InMemoryCuratorNoteExhibition {
  id: string;
  nameKo: string;
  nameEn: string;
  isFeatured: boolean;
  closingDate: string;
  /** The catalogue's cover; never part of the packet. */
  coverImageUrl?: string | null;
  images?: CuratorNoteImage[];
  packet: Record<string, unknown>;
}

const DEFAULT_EXHIBITION: InMemoryCuratorNoteExhibition = {
  id: "who-lived-happily",
  nameKo: "Who Lived Happily Ever After? | 사이먼 후지와라",
  nameEn: "Who Lived Happily Ever After?",
  isFeatured: true,
  closingDate: "2026-10-31",
  packet: {
    name_ko: "Who Lived Happily Ever After? | 사이먼 후지와라",
    description_ko: "에스더쉬퍼 서울은 사이먼 후지와라의 서울 첫 개인전이자 갤러리와의 네 번째 전시를 선보인다.",
    venue_name_ko: "에스더쉬퍼",
    region_ko: "용산구",
    opening_date: "2026-09-02",
    closing_date: "2026-10-31",
    artists: [{ id: "a1", name_ko: "사이먼 후지와라", name_en: "Simon Fujiwara" }],
  },
};

/** Fixture-mode and test stand-in for the staff curator note functions and the drafting function. */
export class InMemoryAdminCuratorNoteRepository implements AdminCuratorNoteRepository {
  private readonly exhibitions: Map<string, InMemoryCuratorNoteExhibition>;
  /** Stands in for the packet hash the database derives from the exhibition's published text. */
  private readonly packetHashes = new Map<string, string>();
  private readonly versions: CuratorNoteVersion[] = [];
  private packetGeneration = 0;
  private readonly approved = new Map<string, string>();
  private readonly blocked = new Set<string>();
  private sequence = 0;

  constructor(
    exhibitions: InMemoryCuratorNoteExhibition[] = [DEFAULT_EXHIBITION],
    private readonly reviewerName: { ko: string; en: string } = { ko: "하신", en: "Hanshin" },
    private readonly now: () => string = () => new Date().toISOString(),
  ) {
    this.exhibitions = new Map(exhibitions.map((exhibition) => [exhibition.id, exhibition]));
    for (const exhibition of exhibitions) this.packetHashes.set(exhibition.id, INITIAL_PACKET_HASH);
  }

  /**
   * Changes the exhibition's published text, as an editor would in the catalogue: the packet hash moves on, so every
   * draft written from the old text reads as stale.
   */
  changeExhibitionText(exhibitionId: string, packet: Record<string, unknown>): void {
    const exhibition = this.exhibitions.get(exhibitionId);
    if (!exhibition) throw new CuratorNoteNotFoundError();
    this.exhibitions.set(exhibitionId, { ...exhibition, packet: { ...exhibition.packet, ...packet } });
    this.packetGeneration += 1;
    this.packetHashes.set(exhibitionId, this.packetGeneration.toString(16).padStart(64, "0"));
  }

  listQueue(): Promise<CuratorNoteQueueItem[]> {
    const items = this.versions
      .filter((version) => version.status === "draft")
      .map((version) => {
        const exhibition = this.exhibitions.get(version.exhibitionId);
        return {
          versionId: version.id,
          exhibitionId: version.exhibitionId,
          nameKo: exhibition?.nameKo ?? "",
          nameEn: exhibition?.nameEn ?? "",
          isFeatured: exhibition?.isFeatured ?? false,
          closingDate: exhibition?.closingDate ?? null,
          versionNumber: version.versionNumber,
          promptVersion: version.promptVersion,
          generatedAt: version.generatedAt,
          requestKind: version.requestKind,
          unsupportedCount: version.unsupportedRefs.length,
          edited: version.edited,
          revision: version.revision,
          stale: this.isStale(version),
        };
      })
      .sort((a, b) => Number(b.isFeatured) - Number(a.isFeatured) || a.generatedAt.localeCompare(b.generatedAt));
    return Promise.resolve(items);
  }

  get(exhibitionId: string): Promise<CuratorNoteFacts> {
    const exhibition = this.exhibitions.get(exhibitionId) ?? null;
    const versions = this.versions.filter((version) => version.exhibitionId === exhibitionId);
    if (!exhibition && versions.length === 0) return Promise.reject(new CuratorNoteNotFoundError());
    return Promise.resolve({
      exhibitionId,
      autoDraftBlocked: this.blocked.has(exhibitionId),
      approvedVersionId: this.approved.get(exhibitionId) ?? null,
      qualifies: exhibition !== null,
      packet: exhibition?.packet ?? null,
      packetHash: this.packetHashes.get(exhibitionId) ?? null,
      coverImageUrl: exhibition?.coverImageUrl ?? null,
      images: (exhibition?.images ?? []).map((image) => ({ ...image })),
      versions: [...versions].sort((a, b) => b.versionNumber - a.versionNumber).map((version) => this.snapshot(version)),
    });
  }

  saveEdit(versionId: string, edit: CuratorNoteEdit, expectedRevision: string): Promise<CuratorNoteVersion> {
    return this.mutateDraft(versionId, expectedRevision, (version) => {
      if (edit.hookKo !== undefined) version.hookKo = edit.hookKo;
      if (edit.hookEn !== undefined) version.hookEn = edit.hookEn;
      if (edit.tipKo !== undefined) version.tipKo = edit.tipKo;
      if (edit.tipEn !== undefined) version.tipEn = edit.tipEn;
      if (edit.insightsKo !== undefined) version.insightsKo = edit.insightsKo.map((insight) => ({ ...insight }));
      if (edit.insightsEn !== undefined) version.insightsEn = edit.insightsEn.map((insight) => ({ ...insight }));
      version.edited = true;
    });
  }

  markSentence(versionId: string, ref: string, unsupported: boolean, expectedRevision: string): Promise<CuratorNoteVersion> {
    return this.mutateDraft(versionId, expectedRevision, (version) => {
      const refs = new Set(version.unsupportedRefs);
      if (unsupported) refs.add(ref);
      else refs.delete(ref);
      version.unsupportedRefs = [...refs].sort();
    });
  }

  approve(versionId: string, expectedRevision: string): Promise<CuratorNoteVersion> {
    return this.mutateDraft(versionId, expectedRevision, (version) => {
      if (this.isStale(version)) throw new CuratorNoteSourceChangedError();
      if (version.unsupportedRefs.length > 0) throw new CuratorNoteUnsupportedError();
      const previous = this.approved.get(version.exhibitionId);
      const earlier = this.versions.find((candidate) => candidate.id === previous);
      if (earlier && earlier.status === "approved") earlier.status = "superseded";
      const at = this.now();
      version.status = "approved";
      version.reviewerNameKo = this.reviewerName.ko;
      version.reviewerNameEn = this.reviewerName.en;
      version.decidedAt = at;
      version.approvedAt = at;
      this.approved.set(version.exhibitionId, version.id);
      this.blocked.delete(version.exhibitionId);
    });
  }

  decline(
    versionId: string,
    reason: CuratorNoteDeclineReason,
    note: string | null,
    expectedRevision: string,
  ): Promise<CuratorNoteVersion> {
    return this.mutateDraft(versionId, expectedRevision, (version) => {
      version.status = "declined";
      version.declineReason = reason;
      version.declineNote = note;
      version.decidedAt = this.now();
      this.blocked.add(version.exhibitionId);
    });
  }

  requestDraft(exhibitionId: string, kind: "single" | "regenerate"): Promise<CuratorNoteDraftRequestResult> {
    const exhibition = this.exhibitions.get(exhibitionId);
    if (!exhibition) return Promise.reject(new CuratorNoteNotFoundError());
    for (const version of this.versions) {
      if (version.exhibitionId === exhibitionId && version.status === "draft") version.status = "replaced";
    }
    this.blocked.delete(exhibitionId);
    const number = this.versions.filter((version) => version.exhibitionId === exhibitionId).length + 1;
    this.sequence += 1;
    const at = this.now();
    this.versions.push({
      id: `note-version-${this.sequence}`,
      exhibitionId,
      versionNumber: number,
      status: "draft",
      hookKo: "곰 한 마리가 동화 속으로 들어간 이유",
      hookEn: "",
      insightsKo: [
        { text: "서울 첫 개인전이라는 사실보다 중요한 것은 네 번째 전시라는 숫자다.", grounding: ["description_ko"] },
        { text: "방마다 색을 바꿔 캐릭터의 변신을 공간으로 번역한다.", grounding: ["description_ko", "artists"] },
      ],
      insightsEn: [],
      tipKo: "복도 끝의 가장 어두운 방부터 보라.",
      tipEn: "",
      unsupportedRefs: [],
      edited: false,
      producer: "claude",
      model: "claude-fixture",
      promptVersion: "2026-10-11.1",
      sourceFields: Object.keys(exhibition.packet).sort(),
      sourceHash: this.packetHashes.get(exhibitionId) ?? INITIAL_PACKET_HASH,
      requestKind: kind,
      generatedAt: at,
      rejectionCode: null,
      reviewerNameKo: null,
      reviewerNameEn: null,
      decidedAt: null,
      declineReason: null,
      declineNote: null,
      approvedAt: null,
      revision: `${at}#${this.sequence}`,
      stale: false,
    });
    return Promise.resolve({ outcome: "drafted", code: null });
  }

  recordExport(versionId: string, _language: CuratorNoteLanguage, slideCount: number): Promise<CuratorNoteVersion> {
    const version = this.versions.find((candidate) => candidate.id === versionId);
    if (!version) return Promise.reject(new CuratorNoteNotFoundError());
    if (version.status !== "approved") return Promise.reject(new CuratorNoteInvalidTransitionError());
    // The database accepts four to ten slides (migration 20261014110000) and checks the count after the status.
    if (slideCount < 4 || slideCount > 10) return Promise.reject(new CuratorNoteInvalidFieldsError());
    return Promise.resolve(this.snapshot(version));
  }

  listApproved(): Promise<ApprovedCuratorNote[]> {
    const text = (packet: Record<string, unknown>, key: string) => (typeof packet[key] === "string" ? (packet[key] as string) : "");
    const today = this.now().slice(0, 10);
    const items = [...this.approved.entries()]
      .flatMap(([exhibitionId, versionId]) => {
        const exhibition = this.exhibitions.get(exhibitionId);
        const version = this.versions.find((candidate) => candidate.id === versionId);
        if (!exhibition || !version || version.status !== "approved" || exhibition.closingDate < today) return [];
        const packet = exhibition.packet;
        return [{
          versionId: version.id,
          exhibitionId,
          versionNumber: version.versionNumber,
          hookKo: version.hookKo,
          hookEn: version.hookEn,
          approvedAt: version.approvedAt ?? "",
          nameKo: exhibition.nameKo,
          nameEn: exhibition.nameEn,
          venueNameKo: text(packet, "venue_name_ko"),
          venueNameEn: text(packet, "venue_name_en"),
          regionKo: text(packet, "region_ko"),
          regionEn: text(packet, "region_en"),
          openingDate: text(packet, "opening_date") || null,
          closingDate: exhibition.closingDate,
          coverImageUrl: exhibition.coverImageUrl ?? null,
        }];
      })
      .sort((a, b) => a.closingDate.localeCompare(b.closingDate) || b.approvedAt.localeCompare(a.approvedAt));
    return Promise.resolve(items);
  }

  recordRoundupExport(versionIds: string[], _language: CuratorNoteLanguage, slideCount: number): Promise<number> {
    const size = versionIds.length;
    if (size < 2 || size > 10 || new Set(versionIds).size !== size || slideCount < size + 2 || slideCount > size + 4) {
      return Promise.reject(new CuratorNoteInvalidFieldsError());
    }
    for (const versionId of versionIds) {
      const version = this.versions.find((candidate) => candidate.id === versionId);
      if (!version) return Promise.reject(new CuratorNoteNotFoundError());
      if (version.status !== "approved") return Promise.reject(new CuratorNoteInvalidTransitionError());
    }
    return Promise.resolve(size);
  }

  private mutateDraft(
    versionId: string,
    expectedRevision: string,
    change: (version: CuratorNoteVersion) => void,
  ): Promise<CuratorNoteVersion> {
    const version = this.versions.find((candidate) => candidate.id === versionId);
    if (!version) return Promise.reject(new CuratorNoteNotFoundError());
    if (version.revision !== expectedRevision) return Promise.reject(new CuratorNoteStaleError());
    if (version.status !== "draft") return Promise.reject(new CuratorNoteInvalidTransitionError());
    try {
      change(version);
    } catch (error) {
      return Promise.reject(error);
    }
    this.sequence += 1;
    version.revision = `${this.now()}#${this.sequence}`;
    return Promise.resolve(this.snapshot(version));
  }

  /** Stale once the exhibition's text moved past the draft's source or the exhibition left the catalogue. */
  private isStale(version: CuratorNoteVersion): boolean {
    const currentHash = this.packetHashes.get(version.exhibitionId);
    return currentHash === undefined || currentHash !== version.sourceHash;
  }

  private snapshot(version: CuratorNoteVersion): CuratorNoteVersion {
    return clone({ ...version, stale: this.isStale(version) });
  }
}

const INITIAL_PACKET_HASH = "f".repeat(64);

function clone(version: CuratorNoteVersion): CuratorNoteVersion {
  return {
    ...version,
    insightsKo: version.insightsKo.map((insight) => ({ ...insight, grounding: [...insight.grounding] })),
    insightsEn: version.insightsEn.map((insight) => ({ ...insight, grounding: [...insight.grounding] })),
    unsupportedRefs: [...version.unsupportedRefs],
    sourceFields: [...version.sourceFields],
  };
}
