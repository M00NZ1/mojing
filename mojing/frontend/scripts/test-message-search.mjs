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
const port = Number(process.env.SMOKE_PORT || 15182);
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
  const history = Array.from({ length: 6000 }, (_, i) => ({ id: i + 1, session_id: 1, branch_id: 'main', speaker_type: 'user', character_id: null, content: `第 ${i + 1} 夜。${i === 2 ? `${'雾港旧报纸和潮声。'.repeat(120)}**旧信封**的秘密。` : ''}${(i + 1) % 100 === 0 ? '线索：' : ''}${'雨声渐密，沈照与林汐对照航海日志，准备去灯塔寻找失踪的守夜人。'.repeat(8)}`, structured_content: {}, created_at: '2026-09-07T00:00:00Z' }));
  let remaining = 100, searchFailure = false, locateFailure = false, rebuildFailure = false;
  let searchCalls = 0, windows = 0;
  const pageSizes = [];
  const searchScopes = [];
  const searchQueries = [];
  await context.route('http://127.0.0.1:18001/api/**', async (route) => {
    const url = new URL(route.request().url());
    const endpoint = url.pathname.replace('/api', '');
    let data = [], status = 200;
    if (endpoint.endsWith('/messages/search-page')) {
      searchCalls++;
      const query = url.searchParams.get('q');
      searchQueries.push(query);
      searchScopes.push(url.searchParams.get('branch_id'));
      if (searchFailure) { status = 500; data = { detail: '临时搜索故障' }; }
      else {
        if (query === '慢查询') await new Promise((resolve) => setTimeout(resolve, 700));
        const before = Number(url.searchParams.get('before') || 6001);
        const allHits = history.filter((m) => m.content.includes(query));
        const hits = allHits.filter((m) => m.id < before).reverse();
        const ready = (url.searchParams.get('advance_index') !== 'false' ? --remaining : remaining) <= 0;
        data = { items: hits.slice(0, 25).map((m) => ({ ...m, content: undefined, snippet: `第 ${m.id} 夜 · ${query}：码头留下的旧证词` })), next_cursor: hits.length > 25 ? hits[24].id : null, total_count: ready ? allHits.length : null, index: { ready, indexed_count: Math.min(6000, searchCalls * 200) } };
      }
    } else if (endpoint.endsWith('/messages/search-index/rebuild')) {
      if (rebuildFailure) { status = 500; data = { detail: '重建暂不可用' }; }
      else { remaining = 1; data = { ok: true }; }
    } else if (endpoint.endsWith('/messages/window')) {
      windows++;
      if (locateFailure) { status = 500; data = { detail: '定位暂不可用' }; }
      else {
        const anchor = Number(url.searchParams.get('anchor_id'));
        const items = history.filter((m) => m.id >= anchor - 20 && m.id <= anchor + 20);
        pageSizes.push(items.length);
        data = { items, older_cursor: items[0].id > 1 ? items[0].id : null, newer_cursor: items.at(-1).id < 6000 ? items.at(-1).id : null };
      }
    } else if (endpoint === '/sessions/1/messages') {
      const before = Number(url.searchParams.get('cursor') || 6001);
      const items = history.filter((m) => m.id < before).slice(-40);
      pageSizes.push(items.length);
      data = { items, next_cursor: items[0].id > 1 ? items[0].id : null };
    } else if (endpoint === '/sessions') data = [chat];
    else if (endpoint === '/sessions/1') data = chat;
    else if (endpoint === '/sessions/1/world') data = world;
    else if (endpoint === '/sessions/1/branches') data = [{ branch_id: 'main', label: '主线' }];
    else if (endpoint === '/system/model-platforms') data = { version: 1, active_id: null, platforms: [] };
    else if (endpoint === '/sessions/1/model-selection') data = { version: 1, selection: null };
    else if (endpoint === '/personas/active') data = { id: 1, name: '玩家', avatar_color: '#53c7a8' };
    else if (endpoint === '/system/local-config') data = { max_auto_speakers: 2 };
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) }).catch(() => {});
  });
  const searchInput = page.getByRole('searchbox', { name: '搜索当前故事线的消息' });
  const results = page.locator('.message-search-results');
  await page.goto(`http://127.0.0.1:${port}/chat/1`, { waitUntil: 'domcontentloaded' });
  await page.locator('[data-chat-message-id="6000"]').waitFor();
  const initialNodes = await page.locator('[data-chat-message-id]').count();
  assert.ok(initialNodes < 40, `initial rendered nodes ${initialNodes}`);
  // Input-method preedit must never trigger a search or advance the index.
  await searchInput.dispatchEvent('compositionstart');
  await searchInput.fill('xian');
  await page.waitForTimeout(400);
  assert.equal(searchCalls, 0, 'IME preedit must not query');
  await searchInput.fill('线索');
  await searchInput.dispatchEvent('compositionend');
  await page.getByRole('button', { name: '暂停索引', exact: true }).click();
  await page.getByText(/索引已暂停/).waitFor();
  await page.waitForTimeout(450); // Allow the one already in-flight batch to finish.
  const pausedCalls = searchCalls;
  await page.waitForTimeout(700);
  assert.equal(searchCalls, pausedCalls, 'paused index must not poll');
  assert.ok(await page.getByRole('button', { name: '更早结果' }).isDisabled());
  remaining = 1;
  await page.getByRole('button', { name: '继续索引', exact: true }).click();
  await page.waitForFunction(() => ![...document.querySelectorAll('button')].find((b) => b.textContent === '更早结果').disabled);
  assert.equal(await results.locator('li').count(), 25);
  assert.equal(await results.locator('mark').count(), 25);
  assert.equal(await results.locator('mark').first().textContent(), '线索');
  assert.ok((await results.boundingBox()).height > 500, 'desktop search page uses available height');
  assert.equal(await page.locator('.chat-inputbar').count(), 0, 'dedicated search page hides chat composer');
  assert.equal(await results.locator('time').first().textContent(), '2026/09/07', 'results show conversation date');
  await searchInput.press('Enter');
  await searchInput.fill('');
  await page.getByText('最近搜索', { exact: true }).waitFor();
  assert.ok(await page.getByRole('button', { name: '线索', exact: true }).count() > 0, 'empty query shows recent history');
  await searchInput.fill('线索');
  await page.getByRole('button', { name: '更早结果' }).click();
  await page.getByText(/^第 2 页 ·/).waitFor();
  await results.getByText('第 3500 夜 · 线索：码头留下的旧证词', { exact: true }).waitFor();
  await results.getByRole('button', { name: /第 3500 夜/ }).click();
  await page.getByText('第 26 / 60 条命中', { exact: true }).waitFor();
  await page.getByRole('button', { name: '返回搜索结果', exact: true }).click();
  await page.getByText(/^第 2 页 ·/).waitFor();
  if (output) { await mkdir(output, { recursive: true }); await page.screenshot({ path: path.join(output, 'search-desktop.png') }); }
  await page.getByRole('button', { name: '更早结果' }).click();
  await page.getByText(/^第 3 页 ·/).waitFor();
  await page.waitForFunction(() => document.querySelectorAll('.message-search-results li').length === 10);
  assert.ok(await page.getByRole('button', { name: '更早结果' }).isDisabled());
  await page.getByRole('button', { name: '较新结果' }).click();
  await page.getByText(/^第 2 页 ·/).waitFor();
  await results.locator('ul').evaluate((list) => { list.scrollTop = list.scrollHeight; });
  assert.ok(await results.locator('ul').evaluate((list) => list.scrollTop > 0));
  await page.getByRole('button', { name: '较新结果' }).click();
  await page.getByText(/^第 1 页 ·/).waitFor();
  assert.equal(await results.locator('ul').evaluate((list) => list.scrollTop), 0);
  await results.locator('ul').evaluate((list) => { list.scrollTop = 80; });
  await page.getByRole('button', { name: '刷新搜索' }).click();
  await page.waitForFunction(() => ![...document.querySelectorAll('button')].find((b) => b.textContent === '刷新搜索').disabled);
  assert.equal(await results.locator('ul').evaluate((list) => list.scrollTop), 80);
  // Fast edits coalesce to one query; old hits cannot be selected while waiting.
  const queriesBeforeTyping = searchQueries.length;
  await searchInput.fill('旧');
  assert.ok(await results.locator('li button').first().isDisabled());
  await searchInput.fill('旧信');
  await searchInput.fill('旧信封');
  await results.getByRole('button', { name: /第 3 夜/ }).waitFor();
  assert.deepEqual(searchQueries.slice(queriesBeforeTyping), ['旧信封']);
  // Unloaded old message, transient location failure, then successful focus.
  await searchInput.fill('旧信封');
  const oldHit = results.getByRole('button', { name: /第 3 夜/ });
  await oldHit.waitFor();
  assert.equal(await oldHit.locator('mark').textContent(), '旧信封');
  locateFailure = true;
  await oldHit.click();
  const locateError = results.locator('.message-search-locate-error').getByRole('alert');
  await locateError.filter({ hasText: '定位暂不可用' }).waitFor();
  assert.equal(await searchInput.inputValue(), '旧信封');
  assert.equal(await page.getByText(/^第 1 页 ·/).count(), 1);
  locateFailure = false;
  await locateError.getByRole('button', { name: '重试', exact: true }).click();
  await page.locator('[data-chat-message-id="3"].chat-message-focus').waitFor();
  assert.equal(await searchInput.inputValue(), '旧信封');
  await page.getByText('第 1 / 1 条命中', { exact: true }).waitFor();
  assert.ok(await page.locator('.message-search-results').count() === 0, 'reading view hides result layer');
  assert.equal(await page.locator('.chat-inputbar').count(), 0, 'reading view hides chat composer');
  assert.equal(await page.locator('[data-chat-message-id="3"] .chat-msg-tools').count(), 0, 'reading view hides message write actions');
  assert.equal(await page.locator('[data-chat-message-id="3"] mark').count(), 1, 'reading view highlights the original message without a second excerpt bubble');
  assert.equal(await page.locator('[data-chat-message-id="3"] strong mark').first().textContent(), '旧信封', 'markdown emphasis remains while the match is highlighted');
  await page.waitForFunction(() => {
    const container = document.querySelector('.chat-msgs');
    const match = document.querySelector('[data-chat-message-id="3"] mark.message-search-match');
    if (!container || !match) return false;
    const bounds = container.getBoundingClientRect();
    const hit = match.getBoundingClientRect();
    return hit.top >= bounds.top && hit.bottom <= bounds.bottom;
  });
  if (output) await page.screenshot({ path: path.join(output, 'search-reading-desktop.png') });
  assert.equal(await page.locator('[data-chat-message-id="4"] mark').count(), 0, 'reading view only highlights the hit message');
  await page.getByRole('button', { name: '返回搜索结果', exact: true }).click();
  await results.getByRole('button', { name: /第 3 夜/ }).waitFor();
  await page.getByRole('button', { name: '关闭搜索', exact: true }).click();
  await page.locator('.chat-inputbar').waitFor();
  assert.ok(windows >= 2);
  assert.ok(await page.locator('[data-chat-message-id]').count() < 40);
  await page.getByRole('button', { name: '回到最新消息', exact: true }).first().click();
  await page.locator('[data-chat-message-id="6000"]').waitFor();
  // Error retry and cancelled stale query must not replace the current result.
  searchFailure = true;
  await searchInput.fill('未出现');
  await page.getByRole('alert').filter({ hasText: '搜索失败' }).waitFor();
  const failedCalls = searchCalls;
  await page.waitForTimeout(700);
  assert.equal(searchCalls, failedCalls);
  searchFailure = false;
  await page.getByRole('alert').filter({ hasText: '搜索失败' }).getByRole('button', { name: /重试/ }).click();
  await page.getByText('无匹配消息', { exact: true }).waitFor();
  await Promise.all([page.waitForRequest((r) => r.url().includes(encodeURIComponent('慢查询'))), searchInput.fill('慢查询')]);
  await searchInput.fill('旧信封');
  await oldHit.waitFor();
  await page.waitForTimeout(800);
  assert.equal(await results.locator('li').count(), 1);
  // Rebuild has a recoverable error and never requests source deletion.
  await results.getByText('搜索维护', { exact: true }).click();
  rebuildFailure = true;
  await page.getByRole('button', { name: '重建本机索引', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '重建失败' }).waitFor();
  rebuildFailure = false;
  await page.getByRole('alert').filter({ hasText: '重建失败' }).getByRole('button', { name: /重试/ }).click();
  await page.getByRole('alert').filter({ hasText: '重建失败' }).waitFor({ state: 'hidden' });
  await results.getByText('搜索维护', { exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await searchInput.fill('线索');
  await page.waitForFunction(() => document.querySelectorAll('.message-search-results li').length === 25);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.getByRole('button', { name: '更早结果' }).click();
  await results.getByText('第 3500 夜 · 线索：码头留下的旧证词', { exact: true }).waitFor();
  assert.equal(await page.locator('.chat-inputbar').count(), 0, 'mobile search hides chat composer');
  if (output) await page.screenshot({ path: path.join(output, 'search-mobile.png') });
  await results.getByRole('button', { name: /第 3500 夜/ }).click();
  await page.locator('[data-chat-message-id="3500"].chat-message-focus').waitFor();
  if (output) await page.screenshot({ path: path.join(output, 'search-reading-mobile.png') });
  const mobileNodes = await page.locator('[data-chat-message-id]').count();
  assert.ok(mobileNodes < 40);
  assert.ok(Math.max(...pageSizes) <= 41);
  assert.ok(searchScopes.every((scope) => scope === 'main'));
  assert.equal(errors.length, 0, errors.join('\n'));
  console.log(`PASS: 6000-message mock, bounded pages <=41, rendered nodes desktop=${initialNodes}/mobile=${mobileNodes}; search indexing pause/resume, 3 result pages, literal old-hit location/retry, return latest, errors, stale query, rebuild/retry, desktop/mobile. Mock APIs only.`);
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
