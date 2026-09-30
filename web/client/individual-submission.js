(function (root) {
  "use strict";
  const limits = { name_ko: 300, venue_name_ko: 300, address_ko: 500, hours: 1000, description_ko: 5000 };
  function validDate(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value || "")) return false;
    const date = new Date(value + "T00:00:00Z");
    return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
  }
  function validatePayload(payload) {
    const errors = {};
    for (const [key, max] of Object.entries(limits)) {
      const value = payload[key];
      if (key !== "description_ko" && (typeof value !== "string" || !value.trim())) errors[key] = "required";
      else if (value && (typeof value !== "string" || value.length > max)) errors[key] = "too_long";
    }
    for (const key of ["opening_date", "closing_date"]) if (!validDate(payload[key])) errors[key] = "date_invalid";
    if (!errors.opening_date && !errors.closing_date && payload.closing_date < payload.opening_date) errors.closing_date = "date_order";
    return errors;
  }
  function validateImage(file) {
    if (!file || !file.size) return "required";
    if (!["image/jpeg", "image/png"].includes(file.type)) return "image_type";
    if (file.size > 5 * 1024 * 1024) return "image_size";
    if (!file.name || file.name.length > 255) return "image_name";
    return "";
  }
  function validReservation(value, file) {
    const uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    return value && new RegExp("^" + uuid + "$", "i").test(value.asset_id || "") &&
      value.bucket_id === "exhibition-media" && value.mime_type === file.type && value.byte_size === file.size &&
      new RegExp("^submissions/" + uuid + "/" + value.asset_id + "/original\\." + (file.type === "image/png" ? "png" : "jpg") + "$", "i").test(value.object_path || "");
  }
  function consumeCallback(href) {
    const url = new URL(href);
    const hash = new URLSearchParams(url.hash.slice(1));
    const token = hash.get("access_token") || "";
    const error = hash.has("error") || url.searchParams.has("error");
    if (token || error || hash.has("refresh_token")) url.hash = "";
    for (const key of ["error", "error_code", "error_description"]) url.searchParams.delete(key);
    return { token, cleanUrl: url.pathname + url.search + url.hash, error };
  }
  function createClient(config, fetcher) {
    async function request(path, body, token) {
      if (!config.url || !config.key) throw new Error("unavailable");
      const headers = { apikey: config.key, "Content-Type": "application/json" };
      if (token) headers.Authorization = "Bearer " + token;
      const response = await fetcher(config.url + path, { method: body ? "POST" : "GET", headers, ...(body ? { body: JSON.stringify(body) } : {}), cache: "no-store", referrerPolicy: "no-referrer", signal: AbortSignal.timeout(15000) });
      if (!response.ok) {
        const failure = await response.json().catch(() => ({}));
        const imageUnavailable = ["individual_image_reservation_expired", "individual_image_reservation_invalid"].includes(failure.message);
        const limited = ["individual_image_rate_limited", "submission_rate_limited"].includes(failure.message);
        throw new Error(imageUnavailable ? "image_unavailable" : limited || response.status === 429 ? "rate_limited" : response.status === 401 || response.status === 403 ? "verification_required" : "request_failed");
      }
      return response.json();
    }
    return {
      sendLink: (email, callback) => request("/auth/v1/otp?redirect_to=" + encodeURIComponent(callback), { email, create_user: true }),
      user: (token) => request("/auth/v1/user", null, token),
      reserveImage: async (token, file, requestId) => {
        if (!token) throw new Error("verification_required");
        if (validateImage(file)) throw new Error("image_invalid");
        const receipt = await request("/rest/v1/rpc/reserve_individual_exhibition_image", {p_request_id:requestId, p_mime_type:file.type, p_byte_size:file.size, p_original_filename:file.name}, token);
        if (!validReservation(receipt, file)) throw new Error("invalid_receipt");
        return receipt;
      },
      uploadImage: async (token, file, reservation) => {
        if (!token) throw new Error("verification_required");
        if (!validReservation(reservation, file)) throw new Error("invalid_receipt");
        const response = await fetcher(config.url + "/storage/v1/object/exhibition-media/" + reservation.object_path, {
          method:"POST", headers:{apikey:config.key, Authorization:"Bearer " + token, "Content-Type":file.type, "x-upsert":"false"}, body:file,
          cache:"no-store", referrerPolicy:"no-referrer", signal:AbortSignal.timeout(60000),
        });
        if (response.ok) return;
        if (response.status === 401 || response.status === 403) throw new Error("verification_required");
        // Storage can have accepted an upload whose response was lost. Intake verifies its actual metadata.
        const failure = await response.json().catch(() => ({}));
        if ((response.status === 400 || response.status === 409) && ["Duplicate", "ResourceAlreadyExists", "KeyAlreadyExists", "already_exists"].includes(failure.code || failure.error)) return;
        throw new Error("image_upload_failed");
      },
      submit: async (token, payload, requestId) => {
        if (!token) throw new Error("verification_required");
        const receipt = await request("/rest/v1/rpc/submit_individual_exhibition", { p_payload: payload, p_request_id: requestId }, token);
        if (receipt.status !== "submitted" || !/^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(receipt.submission_id || "")) throw new Error("invalid_receipt");
        return receipt;
      },
    };
  }
  async function init() {
    const container = document.querySelector("[data-individual-submission]");
    if (!container) return;
    const callback = consumeCallback(location.href);
    history.replaceState(null, "", callback.cleanUrl);
    const status = container.querySelector("[data-status]");
    const notice = container.querySelector("[data-unavailable]");
    const form = container.querySelector("[data-exhibition-form]");
    const emailForm = container.querySelector("[data-email-form]");
    const submitButton = form.querySelector("button");
    const verifyButton = emailForm.querySelector("button");
    const identity = container.querySelector("[data-identity]");
    const switchButton = container.querySelector("[data-switch-email]");
    const imageInput = form.elements.namedItem("image");
    const preview = container.querySelector("[data-image-preview]");
    let previewUrl = "", imageState = null, draftEmail = "";
    const client = createClient({ url: container.dataset.url, key: container.dataset.key }, fetch);
    let token = "", requestId = "", fingerprint = "", verifiedEmail = "", busy = false, verificationAttempt = 0;
    const storageKey = "gallr.individual-submission.draft.v1";
    const message = (text) => { status.textContent = text; };
    const payload = () => Object.fromEntries([...Object.keys(limits), "opening_date", "closing_date"].map(key => [key, form.elements.namedItem(key).value]));
    function saveDraft() {
      try { sessionStorage.setItem(storageKey, JSON.stringify({ payload: payload(), requestId, fingerprint, imageState, draftEmail: verifiedEmail || draftEmail })); } catch { /* Storage is optional; the form still works. */ }
    }
    try {
      const draft = JSON.parse(sessionStorage.getItem(storageKey) || "null");
      if (draft && draft.payload) for (const [key, value] of Object.entries(draft.payload)) {
        if (form.elements.namedItem(key) && typeof value === "string") form.elements.namedItem(key).value = value;
      }
      requestId = draft?.requestId || ""; fingerprint = draft?.fingerprint || "";
      imageState = draft?.imageState || null; draftEmail = draft?.draftEmail || "";
    } catch { /* Ignore corrupt or unavailable draft storage. */ }
    if (!container.dataset.url || !container.dataset.key) return;
    notice.hidden = true; form.hidden = false; emailForm.hidden = false;
    form.addEventListener("input", (event) => { if (event.target === imageInput) return; requestId = ""; fingerprint = ""; saveDraft(); });
    imageInput.addEventListener("change", () => {
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      const file = imageInput.files[0];
      preview.hidden = Boolean(validateImage(file));
      if (!preview.hidden) { previewUrl = URL.createObjectURL(file); preview.src = previewUrl; }
      else { preview.removeAttribute("src"); }
      form.querySelector("#image-error").textContent = validateImage(file) ? "! JPEG 또는 PNG 이미지(최대 5 MiB)를 선택하세요. Choose a JPEG or PNG image, up to 5 MiB." : "";
    });
    function forgetIdentity(resetRequest = false) {
      token = ""; verifiedEmail = ""; verificationAttempt += 1; submitButton.disabled = true; emailForm.hidden = false; switchButton.hidden = true;
      identity.textContent = "";
      if (resetRequest) { requestId = ""; fingerprint = ""; imageState = null; draftEmail = ""; saveDraft(); }
    }
    switchButton.addEventListener("click", () => { forgetIdentity(true); message("다른 이메일을 인증해 주세요. Verify another email."); });
    async function verifyCallback(next) {
      if (next.error) { forgetIdentity(); message("! 인증 링크가 만료되었거나 유효하지 않습니다. 새 링크를 요청하세요. Request a new verification link."); }
      if (!next.token) return;
      const attempt = ++verificationAttempt;
      token = ""; submitButton.disabled = true;
      try {
        const user = await client.user(next.token);
        if (attempt !== verificationAttempt) return;
        if (!user.email || !user.email_confirmed_at || user.is_anonymous) throw new Error("verification_required");
        if ((verifiedEmail || draftEmail) && (verifiedEmail || draftEmail) !== user.email) { requestId = ""; fingerprint = ""; imageState = null; }
        verifiedEmail = user.email; draftEmail = user.email; saveDraft();
        token = next.token; identity.textContent = "인증됨 / Verified: " + user.email;
        emailForm.hidden = true; switchButton.hidden = false; submitButton.disabled = false;
        message("이메일 인증이 완료되었습니다. 전시 정보를 확인하고 제출하세요. Email verified. Review your exhibition and submit.");
      } catch { if (attempt !== verificationAttempt) return; forgetIdentity(); message("! 이메일 인증을 확인할 수 없습니다. 새 링크를 요청하세요. Verification failed. Request a new link."); }
    }
    await verifyCallback(callback);
    window.addEventListener("hashchange", () => {
      const next = consumeCallback(location.href);
      history.replaceState(null, "", next.cleanUrl);
      verifyCallback(next);
    });
    emailForm.addEventListener("submit", async (event) => {
      event.preventDefault(); if (busy || !emailForm.reportValidity()) return;
      busy = true; verifyButton.disabled = true; saveDraft();
      try {
        await client.sendLink(emailForm.elements.email.value.trim(), location.origin + "/submit/individual/");
        message("이메일에서 인증 링크를 열어 주세요. 이 탭으로 돌아오면 전시 정보를 제출할 수 있습니다. Open the link in your email, then complete this form. If a new tab opens, enter your exhibition details there.");
      } catch { message("! 인증 이메일을 보내지 못했습니다. 잠시 후 다시 시도하세요. Could not send the verification email. Try again shortly."); }
      finally { busy = false; verifyButton.disabled = false; }
    });
    form.addEventListener("submit", async (event) => {
      event.preventDefault(); if (busy || !token) return;
      const data = payload(), errors = validatePayload(data);
      const file = imageInput.files[0], imageError = validateImage(file);
      if (imageError) errors.image = imageError;
      for (const element of form.querySelectorAll("[data-error-for]")) {
        const code = errors[element.dataset.errorFor];
        element.textContent = !code ? "" : element.dataset.errorFor === "image" ? "! JPEG 또는 PNG 이미지(최대 5 MiB)를 선택하세요. Choose a JPEG or PNG image, up to 5 MiB." : code === "date_order" ? "! 종료일은 시작일 이후여야 합니다. End date must be on or after start date." : "! 필수 항목과 날짜를 확인하세요. Check this field.";
        form.elements.namedItem(element.dataset.errorFor).setAttribute("aria-invalid", String(Boolean(code)));
      }
      if (Object.keys(errors).length) { form.elements.namedItem(Object.keys(errors)[0]).focus(); return; }
      busy = true; submitButton.disabled = true; switchButton.disabled = true;
      const fields = [...form.querySelectorAll("input,textarea")];
      fields.forEach(field => { field.disabled = true; });
      try {
        message("이미지 확인 중… Checking image…");
        const digest = [...new Uint8Array(await crypto.subtle.digest("SHA-256", await file.arrayBuffer()))].map(byte => byte.toString(16).padStart(2, "0")).join("");
        const imageFingerprint = JSON.stringify([digest, file.name, file.type, file.size]);
        if (!imageState || imageState.fingerprint !== imageFingerprint) {
          imageState = {fingerprint:imageFingerprint, requestId:crypto.randomUUID(), reservation:null, uploaded:false};
          requestId = ""; fingerprint = "";
        }
        saveDraft();
        if (!imageState.reservation) imageState.reservation = await client.reserveImage(token, file, imageState.requestId);
        if (!imageState.uploaded) {
          message("이미지 업로드 중… Uploading image…");
          await client.uploadImage(token, file, imageState.reservation);
          imageState.uploaded = true;
        }
        data.image_asset_id = imageState.reservation.asset_id;
        const body = JSON.stringify(data);
        if (!requestId || fingerprint !== body) { requestId = crypto.randomUUID(); fingerprint = body; }
        saveDraft();
        message("제출 중… Submitting…");
        const receipt = await client.submit(token, data, requestId);
        form.hidden = true; switchButton.hidden = true; token = "";
        try { sessionStorage.removeItem(storageKey); } catch { /* No draft storage available. */ }
        message("검토 요청이 접수되었습니다. 아직 공개되지 않았습니다. Submitted for staff review; not yet published. 접수 번호 / Receipt: " + receipt.submission_id);
      } catch (error) {
        if (error.message === "verification_required") forgetIdentity();
        if (error.message === "image_unavailable") { imageState = null; requestId = ""; fingerprint = ""; }
        message(error.message === "image_unavailable" ? "! 이미지 업로드가 만료되었거나 사용할 수 없습니다. 다시 제출하면 새로 업로드합니다. Image upload unavailable. Retry to upload again." : error.message === "rate_limited" ? "! 등록 요청이 너무 많습니다. 한 시간 후 다시 시도하세요. Too many requests. Try again in an hour." : error.message === "verification_required" ? "! 인증이 만료되었습니다. 이메일을 다시 인증하세요. Verify your email again." : "! 제출을 확인하지 못했습니다. 같은 내용으로 다시 시도할 수 있습니다. We could not confirm submission. Retry with the same details.");
      } finally { busy = false; fields.forEach(field => { field.disabled = false; }); switchButton.disabled = false; if (!form.hidden) saveDraft(); submitButton.disabled = !token; }
    });
  }
  if (typeof module !== "undefined" && module.exports) module.exports = { validatePayload, validateImage, consumeCallback, createClient };
  else { root.IndividualSubmission = { validatePayload, validateImage, consumeCallback, createClient }; init(); }
})(typeof window === "undefined" ? globalThis : window);
