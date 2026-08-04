(function () {
  "use strict";

  if (navigator.doNotTrack === "1" || window.doNotTrack === "1") return;

  var target = document.querySelector("[data-exhibition-impact][data-impact-endpoint]");
  if (!target) return;

  var exhibitionId = target.getAttribute("data-exhibition-impact");
  var endpoint = target.getAttribute("data-impact-endpoint");
  if (!exhibitionId || !endpoint) return;

  void fetch(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ exhibition_id: exhibitionId }),
    credentials: "omit",
    referrerPolicy: "no-referrer",
    keepalive: true,
  }).catch(function () {
    // Impact is passive enhancement and must never interrupt the visitor page.
  });
})();
