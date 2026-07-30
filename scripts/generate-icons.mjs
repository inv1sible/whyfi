// Regenerates every derived icon in the repo from the three master images in
// icons/. Run it via scripts/generate-icons.sh (a throwaway Node container) —
// sharp is not a project dependency and deliberately isn't installed here.
//
// The masters are flat RGB with no alpha, and 02/03 have their rounded
// corners baked in as *black*. Left alone that black shows up as four hard
// wedges anywhere the icon isn't drawn on a black background — a launcher
// using a circular mask, a light-themed browser tab. So every derived asset
// re-cuts the corners as real transparency (see roundedCornerMask).
import { existsSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import pngToIco from "png-to-ico";
import sharp from "sharp";

const SRC = "/src";
const OUT = "/repo";

const MASTERS = {
  favicon: join(SRC, "01_Favicon.png"),
  pwa: join(SRC, "02_PWA.png"),
  android: join(SRC, "03_Android.png"),
};

// Sampled from the masters' own background, just inside the rounded corner
// (see the probe in scripts/generate-icons.sh). Used for the Android adaptive
// background layer and the maskable PWA icon, both of which need to extend
// the artwork's backdrop past the artwork itself.
const BRAND_NAVY = "#03102e";

// Android adaptive icons are 108dp layers of which only the central 72dp is
// guaranteed visible, and only a 66dp circle is guaranteed un-masked. What has
// to fit that 66dp circle is the *ink*, not the artwork's square canvas — the
// canvas corners are plain navy, identical to the background layer, so losing
// them costs nothing. Measured with scripts/ (see the ink-bbox probe): the
// radar ring spans 80.5% of the master, so an 80dp artwork puts the ring at
// 64dp, just inside the safe circle. Sizing to the square instead would shrink
// the icon by a quarter for no reason.
const ADAPTIVE_ARTWORK_FRACTION = 80 / 108;

// Same reasoning for a maskable PWA icon, whose safe area is a circle 80% of
// the icon's width. 02_PWA.png's ink spans 81.7%, so at 95% scale the ink
// lands at 77.6% — inside the safe circle with a little margin.
const MASKABLE_ARTWORK_FRACTION = 0.95;

// 01_Favicon.png's artwork occupies only ~50% of its width, and at 16px that
// wasted margin is the difference between three readable WiFi arcs and a
// teal blob. The favicon family is therefore cropped to its ink plus this
// much breathing room. A browser tab draws no chrome around a favicon, so it
// needs far less padding than a launcher icon does.
const FAVICON_INK_MARGIN = 0.08;

// Below this size, downscaling softens strokes enough that a little sharpening
// is the difference between legible and mushy. Above it, it just adds crunch.
const SHARPEN_AT_OR_BELOW = 48;

const DENSITIES = [
  { dir: "mipmap-mdpi", launcher: 48, foreground: 108 },
  { dir: "mipmap-hdpi", launcher: 72, foreground: 162 },
  { dir: "mipmap-xhdpi", launcher: 96, foreground: 216 },
  { dir: "mipmap-xxhdpi", launcher: 144, foreground: 324 },
  { dir: "mipmap-xxxhdpi", launcher: 192, foreground: 432 },
];

/** Where the baked-in rounded corner ends along the top edge — which, for a
 * rounded rect, is exactly its corner radius. Detected rather than hardcoded
 * so re-exporting a master with different rounding doesn't silently leave a
 * mask that no longer matches the art. */
async function detectCornerRadius(file) {
  const { data, info } = await sharp(file).raw().toBuffer({ resolveWithObject: true });
  const isBlackish = (x, y) => {
    const i = (y * info.width + x) * info.channels;
    return data[i] + data[i + 1] + data[i + 2] < 40;
  };
  let r = 0;
  while (r < info.width / 2 && isBlackish(r, 0)) r++;
  return { radius: r, size: info.width };
}

/** Bounding box of the actual artwork, keyed on hue rather than "differs from
 * the background" — 03_Android.png has a radial vignette that defeats a
 * background-difference test entirely. Cyan strokes plus the one yellow dot. */
async function inkBounds(file) {
  const { data, info } = await sharp(file).raw().toBuffer({ resolveWithObject: true });
  const { width: W, height: H, channels: C } = info;
  const isInk = (x, y) => {
    const i = (y * W + x) * C;
    const [r, g, b] = [data[i], data[i + 1], data[i + 2]];
    return (Math.max(g, b) > 110 && (g + b) / 2 - r > 30) || (r > 150 && g > 110 && b < 110);
  };
  let minX = W, minY = H, maxX = -1, maxY = -1;
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      if (!isInk(x, y)) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  return { minX, minY, maxX, maxY, width: W, height: H };
}

function roundedCornerMask(size, radius) {
  return Buffer.from(
    `<svg width="${size}" height="${size}"><rect width="${size}" height="${size}" rx="${radius}" ry="${radius}" fill="#fff"/></svg>`,
  );
}

function circleMask(size) {
  return Buffer.from(
    `<svg width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="#fff"/></svg>`,
  );
}

/** Master with its black corners replaced by real transparency, as a square
 * PNG buffer at `size`. */
async function withTransparentCorners(file, size) {
  const { radius, size: masterSize } = await detectCornerRadius(file);
  const scaled = sharp(file).resize(size, size, { fit: "fill" });
  if (radius === 0) return scaled.png().toBuffer(); // already full-bleed
  const scaledRadius = Math.round((radius / masterSize) * size);
  return scaled
    .composite([{ input: roundedCornerMask(size, scaledRadius), blend: "dest-in" }])
    .png()
    .toBuffer();
}

async function write(relPath, buffer) {
  const full = join(OUT, relPath);
  await mkdir(dirname(full), { recursive: true });
  await writeFile(full, buffer);
  console.log(`  ${relPath} (${(buffer.length / 1024).toFixed(1)} KB)`);
}

async function buildFavicon() {
  console.log("Website favicon <- 01_Favicon.png");

  // Crop to the ink plus a margin, centred on the artwork rather than on the
  // canvas. Safe to do here (and nowhere else) because this master's backdrop
  // is a flat navy with no rounded corners, so cropping it is invisible.
  const ink = await inkBounds(MASTERS.favicon);
  const extent = Math.max(ink.maxX - ink.minX, ink.maxY - ink.minY);
  const frame = Math.round(extent * (1 + 2 * FAVICON_INK_MARGIN));
  const cx = (ink.minX + ink.maxX) / 2;
  const cy = (ink.minY + ink.maxY) / 2;
  const clamp = (v, max) => Math.max(0, Math.min(Math.round(v), max - frame));
  const region = {
    left: clamp(cx - frame / 2, ink.width),
    top: clamp(cy - frame / 2, ink.height),
    width: frame,
    height: frame,
  };
  console.log(`  (cropped to ${frame}px around the artwork, from ${ink.width}px)`);

  // No corner handling: this master is already full-bleed navy, and a browser
  // tab is a hard-edged square anyway.
  const png = (size) => {
    const pipeline = sharp(MASTERS.favicon).extract(region).resize(size, size, { fit: "fill" });
    return (size <= SHARPEN_AT_OR_BELOW ? pipeline.sharpen({ sigma: 0.6 }) : pipeline).png().toBuffer();
  };

  for (const size of [16, 32, 96]) {
    await write(`frontend/public/favicon-${size}x${size}.png`, await png(size));
  }
  // Still worth shipping in 2026: it's what a bare /favicon.ico request gets,
  // and some feed readers and pinned-tab UIs ask for nothing else.
  await write("frontend/public/favicon.ico", await pngToIco([await png(16), await png(32), await png(48)]));
  // iOS ignores alpha and applies its own rounding, so it wants the square.
  await write("frontend/public/apple-touch-icon.png", await png(180));
}

async function buildPwa() {
  console.log("PWA <- 02_PWA.png");
  for (const size of [192, 512]) {
    await write(`frontend/public/icons/icon-${size}.png`, await withTransparentCorners(MASTERS.pwa, size));
  }

  // Maskable is a different job from "any": the launcher crops it to an
  // arbitrary shape, so the artwork shrinks inside a full-bleed backdrop
  // rather than reaching the edges. The inset artwork still needs its corners
  // cut — otherwise the master's black corners sit on the navy backdrop as a
  // dark square outline, plainly visible once a circular mask is applied.
  const size = 512;
  const artwork = Math.round(size * MASKABLE_ARTWORK_FRACTION);
  const maskable = await sharp({
    create: { width: size, height: size, channels: 4, background: BRAND_NAVY },
  })
    .composite([{ input: await withTransparentCorners(MASTERS.pwa, artwork), gravity: "centre" }])
    .png()
    .toBuffer();
  await write("frontend/public/icons/maskable-512.png", maskable);
}

async function buildAndroid() {
  console.log("Android <- 03_Android.png");
  const res = "android/app/src/main/res";

  for (const { dir, launcher, foreground } of DENSITIES) {
    // Legacy (pre-API-26) launcher icon: the artwork as-is, corners cut.
    await write(`${res}/${dir}/ic_launcher.png`, await withTransparentCorners(MASTERS.android, launcher));

    // Legacy round icon, for launchers that asked for one before adaptive
    // icons existed. The art is a radar disc, so a circle crop suits it.
    await write(
      `${res}/${dir}/ic_launcher_round.png`,
      await sharp(MASTERS.android)
        .resize(launcher, launcher, { fit: "fill" })
        .composite([{ input: circleMask(launcher), blend: "dest-in" }])
        .png()
        .toBuffer(),
    );

    // Adaptive foreground: artwork inset on a transparent 108dp canvas. The
    // background layer supplies the colour that fills the rest.
    const artwork = Math.round(foreground * ADAPTIVE_ARTWORK_FRACTION);
    await write(
      `${res}/${dir}/ic_launcher_foreground.png`,
      await sharp({
        create: { width: foreground, height: foreground, channels: 4, background: "#00000000" },
      })
        .composite([{ input: await withTransparentCorners(MASTERS.android, artwork), gravity: "centre" }])
        .png()
        .toBuffer(),
    );
  }

  // Deliberately no <monochrome>: themed icons tint a drawable by its alpha,
  // and this foreground's alpha is an opaque square (the artwork's own navy
  // backdrop), so it would theme as a solid filled blob. Omitting it makes
  // Android fall back to the normal icon, which is the better of the two.
  const adaptiveXml = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
`;
  await write(`${res}/mipmap-anydpi-v26/ic_launcher.xml`, Buffer.from(adaptiveXml));
  await write(`${res}/mipmap-anydpi-v26/ic_launcher_round.xml`, Buffer.from(adaptiveXml));

  await write(
    `${res}/values/ic_launcher_background.xml`,
    Buffer.from(`<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Sampled from icons/03_Android.png's own backdrop, so the adaptive
         background layer and the inset foreground artwork are the same navy
         and the seam between them is invisible. -->
    <color name="ic_launcher_background">${BRAND_NAVY}</color>
</resources>
`),
  );
}

for (const [name, file] of Object.entries(MASTERS)) {
  if (!existsSync(file)) throw new Error(`Missing master image for ${name}: ${file}`);
}

await buildFavicon();
await buildPwa();
await buildAndroid();
console.log("Done.");
