// Screenshot canvases shared by build.mjs (stills) and video.mjs (previews use the same layouts).
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const REPO = join(ROOT, '..');
export const IPHONE = 'Gallr Store iPhone 17 Pro Max';
export const IPAD = 'Gallr Store iPad Pro 13 M5';

/**
 * Each target canvas. Layout values are in vw of that canvas: where the text block starts, the
 * headline size, and the capture's width and top edge (it runs off the bottom of the canvas).
 */
export const SCREENSHOT_TARGETS = [
  {
    name: 'iPhone 6.9',
    width: 1320,
    height: 2868,
    captures: ['ios', IPHONE],
    out: (locale, id) => join(REPO, 'iosApp/fastlane/screenshots', locale, `iPhone_6.9-${id}.png`),
    layout: { textTop: 12.7, headlineSize: { ko: 8.6, 'en-US': 8.2 }, captureWidth: 86, captureTop: 44, connectorTop: 120 },
  },
  {
    name: 'iPhone 6.3',
    width: 1206,
    height: 2622,
    captures: ['ios', IPHONE],
    out: (locale, id) => join(REPO, 'iosApp/fastlane/screenshots', locale, `iPhone_6.3-${id}.png`),
    layout: { textTop: 12.7, headlineSize: { ko: 8.6, 'en-US': 8.2 }, captureWidth: 86, captureTop: 44, connectorTop: 120 },
  },
  {
    name: 'iPad 13',
    width: 2064,
    height: 2752,
    captures: ['ios', IPAD],
    out: (locale, id) => join(REPO, 'iosApp/fastlane/screenshots', locale, `iPad_13-${id}.png`),
    layout: { textTop: 6.4, headlineSize: { ko: 6.2, 'en-US': 6 }, captureWidth: 84, captureTop: 29, connectorTop: 82 },
  },
  {
    name: 'Play phone',
    width: 1080,
    height: 1920,
    // The same Compose UI as iOS; the iOS status bar is cropped off so nothing reads as iPhone.
    captures: ['ios', IPHONE],
    cropStatusBar: 186,
    out: (locale, id) => join(ROOT, 'dist/play', locale, `phone-${id}.png`),
    layout: { textTop: 8.6, headlineSize: { ko: 7.6, 'en-US': 7.2 }, captureWidth: 66, captureTop: 37, connectorTop: 110 },
  },
];
