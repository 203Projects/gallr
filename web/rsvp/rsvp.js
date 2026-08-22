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

  function optionalText(root, selector, value) {
    var node = root.querySelector(selector);
    if (!node) return "";
    var normalized = String(value || "").trim();
    node.textContent = normalized;
    node.hidden = !normalized;
    return normalized;
  }

  function detail(root, selector, value) {
    var node = root.querySelector(selector);
    if (!node) return;
    var normalized = String(value || "").trim();
    node.textContent = normalized;
    var field = node.closest("[data-rsvp-field]");
    if (field) field.hidden = !normalized;
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
      var name = String(kit.name_ko || kit.name_en || "").trim();
      var nameEn = String(kit.name_en || "").trim();
      var venue = String(kit.venue_name_ko || kit.venue_name_en || "").trim();
      var venueEn = String(kit.venue_name_en || "").trim();
      text(root, "[data-rsvp-name]", name);
      if (nameEn && nameEn !== name) optionalText(root, "[data-rsvp-name-en]", nameEn);
      text(root, "[data-rsvp-venue]", venue);
      if (venueEn && venueEn !== venue) optionalText(root, "[data-rsvp-venue-en]", venueEn);

      var media = root.querySelector("[data-rsvp-media]");
      var image = root.querySelector("[data-rsvp-image]");
      var hero = root.querySelector("[data-rsvp-hero]");
      var cover = String(kit.cover_image_url || "").trim();
      if (cover && media && image && hero) {
        image.addEventListener("error", function () {
          media.hidden = true;
          hero.classList.remove("has-image");
          image.removeAttribute("src");
        }, { once: true });
        image.alt = name;
        image.src = cover;
        media.hidden = false;
        hero.classList.add("has-image");
      }

      var description = String(kit.description_ko || kit.description_en || "").trim();
      var descriptionEn = String(kit.description_en || "").trim();
      if (description) {
        text(root, "[data-rsvp-description]", description);
        root.querySelector("[data-rsvp-description-block]").hidden = false;
      }
      if (descriptionEn && descriptionEn !== description) {
        optionalText(root, "[data-rsvp-description-en]", descriptionEn);
      }

      detail(root, "[data-rsvp-date]", [kit.reception_date, kit.reception_start_time].filter(Boolean).join(" "));
      detail(root, "[data-rsvp-period]", [kit.opening_date, kit.closing_date].filter(Boolean).join(" — "));
      detail(root, "[data-rsvp-address]", kit.address_ko || kit.address_en);
      var addressEn = String(kit.address_en || "").trim();
      if (addressEn && addressEn !== String(kit.address_ko || "").trim()) {
        optionalText(root, "[data-rsvp-address-en]", addressEn);
      }
      detail(root, "[data-rsvp-hours]", kit.hours);
      detail(root, "[data-rsvp-contact]", kit.contact);
      root.querySelector("[data-rsvp-information]").hidden = false;
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
