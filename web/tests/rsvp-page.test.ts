import { test, expect } from "@playwright/test";

const TOKEN = "00000000-0000-4000-8000-000000000001";

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
            reception_date: "2026-09-02",
            reception_start_time: "19:00",
            address_ko: "서울 종로구 삼청로 12",
            address_en: "",
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
