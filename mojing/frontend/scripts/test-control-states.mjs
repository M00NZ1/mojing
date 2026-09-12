import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const css = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8');
const browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
try {
  const page = await browser.newPage({ viewport: { width: 780, height: 520 } });
  await page.route('**/*', route => route.abort());
  await page.setContent(`<style>${css}</style><main style="padding:32px;display:grid;gap:20px">
    <h1>墨境 · 控件状态</h1><label>故事名称 <input aria-label="故事名称" value="雾港来信"></label>
    <div class="btn-group"><button class="btn btn-primary" id="primary">开始新对话</button>
    <button class="btn btn-ghost">返回</button><button class="btn btn-danger">删除</button>
    <button class="btn btn-primary" id="disabled" disabled>正在保存</button></div></main>`);
  const fieldStyle = await page.getByRole('textbox', { name: '故事名称' }).evaluate(field => {
    const style = getComputedStyle(field);
    return { radius: style.borderRadius, border: style.borderTopColor };
  });
  assert.equal(fieldStyle.radius, '16px', 'filled field radius must override generic element rules');
  assert.equal(fieldStyle.border, 'rgba(0, 0, 0, 0)', 'resting field uses a filled surface');
  for (const theme of ['dark', 'light', 'paper', 'ink', 'forest', 'studio', 'midnight']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme);
    const primary = page.locator('#primary');
    for (const hovered of [false, true]) {
      if (hovered) await primary.hover(); else await page.mouse.move(0, 0);
      // Let the production CSS transition finish before measuring its rendered endpoint.
      await page.waitForTimeout(220);
      const ratio = await primary.evaluate(button => {
        const ctx = document.createElement('canvas').getContext('2d');
        const luminance = color => {
          ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = color; ctx.fillRect(0, 0, 1, 1);
          const values = [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3).map(n => {
            const c = n / 255; return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4;
          });
          return values[0] * .2126 + values[1] * .7152 + values[2] * .0722;
        };
        const style = getComputedStyle(button);
        const a = luminance(style.color), b = luminance(style.backgroundColor);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
      });
      assert.ok(ratio >= 4.5, `${theme} ${hovered ? 'hover' : 'rest'} contrast ${ratio}`);
    }
    const disabled = page.locator('#disabled');
    const background = await disabled.evaluate(el => getComputedStyle(el).backgroundColor);
    await disabled.hover({ force: true });
    await page.waitForTimeout(220);
    assert.equal(await disabled.evaluate(el => getComputedStyle(el).backgroundColor), background);
    assert.equal(await disabled.evaluate(el => getComputedStyle(el).transform), 'none');
    await page.getByRole('textbox').focus();
    await page.keyboard.press('Tab');
    assert.equal(await primary.evaluate(el => el === document.activeElement && el.matches(':focus-visible')), true);
    assert.notEqual(await primary.evaluate(el => getComputedStyle(el).outlineStyle), 'none');
    if (process.env.SMOKE_OUTPUT && ['dark', 'light'].includes(theme)) {
      await mkdir(process.env.SMOKE_OUTPUT, { recursive: true });
      await page.screenshot({ path: path.join(process.env.SMOKE_OUTPUT, `controls-${theme}.png`) });
    }
  }
  console.log('PASS: seven themes, primary rest/hover contrast >= 4.5, disabled hover stability, keyboard focus visibility.');
} finally { await browser.close(); }
