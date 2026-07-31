import { test, expect } from "@playwright/test";

// Slugs are <slugified name_en>-<first 4 chars of id>. For the test fixture:
//   fx-001-current-featured  → void-forms-fx-0
//   fx-002-closing-soon      → line-and-form-fx-0
//   fx-003-opening-soon      → concrete-brut-fx-0
//   fx-004-closed            → monochrome-studies-fx-0

test.describe("Detail page", () => {
  test("featured fixture renders title, description, tickets, share, directions", async ({ page }) => {
    await page.goto("/exhibitions/void-forms-fx-0/");
    await expect(page.locator(".detail-page__title")).toContainText("보이드 폼");
    await expect(page.locator(".detail-page__about")).toBeVisible();
    await expect(page.locator(".detail-page__about-prose")).toHaveText([
      "공간의 부재를 탐구하는 전시.",
      "이미지 제공: 노이에 갤러리",
      "An exploration of absence in space.",
      "Images courtesy of Neue Galerie",
    ]);
    await expect(page.locator(".detail-page__about")).not.toContainText("CREDITS");
    await expect(page.locator(".detail-page__about")).not.toContainText("크레딧");
    await expect(page.locator(".detail-page__cta-filled")).toHaveAttribute(
      "href",
      "https://tickets.example/fx-001"
    );
    await expect(page.locator("[data-share-button]")).toBeVisible();
    await expect(page.locator(".detail-page__cta-text")).toContainText("길찾기");
    await expect(page.locator(".detail-page__cta-primary")).toContainText("앱에서 보기");
  });

  test("row with no description omits the About block", async ({ page }) => {
    await page.goto("/exhibitions/line-and-form-fx-0/");
    await expect(page.locator(".detail-page__about")).toHaveCount(0);
  });

  test("row with no ticket_url omits the Tickets button", async ({ page }) => {
    await page.goto("/exhibitions/line-and-form-fx-0/");
    await expect(page.locator(".detail-page__cta-filled")).toHaveCount(0);
  });

  test("status chip reflects the row's status", async ({ page }) => {
    await page.goto("/exhibitions/monochrome-studies-fx-0/");
    await expect(page.locator(".status-chip")).toHaveAttribute("data-status", "closed");
  });
});
