// Standalone UI regression with mocked local API and isolated browser data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15197);
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
  const rows = [1, 2].map(id => ({ id, encyclopedia_id: 1, title: `条目${id}`, entry_type: 'concept', content: '正文', summary: '', tags: '', meta_json: {}, cover_image_path: '' }));
  const pending = [];
  const requests = [];
  await context.route('http://127.0.0.1:18001/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
    let data = [];
    if (endpoint === '/encyclopedia') data = [{ id: 1, name: '测试百科', description: '' }];
    else if (endpoint === '/encyclopedia/entries') data = rows;
    else if (/^\/encyclopedia\/entries\/\d+\/generate-cover-image$/.test(endpoint)) {
      const id = Number(endpoint.split('/')[3]);
      await new Promise(resolve => pending.push(resolve));
      data = { ...rows.find(row => row.id === id), cover_image_path: 'persisted-cover.png' };
    } else if (/^\/encyclopedia\/entries\/\d+$/.test(endpoint)) data = { entry: rows.find(row => row.id === Number(endpoint.split('/').pop())), relations: [] };
    else if (endpoint === '/encyclopedia/entries/preview-cover-image') {
      requests.push(route.request().postDataJSON());
      const fail = await new Promise(resolve => pending.push(resolve));
      if (fail) return route.fulfill({ status: 500, json: { detail: '封面生成失败' } });
      data = { urls: ['https://fixture.invalid/cover.png'], revised_prompt: '' };
    } else if (endpoint === '/encyclopedia/entries/persist-cover-from-url') data = { cover_image_path: 'fixture-cover.png' };
    else if (endpoint === '/system/local-config') data = {};
    await route.fulfill({ json: data });
  });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`http://127.0.0.1:${port}/encyclopedia?encId=1&category=concept`);
  const newEntry = page.getByRole('button', { name: '新建条目', exact: true }).first();
  const title = page.locator('.form-group.full-row input').first();
  const generate = page.getByRole('button', { name: 'AI 生成条目封面', exact: true });
  const waitRequest = async count => { const until = Date.now() + 10000; while (pending.length < count) { if (Date.now() > until) throw new Error('missing request'); await new Promise(r => setTimeout(r, 20)); } };
  await newEntry.click();
  await title.fill('旧草稿');
  await page.getByLabel('封面补充说明', { exact: true }).fill('夜色'.repeat(500));
  await generate.click();
  await waitRequest(1);
  assert.equal(requests[0].title, '旧草稿');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  const confirm = page.getByRole('dialog');
  await confirm.getByRole('button', { name: '确认', exact: true }).click();
  await newEntry.click();
  await title.fill('新草稿');
  pending[0](false);
  await generate.waitFor();
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some(b => b.textContent === 'AI 生成条目封面' && !b.disabled));
  assert.equal(await title.inputValue(), '新草稿');
  assert.equal(await page.locator('img[alt="封面"]').count(), 0);
  const hint = page.getByLabel('封面补充说明', { exact: true });
  await hint.fill('清晨街景'.repeat(300));
  await generate.click();
  await waitRequest(2);
  pending[1](true);
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some(b => b.textContent === 'AI 生成条目封面' && !b.disabled));
  assert.equal(await hint.inputValue(), '清晨街景'.repeat(300));
  await generate.click();
  await waitRequest(3);
  await title.fill('等待时补充的新标题');
  pending[2](false);
  await page.locator('img[alt="封面"]').waitFor();
  assert.equal(await title.inputValue(), '等待时补充的新标题');
  assert.equal(requests[2].title, '新草稿');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
  await page.locator('.secondary-sidebar-list').getByText('条目1', { exact: true }).click();
  await page.locator('[data-encyclopedia-entry-edit-return]').click();
  await generate.click();
  await waitRequest(4);
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.locator('.secondary-sidebar-list').getByText('条目2', { exact: true }).click();
  await page.locator('[data-encyclopedia-entry-edit-return]').click();
  pending[3](false);
  await page.waitForFunction(() => [...document.querySelectorAll('button')].some(b => b.textContent === 'AI 生成条目封面' && !b.disabled));
  assert.equal(await title.inputValue(), '条目2');
  assert.equal(await page.locator('img[alt="封面"]').count(), 0);
  assert.equal(await page.getByText('未保存', { exact: true }).count(), 0);
  await page.setViewportSize({ width: 320, height: 640 });
  await hint.fill('长封面描述。'.repeat(500));
  await hint.scrollIntoViewIfNeeded();
  const bounds = await hint.boundingBox();
  assert.ok(bounds && bounds.x >= 0 && bounds.x + bounds.width <= 320);
  assert.ok(await hint.evaluate(el => el.scrollHeight > el.clientHeight));
  await hint.locator('..').getByRole('button', { name: '展开编辑', exact: true }).click();
  assert.equal(await hint.getAttribute('rows'), '16');
  await hint.locator('..').getByRole('button', { name: '收起编辑', exact: true }).click();
  assert.equal(await hint.getAttribute('rows'), '6');
  assert.deepEqual(errors, []);
  console.log('PASS: cover generation binds to draft lifetime, retains failed hints, uses request snapshots and preserves concurrent title edits.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
