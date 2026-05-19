(function () {
  const REQUIRED_FIELDS = [
    "name_ko",
    "venue_name_ko",
    "opening_date",
    "closing_date",
    "address_ko",
    "opening_time",
    "hours",
    "contact",
  ];
  const MAX_IMAGES = 5;
  const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
  const IMAGE_TYPES = new Set(["image/jpeg", "image/png"]);

  function isValidEmail(value) {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(value || "").trim());
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

    const imageFiles = Array.from(files || []);
    if (imageFiles.length === 0) {
      errors.images = "required";
    } else if (imageFiles.length > MAX_IMAGES) {
      errors.images = "too_many";
    } else if (imageFiles.some((file) => !IMAGE_TYPES.has(file.type))) {
      errors.images = "invalid_type";
    } else if (imageFiles.some((file) => file.size > MAX_IMAGE_BYTES)) {
      errors.images = "too_large";
    }

    return { valid: Object.keys(errors).length === 0, errors };
  }

  function fileToPayload(file) {
    const dataUrl = file.dataUrl || "";
    const base64 = dataUrl.includes(",") ? dataUrl.split(",").pop() : dataUrl;
    return {
      name: file.name,
      contentType: file.type,
      base64,
    };
  }

  function readFileAsPayload(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(fileToPayload({
        name: file.name,
        type: file.type,
        dataUrl: String(reader.result || ""),
      }));
      reader.onerror = () => reject(reader.error || new Error("file read failed"));
      reader.readAsDataURL(file);
    });
  }

  function collectFields(form) {
    const data = new FormData(form);
    const fields = {};
    for (const [key, value] of data.entries()) {
      if (key !== "images") fields[key] = String(value || "").trim();
    }
    return fields;
  }

  function messageFor(code) {
    return {
      required: "필수 항목입니다.",
      before_opening_date: "종료일은 시작일 이후여야 합니다.",
      invalid_email: "유효한 이메일을 입력해 주세요.",
      too_many: "사진은 최대 5장까지 첨부할 수 있습니다.",
      invalid_type: "JPEG 또는 PNG 파일만 첨부할 수 있습니다.",
      too_large: "각 사진은 10MB 이하여야 합니다.",
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
    const token = form.dataset.token || "";
    const serverError = form.querySelector("[data-server-error]");
    const submitButton = form.querySelector("[data-submit-button]");
    const success = document.querySelector("[data-submit-success]");
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
      const images = await Promise.all(files.map(readFileAsPayload));
      const response = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "text/plain;charset=utf-8" },
        body: JSON.stringify({ token, fields, images }),
      });
      const result = await response.json();
      if (!response.ok || !result.success) {
        throw new Error(result.error || "submission failed");
      }
      form.hidden = true;
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

  function init() {
    const form = document.querySelector("[data-submit-form]");
    if (!form) return;
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
      fileToPayload,
    };
  }
})();
