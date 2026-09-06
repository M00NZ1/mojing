// Isolated browser regression; no real backend, provider, or user data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15178);
const output = process.env.SMOKE_OUTPUT;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root, windowsHide: true, env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18001/api' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
child.stdout.on('data', (data) => { log += data; });
child.stderr.on('data', (data) => { log += data; });
let browser;
try {
  const deadline = Date.now() + 15000;
  while (!log.includes('Local:')) {
    if (child.exitCode !== null || Date.now() > deadline) throw new Error(`Vite startup failed: ${log}`);
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
    const pending = [], errors = [], failed = [];
    const result = { job_id: 1, template: { template_id: 'fog', label: '雾港', category: '奇幻', summary: '摘要', world_prompt: '完整结果' }, lore_entries: [], names: { person_names: [], place_names: [], item_names: [] }, quality_report: { score: 90, verdict: '完整', strengths: [], risks: [], issues: [] }, saved_template: null };
    await context.route('http://127.0.0.1:18001/api/**', async (route) => {
      const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
      let data = [];
      if (['/worlds/generate', '/worlds/import'].includes(endpoint)) {
        await new Promise((resolve) => pending.push(resolve));
        data = result;
      } else if (endpoint === '/jobs/world-history') data = { items: [], next_cursor: null };
      try { await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) }); } catch { /* explicitly aborted request */ }
    });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('requestfailed', (request) => failed.push(request.url()));
    await page.goto(`http://127.0.0.1:${port}/workbench`);
    const theme = page.getByPlaceholder('例如：一个被遗忘的上古文明苏醒，改变了整个世界的力量格局');
    await theme.fill('雾港灯塔中的失踪案');
    const start = () => page.getByRole('button', { name: '生成世界设定', exact: true }).filter({ visible: true }).click();
    await start();
    await page.getByRole('button', { name: '停止生成', exact: true }).waitFor();
    if (output) await page.screenshot({ path: path.join(output, `world-cancel-${width}.png`), fullPage: true });
    await page.getByRole('button', { name: '生成记录', exact: true }).click();
    await page.getByRole('dialog').waitFor();
    await page.getByRole('button', { name: '继续生成', exact: true }).click();
    assert.ok(!page.url().includes('history'));
    assert.ok(!failed.some((url) => url.endsWith('/worlds/generate')), failed.join('\n'));
    await page.getByRole('button', { name: '停止生成', exact: true }).click();
    await page.getByText('已停止本次请求。', { exact: false }).waitFor();
    assert.ok(failed.some((url) => url.endsWith('/worlds/generate')));
    pending.shift()();
    await page.reload();
    await theme.waitFor();
    assert.equal(await theme.inputValue(), '雾港灯塔中的失踪案');
    await start();
    await page.getByRole('button', { name: '停止生成', exact: true }).waitFor();
    await page.getByRole('button', { name: '生成记录', exact: true }).click();
    await page.getByRole('button', { name: '停止并离开', exact: true }).click();
    await page.getByRole('heading', { name: '还没有生成记录' }).waitFor();
    pending.shift()();
    await page.getByRole('button', { name: '生成世界', exact: true }).click();
    await start();
    await page.getByRole('button', { name: '停止生成', exact: true }).waitFor();
    await page.getByRole('button', { name: '生成记录', exact: true }).click();
    await page.getByRole('dialog').waitFor();
    pending.shift()();
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    await page.getByText('世界设定已生成', { exact: true }).waitFor();
    assert.ok(!page.url().includes('history'));
    await page.getByRole('button', { name: '从文本整理', exact: true }).click();
    const source = page.getByPlaceholder('粘贴你的世界设定文本，AI 会自动抽取关键信息...');
    const longDraft = '完整原始设定，不可截断。'.repeat(10000);
    await source.fill(longDraft);
    await page.getByRole('button', { name: '整理世界设定', exact: true }).filter({ visible: true }).click();
    await page.getByRole('button', { name: '停止生成', exact: true }).click();
    await page.getByText('已停止本次请求。', { exact: false }).waitFor();
    pending.shift()();
    await page.reload();
    await source.waitFor();
    assert.equal(await source.inputValue(), longDraft);
    const other = await context.newPage();
    await other.goto(`http://127.0.0.1:${port}/workbench`);
    await other.getByPlaceholder('例如：苍玄大陆').waitFor();
    await page.getByRole('button', { name: '生成世界', exact: true }).click();
    await page.getByPlaceholder('例如：苍玄大陆').fill('第一页面的草稿');
    await page.waitForFunction(() => new Promise((resolve) => {
      const open = indexedDB.open('mojing-creation-drafts', 1);
      open.onsuccess = () => {
        const read = open.result.transaction('drafts').objectStore('drafts').get('world');
        read.onsuccess = () => { open.result.close(); resolve(read.result?.values.generateLabel === '第一页面的草稿'); };
      };
    }));
    await other.getByPlaceholder('例如：苍玄大陆').fill('另一个页面未保存的输入');
    await other.getByRole('alert').filter({ hasText: '另一页面已更新草稿' }).waitFor();
    assert.equal(await other.getByPlaceholder('例如：苍玄大陆').inputValue(), '另一个页面未保存的输入');
    await page.evaluate(() => { IDBObjectStore.prototype.put = () => { throw new DOMException('quota', 'QuotaExceededError'); }; });
    await page.getByPlaceholder('例如：苍玄大陆').fill('空间不足时仍保留的输入');
    await page.getByRole('alert').filter({ hasText: '草稿保存失败' }).waitFor();
    assert.equal(await page.getByPlaceholder('例如：苍玄大陆').inputValue(), '空间不足时仍保留的输入');
    assert.equal(errors.length, 0, errors.join('\n'));
    const versionPage = await context.newPage();
    await versionPage.goto(`http://127.0.0.1:${port}/workbench`);
    await versionPage.getByPlaceholder('例如：苍玄大陆').waitFor();
    await versionPage.evaluate(() => new Promise((resolve) => {
      const open = indexedDB.open('mojing-creation-drafts', 1);
      open.onsuccess = () => {
        const transaction = open.result.transaction('drafts', 'readwrite');
        transaction.objectStore('drafts').put({ version: 2, values: { importSourceText: '未来格式原文' } }, 'world');
        transaction.oncomplete = () => { open.result.close(); resolve(); };
      };
    }));
    await versionPage.reload();
    await versionPage.getByRole('alert').filter({ hasText: '浏览器草稿暂不可用' }).waitFor();
    await versionPage.getByPlaceholder('例如：苍玄大陆').fill('未知格式下的新输入');
    const preserved = await versionPage.evaluate(() => new Promise((resolve) => {
      const open = indexedDB.open('mojing-creation-drafts', 1);
      open.onsuccess = () => {
        const read = open.result.transaction('drafts').objectStore('drafts').get('world');
        read.onsuccess = () => { open.result.close(); resolve(read.result); };
      };
    }));
    assert.equal(preserved.version, 2);
    assert.equal(preserved.values.importSourceText, '未来格式原文');
    await context.close();
  }
  const broken = await browser.newContext();
  await broken.addInitScript(() => { Object.defineProperty(window, 'indexedDB', { get() { throw new Error('storage unavailable'); } }); });
  await broken.route('**/*', (route) => {
    const url = new URL(route.request().url());
    if (url.port === '18001') return route.fulfill({ contentType: 'application/json', body: '[]' });
    return url.hostname === '127.0.0.1' && url.port === String(port) ? route.continue() : route.abort();
  });
  const page = await broken.newPage();
  await page.goto(`http://127.0.0.1:${port}/workbench`);
  await page.getByRole('alert').filter({ hasText: '浏览器草稿暂不可用' }).waitFor();
  await page.getByPlaceholder('例如：苍玄大陆').fill('仍可编辑');
  await broken.close();
  console.log('PASS: desktop/mobile stop, continue, stop-and-leave, late completion dismisses stale confirmation, full draft reload, concurrent draft conflict, quota/unavailable storage preserves editable input. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
