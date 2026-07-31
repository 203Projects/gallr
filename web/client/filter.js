// Discover page client-side filter.
// URL params:  ?status=<value>&city=<value>
// Defaults:    status=all, city=all (omit param to mean "all")
//
// Reads the current URL on load, hides non-matching cards, syncs the active
// link in each filter group, and shows the empty-state when nothing matches.
// Filter clicks update the URL via history.replaceState (no navigation).

(function () {
  const root = document.querySelector(".discover-page");
  if (!root) return;

  const cards = Array.from(root.querySelectorAll(".exhibition-card"));
  const empty = root.querySelector("[data-empty]");
  const groups = ["status", "city"];

  function readParams() {
    const p = new URLSearchParams(window.location.search);
    return { status: p.get("status") || "all", city: p.get("city") || "all" };
  }

  function applyFilters() {
    const { status, city } = readParams();
    let visibleCount = 0;
    for (const card of cards) {
      const cardStatus = card.dataset.status;
      const cardCity = card.dataset.city;
      const statusOk = status === "all" || cardStatus === status;
      const cityOk = city === "all" || cardCity === city;
      const visible = statusOk && cityOk;
      card.hidden = !visible;
      if (visible) visibleCount++;
    }
    if (empty) empty.hidden = visibleCount > 0;
    syncActiveLinks(status, city);
  }

  function syncActiveLinks(status, city) {
    for (const group of groups) {
      const value = group === "status" ? status : city;
      const links = root.querySelectorAll(
        `[data-filter-group="${group}"] [data-filter-value]`
      );
      for (const link of links) {
        link.classList.toggle("is-active", link.dataset.filterValue === value);
      }
    }
  }

  function setParam(key, value) {
    const p = new URLSearchParams(window.location.search);
    if (value === "all") p.delete(key);
    else p.set(key, value);
    const search = p.toString();
    const url = window.location.pathname + (search ? `?${search}` : "");
    window.history.replaceState(null, "", url);
  }

  root.addEventListener("click", function (e) {
    const link = e.target.closest("[data-filter-value]");
    if (link) {
      e.preventDefault();
      const group = link.closest("[data-filter-group]").dataset.filterGroup;
      setParam(group, link.dataset.filterValue);
      applyFilters();
      window.dispatchEvent(new CustomEvent("gallr:filters-changed"));
      return;
    }
    if (e.target.closest("[data-filter-reset]")) {
      e.preventDefault();
      window.history.replaceState(null, "", window.location.pathname);
      applyFilters();
      window.dispatchEvent(new CustomEvent("gallr:filters-changed"));
    }
  });

  applyFilters();
})();
