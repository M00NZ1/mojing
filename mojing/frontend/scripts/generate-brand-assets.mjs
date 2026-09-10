// Shared vector geometry for the Android launcher, system splash and Web app icons.
import { createRequire } from 'node:module';
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const frontend = fileURLToPath(new URL('../', import.meta.url));
const res = path.resolve(frontend, '../android/app/src/main/res');
const paths = [
  ['#94CDBA', 'M30,76 L30,46 C30,14 78,14 78,46 L78,76 L68,76 L68,46 C68,27 40,27 40,46 L40,76 Z'],
  ['#F2EEE3', 'M54,43 L66,61 L54,80 L42,61 Z'],
  ['#122723', 'M54,55 A3,3 0,1 0,54,61 A3,3 0,1 0,54,55 M53,60 L55,60 L55,77 L53,77 Z'],
];
const body = paths.map(([fill,d]) => `<path fill="${fill}" d="${d}"/>`).join('');
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" rx="24" fill="#122723"/>${body}</svg>`;
const vector = paths => `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">${paths.map(([fill,d]) => `<path android:fillColor="${fill}" android:pathData="${d}" />`).join('')}</vector>\n`;
await writeFile(path.join(res, 'drawable/ic_launcher_foreground.xml'), vector(paths));
await writeFile(path.join(res, 'drawable/ic_launcher_monochrome.xml'), vector(paths.slice(0,2).map(([,d]) => ['#FFFFFF', d])));
await writeFile(path.join(res, 'drawable/ic_launcher_background.xml'), '<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="#122723" /></shape>\n');
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
