"use strict";
const { createClient } = require("@supabase/supabase-js");
const { validateDraft, validatePoster, statusLabel } = require("./domain.js");
const { createRegistrationRepository } = require("./adapter.js");
const root = document.querySelector("[data-registration-root]");
if (root) start();
function start() {
  const config = JSON.parse(
    document.getElementById("registration-config").textContent,
  );
  if (!config.enabled) return;
  const client = createClient(config.url, config.key);
  const repository = createRegistrationRepository(client);
  const form = root.querySelector("[data-draft-form]");
  const authForm = root.querySelector("[data-auth-form]");
  const errorElement = root.querySelector("[data-registration-error]");
  const statusElement = root.querySelector("[data-registration-status]");
  const app = root.querySelector("[data-registration-app]");
  let user = null,
    busy = false,
    signup = false,
    destination = "preview",
    requestId = null,
    posterUrl = null,
    records = [];
  app.hidden = false;
  root.querySelector("[data-registration-fallback]").hidden = true;
  const draft = () =>
    Object.fromEntries(
      Array.from(form.elements)
        .filter((field) => field.name && field.name !== "poster")
        .map((field) => [field.name, field.value]),
    );
  const poster = () => form.elements.poster.files[0];
  const messages = {
    details:
      "전시명, 작가와 소개를 확인해주세요. / Check title, artists and description.",
    dates: "전시 시작일과 종료일을 확인해주세요. / Check the exhibition dates.",
    venue:
      "장소, 주소와 관람 시간을 입력해주세요. / Enter venue, address and hours.",
    contact:
      "이름과 올바른 연락 이메일을 입력해주세요. / Enter your name and a valid contact email.",
    relationship:
      "전시에 참여하거나 주최하는 역할을 선택해주세요. / Choose your role in this exhibition.",
    poster:
      "10 MiB 이하의 JPEG/PNG 포스터를 첨부해주세요. / Attach a JPEG/PNG poster up to 10 MiB.",
  };
  function notice(message, isError = false) {
    (isError ? errorElement : statusElement).textContent = isError ? "! " + message : message;
  }
  function show(name) {
    errorElement.textContent = "";
    statusElement.textContent = "";
    root.querySelectorAll("[data-pane]").forEach((pane) => {
      pane.hidden = pane.dataset.pane !== name;
    });
    const pane = root.querySelector(`[data-pane="${name}"]`);
    const target = pane.querySelector("h2");
    if (target) {
      target.setAttribute("tabindex", "-1");
      target.focus({ preventScroll: true });
    }
    pane.scrollIntoView({ block: "start", behavior: "instant" });
  }
  function account() {
    root.querySelector('[data-action="signout"]').hidden = !user;
  }
  function reset() {
    form.reset();
    root.querySelector("[name=rights]").checked = false;
    requestId = null;
    if (posterUrl) URL.revokeObjectURL(posterUrl);
    posterUrl = null;
    const image = root.querySelector("[data-poster-preview]");
    image.removeAttribute("src");
    image.hidden = true;
  }
  async function operation(callback) {
    if (busy) return;
    busy = true;
    errorElement.textContent = "";
    app.setAttribute("aria-busy", "true");
    app.querySelectorAll("button,input,select,textarea").forEach((el) => {
      el.disabled = true;
    });
    try {
      await callback();
    } catch (error) {
      const code = String(error?.message || "");
      if (code.includes("auth")) {
        user = null;
        account();
        show("auth");
      }
      const message = code.includes("rate_limited")
        ? "등록 요청이 많아요. 잠시 후 다시 시도해주세요. / Too many requests. Please try later."
        : code.includes("auth")
          ? "이메일 인증을 완료한 계정으로 다시 로그인해주세요. / Sign in with an email-verified account."
          : code.includes("conflict")
            ? "이 요청의 내용이 달라졌어요. 등록 내역을 먼저 확인해주세요. / This request changed. Check My submissions before starting a new request."
            : code.includes("expired")
              ? "업로드 요청이 만료됐어요. 새 전시 등록으로 다시 시작해주세요. / This upload reservation expired. Start a new submission."
              : "요청을 완료하지 못했어요. 등록 내역을 먼저 확인하거나 같은 요청을 다시 시도해주세요. / Unable to confirm completion. Check My submissions or retry the same request.";
      notice(message, true);
      console.warn("registration_operation_failed", {
        code: code.startsWith("registration_")
          ? code
          : "registration_auth_request",
      });
    } finally {
      busy = false;
      app.removeAttribute("aria-busy");
      app.querySelectorAll("button,input,select,textarea").forEach((el) => {
        el.disabled = false;
      });
    }
  }
  function checkPane(name) {
    const fields = root
      .querySelector(`[data-pane="${name}"]`)
      .querySelectorAll("input,textarea,select");
    for (const field of fields) if (!field.reportValidity()) return false;
    return true;
  }
  function preview() {
    const d = draft();
    const rows = [
      ["전시 / Exhibition", d.name_ko],
      ["작가 / Artists", d.artists],
      ["기간 / Dates", d.opening_date + " — " + d.closing_date],
      ["장소 / Venue", d.venue_name_ko],
      ["주소 / Address", d.address_ko],
      ["관람 시간 / Hours", d.hours],
      ["소개 / Description", d.description_ko],
      ["제출자 / Submitter", d.submitter_name],
      ["연락처 / Contact", d.contact_email],
    ];
    const list = root.querySelector("[data-preview]");
    list.replaceChildren();
    rows.forEach(([label, value]) => {
      const row = document.createElement("div"),
        term = document.createElement("dt"),
        text = document.createElement("dd");
      term.textContent = label;
      text.textContent = value;
      row.append(term, text);
      list.append(row);
    });
    if (posterUrl) URL.revokeObjectURL(posterUrl);
    posterUrl = URL.createObjectURL(poster());
    const image = root.querySelector("[data-poster-preview]");
    image.src = posterUrl;
    image.hidden = false;
    show("preview");
  }
  async function list() {
    records = await repository.list();
    const container = root.querySelector("[data-records]");
    container.replaceChildren();
    if (!records.length) {
      const empty = document.createElement("p");
      empty.textContent = "등록 내역이 없습니다. / No submissions yet.";
      container.append(empty);
    }
    records.forEach((record, index) => {
      const item = document.createElement("article");
      item.className = "registration-record";
      const heading = document.createElement("h3");
      heading.textContent = record.payload.name_ko;
      const state = document.createElement("p");
      state.textContent =
        statusLabel(record) + " / " + statusLabel(record, "en");
      const venue = document.createElement("p");
      venue.textContent = record.payload.venue_name_ko || "";
      const notes = document.createElement("p");
      notes.textContent = record.review_notes;
      item.append(heading, state, venue, notes);
      if (record.status === "rejected") {
        const copy = document.createElement("button");
        copy.type = "button";
        copy.className = "registration-text-link";
        copy.dataset.copy = String(index);
        copy.textContent =
          "정보 보완 후 새 요청 작성 → / Prepare a new request";
        item.append(copy);
      }
      container.append(item);
    });
    show("records");
  }
  form.addEventListener("submit", (event) => event.preventDefault());
  form.addEventListener("input", () => {
    root.querySelector("[name=rights]").checked = false;
  });
  root.addEventListener("click", (event) => {
    const copy = event.target.closest("[data-copy]");
    if (copy && !busy) {
      const payload = records[Number(copy.dataset.copy)]?.payload;
      if (!payload) return;
      reset();
      Object.entries(payload).forEach(([key, value]) => {
        if (form.elements[key] && typeof value === "string")
          form.elements[key].value = value;
      });
      const prefix = "참여 작가: " + payload.artists + "\n\n";
      if (form.elements.description_ko.value.startsWith(prefix))
        form.elements.description_ko.value =
          form.elements.description_ko.value.slice(prefix.length);
      show("details");
      notice(
        "이전 검토 내역은 유지됩니다. 포스터와 연락처를 확인하고 새 요청을 제출해주세요. / The previous review is retained. Attach a poster and confirm contact details for the new request.",
      );
      return;
    }
    const action = event.target.closest("[data-action]")?.dataset.action;
    if (!action || busy) return;
    if (action === "details") show("details");
    else if (action === "venue") {
      if (!checkPane("details")) return;
      const d = draft();
      const check =
        validateDraft({
          ...d,
          venue_name_ko: "place",
          address_ko: "address",
          hours: "hours",
          submitter_name: "name",
          relationship: "artist",
          contact_email: "a@example.com",
        }) || validatePoster(poster());
      if (check) return notice(messages[check], true);
      show("venue");
    } else if (action === "contact") {
      if (checkPane("venue")) show("contact");
    } else if (action === "venue-back") show("venue");
    else if (action === "contact-back") show("contact");
    else if (action === "preview") {
      if (!checkPane("contact")) return;
      const invalid = validateDraft(draft()) || validatePoster(poster());
      if (invalid) return notice(messages[invalid], true);
      if (user) preview();
      else {
        destination = "preview";
        authForm.elements.auth_email.value = draft().contact_email;
        show("auth");
      }
    } else if (action === "toggle-signup") {
      signup = !signup;
      root.querySelector("[data-auth-button]").textContent = signup
        ? "계정 만들기 / CREATE ACCOUNT"
        : "로그인하고 계속 / SIGN IN";
      root.querySelector('[data-action="toggle-signup"]').textContent = signup
        ? "이미 계정이 있어요 / Sign in instead"
        : "계정 만들기 / Create an account";
      authForm.elements.password.autocomplete = signup
        ? "new-password"
        : "current-password";
      authForm.elements.password.minLength = signup ? 8 : 1;
      root.querySelector("[data-signup-note]").hidden = !signup;
    } else if (action === "submit") {
      if (!root.querySelector("[name=rights]").checked)
        return notice(
          "전시 관계와 정보·이미지 사용 권한을 확인해주세요. / Confirm your exhibition role and permission to submit.",
          true,
        );
      const d = draft(),
        file = poster();
      const invalid = validateDraft(d) || validatePoster(file);
      if (invalid) return notice(messages[invalid], true);
      if (!user) {
        destination = "preview";
        show("auth");
        return;
      }
      requestId = requestId || crypto.randomUUID();
      operation(async () => {
        notice("제출 중입니다. / Submitting…");
        await repository.submit(requestId, d, file);
        show("success");
      });
    } else if (action === "records" || action === "refresh") {
      if (!user) {
        destination = "records";
        show("auth");
      } else operation(list);
    } else if (action === "new") {
      reset();
      show("details");
    } else if (action === "signout")
      operation(async () => {
        const result = await client.auth.signOut({ scope: "local" });
        if (result.error) throw new Error("registration_auth");
        user = null;
        records = [];
        root.querySelector("[data-records]").replaceChildren();
        reset();
        authForm.reset();
        account();
        show("details");
      });
  });
  authForm.addEventListener("submit", (event) => {
    event.preventDefault();
    if (!authForm.reportValidity()) return;
    const credentials = {
      email: authForm.elements.auth_email.value.trim(),
      password: authForm.elements.password.value,
    };
    operation(async () => {
      const result = signup
        ? await client.auth.signUp(credentials)
        : await client.auth.signInWithPassword(credentials);
      authForm.elements.password.value = "";
      if (result.error) throw new Error("registration_auth");
      if (!result.data.session) {
        notice(
          "이메일에서 인증을 완료한 뒤 로그인해주세요. / Verify your email, then sign in.",
        );
        return;
      }
      const identity = await client.auth.getUser();
      if (
        identity.error ||
        !identity.data.user?.email_confirmed_at ||
        identity.data.user.is_anonymous
      )
        throw new Error("registration_auth");
      user = identity.data.user;
      account();
      if (destination === "records") await list();
      else preview();
    });
  });
  client.auth.onAuthStateChange((event, session) => {
    if (
      event === "SIGNED_OUT" ||
      (user && session?.user && session.user.id !== user.id)
    ) {
      user = null;
      records = [];
      root.querySelector("[data-records]").replaceChildren();
      reset();
      authForm.reset();
      account();
      show("details");
    }
  });
  client.auth
    .getSession()
    .then(async (result) => {
      if (!result.data.session) return;
      const identity = await client.auth.getUser();
      if (
        !identity.error &&
        identity.data.user?.email_confirmed_at &&
        !identity.data.user.is_anonymous
      ) {
        user = identity.data.user;
        account();
      }
    })
    .catch(() =>
      notice(
        "로그인 상태를 확인하지 못했어요. 다시 로그인해주세요. / Please sign in again.",
        true,
      ),
    );
}
