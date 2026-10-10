/** Deliberately accepts only routing metadata; payloads and error messages are never logged. */
export function deliveryLog(
  event: {
    id: string;
    attempts: number;
    max_attempts: number;
    payload?: unknown;
  },
  outcome: "started" | "completed" | "failed" | "lease_lost",
) {
  return {
    operation: "outbox_delivery",
    event_id: /^[0-9a-f-]{36}$/i.test(event.id) ? event.id : "invalid",
    attempt: Number.isInteger(event.attempts) ? event.attempts : 0,
    outcome: outcome === "failed"
      ? event.attempts >= event.max_attempts ? "final_failure" : "retry_pending"
      : outcome,
  };
}
