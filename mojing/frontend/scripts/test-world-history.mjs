// Isolated UI regression: all APIs are mocked, no user data or provider requests.
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
const port = Number(process.env.SMOKE_PORT || 15177);
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
    await context.addInitScript(() => {
      window.copiedText = '';
      window.failClipboard = true;
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => {
        if (window.failClipboard) throw new Error('fixture clipboard failure');
        window.copiedText = text;
      } } });
      document.execCommand = () => false;
    });
    await context.route('**/*', (route) => {
      const url = new URL(route.request().url());
      return url.hostname === '127.0.0.1' && url.port === String(port) ? route.continue() : route.abort();
    });
    let empty = true, failDetail = true, failSave = true, failPause = true, pauseStatus = 'running', releasePause, releaseSave, releaseDetail, holdDetail = false;
    const longError = '连接中断，供应商暂时无法完成请求。\n'.repeat(150);
    const errors = [], requests = [];
    const result = { job_id: 40, template: { template_id: 'fog', label: '雾港回声', category: '悬疑', summary: '灯塔来信之后，寻找失踪的航海家。', world_prompt: '完整世界正文：港口、灯塔与远海。\n'.repeat(200), gameplay_mode: '自由剧情' }, saved_template: null,
      lore_entries: Array.from({ length: 41 }, (_, index) => ({ title: `地方志 ${index + 1}`, content: `完整条目 ${index + 1}` })),
      names: { person_names: ['林汐'], place_names: ['雾港'], item_names: [] }, quality_report: { score: 90, verdict: '设定完整', risks: [] } };
    await context.route('http://127.0.0.1:18001/api/**', async (route) => {
      const url = new URL(route.request().url());
      const endpoint = url.pathname.replace('/api', '');
      requests.push(endpoint + url.search);
      let data = [], status = 200;
      if (endpoint === '/jobs/world-history') {
        data = { items: empty ? [] : (url.search ? [{ id: 19, job_type: 'world_generate', status: 'failed', label: '远海之旅', error_message: longError, created_at: '2026-09-06T00:00:00Z' }] : [
          { id: 40, job_type: 'world_generate', status: 'succeeded', label: '雾港回声', result_version: 1, created_at: '2026-09-07T00:00:00Z' },
          { id: 38, job_type: 'world_generate', status: pauseStatus, request_version: 1, completed_steps: 3, stage_label: '整理人物', label: '正在整理的世界', created_at: '2026-09-06T00:00:00Z' },
          { id: 39, job_type: 'world_import', status: 'succeeded', label: '旧世界', result_version: null, created_at: '2026-09-06T00:00:00Z' },
        ]), next_cursor: !empty && !url.search ? 20 : null };
      } else if (endpoint === '/jobs/38/pause-world') {
        await new Promise(resolve => { releasePause = resolve; });
        if (failPause) { status = 500; data = { detail: '暂时无法暂停' }; }
        else { pauseStatus = 'paused'; data = { status: 'paused' }; }
      } else if (endpoint === '/jobs/40/world-result') {
        if (failDetail) { status = 500; data = { detail: '暂时无法读取' }; } else {
          data = structuredClone(result);
          if (holdDetail) await new Promise(resolve => { releaseDetail = resolve; });
        }
      } else if (endpoint === '/jobs/40/save-world') {
        if (failSave) { status = 500; data = { detail: '写入失败' }; }
        else { await new Promise(resolve => { releaseSave = resolve; }); result.saved_template = { ...result.template, id: 3, is_builtin: false }; data = result; }
      } else if (endpoint === '/worlds/templates') data = result.saved_template ? [result.saved_template] : [];
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${port}/workbench?tab=history`);
    await page.getByRole('heading', { name: '还没有生成记录' }).waitFor();
    const tabBounds = await page.getByRole('button', { name: '生成记录', exact: true }).boundingBox();
    assert.ok(tabBounds.x >= 0 && tabBounds.x + tabBounds.width <= width);
    empty = false;
    await page.getByRole('button', { name: '刷新记录', exact: true }).click();
    await page.getByRole('heading', { name: '雾港回声' }).waitFor();
    assert.equal(await page.getByText('已完成', { exact: true }).count(), 2);
    await page.getByText('旧记录未保留完整结果', { exact: false }).waitFor();
    assert.ok(!requests.some((request) => request.includes('world-result')));
    if (output) await page.screenshot({ path: path.join(output, `world-history-${width}.png`), fullPage: true });
    await page.getByRole('button', { name: '更早记录' }).click();
    const failure = page.locator('.world-history-failure');
    assert.equal(await failure.getAttribute('open'), null);
    await failure.locator('summary').click();
    const errorBox = failure.locator('.world-history-error');
    await errorBox.waitFor();
    assert.ok(await errorBox.evaluate(el => el.scrollHeight > el.clientHeight));
    assert.ok((await errorBox.boundingBox()).height <= 205);
    assert.ok(requests.includes('/jobs/world-history?before_id=20'));
    await page.getByRole('button', { name: '较新记录' }).click();
    const running = page.getByRole('article', { name: '正在整理的世界' });
    await running.getByRole('button', { name: '暂停', exact: true }).click();
    await running.getByRole('button', { name: '正在提交暂停…', exact: true }).waitFor();
    assert.equal(await running.getByRole('button').isDisabled(), true);
    for (let i = 0; !releasePause && i < 100; i++) await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(releasePause); releasePause(); releasePause = undefined;
    await running.getByRole('alert').waitFor();
    failPause = false;
    await running.getByRole('button', { name: '重试', exact: true }).click();
    for (let i = 0; !releasePause && i < 100; i++) await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(releasePause); releasePause(); releasePause = undefined;
    await running.getByText('已暂停', { exact: true }).waitFor();
    await running.getByRole('button', { name: '继续生成', exact: true }).waitFor();
    await page.getByRole('button', { name: '查看结果', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '结果读取失败' }).waitFor();
    failDetail = false; holdDetail = true;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    for (let i = 0; !releaseDetail && i < 100; i++) await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(releaseDetail);
    const leavingRequest = page.waitForEvent('requestfailed', { predicate: request => request.url().endsWith('/jobs/40/world-result') });
    await page.getByRole('button', { name: '返回记录', exact: true }).click();
    assert.ok((await leavingRequest).failure()?.errorText.includes('ABORTED'));
    releaseDetail(); releaseDetail = undefined; holdDetail = false;
    await page.getByRole('button', { name: '查看结果', exact: true }).click();
    await page.getByText(result.template.world_prompt, { exact: true }).waitFor();
    const reader = page.getByRole('region', { name: '世界设定正文', exact: true });
    assert.ok((await reader.locator('.world-result-text').boundingBox()).height < 200);
    await reader.getByRole('button', { name: '展开阅读', exact: true }).click();
    const fullText = reader.getByRole('region', { name: '世界设定正文全文', exact: true });
    assert.ok((await fullText.boundingBox()).height <= 480);
    assert.ok(await fullText.evaluate(el => el.scrollHeight > el.clientHeight));
    await fullText.focus();
    await page.keyboard.press('End');
    await page.waitForFunction(() => document.querySelector('.world-result-text.is-expanded')?.scrollTop > 0);
    await reader.getByRole('button', { name: '复制全文', exact: true }).click();
    await reader.getByRole('alert').waitFor();
    await page.evaluate(() => { window.failClipboard = false; });
    await reader.getByRole('button', { name: '复制全文', exact: true }).click();
    await reader.getByRole('status').waitFor();
    assert.equal(await page.evaluate(() => window.copiedText), result.template.world_prompt);
    await reader.getByRole('button', { name: '收起阅读', exact: true }).click();
    assert.equal(await reader.locator('.world-result-text').evaluate(el => el.scrollTop), 0);
    assert.ok((await reader.locator('.world-result-text').boundingBox()).height < 200);
    assert.equal(await page.locator('.world-history-lore').count(), 21);
    await page.getByRole('button', { name: '下一页条目' }).click();
    await page.getByText('地方志 21', { exact: true }).click();
    await page.getByText('完整条目 21', { exact: true }).waitFor();
    await page.getByRole('button', { name: '保存到世界库', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '结果仍在记录中' }).waitFor();
    // Leaving and remounting the history component must preserve the failed save.
    await page.getByRole('button', { name: '我的世界', exact: true }).click();
    await page.getByRole('button', { name: '生成记录', exact: true }).click();
    await page.getByRole('button', { name: '查看结果', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '结果仍在记录中' }).waitFor();
    failSave = false;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    await page.getByRole('button', { name: '正在保存…', exact: true }).waitFor();
    await page.getByRole('button', { name: '返回记录', exact: true }).click();
    await page.getByRole('button', { name: '查看结果', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '正在保存…', exact: true }).isDisabled(), true);
    holdDetail = true;
    await page.getByRole('button', { name: '返回记录', exact: true }).click();
    await page.getByRole('button', { name: '查看结果', exact: true }).click();
    for (let i = 0; (!releaseSave || !releaseDetail) && i < 100; i++) await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(releaseSave && releaseDetail);
    const staleRequest = page.waitForEvent('requestfailed', { predicate: request => request.url().endsWith('/jobs/40/world-result') });
    releaseSave();
    await page.getByText('已保存到世界库', { exact: true }).waitFor();
    releaseDetail(); holdDetail = false;
    assert.ok((await staleRequest).failure()?.errorText.includes('ABORTED'));
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await page.getByRole('button', { name: '管理此世界', exact: true }).waitFor();
    assert.equal(requests.filter(request => request === '/jobs/40/save-world').length, 2);
    await page.reload();
    await page.getByText('已保存到世界库', { exact: true }).waitFor();
    assert.ok(page.url().includes('job=40'));
    if (output) await page.screenshot({ path: path.join(output, `world-result-${width}.png`), fullPage: true });
    await page.getByRole('button', { name: '返回记录', exact: true }).click();
    await page.getByRole('button', { name: '查看结果', exact: true }).click();
    await page.getByRole('button', { name: '管理此世界', exact: true }).click();
    await page.getByLabel('世界名称', { exact: true }).waitFor();
    assert.equal(await page.getByLabel('世界名称', { exact: true }).inputValue(), '雾港回声');
    assert.equal(errors.length, 0, errors.join('\n'));
    await context.close();
  }
  console.log('PASS: desktop/mobile world history empty state, pagination, legacy records, result read retry, bounded Lore, save retry, deep-link reload and manage navigation. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
