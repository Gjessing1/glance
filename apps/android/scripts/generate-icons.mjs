// Glance launcher, splash, and web icons from one SVG drawing.
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const here = dirname(fileURLToPath(import.meta.url));
const res = join(here, '../android/app/src/main/res');
const webPublic = join(here, '../../web/public');

const blue = '#183d78';
const pink = '#d63382';
const markWidth = 320;

function mark() {
  return `
    <rect x="96" y="96" width="320" height="320" rx="56" fill="#ffffff"/>
    <rect x="128" y="154" width="103" height="81" rx="16" fill="${pink}"/>
    <rect x="147" y="181" width="65" height="19" rx="7" fill="#ffffff"/>
    <circle cx="159" cy="210" r="6" fill="#ffffff"/><circle cx="200" cy="210" r="6" fill="#ffffff"/>
    <rect x="251" y="154" width="133" height="81" rx="16" fill="${blue}"/>
    <path d="M267 202h101M287 181h62M286 210l-9 11m72-11 9 11" stroke="#ffffff" stroke-width="10" stroke-linecap="round"/>
    <rect x="128" y="276" width="256" height="17" rx="8" fill="${blue}"/>
    <rect x="128" y="317" width="176" height="17" rx="8" fill="${blue}"/>
    <circle cx="345" cy="325" r="19" fill="${pink}"/>`;
}

function silhouette() {
  return `<rect x="96" y="96" width="320" height="320" rx="56" fill="#000000"/>`;
}

function tile(radius) {
  return `<rect width="512" height="512" rx="${radius}" fill="${blue}"/>`;
}

function svg512(body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">${body}\n</svg>\n`;
}

// The adaptive-icon canvas is 108 units and launchers only promise the central 66-unit circle, so
// the mark is scaled to 56 units wide there (a square's corners reach further than a circle's).
function adaptive(body) {
  const scale = 56 / markWidth;
  const offset = 54 - 256 * scale;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><g transform="translate(${offset.toFixed(3)} ${offset.toFixed(3)}) scale(${scale.toFixed(5)})">${body}</g></svg>`;
}

async function png(file, size, source) {
  await mkdir(dirname(file), { recursive: true });
  await sharp(Buffer.from(source)).resize(size, size).png().toFile(file);
}

const favicon = svg512(`\n  ${tile(112)}${mark()}`);
const foreground = adaptive(mark());
const monochrome = adaptive(silhouette());
// Pre-API-26 launchers show the legacy bitmaps as they are, so those carry their own tile.
const legacySquare = svg512(`${tile(112)}${mark()}`);
const legacyRound = svg512(`<circle cx="256" cy="256" r="256" fill="${blue}"/>${mark()}`);

const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
for (const [density, scale] of Object.entries(densities)) {
  const dir = join(res, `mipmap-${density}`);
  await png(join(dir, 'ic_launcher_foreground.png'), 108 * scale, foreground);
  await png(join(dir, 'ic_launcher_monochrome.png'), 108 * scale, monochrome);
  await png(join(dir, 'ic_launcher.png'), 48 * scale, legacySquare);
  await png(join(dir, 'ic_launcher_round.png'), 48 * scale, legacyRound);
  await png(join(res, `drawable-${density}`, 'splash_mark.png'), 108 * scale, foreground);
}
await mkdir(webPublic, { recursive: true });
await writeFile(join(webPublic, 'favicon.svg'), favicon);
await png(join(webPublic, 'apple-touch-icon.png'), 180, svg512(`${tile(0)}${mark()}`));
console.log(`Icons written under ${res} and ${webPublic}`);
