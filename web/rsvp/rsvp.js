(function () {
  "use strict";

  function valid(values) {
    return Boolean(
      values && String(values.name || "").trim().length > 0 &&
      /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(values.email || "").trim()) &&
      Number.isInteger(Number(values.party_size)) && Number(values.party_size) >= 1 &&
      Number(values.party_size) <= 6 && values.privacy_acknowledged === true
    );
  }

  function token() {
    return new URLSearchParams(window.location.search).get("token") || "";
  }

  function text(root, selector, value) {
    var node = root.querySelector(selector);
    if (node) node.textContent = value || "";
  }

  async function init() {
    var root = document.querySelector("[data-rsvp-root]");
    if (!root) return;
    var endpoint = root.dataset.endpoint;
    var publicToken = token();
    var form = root.querySelector("[data-rsvp-form]");
    var error = root.querySelector("[data-rsvp-error]");
    if (!endpoint || !publicToken) {
      text(root, "[data-rsvp-name]", "유효하지 않은 초대입니다.");
      return;
    }
    try {
      var response = await fetch(endpoint + "?token=" + encodeURIComponent(publicToken), {
        credentials: "omit", referrerPolicy: "no-referrer",
      });
      var result = await response.json();
      if (!response.ok || !result.launchKit) throw new Error("not found");
      var kit = result.launchKit;
      text(root, "[data-rsvp-name]", kit.name_ko || kit.name_en);
      text(root, "[data-rsvp-venue]", kit.venue_name_ko || kit.venue_name_en);
      text(root, "[data-rsvp-date]", [kit.reception_date, kit.reception_start_time].filter(Boolean).join(" "));
      text(root, "[data-rsvp-address]", kit.address_ko || kit.address_en);
      root.querySelector("[data-rsvp-details]").hidden = false;
      form.hidden = false;
    } catch (_) {
      text(root, "[data-rsvp-name]", "유효하지 않은 초대입니다.");
      return;
    }

    form.addEventListener("submit", async function (event) {
      event.preventDefault();
      var data = new FormData(form);
      var values = {
        name: String(data.get("name") || "").trim(),
        email: String(data.get("email") || "").trim(),
        party_size: Number(data.get("party_size")),
        privacy_acknowledged: data.get("privacy_acknowledged") === "on",
      };
      if (!valid(values)) {
        error.textContent = "! 입력 내용과 개인정보 동의를 확인해 주세요.";
        error.hidden = false;
        return;
      }
      var button = root.querySelector("[data-rsvp-submit]");
      button.disabled = true;
      error.hidden = true;
      try {
        var response = await fetch(endpoint + "?token=" + encodeURIComponent(publicToken), {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify(values), credentials: "omit", referrerPolicy: "no-referrer",
        });
        if (!response.ok) throw new Error("submit failed");
        form.hidden = true;
        root.querySelector("[data-rsvp-success]").hidden = false;
      } catch (_) {
        error.textContent = "! 신청을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        error.hidden = false;
        button.disabled = false;
      }
    });
  }

  if (typeof document !== "undefined") document.addEventListener("DOMContentLoaded", init);
  if (typeof module !== "undefined") module.exports = { valid };
})();
