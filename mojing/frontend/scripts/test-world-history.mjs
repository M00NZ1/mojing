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
    await context.route('**/*', (route) => {
      const url = new URL(route.request().url());
      return url.hostname === '127.0.0.1' && url.port === String(port) ? route.continue() : route.abort();
    });
    let empty = true, failDetail = true, failSave = true;
    const errors = [], requests = [];
    const result = { job_id: 40, template: { template_id: 'fog', label: '雾港回声', category: '悬疑', summary: '灯塔来信之后，寻找失踪的航海家。', world_prompt: '完整世界正文：港口、灯塔与远海。', gameplay_mode: '自由剧情' }, saved_template: null,
      lore_entries: Array.from({ length: 41 }, (_, index) => ({ title: `地方志 ${index + 1}`, content: `完整条目 ${index + 1}` })),
      names: { person_names: ['林汐'], place_names: ['雾港'], item_names: [] }, quality_report: { score: 90, verdict: '设定完整', risks: [] } };
    await context.route('http://127.0.0.1:18001/api/**', async (route) => {
      const url = new URL(route.request().url());
      const endpoint = url.pathname.replace('/api', '');
      requests.push(endpoint + url.search);
      let data = [], status = 200;
      if (endpoint === '/jobs/world-history') {
        data = { items: empty ? [] : (url.search ? [{ id: 19, job_type: 'world_generate', status: 'failed', label: '远海之旅', error_message: '连接中断', created_at: '2026-09-06T00:00:00Z' }] : [
          { id: 40, job_type: 'world_generate', status: 'succeeded', label: '雾港回声', result_version: 1, created_at: '2026-09-07T00:00:00Z' },
          { id: 39, job_type: 'world_import', status: 'succeeded', label: '旧世界', result_version: null, created_at: '2026-09-06T00:00:00Z' },
        ]), next_cursor: !empty && !url.search ? 20 : null };
      } else if (endpoint === '/jobs/40/world-result') {
        if (failDetail) { status = 500; data = { detail: '暂时无法读取' }; } else data = result;
      } else if (endpoint === '/jobs/40/save-world') {
        if (failSave) { status = 500; data = { detail: '写入失败' }; }
        else { result.saved_template = { ...result.template, id: 3, is_builtin: false }; data = result; }
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
    await page.getByText('连接中断', { exact: true }).waitFor();
    assert.ok(requests.includes('/jobs/world-history?before_id=20'));
    await page.getByRole('button', { name: '较新记录' }).click();
    await page.getByRole('button', { name: '查看结果', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '结果读取失败' }).waitFor();
    failDetail = false;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    await page.getByText(result.template.world_prompt, { exact: true }).waitFor();
    assert.equal(await page.locator('.world-history-lore').count(), 21);
    await page.getByRole('button', { name: '下一页条目' }).click();
    await page.getByText('地方志 21', { exact: true }).click();
    await page.getByText('完整条目 21', { exact: true }).waitFor();
    await page.getByRole('button', { name: '保存到世界库', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: '结果仍在记录中' }).waitFor();
    failSave = false;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    await page.getByText('已保存到世界库', { exact: true }).waitFor();
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
