(function () {
  const root = document.querySelector("[data-promotion]");
  if (!root || navigator.doNotTrack === "1" || window.doNotTrack === "1") return;
  const endpoint = root.dataset.promotionEndpoint || root.dataset.endpoint || "";
  if (!endpoint) return;

  const title = document.querySelector("[data-promotion-title]");
  const venue = document.querySelector("[data-promotion-venue]");
  const locality = document.querySelector("[data-promotion-locality]");
  const dates = document.querySelector("[data-promotion-dates]");
  const link = document.querySelector("[data-promotion-link]");
  const image = document.querySelector("[data-promotion-image]");
  let requestedCity = null;

  function installationKey() {
    const storageKey = "gallr.promotion.installation.v1";
    let value = localStorage.getItem(storageKey);
    if (!value) {
      value = crypto.randomUUID();
      localStorage.setItem(storageKey, value);
    }
    return value;
  }

  function cardLink(exhibitionId) {
    const escaped = typeof CSS !== "undefined" && CSS.escape
      ? CSS.escape(exhibitionId)
      : exhibitionId.replace(/["\\]/g, "\\$&");
    return document.querySelector(
      `.exhibition-card[data-exhibition-id="${escaped}"] .exhibition-card__link`,
    )?.getAttribute("href") || null;
  }

  function valid(value) {
    return value && typeof value === "object" && !Array.isArray(value) &&
      typeof value.promotion_id === "string" &&
      typeof value.exhibition_id === "string" &&
      typeof value.name_ko === "string" &&
      typeof value.venue_name_ko === "string" &&
      typeof value.city_ko === "string" &&
      typeof value.region_ko === "string" &&
      typeof value.opening_date === "string" &&
      typeof value.closing_date === "string" &&
      value.disclosure === "paid_placement";
  }

  function hide() { root.hidden = true; }

  async function load() {
    const city = new URLSearchParams(window.location.search).get("city") || "";
    if (!city || city === "all") { requestedCity = null; hide(); return; }
    if (requestedCity === city) return;
    requestedCity = city;
    hide();
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        mode: "cors",
        credentials: "omit",
        referrerPolicy: "no-referrer",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          installation_key: installationKey(), city_ko: city, region_ko: "",
        }),
      });
      if (response.status === 204) return;
      if (!response.ok) return;
      const payload = await response.json();
      const placement = payload?.placement;
      if (!valid(placement)) return;
      const href = cardLink(placement.exhibition_id);
      if (!href) return;
      title.textContent = placement.name_ko;
      venue.textContent = placement.venue_name_ko;
      locality.textContent = [placement.city_ko, placement.region_ko].filter(Boolean).join(" · ");
      dates.textContent = `${placement.opening_date} — ${placement.closing_date}`;
      link.setAttribute("href", href);
      if (typeof placement.cover_image_url === "string" && placement.cover_image_url) {
        image.setAttribute("src", placement.cover_image_url);
        image.setAttribute("alt", `${placement.name_ko}, ${placement.venue_name_ko}`);
        image.hidden = false;
      } else {
        image.removeAttribute("src");
        image.hidden = true;
      }
      root.hidden = false;
    } catch {
      hide();
    }
  }

  window.addEventListener("gallr:filters-changed", function () { void load(); });
  window.addEventListener("popstate", function () { void load(); });
  void load();
})();
