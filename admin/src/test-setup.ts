import "@testing-library/jest-dom/vitest";

// jsdom has no canvas renderer. Keep the real orb's DOM/accessibility behavior;
// animation, ink contrast, and reduced motion are verified in a real browser.
HTMLCanvasElement.prototype.getContext = () => null;
