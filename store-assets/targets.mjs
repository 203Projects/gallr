// Screenshot canvases shared by build.mjs (stills) and video.mjs (previews). Every canvas is laid out
// in the same 1320-wide units and rendered at `zoom`, so each size stays sharp: zoom = width / 1320.
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const REPO = join(ROOT, '..');
export const IPHONE = 'Gallr Store iPhone 17 Pro Max';
export const IPAD = 'Gallr Store iPad Pro 13 M5';
export const LAYOUT_WIDTH = 1320;

/** `layout` picks the frame compositions in creative.mjs: phone or tablet. */
export const SCREENSHOT_TARGETS = [
  {
    name: 'iPhone 6.9',
    width: 1320,
    height: 2868,
    layout: 'phone',
    captures: ['ios', IPHONE],
    out: (locale, id) => join(REPO, 'iosApp/fastlane/screenshots', locale, `iPhone_6.9-${id}.png`),
  },
  {
    // App Store Connect's large iPhone slot (iPhone with Dynamic Island or Face ID, large display) takes
    // 1284 × 2778 and not 6.9" sizes; without it that slot keeps whatever set was uploaded before.
    name: 'iPhone 6.5',
    width: 1284,
    height: 2778,
    layout: 'phone',
    captures: ['ios', IPHONE],
    out: (locale, id) => join(REPO, 'iosApp/fastlane/screenshots', locale, `iPhone_6.5-${id}.png`),
  },
  {
    name: 'iPhone 6.3',
    width: 1206,
    height: 2622,
    layout: 'phone',
    captures: ['ios', IPHONE],
    out: (locale, id) => join(REPO, 'iosApp/fastlane/screenshots', locale, `iPhone_6.3-${id}.png`),
  },
  {
    name: 'iPad 13',
    width: 2064,
    height: 2752,
    layout: 'tablet',
    captures: ['ios', IPAD],
    out: (locale, id) => join(REPO, 'iosApp/fastlane/screenshots', locale, `iPad_13-${id}.png`),
  },
  {
    name: 'Play phone',
    width: 1080,
    height: 1920,
    layout: 'phone',
    captures: ['ios', IPHONE],
    // The same Compose UI as iOS; the iOS status bar is cropped off so nothing reads as iPhone.
    cropStatusBar: 186,
    out: (locale, id) => join(ROOT, 'dist/play', locale, `phone-${id}.png`),
  },
];
