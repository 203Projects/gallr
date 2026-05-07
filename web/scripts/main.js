// gallr web — motion primitives.
// Deferred. Vanilla ES2022. No dependencies.
//
// Primitives (all opt-in via data-* attributes):
//   data-reveal              fade + 24px translateY on entry
//   data-reveal-stagger      same, child <* > elements stagger 80ms
//   data-marquee             continuous left scroll, paused on hover
//   data-kinetic             cycle child <span> elements with crossfade
//   data-magnetic            magnetic hover offset (≤6px toward cursor)
//
// All motion is gated by prefers-reduced-motion. When reduce is set,
// every [data-reveal] gets is-revealed immediately and other primitives
// no-op so no element stays in a hidden start state.

(function () {
  "use strict";

  const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const isTouch = matchMedia("(pointer: coarse)").matches;

  function revealAllImmediately() {
    // Includes stagger children so the reduced-motion path covers them too —
    // their hidden state is set by the same CSS rule as [data-reveal].
    document
      .querySelectorAll("[data-reveal], [data-reveal-stagger], [data-reveal-stagger] > *")
      .forEach((el) => el.classList.add("is-revealed"));
  }

  function setupReveal() {
    const els = document.querySelectorAll("[data-reveal]");
    if (els.length === 0) return;
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-revealed");
            io.unobserve(entry.target);
          }
        }
      },
      { rootMargin: "0px 0px -10% 0px", threshold: 0.01 }
    );
    els.forEach((el) => io.observe(el));
  }

  function setupRevealStagger() {
    const containers = document.querySelectorAll("[data-reveal-stagger]");
    if (containers.length === 0) return;
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const children = Array.from(entry.target.children);
          children.forEach((child, i) => {
            child.style.transitionDelay = `${i * 80}ms`;
            requestAnimationFrame(() => child.classList.add("is-revealed"));
          });
          entry.target.classList.add("is-revealed");
          io.unobserve(entry.target);
        }
      },
      { rootMargin: "0px 0px -10% 0px", threshold: 0.01 }
    );
    containers.forEach((el) => io.observe(el));
  }

  function setupMarquee() {
    document.querySelectorAll("[data-marquee]").forEach((track) => {
      // Duplicate children once so the loop is seamless
      const inner = track.querySelector("[data-marquee-inner]");
      if (!inner) return;
      const clone = inner.cloneNode(true);
      clone.setAttribute("aria-hidden", "true");
      track.appendChild(clone);

      const duration = parseFloat(track.dataset.duration || "40") * 1000;
      let start = null;
      let paused = false;
      let pauseStart = 0;
      let pauseAccum = 0;

      track.addEventListener("mouseenter", () => {
        paused = true;
        pauseStart = performance.now();
      });
      track.addEventListener("mouseleave", () => {
        if (!paused) return; // guard against spurious mouseleave with no prior mouseenter
        paused = false;
        pauseAccum += performance.now() - pauseStart;
      });

      function step(ts) {
        if (start === null) start = ts;
        const elapsed = ts - start - pauseAccum - (paused ? ts - pauseStart : 0);
        const progress = (elapsed % duration) / duration;
        const x = -progress * 50; // -50% = single inner width
        track.style.setProperty("--marquee-x", `${x}%`);
        requestAnimationFrame(step);
      }
      requestAnimationFrame(step);
    });
  }

  function setupKinetic() {
    document.querySelectorAll("[data-kinetic]").forEach((host) => {
      const words = Array.from(host.querySelectorAll(":scope > span"));
      if (words.length < 2) return;
      let active = 0;
      words.forEach((w, i) => {
        w.classList.toggle("is-active", i === 0);
      });
      let paused = false;
      host.addEventListener("mouseenter", () => (paused = true));
      host.addEventListener("mouseleave", () => (paused = false));
      setInterval(() => {
        if (paused) return;
        words[active].classList.remove("is-active");
        active = (active + 1) % words.length;
        words[active].classList.add("is-active");
      }, 2400);
    });
  }

  function setupMagnetic() {
    if (isTouch) return;
    document.querySelectorAll("[data-magnetic]").forEach((el) => {
      const RADIUS = 60;
      const PULL = 6;
      el.addEventListener("mousemove", (e) => {
        const rect = el.getBoundingClientRect();
        const cx = rect.left + rect.width / 2;
        const cy = rect.top + rect.height / 2;
        const dx = e.clientX - cx;
        const dy = e.clientY - cy;
        const dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > RADIUS) {
          el.style.transform = "translate(0, 0)";
          return;
        }
        const k = (1 - dist / RADIUS) * PULL;
        el.style.transform = `translate(${(dx / dist) * k}px, ${(dy / dist) * k}px)`;
      });
      el.addEventListener("mouseleave", () => {
        el.style.transform = "translate(0, 0)";
      });
    });
  }

  function setupHeader() {
    const header = document.querySelector(".site-header");
    const progress = document.querySelector(".site-header__progress");
    if (!header) return;
    function onScroll() {
      const y = window.scrollY;
      header.classList.toggle("is-stuck", y > 80);
      if (progress) {
        const max = document.documentElement.scrollHeight - window.innerHeight;
        const ratio = max > 0 ? y / max : 0;
        progress.style.transform = `scaleX(${ratio})`;
      }
    }
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
  }

  function init() {
    document.body.classList.add("js-initialised");
    if (reduceMotion) {
      revealAllImmediately();
      // Magnetic and marquee are nice-to-have; skip them entirely.
      // Kinetic stays static (CSS shows only first .is-active word).
      setupHeader(); // header is a layout concern, not motion — keep it
      return;
    }
    setupReveal();
    setupRevealStagger();
    setupMarquee();
    setupKinetic();
    setupMagnetic();
    setupHeader();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
