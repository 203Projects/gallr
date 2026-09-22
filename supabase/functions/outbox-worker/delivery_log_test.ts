import { deliveryLog } from "./delivery_log.ts";

Deno.test("delivery logs exclude payloads, recipients, and provider messages", () => {
  const output = JSON.stringify(deliveryLog({
    id: "00000000-0000-4000-8000-000000000001",
    attempts: 5,
    max_attempts: 5,
    payload: { email: "private@example.com", review_notes: "secret notes" },
  }, "failed"));
  if (
    output.includes("private") || output.includes("secret") ||
    !output.includes("final_failure")
  ) throw new Error("unsafe log");
});

Deno.test("attempt and retry states are distinct and malformed identifiers are suppressed", () => {
  const event = { id: "private@example.com", attempts: 1, max_attempts: 5 };
  if (deliveryLog(event, "started").outcome !== "started") {
    throw new Error("missing attempt");
  }
  const log = deliveryLog(event, "failed");
  if (log.outcome !== "retry_pending" || log.event_id !== "invalid") {
    throw new Error("invalid retry log");
  }
});
