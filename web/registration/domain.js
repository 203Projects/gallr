"use strict";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function bounded(value, max) {
  return (
    typeof value === "string" && value.trim().length > 0 && value.length <= max
  );
}
function date(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value))
    return false;
  const parsed = new Date(value + "T00:00:00Z");
  return (
    !Number.isNaN(parsed.valueOf()) &&
    parsed.toISOString().slice(0, 10) === value
  );
}
/** Returns a bounded validation category, never input contents. No gallery identity is required. */
function validateDraft(d) {
  if (
    !d ||
    !bounded(d.name_ko, 300) ||
    !bounded(d.artists, 1000) ||
    !bounded(d.description_ko, 18000)
  )
    return "details";
  if (
    !date(d.opening_date) ||
    !date(d.closing_date) ||
    d.closing_date < d.opening_date
  )
    return "dates";
  if (
    !bounded(d.venue_name_ko, 300) ||
    !bounded(d.address_ko, 500) ||
    !bounded(d.hours, 1000)
  )
    return "venue";
  if (!["artist", "curator", "organizer", "gallery"].includes(d.relationship))
    return "relationship";
  if (
    !bounded(d.submitter_name, 200) ||
    !bounded(d.contact_email, 254) ||
    !/^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(d.contact_email)
  )
    return "contact";
  return null;
}
function validatePoster(file) {
  return file &&
    ["image/png", "image/jpeg"].includes(file.type) &&
    file.size > 0 &&
    file.size <= 10485760 &&
    bounded(file.name, 255)
    ? null
    : "poster";
}
/** Validate unknown RPC responses at the adapter boundary. Approval is distinct from publication. */
function readRegistration(record) {
  if (
    !record ||
    !UUID.test(record.id) ||
    !["submitted", "in_review", "accepted", "rejected", "withdrawn"].includes(
      record.status,
    ) ||
    typeof record.published !== "boolean" ||
    (record.published && record.status !== "accepted") ||
    !record.payload ||
    !bounded(record.payload.name_ko, 300) ||
    typeof record.review_notes !== "string" ||
    typeof record.submitted_at !== "string" ||
    Number.isNaN(Date.parse(record.submitted_at)) ||
    (record.exhibition_id !== null && typeof record.exhibition_id !== "string")
  )
    throw new Error("registration_response_invalid");
  return record;
}
function statusLabel(record, language = "ko") {
  if (record.published) return language === "ko" ? "공개 완료" : "Published";
  const labels = {
    submitted: ["검토 대기", "Awaiting review"],
    in_review: ["검토 중", "In review"],
    accepted: ["승인됨 · 공개 준비 중", "Approved · awaiting publication"],
    rejected: ["반려", "Not accepted"],
    withdrawn: ["철회됨", "Withdrawn"],
  };
  return (
    labels[record.status]?.[language === "ko" ? 0 : 1] ||
    (language === "ko" ? "상태 확인 필요" : "Status unavailable")
  );
}
module.exports = {
  UUID,
  validateDraft,
  validatePoster,
  readRegistration,
  statusLabel,
};
