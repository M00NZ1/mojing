// Shared vector geometry for the Android launcher, system splash and Web app icons.
import { createRequire } from 'node:module';
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const frontend = fileURLToPath(new URL('../', import.meta.url));
const res = path.resolve(frontend, '../android/app/src/main/res');
// A circular landscape: the upper silhouette leaves a mountain ridge in negative
// space; the lower brush sweep also reads as an open page. All geometry stays
// inside Android's central 66dp safe circle, including circular launcher masks.
const paths = [
  ['#F1EBDD', 'M28,59 C25,43 37,28 54,28 C70,28 82,42 80,58 L65,44 L52,59 L43,51 Z'],
  ['#A2C5B5', 'M29,66 C41,70 48,62 57,60 C66,57 72,66 79,64 C75,75 65,81 54,81 C43,81 34,75 29,66 Z'],
];
const body = paths.map(([fill,d]) => `<path fill="${fill}" d="${d}"/>`).join('');
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" rx="24" fill="#142822"/>${body}</svg>`;
const vector = paths => `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">${paths.map(([fill,d]) => `<path android:fillColor="${fill}" android:pathData="${d}" />`).join('')}</vector>\n`;
await writeFile(path.join(res, 'drawable/ic_launcher_foreground.xml'), vector(paths));
await writeFile(path.join(res, 'drawable/ic_launcher_monochrome.xml'), vector(paths.map(([,d]) => ['#FFFFFF', d])));
await writeFile(path.join(res, 'drawable/ic_launcher_background.xml'), '<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="#142822" /></shape>\n');
const browser = await chromium.launch({headless:true, channel: process.env.SMOKE_BROWSER || undefined});
try {
  const page = await browser.newPage({deviceScaleFactor:1});
  for (const [size, destination] of [[192,'icons/icon-192'],[512,'icons/icon-512']]) {
    await writeFile(path.join(frontend, 'public', destination + '.svg'), svg + '\n');
    await page.setViewportSize({width:size,height:size});
    await page.setContent(`<style>body{margin:0}svg{display:block;width:100vw;height:100vh}</style>${svg}`);
    await page.screenshot({path:path.join(frontend,'public',destination+'.png'),omitBackground:true});
  }
  for (const [density,size] of [['mdpi',48],['hdpi',72],['xhdpi',96],['xxhdpi',144],['xxxhdpi',192]]) {
    await page.setViewportSize({width:size,height:size});
    await page.setContent(`<style>body{margin:0}svg{display:block;width:100vw;height:100vh}</style>${svg}`);
    await page.screenshot({path:path.join(res,`mipmap-${density}/ic_launcher.png`),omitBackground:true});
  }
} finally { await browser.close(); }
console.log('Brand assets generated from shared vector geometry.');
