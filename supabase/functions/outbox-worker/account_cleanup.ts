const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

type JsonObject = Record<string, unknown>;

export interface AccountCleanupEvent {
  aggregate_type: string;
  aggregate_id: string;
  event_type: string;
  payload: JsonObject;
}

export interface AccountCleanupBackend {
  identityExists(userId: string): Promise<boolean>;
  listAvatarNames(userId: string): Promise<string[]>;
  removeAvatars(names: string[]): Promise<void>;
}

export class AccountCleanupError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "AccountCleanupError";
  }
}

function accountId(event: AccountCleanupEvent): string {
  if (
    event.event_type !== "account.avatar_cleanup_requested" ||
    event.aggregate_type !== "account" ||
    !UUID_PATTERN.test(event.aggregate_id) ||
    event.payload.bucket_id !== "avatars"
  ) {
    throw new AccountCleanupError(
      "account_cleanup_payload_invalid",
      "Account cleanup event does not match the canonical contract.",
    );
  }
  return event.aggregate_id.toLowerCase();
}

function canonicalAvatarNames(userId: string, names: string[]): string[] {
  const prefix = `${userId}.`;
  return [...new Set(names)].filter((name) =>
    name.startsWith(prefix) && name.length > prefix.length &&
    name.length <= prefix.length + 32 && !name.includes("/") &&
    !/[\u0000-\u001f\u007f]/u.test(name)
  ).sort();
}

export async function cleanupDeletedAccount(
  event: AccountCleanupEvent,
  backend: AccountCleanupBackend,
): Promise<void> {
  const userId = accountId(event);
  if (await backend.identityExists(userId)) {
    throw new AccountCleanupError(
      "account_identity_still_exists",
      "Avatar cleanup waits until the Auth identity has been deleted.",
    );
  }
  const names = canonicalAvatarNames(
    userId,
    await backend.listAvatarNames(userId),
  );
  if (names.length > 0) await backend.removeAvatars(names);
}
