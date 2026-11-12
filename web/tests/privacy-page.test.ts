import { test, expect } from "@playwright/test";

test.describe("privacy policy", () => {
  test("uses the editorial legal-document layout", async ({ page }) => {
    await page.goto("/privacy/");

    await expect(page).toHaveTitle("Privacy Policy — gallr");
    await expect(page.locator('meta[name="description"]')).toHaveAttribute(
      "content",
      "Learn what information gallr uses, why it is used, and the choices available to you.",
    );
    await expect(page.getByRole("heading", { level: 1, name: "Privacy Policy" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Privacy policy sections" })).toBeVisible();
    await expect(page.getByRole("link", { name: /Usage analytics/ })).toHaveAttribute(
      "href",
      "#analytics",
    );
    await expect(page.getByRole("link", { name: /privacy@gallrmap.com/ })).toHaveAttribute(
      "href",
      "mailto:privacy@gallrmap.com",
    );

    const articleWidth = await page.locator(".privacy__article").evaluate(
      (element) => element.getBoundingClientRect().width,
    );
    expect(articleWidth).toBeLessThanOrEqual(680);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
      await page.evaluate(() => document.documentElement.clientWidth),
    );
  });

  test("documents optional identity-free analytics and bounded retention", async ({ page }) => {
    await page.goto("/privacy/");

    const analytics = page.locator("#analytics");
    await expect(
      analytics.getByRole("heading", { level: 2, name: "Optional mobile usage analytics" }),
    ).toBeVisible();
    await expect(analytics).toContainText("Usage analytics is off by default");
    await expect(analytics).toContainText("사용 분석은 기본적으로 꺼져 있습니다");
    await expect(analytics).toContainText("at most 200 unsent events");
    await expect(analytics).toContainText("seven-day deduplication window");
    await expect(analytics).toContainText("24-hour rate-limit window");
    await expect(analytics).toContainText("24-calendar-month reporting window");
    await expect(analytics).toContainText("hourly cleanup");
    await expect(analytics).toContainText("paid promotion");
    await expect(analytics).toContainText("We do not sell them");
    await expect(analytics).toContainText("precise location");
    await expect(analytics).toContainText("recommendation profile");
    await expect(analytics).toContainText("Opening sharing options does not mean");

    const choices = page.locator("#choices");
    await expect(choices.getByRole("heading", { level: 3, name: "Your choice / 선택" })).toBeVisible();
    await expect(choices).toContainText("Settings → Usage analytics");
    await expect(choices).toContainText("설정 → 사용 분석");
    await expect(choices).toContainText("keeps collection paused and offers a retry");
  });

  test("preserves account, sync, and requested-alert disclosures", async ({ page }) => {
    await page.goto("/privacy/");

    const collection = page.locator("#collection");
    await expect(collection).toContainText("Account and profile");
    await expect(collection).toContainText("Individual exhibition submissions");
    await expect(collection).toContainText("not published with your contact email");
    await expect(collection).toContainText("Bookmarks, diary entries, and followed galleries");
    await expect(collection).toContainText("random app-installation identifier");
    await expect(collection).toContainText("not reused for usage analytics");

    const sharing = page.locator("#sharing");
    await expect(sharing).toContainText("Supabase processes account, synced-content, alert");
    await expect(sharing).toContainText("Apple and Google process push addresses");
  });

  test("documents the private diary, public notes, reports and hidden people", async ({ page }) => {
    await page.goto("/privacy/");

    await expect(page.locator(".privacy__updated")).toHaveText("Effective 12 November 2026");

    const collection = page.locator("#collection");
    await expect(collection).toContainText("Your diary");
    await expect(collection).toContainText(
      "your visit date, an overall feeling, up to three tags, and a private note",
    );
    await expect(collection).toContainText("synced to your account as private data");
    await expect(collection).toContainText("Only you can read them in the app");
    await expect(collection).toContainText("used only on your device to tune 내 취향 recommendations");
    await expect(collection).toContainText("never sent to usage analytics");
    await expect(collection).toContainText("방문 날짜, 전체적인 기분, 최대 3개의 태그와 나만 보는 메모");

    await expect(collection).toContainText("Public notes and reports");
    await expect(collection).toContainText("staff review its text before it is published");
    await expect(collection).toContainText("your visit date, feeling, and tags stay private");
    await expect(collection).toContainText("An edited note returns to review");
    await expect(collection).toContainText("not who sent them");
    await expect(collection).toContainText("stored only on your device and is never sent to us");
    await expect(collection).toContainText("이 기기에만 저장되며 gallr로 전송되지 않습니다");

    const analytics = page.locator("#analytics");
    await expect(analytics).toContainText("diary feelings, tags, or private notes, reports");
    await expect(analytics).toContainText("일기의 기분·태그·메모, 신고");

    const retention = page.locator("#retention");
    await expect(retention).toContainText(
      "Deleting your account deletes the diary entries, published notes, and reports linked to it",
    );
    await expect(retention).toContainText("일기 기록, 공개한 감상, 신고가 다른 동기화 정보와 함께 삭제됩니다");
  });

  test("gives privacy navigation links accessible targets and focus treatment", async ({ page }) => {
    await page.goto("/privacy/");

    const analyticsLink = page.getByRole("link", { name: /Usage analytics/ });
    const targetHeight = await analyticsLink.evaluate(
      (element) => element.getBoundingClientRect().height,
    );
    expect(targetHeight).toBeGreaterThanOrEqual(44);

    await analyticsLink.focus();
    const focusedStyle = await analyticsLink.evaluate((element) => {
      const style = getComputedStyle(element);
      return { color: style.color, decoration: style.textDecorationLine };
    });
    expect(focusedStyle.color).toBe("rgb(0, 0, 0)");
    expect(focusedStyle.decoration).toContain("underline");
  });

  test("keeps the policy readable on mobile", async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.goto("/privacy/");

    const titleSize = await page.locator(".privacy__header h1").evaluate(
      (element) => parseFloat(getComputedStyle(element).fontSize),
    );
    expect(titleSize).toBeLessThanOrEqual(40);
    await expect(page.locator(".privacy__contents ol")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(
      await page.evaluate(() => document.documentElement.clientWidth),
    );
  });
});
