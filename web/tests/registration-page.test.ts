import { test, expect } from "@playwright/test";

test("exhibition-first chooser separates artist intake from gallery registration", async ({
  page,
}) => {
  await page.goto("/submit/");
  await expect(page.locator('.site-nav__link[href="/submit/"]')).toHaveCSS('color','rgb(0, 0, 0)');
  await expect(
    page.getByRole("heading", { name: "전시를 알리고 싶으신가요?" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /전시 등록하기/ }),
  ).toHaveAttribute("href", "/submit/exhibition/");
  await expect(
    page.getByRole("link", { name: /갤러리 등록하기/ }),
  ).toHaveAttribute("href", "https://gallery.gallrmap.com/");
  if (process.env.GALLR_QA_SCREENSHOT_DIR)
    await page.screenshot({
      path: process.env.GALLR_QA_SCREENSHOT_DIR + "/registration-desktop.png",
      fullPage: true,
    });
});

test("artist form keeps the draft through login and enters account-only review without a gallery", async ({
  page,
}) => {
  const user = {
    id: "00000000-0000-4000-8000-000000000001",
    email: "artist@example.com",
    email_confirmed_at: "2026-09-30T00:00:00Z",
    app_metadata: { provider: "email" },
    user_metadata: {},
    aud: "authenticated",
    created_at: "2026-09-30T00:00:00Z",
  };
  const jwt =
    Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString(
      "base64url",
    ) +
    "." +
    Buffer.from(
      JSON.stringify({
        sub: user.id,
        exp: Math.floor(Date.now() / 1000) + 3600,
        role: "authenticated",
      }),
    ).toString("base64url") +
    ".test-signature";
  let submissions = 0;
  await page.route("**/submit/exhibition/", async (route) => {
    const response = await route.fetch();
    const html = (await response.text()).replace(
      /(<script type="application\/json" id="registration-config">)[\s\S]*?(<\/script>)/,
      "$1" +
        JSON.stringify({
          enabled: true,
          url: "http://127.0.0.1:54329",
          key: "sb_publishable_registration_test_fixture",
        }) +
        "$2",
    );
    await route.fulfill({ response, body: html });
  });
  await page.route("http://127.0.0.1:54329/**", async (route) => {
    const url = route.request().url();
    let data: unknown = {};
    if (url.includes("/auth/v1/token"))
      data = {
        access_token: jwt,
        refresh_token: "fixture-refresh",
        expires_in: 3600,
        token_type: "bearer",
        user,
      };
    else if (url.includes("/auth/v1/user")) data = user;
    else if (url.includes("/artist_reserve_registration"))
      data = {
        request_id: route.request().postDataJSON().p_request_id,
        bucket_id: "exhibition-media",
        finalized: false,
        object_path:
          "submissions/00000000-0000-4000-8000-000000000002/00000000-0000-4000-8000-000000000003/original.png",
      };
    else if (url.includes("/artist_submit_registration")) {
      submissions++;
      data = {
        submission_id: "00000000-0000-4000-8000-000000000002",
        status: "submitted",
        submitted_at: "2026-09-30T00:00:00Z",
      };
    } else if (url.includes("/artist_list_registrations")) data = [];
    else if (url.includes("/storage/v1/object/")) data = { Key: "fixture" };
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(data),
    });
  });
  await page.goto("/submit/exhibition/");
  if (process.env.GALLR_QA_SCREENSHOT_DIR)
    await page.screenshot({
      path: process.env.GALLR_QA_SCREENSHOT_DIR + "/registration-form.png",
      fullPage: true,
    });
  await page.locator("[name=name_ko]").fill("작가 전시");
  await page.locator("[name=artists]").fill("참여 작가");
  await page.locator("[name=opening_date]").fill("2026-10-01");
  await page.locator("[name=closing_date]").fill("2026-10-31");
  await page.locator("[name=description_ko]").fill("전시 소개");
  await page
    .locator("[name=poster]")
    .setInputFiles({
      name: "poster.png",
      mimeType: "image/png",
      buffer: Buffer.from(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
        "base64",
      ),
    });
  await page.getByRole("button", { name: /다음: 전시 장소/ }).click();
  await page.locator("[name=venue_name_ko]").fill("대관 공간");
  await page.locator("[name=address_ko]").fill("서울 종로구");
  await page.locator("[name=hours]").fill("10:00–18:00");
  await page.getByRole("button", { name: /다음: 제출자 정보/ }).click();
  await page.locator("[name=submitter_name]").fill("Artist");
  await page.locator("[name=contact_email]").fill("artist@example.com");
  await page.getByRole("button", { name: /제출 전 확인/ }).click();
  await page.locator("[name=auth_email]").fill("artist@example.com");
  await page.locator("[name=password]").fill("fixture-only-password");
  await page.getByRole("button", { name: /로그인하고 계속/ }).click();
  await expect(page.locator("[data-preview]")).toContainText("작가 전시");
  await page.getByRole("button", { name: /검토 요청/ }).click();
  await expect(page.locator('[data-registration-error]')).toHaveText(/^! /);
  expect(submissions).toBe(0);
  await page.locator("[name=rights]").check();
  await page.getByRole("button", { name: /검토 요청/ }).click();
  await expect(
    page.getByRole("heading", { name: /전시 등록 요청을 받았어요/ }),
  ).toBeVisible();
  expect(submissions).toBe(1);
});

test("registration chooser fits a narrow mobile screen", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await page.goto("/submit/");
  await expect(page.getByRole("link", { name: /전시 등록하기/ })).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(320);
  if (process.env.GALLR_QA_SCREENSHOT_DIR) {
    await page.setViewportSize({ width: 390, height: 1024 });
    await page.screenshot({
      path: process.env.GALLR_QA_SCREENSHOT_DIR + "/registration-mobile.png",
      fullPage: false,
    });
  }
});
