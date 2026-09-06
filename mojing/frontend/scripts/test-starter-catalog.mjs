// Standalone UI regression with mocked local API and isolated browser data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15179);
const output = process.env.SMOKE_OUTPUT;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: fileURLToPath(new URL('../', import.meta.url)), windowsHide: true, env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18001/api' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '', browser;
child.stdout.on('data', (data) => { log += data; });
child.stderr.on('data', (data) => { log += data; });
try {
  const deadline = Date.now() + 15000;
  while (!log.includes('Local:')) {
    if (child.exitCode !== null || Date.now() > deadline) throw new Error(log);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  if (output) await mkdir(output, { recursive: true });
  for (const width of [1365, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 900 } });
    await context.route('**/*', (route) => {
      const url = new URL(route.request().url());
      return url.hostname === '127.0.0.1' && url.port === String(port) ? route.continue() : route.abort();
    });
    const catalog = { available: true, title: '雾港来信', summary: '一封没有寄件人的旧信，将你带到秋雾中的海港。与灯塔维护者和档案员相识，循着十年前失踪船只的线索，决定属于你的故事。', template_id: 'fog', encyclopedia_id: 9, characters: [{ id: 10, name: '沈照' }, { id: 11, name: '林汐' }], retired: { characters: ['旧角色'], worlds: ['旧世界'], encyclopedias: ['旧百科'] } };
    let failRestore = true, failStart = true, submitted;
    const world = { template_id: 'fog', world_prompt: '雾港设定', narrator_enabled: true, narrator_name: '旁白', gameplay_mode: '自由剧情', suggested_choices_json: [], choice_generation_enabled: true, max_choice_count: 3 };
    const session = { id: 7, title: '雾港来信 · 新故事', summary: '', created_at: '2026-09-07T00:00:00Z', updated_at: '2026-09-07T00:00:00Z', world };
    await context.route('http://127.0.0.1:18001/api/**', async (route) => {
      const request = route.request();
      const endpoint = new URL(request.url()).pathname.replace('/api', '');
      let status = 200, data = [];
      if (endpoint === '/system/starter-catalog') data = catalog;
      else if (endpoint === '/system/starter-catalog/restore') {
        if (failRestore) { status = 500; data = { detail: '暂时无法恢复' }; }
        else { catalog.retired = {}; data = catalog; }
      } else if (endpoint === '/sessions' && request.method() === 'POST') {
        submitted = request.postDataJSON();
        if (failStart) { status = 500; data = { detail: '暂时无法创建故事' }; } else data = session;
      } else if (endpoint === '/sessions/7') data = session;
      else if (endpoint === '/sessions/7/world') data = world;
      else if (endpoint === '/sessions/7/messages') data = { items: [], next_cursor: null };
      else if (endpoint === '/sessions/7/branches') data = [{ branch_id: 'main', label: '主线' }];
      else if (endpoint === '/sessions/7/model-choice') data = { version: 1, selection: null };
      else if (endpoint === '/system/model-platforms') data = { version: 1, platforms: [], active_id: null };
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${port}/create`);
    await page.getByRole('heading', { name: '雾港来信', exact: true }).waitFor();
    await page.getByText('沈照 / 林汐', { exact: true }).waitFor();
    const bounds = await page.getByRole('button', { name: '从雾港开始' }).boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width);
    if (output) await page.screenshot({ path: path.join(output, `starter-${width}.png`), fullPage: true });
    await page.getByText('旧示例已收起', { exact: false }).click();
    await page.getByRole('button', { name: '恢复旧示例', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '恢复失败' }).waitFor();
    failRestore = false;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    await page.locator('.starter-retired').waitFor({ state: 'hidden' });
    await page.getByRole('button', { name: '从雾港开始', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '未能打开故事' }).waitFor();
    failStart = false;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    await page.waitForURL('**/chat/7');
    assert.equal(submitted.template_id, 'fog');
    assert.equal(submitted.encyclopedia_id, 9);
    assert.deepEqual(submitted.initial_character_ids, [10, 11]);
    assert.equal(errors.length, 0, errors.join('\n'));
    catalog.available = false;
    await page.goto(`http://127.0.0.1:${port}/create`);
    await page.getByRole('heading', { name: '开始创作' }).waitFor();
    await page.locator('.starter-world').waitFor({ state: 'hidden' });
    await context.close();
  }
  console.log('PASS: desktop/mobile starter presentation, restore failure/retry, session creation failure/retry with world+encyclopedia+two characters, removed catalog hides starter action. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
