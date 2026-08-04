import { MediaSourcePathError, validateSourcePath } from "./path.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(
      `Expected ${String(expected)}, received ${String(actual)}.`,
    );
  }
}

Deno.test("accepts staff, owner, and accepted-submission source paths", () => {
  const assetId = "64248006-462e-4d80-8967-53f1f4bd7df7";
  assertEquals(
    validateSourcePath(
      assetId,
      `drafts/exhibition-1/${assetId}/original.jpg`,
    ),
    "jpg",
  );
  assertEquals(
    validateSourcePath(
      assetId,
      `owner-drafts/305e28a5-b531-41f3-b9f2-2f32dcb9cd17/${assetId}/original.webp`,
    ),
    "webp",
  );
  assertEquals(
    validateSourcePath(
      assetId,
      `submissions/70000000-0000-0000-0000-000000000001/${assetId}/original.png`,
    ),
    "png",
  );
});

Deno.test("rejects unscoped, mismatched, and executable source paths", () => {
  const assetId = "64248006-462e-4d80-8967-53f1f4bd7df7";
  for (
    const path of [
      `${assetId}/original.jpg`,
      `uploads/exhibition-1/${assetId}/original.jpg`,
      `owner-drafts/../${assetId}/original.jpg`,
      "submissions/one/another-asset/original.jpg",
      `submissions/one/${assetId}/original.svg`,
      `submissions/../${assetId}/original.jpg`,
    ]
  ) {
    let rejected = false;
    try {
      validateSourcePath(assetId, path);
    } catch (error) {
      rejected = error instanceof MediaSourcePathError;
    }
    if (!rejected) throw new Error(`Unsafe path was accepted: ${path}`);
  }
});
