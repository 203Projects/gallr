// Date → status classifier for exhibitions.
// Shared by the build pipeline (fetch-exhibitions.js) and tests.
// Mirrors the mobile app's spec 022 logic with explicit web statuses:
//   current        — open today, more than 7 days until close
//   closing_soon   — open today, ≤ 7 days until close
//   opening_soon   — not yet open
//   closed         — already closed (closing_date < today)

const STATUSES = ["current", "opening_soon", "closing_soon", "closed"];
const OPENING_SOON_DAYS = 7;
const CLOSING_SOON_DAYS = 7;

function daysBetween(a, b) {
  const ms = new Date(b).getTime() - new Date(a).getTime();
  return Math.round(ms / (1000 * 60 * 60 * 24));
}

function classify(openingDate, closingDate, today) {
  const dToClose = daysBetween(today, closingDate);
  const dToOpen = daysBetween(today, openingDate);

  // Already closed
  if (dToClose < 0) return "closed";

  // Not yet open: any future opening counts as opening_soon for catalog purposes.
  if (dToOpen > 0) return "opening_soon";

  // Open today: closing within the window is closing_soon, else current.
  if (dToClose <= CLOSING_SOON_DAYS) return "closing_soon";
  return "current";
}

module.exports = { classify, STATUSES, OPENING_SOON_DAYS, CLOSING_SOON_DAYS };
