import { test, expect } from "@playwright/test";

const payload = { name_ko: "개인 전시", venue_name_ko: "서울 공간", address_ko: "서울시", hours: "10:00–18:00", opening_date: "2026-09-30", closing_date: "2026-10-31" };
async function enableForm(page: import("@playwright/test").Page) {
  await page.route("**/submit/individual/", async (route) => {
    const response = await route.fetch();
    const body = (await response.text()).replace('data-url="" data-key=""', 'data-url="https://individual-test.supabase.co" data-key="sb_publishable_test"');
    await route.fulfill({ response, body });
  });
}
test("SUBMIT offers gallery and individual paths, including without JavaScript", async ({ page, browser }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "전시 등록 SUBMIT", exact: true }).first().click();
  await expect(page).toHaveURL(/\/submit\/$/);
  await expect(page.getByRole("link", { name: /GALLERY WORKSPACE/ })).toHaveAttribute("href", "https://gallery.gallrmap.com/");
  await page.getByRole("link", { name: /INDIVIDUAL SUBMISSION/ }).click();
  await expect(page).toHaveURL(/\/submit\/individual\/$/);
  const context = await browser.newContext({ javaScriptEnabled: false });
  const noJs = await context.newPage();
  await noJs.goto("http://localhost:4242/submit/");
  await expect(noJs.getByRole("link", { name: /INDIVIDUAL SUBMISSION/ })).toBeVisible();
  await context.close();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("email verification stays on the individual route and a failed submission retries once", async ({ page }) => {
  await enableForm(page);
  let otp: any, attempts: any[] = [];
  await page.route("**/rest/v1/rpc/reserve_individual_exhibition_image", route => route.fulfill({json:{asset_id:"00000000-0000-4000-8000-000000000087", bucket_id:"exhibition-media",object_path:"submissions/00000000-0000-4000-8000-000000000088/00000000-0000-4000-8000-000000000087/original.png",mime_type:"image/png",byte_size:68}}));
  let uploads = 0;
  await page.route("**/storage/v1/object/exhibition-media/**", route => { uploads++; return route.fulfill({json:{Key:"uploaded"}}); });
  await page.route("https://individual-test.supabase.co/auth/v1/otp?*", async route => {
    otp = { url: route.request().url(), body: route.request().postDataJSON() };
    await route.fulfill({ json: {} });
  });
  await page.route("https://individual-test.supabase.co/auth/v1/user", route => route.fulfill({ json: { email: "visitor@example.com", email_confirmed_at: "2026-09-30T00:00:00Z", is_anonymous: false } }));
  await page.route("https://individual-test.supabase.co/rest/v1/rpc/submit_individual_exhibition", async route => {
    attempts.push(route.request().postDataJSON());
    if (attempts.length === 1) await route.abort();
    else if (attempts.length === 2) await route.fulfill({ status: 401, json: {} });
    else await route.fulfill({ json: { submission_id: "00000000-0000-4000-8000-000000000001", status: "submitted" } });
  });
  await page.goto("/submit/individual/");
  for (const [key, value] of Object.entries(payload)) await page.locator('[name="' + key + '"]').fill(value);
  await page.locator('[name="email"]').fill("visitor@example.com");
  await page.getByRole("button", { name: /Send verification link/ }).click();
  await expect(page.getByRole("status")).toContainText("Open the link");
  expect(new URL(otp.url).searchParams.get("redirect_to")).toBe("http://localhost:4242/submit/individual/");
  expect(otp.body).toEqual({ email: "visitor@example.com", create_user: true });
  await page.goto("/submit/individual/#access_token=synthetic-test-token&refresh_token=synthetic-refresh&type=magiclink");
  await expect(page.getByRole("status")).toContainText("Email verified");
  expect(page.url()).toBe("http://localhost:4242/submit/individual/");
  await page.addScriptTag({ path: require.resolve("axe-core/axe.min.js") });
  const violations = await page.evaluate(async () => (await (window as any).axe.run(document, { runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"] } })).violations);
  expect(violations).toEqual([]);
  await expect(page.locator('[name="name_ko"]')).toHaveValue(payload.name_ko);
  expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toContain("synthetic-test-token");
  await page.getByRole("button", {name:/Submit for review/}).click();
  await expect(page.locator("#image-error")).toContainText("Choose");
  await page.locator('[name="image"]').setInputFiles({name:"poster.png",mimeType:"image/png",buffer:Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6XcAAAAASUVORK5CYII=","base64")});
  await expect(page.locator("[data-image-preview]")).toBeVisible();
  await page.locator('[name="closing_date"]').fill("2026-09-01");
  await page.getByRole("button", { name: /Submit for review/ }).click();
  await expect(page.locator("#closing_date-error")).toContainText("End date");
  expect(attempts).toHaveLength(0);
  await page.locator('[name="closing_date"]').fill(payload.closing_date);
  await page.getByRole("button", { name: /Submit for review/ }).click();
  await expect(page.getByRole("status")).toContainText("Retry");
  await page.getByRole("button", { name: /Submit for review/ }).click();
  await expect(page.getByRole("status")).toContainText("Verify your email again");
  await page.goto("/submit/individual/#access_token=renewed-synthetic-token");
  await page.locator('[name="image"]').setInputFiles({name:"poster.png",mimeType:"image/png",buffer:Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6XcAAAAASUVORK5CYII=","base64")});
  await expect(page.getByRole("status")).toContainText("Email verified");
  await page.getByRole("button", { name: /Submit for review/ }).click();
  await expect(page.getByRole("status")).toContainText("not yet published");
  expect(attempts).toHaveLength(3);
  expect(uploads).toBe(1);
  expect(attempts[0].p_payload.image_asset_id).toBe("00000000-0000-4000-8000-000000000087");
  expect(attempts[1]).toEqual(attempts[2]);
  expect(attempts[0]).toEqual(attempts[1]);
  expect(attempts[0].p_payload.name_ko).toBe(payload.name_ko);
  expect(page.url()).not.toContain("gallery.");
});

test("expired link can request another verification email", async ({ page }) => {
  await enableForm(page);
  await page.goto("/submit/individual/#error=access_denied&error_description=expired");
  await expect(page.getByRole("status")).toContainText("Request a new");
  expect(page.url()).not.toContain("error");
  await expect(page.getByRole("button", { name: /Send verification link/ })).toBeEnabled();
  await expect(page.getByRole("button", { name: /Submit for review/ })).toBeDisabled();
});


test("unverified identity cannot unlock submit", async ({ page }) => {
  await enableForm(page);
  await page.route("https://individual-test.supabase.co/auth/v1/user", route => route.fulfill({ json: { email: "visitor@example.com", email_confirmed_at: null } }));
  await page.goto("/submit/individual/#access_token=unverified-synthetic-token");
  await expect(page.getByRole("status")).toContainText("Verification failed");
  await expect(page.getByRole("button", { name: /Submit for review/ })).toBeDisabled();
  expect(page.url()).not.toContain("token");
});
