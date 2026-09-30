"use strict";
const {
  UUID,
  validateDraft,
  validatePoster,
  readRegistration,
} = require("./domain.js");
function fail(error) {
  const message = typeof error?.message === "string" ? error.message : "";
  const category = message.includes("rate_limited")
    ? "rate_limited"
    : message.includes("expired")
      ? "expired"
      : message.includes("conflict")
        ? "conflict"
        : error?.code === "42501"
          ? "auth"
          : "request";
  throw new Error("registration_" + category);
}
/** Narrow authenticated RPC/storage adapter; UI never receives raw provider errors. */
function createRegistrationRepository(client) {
  return {
    async submit(requestId, draft, poster) {
      if (
        !UUID.test(requestId) ||
        validateDraft(draft) ||
        validatePoster(poster)
      )
        throw new Error("registration_invalid");
      const reserved = await client.rpc("artist_reserve_registration", {
        p_request_id: requestId,
        p_mime_type: poster.type,
        p_byte_size: poster.size,
        p_filename: poster.name,
      });
      if (reserved.error) fail(reserved.error);
      const slot = reserved.data;
      const extension = poster.type === "image/png" ? "png" : "jpg";
      const pattern = new RegExp(
        "^submissions/[0-9a-f-]{36}/[0-9a-f-]{36}/original\\." +
          extension +
          "$",
      );
      if (
        !slot ||
        slot.request_id !== requestId ||
        slot.bucket_id !== "exhibition-media" ||
        typeof slot.finalized !== "boolean" ||
        !pattern.test(slot.object_path)
      )
        throw new Error("registration_response_invalid");
      if (!slot.finalized) {
        const uploaded = await client.storage
          .from(slot.bucket_id)
          .upload(slot.object_path, poster, {
            upsert: false,
            contentType: poster.type,
          });
        // An ambiguous upload retry can encounter the same immutable reserved
        // object. Finalization still checks server ownership, MIME and byte size.
        if (uploaded.error && String(uploaded.error.statusCode) !== "409")
          fail(uploaded.error);
      }
      // A committed request can no longer upload. Retrying only the command
      // preserves both its receipt and the changed-content conflict guard.
      const result = await client.rpc("artist_submit_registration", {
        p_request_id: requestId,
        p_payload: {
          name_ko: draft.name_ko,
          artists: draft.artists,
          opening_date: draft.opening_date,
          closing_date: draft.closing_date,
          description_ko: draft.description_ko,
          venue_name_ko: draft.venue_name_ko,
          address_ko: draft.address_ko,
          hours: draft.hours,
        },
        p_name: draft.submitter_name,
        p_relationship: draft.relationship,
        p_email: draft.contact_email,
      });
      if (result.error) fail(result.error);
      if (
        !result.data ||
        !UUID.test(result.data.submission_id) ||
        result.data.status !== "submitted" ||
        Number.isNaN(Date.parse(result.data.submitted_at))
      )
        throw new Error("registration_response_invalid");
      return result.data;
    },
    async list() {
      const result = await client.rpc("artist_list_registrations");
      if (result.error) fail(result.error);
      if (!Array.isArray(result.data) || result.data.length > 20)
        throw new Error("registration_response_invalid");
      return result.data.map(readRegistration);
    },
  };
}
module.exports = { createRegistrationRepository };
