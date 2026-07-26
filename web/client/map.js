// /map/ page client.
// Reads the #exhibitions-data JSON island, initializes the Naver map
// with one pin per row. Bidirectional selection:
//   - Pin click   → scrolls the matching sidebar row into view,
//                   activates both the row and pin, and opens the
//                   exhibition detail panel over the map.
//   - Sidebar row → the row remains a plain <a> that navigates to
//                   /exhibitions/[slug]/ (one-click access to detail).
//   - .map-page__list-focus action → pans the map to the row's pin
//                   without navigating, activates both pin and row,
//                   and opens the same detail panel.
//
// Failure modes handled:
//   - Naver SDK didn't load (network error, ad blocker) → leaves the
//     map container with a "map-failed" class so CSS can render an
//     explanatory fallback. Focus buttons silently no-op in that case.
//   - SDK loaded but Naver rejects auth (unlisted referrer) → the
//     `navermap_authFailure` global callback registered in map/index.html
//     adds the same "map-failed" class. Without this, the SDK would
//     render its auth-fail placeholder PNG in every tile.
//   - JSON island missing or unparseable → silently no-op.
//   - All rows missing lat/lng → no-op (no pins to drop).

(function () {
  const dataEl = document.getElementById("exhibitions-data");
  if (!dataEl) return;

  let exhibitions;
  try {
    exhibitions = JSON.parse(dataEl.textContent);
  } catch {
    return;
  }
  if (!Array.isArray(exhibitions)) return;

  const container = document.getElementById("naver-map");
  const sidebarItems = Array.from(
    document.querySelectorAll("[data-exhibition-id]")
  );
  const detailPanel = document.querySelector("[data-map-detail]");
  const exhibitionsById = new Map(exhibitions.map((ex) => [ex.id, ex]));
  const statusLabels = {
    current: { ko: "진행 중", en: "CURRENT" },
    opening_soon: { ko: "오픈 예정", en: "OPENING SOON" },
    closing_soon: { ko: "종료 임박", en: "CLOSING SOON" },
    closed: { ko: "종료됨", en: "CLOSED" },
  };

  // Shared state populated by initMap; the focus-button handler reads
  // them after the map is up.
  let map = null;
  const markersById = new Map(); // id → { marker, lat, lng }

  function setActiveSidebar(id) {
    for (const item of sidebarItems) {
      const match = item.dataset.exhibitionId === id;
      item.classList.toggle("is-active", match);
      if (match) {
        item.scrollIntoView({ block: "nearest", behavior: "smooth" });
      }
    }
  }

  function setActivePin(id) {
    // The marker's content is re-rendered by Naver as a live DOM node;
    // find it via the data-pin-id attribute we set on creation.
    document
      .querySelectorAll(".map-pin")
      .forEach((p) => p.classList.toggle("is-active", p.dataset.pinId === id));
  }

  function setDetailText(selector, value) {
    if (!detailPanel) return;
    const el = detailPanel.querySelector(selector);
    if (!el) return;
    const text = value == null ? "" : String(value);
    el.textContent = text;
    el.hidden = text.length === 0;
  }

  function showDetails(id) {
    if (!detailPanel) return;
    const ex = exhibitionsById.get(id);
    if (!ex) return;

    const status = statusLabels[ex.status] || statusLabels.current;
    const statusEl = detailPanel.querySelector("[data-map-detail-status]");
    if (statusEl) {
      statusEl.dataset.status = ex.status || "current";
      statusEl.classList.toggle(
        "status-chip--accent",
        ex.status === "opening_soon" || ex.status === "closing_soon"
      );
      statusEl.classList.toggle(
        "status-chip--default",
        ex.status !== "opening_soon" && ex.status !== "closing_soon"
      );
    }

    setDetailText("[data-map-detail-status-ko]", status.ko);
    setDetailText("[data-map-detail-status-en]", status.en);
    setDetailText("[data-map-detail-title-ko]", ex.nameKo);
    setDetailText("[data-map-detail-title-en]", ex.nameEn);
    setDetailText("[data-map-detail-venue-ko]", ex.venueKo);
    setDetailText("[data-map-detail-venue-en]", ex.venueEn);
    setDetailText(
      "[data-map-detail-dates]",
      [ex.openingDate, ex.closingDate].filter(Boolean).join(" — ")
    );
    setDetailText("[data-map-detail-address]", ex.addressKo);

    const detailLink = detailPanel.querySelector("[data-map-detail-link]");
    if (detailLink) {
      detailLink.href = `/exhibitions/${encodeURIComponent(ex.slug)}/`;
    }

    detailPanel.hidden = false;
  }

  function selectExhibition(id) {
    setActivePin(id);
    setActiveSidebar(id);
    showDetails(id);
  }

  function hideDetails() {
    if (!detailPanel) return;
    detailPanel.hidden = true;
    const activeFocusAction = document.querySelector(
      ".map-page__list-item.is-active [data-focus-id]"
    );
    if (activeFocusAction) {
      activeFocusAction.focus({ preventScroll: true });
    }
  }

  function focusOn(id) {
    const entry = markersById.get(id);
    if (
      !entry ||
      !map ||
      (container && container.classList.contains("map-failed"))
    ) {
      return false;
    }
    map.setCenter(new naver.maps.LatLng(entry.lat, entry.lng));
    map.setZoom(14, true);
    selectExhibition(id);
    return true;
  }

  const detailClose = detailPanel &&
    detailPanel.querySelector("[data-map-detail-close]");
  if (detailClose) {
    detailClose.addEventListener("click", hideDetails);
  }
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && detailPanel && !detailPanel.hidden) {
      hideDetails();
    }
  });

  // Wire focus-action clicks at the document level so they work whether
  // or not initMap has finished yet. When the embedded map is available,
  // prevent the link navigation and pan locally. Otherwise, preserve the
  // link's external Naver Map fallback.
  document.addEventListener("click", function (e) {
    const action = e.target.closest("[data-focus-id]");
    if (!action) return;
    if (focusOn(action.dataset.focusId)) {
      e.preventDefault();
    }
  });

  function initMap() {
    if (!window.naver || !window.naver.maps) {
      if (container) container.classList.add("map-failed");
      return;
    }
    if (!container) return;

    const valid = exhibitions.filter(
      (e) => typeof e.lat === "number" && typeof e.lng === "number"
    );
    if (valid.length === 0) {
      container.classList.add("map-empty");
      return;
    }

    map = new naver.maps.Map(container, {
      center: new naver.maps.LatLng(valid[0].lat, valid[0].lng),
      zoom: 11,
      // Tone down the Naver default UI to match the editorial aesthetic.
      mapTypeControl: false,
      scaleControl: false,
      logoControl: true,
      mapDataControl: false,
    });

    // Hide tile imagery until the SDK confirms tiles are painted.
    // Eliminates the brief flash where Naver's auth-fail placeholder
    // PNG renders before real tiles arrive on cold-cache loads.
    container.classList.add("map-loading");
    const clearLoading = () => container.classList.remove("map-loading");
    naver.maps.Event.once(map, "tilesloaded", clearLoading);
    // Safety net: if tilesloaded never fires (ad-blocker, network
    // failure on the tile CDN, etc.), reveal whatever is there after
    // 2 seconds rather than leaving the map looking frozen.
    setTimeout(clearLoading, 2000);

    const bounds = new naver.maps.LatLngBounds();

    for (const ex of valid) {
      const pos = new naver.maps.LatLng(ex.lat, ex.lng);
      bounds.extend(pos);
      const marker = new naver.maps.Marker({
        position: pos,
        map,
        icon: {
          content: `<span class="map-pin" data-pin-id="${escapeAttr(ex.id)}" title="${escapeAttr(ex.nameKo)}"></span>`,
          anchor: new naver.maps.Point(6, 6),
        },
      });
      naver.maps.Event.addListener(marker, "click", () => {
        selectExhibition(ex.id);
      });
      markersById.set(ex.id, { marker, lat: ex.lat, lng: ex.lng });
    }

    if (markersById.size > 1) {
      map.fitBounds(bounds, { top: 40, right: 40, bottom: 40, left: 40 });
    }
  }

  function escapeAttr(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    }[c]));
  }

  if (window.naver && window.naver.maps) {
    initMap();
  } else {
    window.addEventListener("load", initMap);
  }
})();
