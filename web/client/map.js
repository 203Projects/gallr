// /map/ page client.
// Reads the #exhibitions-data JSON island, initializes the Naver map
// with one pin per row. Bidirectional selection:
//   - Pin click   → scrolls the matching sidebar row into view +
//                   .is-active on both the row and the pin.
//   - Sidebar row → the row remains a plain <a> that navigates to
//                   /exhibitions/[slug]/ (one-click access to detail).
//   - .map-page__list-focus button → pans the map to the row's pin
//                   without navigating, activates both pin and row.
//
// Failure modes handled:
//   - Naver SDK didn't load (401 from referrer mismatch, network error,
//     ad blocker) → leaves the map container with a "map-failed" class
//     so CSS can render an explanatory fallback. Focus buttons silently
//     no-op in that case.
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

  function focusOn(id) {
    const entry = markersById.get(id);
    if (!entry || !map) return;
    map.setCenter(new naver.maps.LatLng(entry.lat, entry.lng));
    map.setZoom(14, true);
    setActivePin(id);
    setActiveSidebar(id);
  }

  // Wire focus-button clicks at the document level so they work whether
  // or not initMap has finished yet. If the map isn't up, the click
  // silently no-ops (focusOn returns early).
  document.addEventListener("click", function (e) {
    const btn = e.target.closest("[data-focus-id]");
    if (!btn) return;
    e.preventDefault();
    focusOn(btn.dataset.focusId);
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
        setActivePin(ex.id);
        setActiveSidebar(ex.id);
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
