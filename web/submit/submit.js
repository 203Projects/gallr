(function () {
  const REQUIRED_FIELDS = [
    "name_ko",
    "venue_name_ko",
    "opening_date",
    "closing_date",
    "address_ko",
    "hours",
    "contact",
  ];
  const MAX_IMAGES = 5;
  const MAX_IMAGE_BYTES = 6 * 1024 * 1024;
  const IMAGE_TYPES = new Set(["image/jpeg", "image/png"]);

  function isValidEmail(value) {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(value || "").trim());
  }

  // Convert a 12-hour h/m/AM-PM trio into a 24-hour "HH:MM" string, or null
  // if the hour is missing/out of the 1-12 range. Minute defaults to "00".
  function to24h(hour, minute, ampm) {
    const h = parseInt(String(hour || "").trim(), 10);
    if (!Number.isInteger(h) || h < 1 || h > 12) return null;
    let m = parseInt(String(minute || "").trim(), 10);
    if (!Number.isInteger(m) || m < 0 || m > 59) m = 0;
    const isPm = String(ampm || "PM").toUpperCase() === "PM";
    let h24 = h % 12; // 12 → 0
    if (isPm) h24 += 12; // 12 PM → 12, 1 PM → 13 … ; 12 AM → 0
    const pad = (n) => String(n).padStart(2, "0");
    return pad(h24) + ":" + pad(m);
  }

  // Fix 3: the form collects the opening reception as date + start (h/m/AMPM)
  // + end (h/m/AMPM). Supabase stores a single `reception_date` timestamp, so
  // the client combines date + start into it. `reception_end` is collected too
  // and forwarded to the review sheet (no Supabase column yet — see
  // Canonical Edge Function payload. No date → both values remain empty.
  function composeReception(fields) {
    const day = String((fields || {}).reception_date_day || "").trim();
    if (!day) return { reception_date: "", reception_end: "" };
    const start = to24h(fields.reception_start_h, fields.reception_start_m, fields.reception_start_ampm);
    const end = to24h(fields.reception_end_h, fields.reception_end_m, fields.reception_end_ampm);
    return {
      reception_date: start ? day + "T" + start : "",
      reception_end: end ? day + "T" + end : "",
    };
  }

  function validateSubmission(fields, files) {
    const errors = {};
    REQUIRED_FIELDS.forEach((field) => {
      if (!String(fields[field] || "").trim()) {
        errors[field] = "required";
      }
    });

    if (
      fields.opening_date &&
      fields.closing_date &&
      String(fields.closing_date) < String(fields.opening_date)
    ) {
      errors.closing_date = "before_opening_date";
    }

    if (fields.contact && !isValidEmail(fields.contact)) {
      errors.contact = "invalid_email";
    }

    // Fix 3: reception start time becomes required once a reception date is
    // entered; an out-of-range hour is flagged. With no date, all three
    // reception fields stay optional.
    if (String(fields.reception_date_day || "").trim()) {
      if (!String(fields.reception_start_h || "").trim()) {
        errors.reception_start = "required";
      } else if (to24h(fields.reception_start_h, fields.reception_start_m, fields.reception_start_ampm) === null) {
        errors.reception_start = "invalid_time";
      }
    }

    const imageFiles = Array.from(files || []);
    if (imageFiles.length > MAX_IMAGES) {
      errors.images = "too_many";
    } else if (imageFiles.some((file) => !IMAGE_TYPES.has(file.type))) {
      errors.images = "invalid_type";
    } else if (imageFiles.some((file) => file.size > MAX_IMAGE_BYTES)) {
      errors.images = "too_large";
    }

    return { valid: Object.keys(errors).length === 0, errors };
  }

  const RECEPTION_RAW_FIELDS = [
    "reception_date_day",
    "reception_start_h", "reception_start_m", "reception_start_ampm",
    "reception_end_h", "reception_end_m", "reception_end_ampm",
  ];

  function collectFields(form) {
    const data = new FormData(form);
    const fields = {};
    for (const [key, value] of data.entries()) {
      if (key !== "images") fields[key] = String(value || "").trim();
    }
    return fields;
  }

  // Build the wire payload: drop the raw reception sub-inputs and replace them
  // with the composed `reception_date` (+ `reception_end`) the backend expects.
  function toPayloadFields(fields) {
    const reception = composeReception(fields);
    const out = {};
    Object.keys(fields).forEach((key) => {
      if (RECEPTION_RAW_FIELDS.indexOf(key) === -1) out[key] = fields[key];
    });
    out.reception_date = reception.reception_date;
    out.reception_end = reception.reception_end;
    return out;
  }

  function messageFor(code) {
    return {
      required: "필수 항목입니다.",
      before_opening_date: "종료일은 시작일 이후여야 합니다.",
      invalid_email: "유효한 이메일을 입력해 주세요.",
      invalid_time: "시간을 확인해 주세요.",
      too_many: "사진은 최대 5장까지 첨부할 수 있습니다.",
      invalid_type: "JPEG 또는 PNG 파일만 첨부할 수 있습니다.",
      too_large: "각 사진은 6MB 이하여야 합니다.",
    }[code] || "입력값을 확인해 주세요.";
  }

  function renderErrors(form, errors) {
    form.querySelectorAll("[data-error-for]").forEach((node) => {
      const key = node.getAttribute("data-error-for");
      // DESIGN.md Error Treatment: monochrome "! message" exclamation prefix.
      node.textContent = errors[key] ? "! " + messageFor(errors[key]) : "";
      node.toggleAttribute("data-active-error", Boolean(errors[key]));
    });
  }

  async function submitForm(form) {
    const endpoint = form.dataset.endpoint || window.GALLR_SUBMIT_ENDPOINT || "";
    const serverError = form.querySelector("[data-server-error]");
    const submitButton = form.querySelector("[data-submit-button]");
    const success = document.querySelector("[data-submit-success]");
    const reference = document.querySelector("[data-submission-reference]");
    const fields = collectFields(form);
    const files = Array.from(form.querySelector('input[name="images"]').files || []);
    const validation = validateSubmission(fields, files);

    renderErrors(form, validation.errors);
    if (serverError) serverError.hidden = true;
    if (!validation.valid) return;

    if (!endpoint) {
      if (serverError) {
        serverError.textContent = "제출 엔드포인트가 설정되지 않았습니다.";
        serverError.hidden = false;
      }
      return;
    }

    submitButton.disabled = true;
    submitButton.textContent = "업로드 중...";
    try {
      const body = new FormData(form);
      RECEPTION_RAW_FIELDS.forEach((field) => body.delete(field));
      const reception = composeReception(fields);
      body.set("reception_date", reception.reception_date);
      body.set("reception_end", reception.reception_end);
      const response = await fetch(endpoint, {
        method: "POST",
        body,
      });
      const result = await response.json();
      if (!response.ok || !result.success) {
        throw new Error(result.error?.message || "submission failed");
      }
      form.hidden = true;
      if (reference && result.submissionId) {
        reference.textContent = "접수 번호: " + result.submissionId;
      }
      if (success) success.hidden = false;
    } catch (error) {
      if (serverError) {
        serverError.textContent = "제출 중 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.";
        serverError.hidden = false;
      }
    } finally {
      submitButton.disabled = false;
      submitButton.textContent = "제출하기";
    }
  }

  // Fix 3: reveal the "*" on 시작 시간 only when a reception date is entered,
  // mirroring the conditional-required rule enforced in validateSubmission.
  // The visual "*" alone is not announced to assistive tech, so we also toggle
  // aria-required on the start-hour input to keep the a11y signal in sync.
  function syncReceptionRequired(form) {
    const dateInput = form.querySelector("[data-reception-date]");
    const startReq = form.querySelector("[data-reception-start-req]");
    const startInput = form.querySelector("[data-reception-start-input]");
    if (!dateInput) return;
    const update = () => {
      const required = Boolean(String(dateInput.value || "").trim());
      if (startReq) startReq.hidden = !required;
      if (startInput) startInput.setAttribute("aria-required", required ? "true" : "false");
    };
    dateInput.addEventListener("input", update);
    dateInput.addEventListener("change", update);
    update();
  }

  function init() {
    const form = document.querySelector("[data-submit-form]");
    if (!form) return;
    syncReceptionRequired(form);
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      submitForm(form);
    });
  }

  if (typeof document !== "undefined") {
    document.addEventListener("DOMContentLoaded", init);
  }

  if (typeof module !== "undefined") {
    module.exports = {
      validateSubmission,
      composeReception,
      toPayloadFields,
    };
  }
})();
