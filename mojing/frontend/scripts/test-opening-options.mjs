// Isolated browser regression. No real backend, user database, or provider calls.
// PLAYWRIGHT_MODULE may point to a preinstalled Playwright package.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15202);
const output = process.env.SMOKE_OUTPUT;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root, windowsHide: true, env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18001/api' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
child.stdout.on('data', (data) => { log += data; });
child.stderr.on('data', (data) => { log += data; });
let browser;
try {
  await Promise.race([
    new Promise((resolve, reject) => {
      const timer = setInterval(() => {
        if (log.includes('Local:')) { clearInterval(timer); resolve(); }
        else if (child.exitCode !== null) { clearInterval(timer); reject(new Error(log)); }
      }, 100);
      timer.unref();
    }),
    new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error(`Vite startup timeout: ${log}`)), 15000); timer.unref(); }),
  ]);
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  await context.route('**/*', route => {
    const url = new URL(route.request().url());
    return url.hostname === '127.0.0.1' && url.port === String(port) ? route.continue() : route.abort();
  });
  const page = await context.newPage();
  let failCreate = true;
  const bodies = [];
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await context.route('http://127.0.0.1:18001/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
    let data = [], status = 200;
    if (endpoint === '/system/local-config') data = { default_narrator_enabled: true, default_choice_generation_enabled: true, default_anti_cheat_enabled: true };
    else if (endpoint === '/sessions' && route.request().method() === 'POST') {
      bodies.push(JSON.parse(route.request().postData()));
      status = failCreate ? 503 : 200;
      data = failCreate ? { detail: '开局保存失败' } : { id: 42, title: '新故事', message_count: 0, participant_count: 0 };
    } else if (endpoint === '/sessions/42') data = { id: 42, title: '新故事', world: {} };
    else if (endpoint === '/sessions/42/world') data = {};
    else if (endpoint === '/sessions/42/messages') data = { items: [], next_cursor: null };
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
  });
  await page.goto(`http://127.0.0.1:${port}/chat`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: '新建对话', exact: true }).click();
  await page.getByText('对话设置', { exact: true }).click();
  for (const label of ['旁白', '剧情选项', '规则约束']) {
    const checkbox = page.getByRole('checkbox', { name: label, exact: true });
    await checkbox.waitFor();
    assert.equal(await checkbox.isChecked(), true);
    await checkbox.uncheck();
  }
  await page.setViewportSize({ width: 320, height: 640 });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.evaluate(() => window.dispatchEvent(new Event('open-mobile-sessions')));
  await page.getByRole('button', { name: '开始新对话', exact: true }).click({ timeout: 5000 });
  await page.getByRole('alert').filter({ hasText: '开局保存失败' }).waitFor();
  for (const label of ['旁白', '剧情选项', '规则约束']) {
    const checkbox = page.getByRole('checkbox', { name: label, exact: true });
    await checkbox.scrollIntoViewIfNeeded();
    assert.equal(await checkbox.isChecked(), false);
    const bounds = await checkbox.boundingBox();
    assert.ok(bounds.width >= 20 && bounds.height >= 20 && bounds.x >= 0 && bounds.x + bounds.width <= 320);
  }
  for (const summary of await page.locator('.session-create-options > summary').all()) {
    assert.ok((await summary.boundingBox()).height >= 48, 'settings headings must not collapse in a short form');
  }

  if (output) { await mkdir(output, { recursive: true }); await page.screenshot({ path: path.join(output, 'opening-options-320.png') }); }
  failCreate = false;
  await page.getByRole('button', { name: '开始新对话', exact: true }).click();
  await page.waitForURL('**/chat/42');
  assert.equal(bodies.length, 2);
  for (const body of bodies) for (const key of ['narrator_enabled', 'choice_generation_enabled', 'anti_cheat_enabled']) assert.equal(body[key], false);
  assert.deepEqual(errors, []);
  console.log('PASS: opening defaults, explicit false payloads, failure retention, retry, and 320px submission. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
