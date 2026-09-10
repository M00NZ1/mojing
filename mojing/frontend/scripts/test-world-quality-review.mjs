// Standalone UI regression with mocked local API and isolated browser data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15200);
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
  const context = await browser.newContext({ viewport: { width: 1365, height: 900 } });
  await context.route('**/*', route => route.request().url().startsWith(`http://127.0.0.1:${port}`) ? route.continue() : route.abort());
  const rows = [1, 2].map(id => ({ id, template_id: `world-${id}`, label: `世界${id}`, category: '奇幻', summary: '摘要', gameplay_mode: '自由剧情', world_prompt: '原世界设定', cover_image_path: '', suggested_choices: [], anti_cheat_prompt: '', is_builtin: false }));
  const pending = [], requests = [], loreReads = [];
  const report = { score: 81, verdict: 'fixture-result', strengths: [], risks: [], issues: [] };
  await context.route('http://127.0.0.1:18001/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
    let data = [];
    if (endpoint === '/worlds/review-quality') {
      requests.push(route.request().postDataJSON());
      const fail = await new Promise(resolve => pending.push(resolve));
      if (fail) return route.fulfill({ status: 500, json: { detail: '完整度检查暂时失败' } });
      data = report;
    } else if (endpoint.endsWith('/lore')) {
      loreReads.push(endpoint);
      if (!rows.some(row => endpoint.includes(row.template_id))) return route.fulfill({ status: 404, json: { detail: 'world missing' } });
      data = [{ title: '地方志', entry_type: 'location', content: '港口设定', keywords_json: [], sort_order: 0, is_core: true }];
    } else if (endpoint === '/worlds/templates') data = rows;
    else if (endpoint === '/system/local-config') data = {};
    await route.fulfill({ json: data });
  });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const waitRequest = async count => { const until = Date.now() + 10000; while (pending.length < count) { if (Date.now() > until) throw new Error('missing request'); await new Promise(r => setTimeout(r, 20)); } };
  await page.goto(`http://127.0.0.1:${port}/workbench`);
  await page.getByRole('button', { name: '我的世界', exact: true }).click();
  const row = name => page.locator('.workbench-world-row').filter({ has: page.getByText(name, { exact: true }) });
  const prompt = page.getByLabel('世界背景设定', { exact: true });
  const review = page.getByRole('button', { name: /^(检查世界完整度|重新检查完整度)$/ });
  await page.getByRole('button', { name: '新建世界', exact: true }).click();
  await prompt.fill('未保存的新世界');
  await review.click();
  await waitRequest(1);
  assert.equal(loreReads.length, 0);
  assert.equal(requests[0].world_prompt, '未保存的新世界');
  assert.deepEqual(requests[0].lore_entries, []);
  await prompt.fill('等待时补充的新设定');
  pending[0](false);
  await page.getByText('设定已修改，请重新检查完整度。', { exact: true }).waitFor();
  assert.equal(await page.getByText('fixture-result', { exact: true }).count(), 0);
  await review.click();
  await waitRequest(2);
  pending[1](true);
  await page.getByRole('alert').filter({ hasText: '完整度检查暂时失败' }).waitFor();
  await review.click();
  await waitRequest(3);
  pending[2](false);
  await page.getByText('fixture-result', { exact: true }).waitFor();
  assert.equal(await page.getByRole('alert').count(), 0);
  await row('世界1').getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
  await review.click();
  await waitRequest(4);
  assert.equal(requests[3].template_id, 'world-1');
  assert.equal(requests[3].lore_entries[0].title, '地方志');
  await row('世界2').getByRole('button', { name: '编辑', exact: true }).click();
  pending[3](false);
  await review.waitFor();
  assert.equal(await page.getByText('fixture-result', { exact: true }).count(), 0);
  assert.equal(await page.getByLabel('世界名称', { exact: true }).inputValue(), '世界2');
  await page.setViewportSize({ width: 320, height: 640 });
  await review.click();
  await waitRequest(5);
  pending[4](false);
  await page.getByText('fixture-result', { exact: true }).waitFor();
  await prompt.fill('分析完成后继续修改');
  await page.getByText('设定已修改，请重新检查完整度。', { exact: true }).waitFor();
  assert.equal(await page.getByText('fixture-result', { exact: true }).count(), 0);
  assert.deepEqual(errors, []);
  console.log('PASS: unsaved worlds skip lore reads, requests use snapshots, changed content marks reports stale, errors retry, and late reports cannot cross worlds.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
