import { test, expect } from "@playwright/test";

const TOKEN = "00000000-0000-4000-8000-000000000001";
const COVER = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='800' height='1000'%3E%3Crect width='800' height='1000' fill='%23000'/%3E%3C/svg%3E";

test("an invalid invitation never exposes an RSVP form", async ({ page }) => {
  await page.goto("/rsvp/");

  await expect(
    page.getByRole("heading", { name: "유효하지 않은 초대입니다." }),
  ).toBeVisible();
  await expect(page.locator("[data-rsvp-form]")).toBeHidden();
});

test("public RSVP loads invitation details and reaches its confirmation state", async ({ page }) => {
  let submitted: Record<string, unknown> | null = null;
  await page.route("**/__test-rsvp?token=*", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          launchKit: {
            name_ko: "작은 방의 기록",
            name_en: "Notes from a Small Room",
            venue_name_ko: "갤러리 알파",
            venue_name_en: "Gallery Alpha",
            cover_image_url: COVER,
            description_ko: "작은 방에서 시작된 기록입니다.",
            description_en: "A record that began in a small room.",
            opening_date: "2026-09-02",
            closing_date: "2026-11-08",
            reception_date: "2026-09-02",
            reception_start_time: "19:00",
            address_ko: "서울 종로구 삼청로 12",
            address_en: "",
            hours: "화–일 11:00–18:00",
            contact: "hello@gallery-alpha.example",
          },
        }),
      });
      return;
    }
    submitted = route.request().postDataJSON();
    await route.fulfill({ status: 204, body: "" });
  });

  await page.goto(`/rsvp/?token=${TOKEN}`);
  await expect(page.getByRole("heading", { name: "작은 방의 기록" })).toBeVisible();
  await expect(page.getByText("갤러리 알파")).toBeVisible();
  await expect(page.getByRole("img", { name: "작은 방의 기록" })).toHaveAttribute("src", COVER);
  await expect(page.getByText("작은 방에서 시작된 기록입니다.")).toBeVisible();
  await expect(page.getByText("2026-09-02 — 2026-11-08")).toBeVisible();
  await expect(page.getByText("화–일 11:00–18:00")).toBeVisible();
  await expect(page.getByText("hello@gallery-alpha.example")).toBeVisible();
  await page.getByRole("textbox", { name: "이름", exact: true }).fill("Maya Chen");
  await page.getByRole("textbox", { name: "이메일", exact: true }).fill("maya@example.test");
  await page.getByRole("combobox", { name: "참석 인원", exact: true }).selectOption("2");
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "참석 신청" }).click();

  await expect(page.getByRole("heading", { name: "신청이 완료되었습니다." })).toBeVisible();
  expect(submitted).toEqual({
    name: "Maya Chen",
    email: "maya@example.test",
    party_size: 2,
    privacy_acknowledged: true,
  });
});

test("public RSVP falls back cleanly when its cover cannot load", async ({ page }) => {
  await page.route("**/__test-rsvp?token=*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        launchKit: {
          name_ko: "표지 없는 전시",
          name_en: "Exhibition Without a Cover",
          venue_name_ko: "갤러리 베타",
          venue_name_en: "Gallery Beta",
          cover_image_url: "/__missing-rsvp-cover.jpg",
          description_ko: "표지를 불러오지 못해도 초대 정보는 계속 표시됩니다.",
          description_en: "Invitation details remain available when the cover fails.",
          opening_date: "2026-10-01",
          closing_date: "2026-10-31",
          reception_date: "2026-10-01",
          reception_start_time: "18:30",
          address_ko: "서울 중구 을지로 1",
          address_en: "1 Eulji-ro, Jung-gu, Seoul",
          hours: "화–토 12:00–19:00",
          contact: "hello@gallery-beta.example",
        },
      }),
    });
  });
  await page.route("**/__missing-rsvp-cover.jpg", (route) => route.abort());

  await page.goto(`/rsvp/?token=${TOKEN}`);

  await expect(page.locator("[data-rsvp-media]")).toBeHidden();
  await expect(page.getByRole("heading", { name: "표지 없는 전시" })).toBeVisible();
  await expect(page.getByText("2026-10-01 18:30")).toBeVisible();
  await expect(page.getByRole("button", { name: "참석 신청" })).toBeVisible();
});
