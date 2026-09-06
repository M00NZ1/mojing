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
const port = Number(process.env.SMOKE_PORT || 15181);
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
    await context.route('**/*', (route) => route.request().url().startsWith(`http://127.0.0.1:${port}`) ? route.continue() : route.abort());
    const result = { job_id: 1, template: { template_id: 'fog', label: '雾港', category: '奇幻', summary: '摘要', world_prompt: '完成的设定' }, lore_entries: [], names: { person_names: [], place_names: [], item_names: [] }, quality_report: { score: 90, verdict: '完整', strengths: [], risks: [], issues: [] }, saved_template: null };
    const job = { id: 1, status: 'pending', request_version: 1, completed_steps: 0, stage_label: '', job_type: 'world_generate', label: '雾港', created_at: '2026-09-07T00:00:00Z', result_version: null };
    let created = 0, runs = 0, release = null, failPause = true;
    await context.route('http://127.0.0.1:18001/api/**', async (route) => {
      const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
      let data = [], status = 200;
      if (endpoint === '/jobs/world-request') { created++; status = 201; data = { id: 1, status: 'pending' }; }
      else if (endpoint === '/jobs/1/run-world') {
        runs++;
        if (runs === 1) {
          job.status = 'running'; job.completed_steps = 1; job.stage_label = '命名参考';
          await new Promise((resolve) => { release = resolve; });
          job.status = 'paused'; job.completed_steps = 2; job.stage_label = '世界骨架';
          data = { status: 'paused', result: null };
        } else if (runs === 2) { job.status = 'failed'; status = 502; data = { detail: '模型暂时不可用，已保存步骤保留' }; }
        else { job.status = 'succeeded'; job.result_version = 1; data = { status: 'succeeded', result }; }
      } else if (endpoint === '/jobs/1/pause-world') {
        if (failPause) { failPause = false; status = 500; data = { detail: '暂停请求暂时失败' }; }
        else { job.status = 'pause_requested'; data = job; }
      } else if (endpoint === '/jobs/1/world-progress') data = job;
      else if (endpoint === '/jobs/world-history') data = { items: [job], next_cursor: null };
      else if (endpoint === '/jobs/1/world-result') data = result;
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    });
    const page = await context.newPage(), errors = [];
    page.setDefaultTimeout(10000);
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${port}/workbench`);
    await page.getByPlaceholder('例如：一个被遗忘的上古文明苏醒，改变了整个世界的力量格局').fill('雾港来信');
    await page.getByRole('button', { name: '生成世界设定', exact: true }).filter({ visible: true }).click();
    await page.getByText('已保存 1 个步骤', { exact: false }).waitFor();
    await page.getByRole('button', { name: '暂停生成', exact: true }).click();
    await page.locator('.toast-message').filter({ hasText: '暂停请求暂时失败' }).waitFor();
    assert.equal(job.status, 'running');
    await page.getByRole('button', { name: '暂停生成', exact: true }).click();
    await page.getByText('正在暂停…', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '暂停生成', exact: true }).isDisabled(), true);
    if (output) await page.screenshot({ path: path.join(output, `world-pausing-${width}.png`), fullPage: true });
    release();
    await page.getByText('已暂停，完成的步骤已保存。', { exact: false }).waitFor();
    await page.getByRole('button', { name: '查看生成记录', exact: true }).click();
    await page.reload();
    await page.getByText('已保存 2 个步骤', { exact: false }).waitFor();
    if (output) await page.screenshot({ path: path.join(output, `world-paused-history-${width}.png`), fullPage: true });
    await page.getByRole('button', { name: '继续生成', exact: true }).click();
    await page.locator('.toast-message').filter({ hasText: '模型暂时不可用，已保存步骤保留' }).waitFor();
    await page.getByRole('button', { name: '继续生成', exact: true }).click();
    await page.getByRole('button', { name: '查看结果', exact: true }).click();
    await page.getByText('完成的设定', { exact: true }).waitFor();
    assert.equal(created, 1); assert.equal(runs, 3);
    assert.equal(errors.length, 0, errors.join('\n'));
    await context.close();
  }
  console.log('PASS: desktop/mobile pause request retry, checkpoint status, reload/resume, failed resume retry, same-record completion and result access. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
