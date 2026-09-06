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
const port = Number(process.env.SMOKE_PORT || 15183);
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
  const context = await browser.newContext({ viewport: { width: 1365, height: 900 } });
  await context.route('**/*', (route) => {
    const url = new URL(route.request().url());
    return url.hostname === '127.0.0.1' && url.port === String(port) ? route.continue() : route.abort();
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const world = { template_id: 'custom', world_prompt: '', narrator_enabled: false, narrator_name: '旁白', gameplay_mode: '自由剧情', suggested_choices_json: [], choice_generation_enabled: true, max_choice_count: 3, anti_cheat_enabled: false, anti_cheat_prompt: '' };
  const chat = { id: 1, title: '雾港 · 长篇历史', summary: '', created_at: '2026-09-07T00:00:00Z', world };
  let history = Array.from({ length: 6000 }, (_, i) => ({ id: i + 1, session_id: 1, branch_id: 'main', speaker_type: 'user', character_id: null, content: `第 ${i + 1} 夜。${i === 2 ? '旧信封的秘密。' : ''}${(i + 1) % 100 === 0 ? '线索：' : ''}${'雨声渐密，沈照与林汐对照航海日志，准备去灯塔寻找失踪的守夜人。'.repeat(8)}`, structured_content: {}, created_at: '2026-09-07T00:00:00Z' }));
  let previewFailure = false, deleteFailure = '', deletionRequests = 0;
  const blocked = new Set([3]);
  const windowRequests = [];
  await context.route('http://127.0.0.1:18001/api/**', async (route) => {
    const url = new URL(route.request().url());
    const endpoint = url.pathname.replace('/api', '');
    let data = [], status = 200;
    const matched = endpoint.match(/^\/sessions\/1\/messages\/(\d+)(?:\/(deletion-impact))?$/);
    if (matched) {
      const id = Number(matched[1]);
      if (matched[2]) {
        if (previewFailure) { status = 500; data = { detail: '检查暂不可用' }; }
        else data = { can_delete: !blocked.has(id), reason: blocked.has(id) ? '这条消息是故事线或编辑版本的来源，删除会使相关剧情无法读取。请保留原文，使用编辑创建新的故事线。' : '', reference_count: blocked.has(id) ? 1 : 0, branches: blocked.has(id) ? [{ branch_id: 'A', label: '灯塔的另一种结局', is_checkpoint: false }] : [] };
      } else if (route.request().method() === 'DELETE') {
        deletionRequests++;
        if (deleteFailure) {
          status = deleteFailure === 'conflict' ? 409 : 500;
          data = { detail: deleteFailure === 'conflict' ? '消息刚被新的故事线引用，请保留原文' : '删除暂不可用' };
          if (deleteFailure === 'conflict') blocked.add(id);
        } else { history = history.filter((item) => item.id !== id); data = { ok: true }; }
      }
    } else if (endpoint.endsWith('/messages/search-page')) {
      const query = url.searchParams.get('q');
      data = { items: history.filter((item) => item.content.includes(query)).slice(0, 25).map((item) => ({ ...item, snippet: `第 ${item.id} 夜 · 旧信封` })), next_cursor: null, index: { ready: true, indexed_count: 6000 } };
    } else if (endpoint.endsWith('/messages/window')) {
      const anchor = Number(url.searchParams.get('anchor_id'));
      windowRequests.push(anchor);
      const items = history.filter((item) => item.id >= anchor - 20 && item.id <= anchor + 20);
      data = { items, older_cursor: items[0].id > 1 ? items[0].id : null, newer_cursor: items.at(-1).id < 6000 ? items.at(-1).id : null };
    } else if (endpoint === '/sessions/1/messages') data = { items: history.slice(-40), next_cursor: history.at(-40).id };
    else if (endpoint === '/sessions') data = [chat];
    else if (endpoint === '/sessions/1') data = chat;
    else if (endpoint === '/sessions/1/world') data = world;
    else if (endpoint === '/sessions/1/branches') data = [{ branch_id: 'main', label: '主线' }];
    else if (endpoint === '/system/model-platforms') data = { version: 1, active_id: null, platforms: [] };
    else if (endpoint === '/sessions/1/model-selection') data = { version: 1, selection: null };
    else if (endpoint === '/personas/active') data = { id: 1, name: '玩家', avatar_color: '#53c7a8' };
    else if (endpoint === '/system/local-config') data = { max_auto_speakers: 2 };
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
  });
  const dialog = page.getByRole('dialog', { name: '删除这条消息？' });
  async function openDelete(id) {
    const row = page.locator(`[data-chat-message-id="${id}"]`);
    await row.scrollIntoViewIfNeeded();
    await row.hover();
    await row.getByRole('button', { name: '删除消息', exact: true }).click();
    await dialog.waitFor();
  }
  await page.goto(`http://127.0.0.1:${port}/chat/1`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('searchbox', { name: '搜索当前故事线的消息' }).fill('旧信封');
  await page.locator('.message-search-results').getByRole('button', { name: /第 3 夜/ }).click();
  await page.locator('[data-chat-message-id="3"].chat-message-focus').waitFor();
  await openDelete(3);
  await dialog.getByText('需要保留这条消息', { exact: true }).waitFor();
  assert.ok(await dialog.getByRole('button', { name: '确认删除' }).isDisabled());
  await dialog.getByText('故事线 · 灯塔的另一种结局', { exact: true }).waitFor();
  if (output) { await mkdir(output, { recursive: true }); await page.screenshot({ path: path.join(output, 'delete-protected-desktop.png') }); }
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'hidden' });
  assert.equal(deletionRequests, 0);
  // Preview errors disable deletion; retry does not itself delete anything.
  previewFailure = true;
  await openDelete(4);
  await dialog.getByRole('alert').filter({ hasText: '暂时无法确认删除影响' }).waitFor();
  assert.ok(await dialog.getByRole('button', { name: '确认删除' }).isDisabled());
  previewFailure = false;
  await dialog.getByRole('button', { name: '重试', exact: true }).click();
  await dialog.getByText(/删除后无法撤销/).waitFor();
  deleteFailure = 'failure';
  await dialog.getByRole('button', { name: '确认删除' }).click();
  await dialog.getByRole('alert').filter({ hasText: '删除未完成' }).waitFor();
  assert.ok(history.some((item) => item.id === 4));
  deleteFailure = '';
  await dialog.getByRole('button', { name: '重试删除' }).click();
  await dialog.waitFor({ state: 'hidden' });
  await page.locator('[data-chat-message-id="3"].chat-message-focus').waitFor();
  assert.ok(!history.some((item) => item.id === 4));
  assert.equal(windowRequests.at(-1), 3, 'deletion should stay near old history');
  // A new reference can appear between preview and actual deletion.
  await openDelete(5);
  await dialog.getByText(/删除后无法撤销/).waitFor();
  deleteFailure = 'conflict';
  await dialog.getByRole('button', { name: '确认删除' }).click();
  await dialog.getByText('需要保留这条消息', { exact: true }).waitFor();
  assert.ok(await dialog.getByRole('button', { name: '重试删除' }).isDisabled());
  assert.ok(history.some((item) => item.id === 5));
  await dialog.getByRole('button', { name: '保留并返回' }).click();
  deleteFailure = '';
  for (const close of await page.locator('.toast-close').all()) await close.click();
  await page.setViewportSize({ width: 390, height: 844 });
  // Focus an old item via search again after viewport remeasurement.
  await page.getByRole('searchbox', { name: '搜索当前故事线的消息' }).fill('旧信封');
  await page.locator('.message-search-results').getByRole('button', { name: /第 3 夜/ }).click();
  await openDelete(3);
  await dialog.getByText('需要保留这条消息', { exact: true }).waitFor();
  const bounds = await dialog.boundingBox();
  assert.ok(bounds.x >= 10 && bounds.x + bounds.width <= 380);
  assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= 844);
  if (output) await page.screenshot({ path: path.join(output, 'delete-protected-mobile.png') });
  await dialog.getByRole('button', { name: '保留并返回' }).click();
  await openDelete(6);
  await dialog.getByText(/删除后无法撤销/).waitFor();
  await page.waitForFunction(() => ![...document.querySelectorAll('dialog button')].find((button) => button.textContent === '确认删除').disabled);
  if (output) await page.screenshot({ path: path.join(output, 'delete-confirm-mobile.png') });
  const countBeforeCancel = deletionRequests;
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  assert.equal(deletionRequests, countBeforeCancel);
  assert.ok(history.some((item) => item.id === 6));
  assert.equal(errors.length, 0, errors.join('\n'));
  console.log('PASS: source protection, no delayed deletion, bounded preview, cancel/Escape, preview retry, delete retry, late reference rejection, preserved old reading position, desktop/mobile layout. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
