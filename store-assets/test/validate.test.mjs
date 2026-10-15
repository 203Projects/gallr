import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { checkImage, checkVideo } from '../validate.mjs';

const dir = mkdtempSync(join(tmpdir(), 'gallr-store-validate-'));

function ffmpeg(...args) {
  execFileSync('ffmpeg', ['-v', 'error', '-y', ...args]);
}

function png(name, { width, height, pixelFormat }) {
  const path = join(dir, name);
  ffmpeg('-f', 'lavfi', '-i', `color=c=white:s=${width}x${height}`, '-frames:v', '1', '-pix_fmt', pixelFormat, path);
  return path;
}

function clip(name, { width, height, fps, seconds, audio }) {
  const path = join(dir, name);
  const inputs = ['-f', 'lavfi', '-i', `color=c=white:s=${width}x${height}:r=${fps}:d=${seconds}`];
  const audioArgs = audio
    ? ['-f', 'lavfi', '-i', `anullsrc=r=48000:cl=stereo`, '-shortest', '-c:a', 'aac', '-b:a', '256k']
    : [];
  ffmpeg(...inputs, ...audioArgs, '-c:v', 'libx264', '-profile:v', 'high', '-pix_fmt', 'yuv420p', path);
  return path;
}

const preview = { width: 886, height: 1920, minSeconds: 15, maxSeconds: 30, maxFps: 30, audio: true };

test('an opaque PNG at the expected size passes', async () => {
  const result = await checkImage(png('ok.png', { width: 1320, height: 2868, pixelFormat: 'rgb24' }), {
    width: 1320,
    height: 2868,
  });
  assert.deepEqual(result, { ok: true, problems: [] });
});

test('a PNG with an alpha channel is rejected', async () => {
  const result = await checkImage(png('alpha.png', { width: 1320, height: 2868, pixelFormat: 'rgba' }), {
    width: 1320,
    height: 2868,
  });
  assert.equal(result.ok, false);
  assert.match(result.problems.join('\n'), /alpha channel/);
});

test('a PNG at the wrong size names the expected size', async () => {
  const result = await checkImage(png('small.png', { width: 1290, height: 2796, pixelFormat: 'rgb24' }), {
    width: 1320,
    height: 2868,
  });
  assert.equal(result.ok, false);
  assert.match(result.problems.join('\n'), /expected 1320x2868, got 1290x2796/);
});

test('a preview at 30 fps with a stereo audio track passes', async () => {
  const path = clip('ok.mp4', { width: 886, height: 1920, fps: 30, seconds: 16, audio: true });
  assert.deepEqual(await checkVideo(path, preview), { ok: true, problems: [] });
});

test('a preview above 30 fps is rejected', async () => {
  const path = clip('fast.mp4', { width: 886, height: 1920, fps: 60, seconds: 16, audio: true });
  const result = await checkVideo(path, preview);
  assert.equal(result.ok, false);
  assert.match(result.problems.join('\n'), /60 fps exceeds 30/);
});

test('a preview without an audio track is rejected', async () => {
  const path = clip('silent.mp4', { width: 886, height: 1920, fps: 30, seconds: 16, audio: false });
  const result = await checkVideo(path, preview);
  assert.equal(result.ok, false);
  assert.match(result.problems.join('\n'), /no stereo audio track/);
});

test('a preview shorter than 15 seconds is rejected', async () => {
  const path = clip('short.mp4', { width: 886, height: 1920, fps: 30, seconds: 6, audio: true });
  const result = await checkVideo(path, preview);
  assert.equal(result.ok, false);
  assert.match(result.problems.join('\n'), /duration 6\.0 s outside 15–30 s/);
});

test('names every expected still that is missing', async () => {
  const { missingStills } = await import('../validate.mjs');
  const root = mkdtempSync(join(tmpdir(), 'gallr-store-expected-'));
  const present = join(root, 'set', 'ko');
  mkdirSync(present, { recursive: true });
  writeFileSync(join(present, 'a-01.png'), '');
  const targets = [{ name: 'T', out: (locale, id) => join(root, 'set', locale, `a-${id}.png`) }];
  const missing = missingStills({ targets, locales: ['ko', 'en-US'], slots: ['01', '02'] });
  assert.deepEqual(missing.map((path) => path.slice(root.length + 1)).sort(), ['set/en-US/a-01.png', 'set/en-US/a-02.png', 'set/ko/a-02.png']);
});
