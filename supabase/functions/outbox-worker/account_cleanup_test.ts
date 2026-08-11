import { assertEquals, assertRejects } from "jsr:@std/assert@1.0.19";

import {
  type AccountCleanupBackend,
  AccountCleanupError,
  type AccountCleanupEvent,
  cleanupDeletedAccount,
} from "./account_cleanup.ts";

const USER_ID = "00000000-0000-0000-0000-000000000001";

function event(
  overrides: Partial<AccountCleanupEvent> = {},
): AccountCleanupEvent {
  return {
    aggregate_type: "account",
    aggregate_id: USER_ID,
    event_type: "account.avatar_cleanup_requested",
    payload: { bucket_id: "avatars" },
    ...overrides,
  };
}

class FakeBackend implements AccountCleanupBackend {
  exists = false;
  names: string[] = [];
  removals: string[][] = [];

  identityExists(): Promise<boolean> {
    return Promise.resolve(this.exists);
  }

  listAvatarNames(): Promise<string[]> {
    return Promise.resolve(this.names);
  }

  removeAvatars(names: string[]): Promise<void> {
    this.removals.push(names);
    return Promise.resolve();
  }
}

Deno.test("account cleanup rejects non-canonical event identity and bucket", async () => {
  const backend = new FakeBackend();
  for (
    const candidate of [
      event({ aggregate_type: "media_asset" }),
      event({ aggregate_id: "not-a-uuid" }),
      event({ event_type: "account.other" }),
      event({ payload: { bucket_id: "exhibition-images" } }),
    ]
  ) {
    await assertRejects(
      () => cleanupDeletedAccount(candidate, backend),
      AccountCleanupError,
      "canonical contract",
    );
  }
});

Deno.test("account cleanup retries while the Auth identity exists", async () => {
  const backend = new FakeBackend();
  backend.exists = true;
  backend.names = [`${USER_ID}.jpg`];
  await assertRejects(
    () => cleanupDeletedAccount(event(), backend),
    AccountCleanupError,
    "waits until",
  );
  assertEquals(backend.removals, []);
});

Deno.test("account cleanup removes only exact root avatar names", async () => {
  const backend = new FakeBackend();
  backend.names = [
    `${USER_ID}.png`,
    `${USER_ID}.jpg`,
    `${USER_ID}.jpg`,
    `${USER_ID}/nested.jpg`,
    `prefix-${USER_ID}.jpg`,
    `${USER_ID}.jpg/child`,
    `${USER_ID}.` + "x".repeat(33),
  ];
  await cleanupDeletedAccount(event(), backend);
  assertEquals(backend.removals, [[`${USER_ID}.jpg`, `${USER_ID}.png`]]);
});

Deno.test("account cleanup succeeds when no avatar exists", async () => {
  const backend = new FakeBackend();
  await cleanupDeletedAccount(event(), backend);
  assertEquals(backend.removals, []);
});
