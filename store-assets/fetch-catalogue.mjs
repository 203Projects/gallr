// Reads the public exhibition catalogue the app shows (open and upcoming exhibitions with a place) and
// downloads every cover into data/covers/<id>.img (gitignored) for the header wall. Read-only: it uses the
// publishable key the iOS app bundles, from iosApp/iosApp/Config.swift, and never prints it.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));

async function publicConfig() {
  const swift = await readFile(join(ROOT, '../iosApp/iosApp/Config.swift'), 'utf8');
  const url = swift.match(/productionSupabaseUrl = "([^"]+)"/)?.[1];
  const key = swift.match(/productionSupabasePublishableKey = "([^"]+)"/)?.[1];
  if (!url || !key) throw new Error('public Supabase configuration not found in Config.swift');
  return { url, key };
}

async function main() {
  const { url, key } = await publicConfig();
  const today = new Date().toISOString().slice(0, 10);
  const query = new URLSearchParams({
    select: 'id,name_ko,name_en,venue_name_ko,venue_name_en,latitude,longitude,cover_image_url,opening_date,closing_date',
    closing_date: `gte.${today}`,
    latitude: 'not.is.null',
  });
  const response = await fetch(`${url}/rest/v1/exhibitions?${query}`, {
    headers: { apikey: key, Authorization: `Bearer ${key}` },
  });
  if (!response.ok) throw new Error(`catalogue request failed: ${response.status}`);
  const rows = await response.json();
  await mkdir(join(ROOT, 'data/covers'), { recursive: true });
  await writeFile(join(ROOT, 'data/catalogue.json'), `${JSON.stringify(rows, null, 2)}\n`);

  let saved = 0;
  for (const row of rows) {
    if (!row.cover_image_url) continue;
    const image = await fetch(row.cover_image_url);
    if (!image.ok) {
      console.warn(`cover download failed for ${row.id}: ${image.status}`);
      continue;
    }
    await writeFile(join(ROOT, 'data/covers', `${row.id}.img`), Buffer.from(await image.arrayBuffer()));
    saved += 1;
  }
  console.log(`${rows.length} exhibitions; ${saved} covers saved`);
}

await main();
