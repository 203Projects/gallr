import { test } from 'node:test';
import assert from 'node:assert/strict';
import { wallLayout, cleanTitle } from '../creatives.mjs';

const works = [
  { aspect: 0.7 }, // portrait
  { aspect: 1.5 }, // landscape
  { aspect: 1 },
];
const options = { centreY: 780, portraitHeight: 640, landscapeWidth: 760, minHeight: 420, gap: 150 };

test('hangs every work on one centre line', () => {
  const { placed } = wallLayout(works, options);
  for (const work of placed) assert.equal(work.y + work.height / 2, 780);
});

test('portrait works take the full height and landscape works the full width', () => {
  const { placed } = wallLayout(works, options);
  assert.equal(placed[0].height, 640);
  assert.equal(placed[1].width, 760);
  assert.ok(placed[2].height <= 640 && placed[2].width <= 760);
});

test('spaces works by the gap and reports the period, including the gap after the last work', () => {
  const { placed, period } = wallLayout(works, options);
  assert.equal(placed[1].x, placed[0].x + placed[0].width + 150);
  assert.equal(period, placed[2].x + placed[2].width + 150);
});

test('a very wide work is never shorter than the minimum height', () => {
  const { placed } = wallLayout([{ aspect: 4 }], options);
  assert.equal(placed[0].height, 420);
});

test('titles lose the museum emoji and the artist after the bar', () => {
  assert.equal(cleanTitle('🏛️ OUSSSMOS | KOO JEONG A '), 'OUSSSMOS');
  assert.equal(cleanTitle('Per Mano | Georg Baselitz'), 'Per Mano');
});
