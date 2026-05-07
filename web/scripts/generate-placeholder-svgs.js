#!/usr/bin/env node
// One-shot generator for 12 monochrome placeholder SVGs.
// Each is a sharp 4:5 rectangle with the seed number top-left and a
// thin diagonal line — minimum visual weight, on-brand. Replace with
// real images when the live Supabase fetch is configured.
//
// Run: node scripts/generate-placeholder-svgs.js

const fs = require("fs");
const path = require("path");

const OUT_DIR = path.join(__dirname, "..", "public", "showcase");
if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });

for (let i = 1; i <= 12; i++) {
  const n = String(i).padStart(2, "0");
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 500" preserveAspectRatio="xMidYMid slice">
  <rect width="400" height="500" fill="#f5f5f5"/>
  <line x1="0" y1="500" x2="400" y2="0" stroke="#000" stroke-width="1"/>
  <text x="20" y="48" font-family="Inter, sans-serif" font-size="14" font-weight="700" fill="#000" letter-spacing="2">No. ${n}</text>
  <text x="20" y="480" font-family="Inter, sans-serif" font-size="11" font-weight="400" fill="#525252" letter-spacing="2">PLACEHOLDER</text>
</svg>
`;
  fs.writeFileSync(path.join(OUT_DIR, `seed-${n}.svg`), svg);
  console.log(`✓ wrote public/showcase/seed-${n}.svg`);
}
