// Isolated UI test; all API requests are intercepted, no user service is used.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15185);
const apiPort = 18185;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: fileURLToPath(new URL('../', import.meta.url)), windowsHide: true,
  env: { ...process.env, VITE_API_BASE: `http://127.0.0.1:${apiPort}/api` }, stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '', browser;
child.stdout.on('data', (data) => { log += data; });
child.stderr.on('data', (data) => { log += data; });
try {
  const deadline = Date.now() + 15000;
  while (!log.includes('Local:')) {
    if (child.exitCode !== null || Date.now() > deadline) throw new Error(log || 'Vite did not start');
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  for (const width of [1280, 320]) {
    const context = await browser.newContext({ viewport: { width, height: 650 } });
    let moved = false, calls = 0, fail = true;
    const errors = [];
    await context.route('**/*', async (route) => {
      const url = new URL(route.request().url());
      if (url.hostname !== '127.0.0.1' || ![String(port), String(apiPort)].includes(url.port)) return route.abort();
      if (url.port === String(port)) return route.continue();
      const endpoint = url.pathname.replace('/api', '');
      let data = {};
      if (endpoint === '/worlds/library') data = {
        worlds: [{ id: 1, name: '既有世界', description: '已有百科内容', gameplay_mode: '探索' }, ...(moved ? [{ id: 2, name: '雾港', description: '钟楼与港口', gameplay_mode: '自由剧情' }] : [])],
        legacy_templates: moved ? [] : [{ template_id: 'mist', name: '雾港', description: '钟楼与港口'.repeat(60), updated_at: '2026-09-13T00:00:00', source_hash: 'fixture-v1' }],
      };
      else if (endpoint === '/worlds/templates/mist/promote') {
        calls++;
        assert.equal(route.request().postDataJSON().updated_at, '2026-09-13T00:00:00');
        assert.equal(route.request().postDataJSON().source_hash, 'fixture-v1');
        if (fail) { fail = false; return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: '暂时无法保存' }) }); }
        moved = true; data = { encyclopedia_id: 2, name: '雾港' };
      }
      else if (endpoint === '/system/starter-catalog') data = { available: false };
      else if (endpoint === '/characters' || endpoint === '/sessions') data = [];
      else return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
    });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${port}/create`);
    await page.getByRole('link', { name: /世界 管理背景与百科资料/ }).click();
    await page.getByRole('heading', { name: '世界', exact: true }).waitFor();
    assert.equal(await page.getByRole('link', { name: /打开世界/ }).count(), 1);
    await page.getByText('旧工坊资料 · 1', { exact: true }).click();
    await page.getByRole('button', { name: '归入世界', exact: true }).click();
    await page.getByRole('button', { name: '取消', exact: true }).click();
    assert.equal(calls, 0);
    await page.getByRole('button', { name: '归入世界', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '归入世界', exact: true }).click();
    await page.getByText('整理未完成，原资料已保留').waitFor();
    await page.getByRole('button', { name: /重试/ }).click();
    await page.getByRole('link', { name: /雾港.*打开世界/ }).waitFor();
    assert.equal(calls, 2);
    await page.reload();
    await page.getByRole('link', { name: /雾港.*打开世界/ }).waitFor();
    assert.equal(await page.getByText(/旧工坊资料 ·/).count(), 0);
    if (process.env.SMOKE_OUTPUT) {
      await mkdir(process.env.SMOKE_OUTPUT, { recursive: true });
      await page.screenshot({ path: join(process.env.SMOKE_OUTPUT, `world-library-${width}.png`) });
    }
    await page.getByLabel('搜索世界').fill('不存在');
    await page.getByText('没有匹配的世界。').waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.deepEqual(errors, []);
    await context.close();
  }
  console.log('PASS world library: desktop/320px navigation, cancel, failed save retry, refresh, search');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
