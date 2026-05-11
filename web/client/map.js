// /map/ page client.
// Reads the #exhibitions-data JSON island, initializes the Naver map
// with one pin per row, and wires pin→sidebar selection (clicking a pin
// scrolls + highlights the matching sidebar row).
//
// Failure modes handled:
//   - Naver SDK didn't load (401 from referrer mismatch, network error,
//     ad blocker) → leaves the map container with a "map-failed" class
//     so CSS can render an explanatory fallback.
//   - JSON island missing or unparseable → silently no-op.
//   - All rows missing lat/lng → no-op (no pins to drop).
//
// Sidebar→pin direction (clicking a row pans the map) is a follow-up;
// today's sidebar row is a plain <a> that navigates to /exhibitions/[slug]/.

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

  function setActiveSidebar(id) {
    for (const item of sidebarItems) {
      const match = item.dataset.exhibitionId === id;
      item.classList.toggle("is-active", match);
      if (match) {
        item.scrollIntoView({ block: "nearest", behavior: "smooth" });
      }
    }
  }

  function initMap() {
    if (!window.naver || !window.naver.maps) {
      // SDK never loaded (referrer not allowlisted, network blocked, etc).
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

    const map = new naver.maps.Map(container, {
      center: new naver.maps.LatLng(valid[0].lat, valid[0].lng),
      zoom: 11,
      // Tone down the Naver default UI to match the editorial aesthetic.
      mapTypeControl: false,
      scaleControl: false,
      logoControl: true,
      mapDataControl: false,
    });

    const bounds = new naver.maps.LatLngBounds();
    const markers = [];

    for (const ex of valid) {
      const pos = new naver.maps.LatLng(ex.lat, ex.lng);
      bounds.extend(pos);
      // HTML marker so we can style with brand tokens. The class name
      // doubles as the selector for the active-state swap below.
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
      markers.push({ id: ex.id, marker });
    }

    function setActivePin(id) {
      // The marker's content is re-rendered by Naver as a live DOM node;
      // find it via the data-pin-id attribute we set above.
      document
        .querySelectorAll(".map-pin")
        .forEach((p) => p.classList.toggle("is-active", p.dataset.pinId === id));
    }

    if (markers.length > 1) {
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

  // The SDK script tag is synchronous in the page's <head>-equivalent;
  // by the time this deferred script runs, window.naver should exist.
  // The window.load listener is a safety net for slow networks.
  if (window.naver && window.naver.maps) {
    initMap();
  } else {
    window.addEventListener("load", initMap);
  }
})();
