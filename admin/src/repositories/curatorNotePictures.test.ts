import { NOTE_PICTURE_LIMIT, notePictures } from "./curatorNotePictures";
import type { CuratorNoteImage } from "./AdminCuratorNoteRepository";

const cover: CuratorNoteImage = { url: "https://images.example.test/cover.jpg", role: "cover", credit: "", altKo: "", altEn: "" };

function view(number: number): CuratorNoteImage {
  return {
    url: `https://images.example.test/view-${number}.jpg`,
    role: "gallery",
    credit: "",
    altKo: `전시 전경 ${number}`,
    altEn: `Installation view ${number}`,
  };
}

describe("notePictures", () => {
  it("sets the gallery pictures above the insights in staff order, as the app does", () => {
    const pictures = notePictures(cover.url, [cover, view(1), view(2)], 3);

    expect(pictures.cover).toEqual(cover);
    expect(pictures.placed).toEqual([
      { image: view(1), insight: 1 },
      { image: view(2), insight: 2 },
    ]);
    expect(pictures.withoutPicture).toEqual([3]);
    expect(pictures.unused).toEqual([]);
  });

  it("leaves out a gallery picture that repeats the cover and uses at most the note's limit", () => {
    const repeated: CuratorNoteImage = { ...view(0), url: cover.url };
    const views = [1, 2, 3, 4, 5, 6].map(view);

    const pictures = notePictures(cover.url, [cover, repeated, ...views], 4);

    expect(NOTE_PICTURE_LIMIT).toBe(4);
    expect(pictures.placed.map((entry) => entry.image.url)).toEqual(views.slice(0, 4).map((image) => image.url));
    expect(pictures.unused).toEqual(views.slice(4));
    expect(pictures.withoutPicture).toEqual([]);
  });

  it("keeps a picture the note has no insight for as gallery only", () => {
    const pictures = notePictures(cover.url, [cover, view(1), view(2), view(3)], 2);

    expect(pictures.placed.map((entry) => entry.insight)).toEqual([1, 2]);
    expect(pictures.unused).toEqual([view(3)]);
  });

  it("reports every insight when the exhibition has only a cover, and no cover when there is none", () => {
    expect(notePictures(cover.url, [cover], 3)).toEqual({ cover, placed: [], unused: [], withoutPicture: [1, 2, 3] });
    expect(notePictures(null, [], 2)).toEqual({ cover: null, placed: [], unused: [], withoutPicture: [1, 2] });
  });

  it("shows a cover that has no media row of its own, and none for a blank address", () => {
    const pictures = notePictures(cover.url, [view(1)], 1);

    expect(pictures.cover).toMatchObject({ url: cover.url, role: "cover" });
    expect(pictures.placed).toEqual([{ image: view(1), insight: 1 }]);
    expect(notePictures("   ", [view(1)], 1).cover).toBeNull();
  });
});
