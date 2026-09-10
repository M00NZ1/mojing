import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const css = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8');
const browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
try {
  const page = await browser.newPage({ viewport: { width: 390, height: 760 } });
  await page.route('**/*', route => route.abort());
  const body = `<div class="chat-bubble-text"><p class="md-paragraph">雨夜的灯塔仍亮着，故事继续。</p><blockquote class="md-blockquote">码头见，这是引用的原文。</blockquote><p><a href="#">查看线索</a> · <code class="md-inline-code">夜航日志</code></p><pre class="md-code-block">守灯人的记录</pre><table class="md-table"><tr><th>地点</th><td>雾港</td></tr></table></div>`;
  await page.setContent(`<style>${css}</style><main style="padding:20px;display:grid;gap:16px"><h2>墨境 · 对话</h2><div class="chat-bubble bubble-other">${body}</div><div class="chat-bubble bubble-self">${body}</div></main>`);
  let minimum = Infinity;
  for (const theme of ['dark', 'light', 'paper', 'ink', 'forest', 'studio', 'midnight']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme);
    const ratios = await page.locator('.chat-bubble').evaluateAll(bubbles => {
      const ctx = document.createElement('canvas').getContext('2d');
      const luminance = () => {
        const c = [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3).map(n => {
          const v = n / 255; return v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4;
        });
        return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
      };
      return bubbles.flatMap(bubble => {
        if (getComputedStyle(bubble).backgroundImage !== 'none') throw new Error('Gradient requires sampled contrast checks');
        return [...bubble.querySelectorAll('.md-paragraph, .md-blockquote, a, code, pre, th, td')].map(node => {
          ctx.globalAlpha = 1;
          ctx.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--bg');
          ctx.fillRect(0, 0, 1, 1);
          const ancestors = [];
          for (let el = node; el; el = el.parentElement) ancestors.unshift(el);
          let opacity = 1;
          for (const el of ancestors) {
            const style = getComputedStyle(el);
            opacity *= Number(style.opacity);
            ctx.globalAlpha = opacity;
            ctx.fillStyle = style.backgroundColor;
            ctx.fillRect(0, 0, 1, 1);
          }
          const background = luminance();
          ctx.fillStyle = getComputedStyle(node).color;
          ctx.fillRect(0, 0, 1, 1);
          const foreground = luminance();
          return { selector: `${bubble.className} ${node.tagName}`, ratio: (Math.max(background, foreground) + .05) / (Math.min(background, foreground) + .05) };
        });
      });
    });
    for (const { selector, ratio } of ratios) {
      assert.ok(ratio >= 4.5, `${theme} ${selector}: ${ratio.toFixed(2)}`);
      minimum = Math.min(minimum, ratio);
    }
    if (process.env.SMOKE_OUTPUT) {
      await mkdir(process.env.SMOKE_OUTPUT, { recursive: true });
      await page.screenshot({ path: path.join(process.env.SMOKE_OUTPUT, `messages-${theme}.png`) });
    }
  }
  console.log(`PASS: seven themes, both bubble roles and Markdown content; minimum contrast ${minimum.toFixed(2)}.`);
} finally { await browser.close(); }
