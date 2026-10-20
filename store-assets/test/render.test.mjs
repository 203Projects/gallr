import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { renderTemplate, closeBrowser } from '../render.mjs';
import { checkImage } from '../validate.mjs';

const dir = mkdtempSync(join(tmpdir(), 'gallr-store-render-'));

const template = join(dir, 'fit.html');
writeFileSync(
  template,
  `<!doctype html><html><head><style>
    html, body { margin: 0; width: 100%; height: 100%; background: #fff; }
    h1 { width: 400px; font: 700 80px/1 sans-serif; margin: 0; white-space: nowrap; overflow: hidden; }
  </style></head><body><h1 data-fit>{{headline}}</h1></body></html>`,
);

after(closeBrowser);

test('renders an opaque PNG at the exact size', async () => {
  const out = join(dir, 'ok.png');
  await renderTemplate({ template, data: { headline: 'gallr' }, width: 1206, height: 2622, out });
  assert.deepEqual(await checkImage(out, { width: 1206, height: 2622 }), { ok: true, problems: [] });
});

test('fails when a data-fit element overflows its box', async () => {
  await assert.rejects(
    renderTemplate({
      template,
      data: { headline: 'A headline far too long for its box' },
      width: 1206,
      height: 2622,
      out: join(dir, 'overflow.png'),
    }),
    /overflow: h1/,
  );
});

test('escapes caption text so markup in copy cannot alter the layout', async () => {
  const out = join(dir, 'escaped.png');
  await renderTemplate({ template, data: { headline: '<b>x</b>' }, width: 400, height: 400, out });
  assert.equal((await checkImage(out, { width: 400, height: 400 })).ok, true);
});

test('fill escapes markup and rejects missing or undefined values', async () => {
  const { fill } = await import('../render.mjs');
  assert.equal(fill('<p>{{text}}</p>', { text: '<b>"x"&</b>' }), '<p>&lt;b&gt;&quot;x&quot;&amp;&lt;/b&gt;</p>');
  assert.throws(() => fill('{{text}}', {}), /template value missing: text/);
  assert.throws(() => fill('{{text}}', { text: undefined }), /template value missing: text/);
});
