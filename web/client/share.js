// Web Share API + clipboard fallback. Used by detail page Share button.
(function () {
  document.addEventListener("click", function (e) {
    const btn = e.target.closest("[data-share-button]");
    if (!btn) return;
    const title = btn.dataset.shareTitle || document.title;
    const path = btn.dataset.shareUrl || window.location.pathname;
    const url = new URL(path, window.location.origin).toString();
    if (navigator.share) {
      navigator.share({ title, url }).catch(() => {});
    } else if (navigator.clipboard) {
      navigator.clipboard.writeText(url).then(() => {
        btn.dataset.shareCopied = "true";
        const original = btn.textContent;
        btn.textContent = "복사됨";
        setTimeout(() => { btn.textContent = original; delete btn.dataset.shareCopied; }, 2000);
      });
    }
  });
})();
