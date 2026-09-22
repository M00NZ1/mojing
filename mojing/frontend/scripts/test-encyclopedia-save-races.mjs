// Standalone UI regression with mocked local API and isolated browser data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15198);
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
  const rows = [];
  const libraries = [{ id: 1, name: '测试百科', description: '' }];
  const pending = [], requests = [];
  await context.route('http://127.0.0.1:18001/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
    let data = [];
    if (route.request().method() === 'POST' && ['/encyclopedia', '/encyclopedia/entries'].includes(endpoint)) {
      const payload = route.request().postDataJSON();
      requests.push({ endpoint, payload });
      const fail = await new Promise(resolve => pending.push(resolve));
      if (fail) return route.fulfill({ status: 500, json: { detail: '暂时无法保存' } });
      const target = endpoint.endsWith('/entries') ? rows : libraries;
      data = { ...payload, id: payload.id || (endpoint.endsWith('/entries') ? 99 : 2) };
      const index = target.findIndex(row => row.id === data.id);
      if (index < 0) target.push(data); else target[index] = data;
    } else if (endpoint === '/encyclopedia') data = libraries;
    else if (endpoint === '/encyclopedia/entries') data = rows;
    else if (/^\/encyclopedia\/entries\/\d+$/.test(endpoint)) data = { entry: rows.find(row => row.id === Number(endpoint.split('/').pop())), relations: [] };
    else if (endpoint === '/system/local-config') data = {};
    await route.fulfill({ json: data });
  });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const waitRequest = async count => { const until = Date.now() + 10000; while (pending.length < count) { if (Date.now() > until) throw new Error('missing request'); await new Promise(r => setTimeout(r, 20)); } };
  await page.goto(`http://127.0.0.1:${port}/encyclopedia?encId=1&category=concept`);
  await page.getByRole('button', { name: '新建条目', exact: true }).first().click();
  await page.setViewportSize({ width: 390, height: 760 });
  await page.locator('#encyclopedia-entry-section-extended').scrollIntoViewIfNeeded();
  const actionBounds = await page.getByRole('region', { name: '条目保存操作' }).boundingBox();
  assert.ok(actionBounds && actionBounds.y >= 0 && actionBounds.y + actionBounds.height <= 760);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  if (process.env.SMOKE_OUTPUT) await page.screenshot({ path: process.env.SMOKE_OUTPUT });
  await page.setViewportSize({ width: 1365, height: 900 });
  const title = page.locator('.form-group.full-row input').first();
  const save = page.getByRole('button', { name: '保存条目', exact: true });
  await title.fill('第一次提交');
  await save.click();
  await waitRequest(1);
  assert.equal(await page.getByRole('button', { name: 'AI 生成条目封面', exact: true }).isDisabled(), true);
  await title.fill('保存期间的新标题');
  pending[0](false);
  await save.waitFor();
  assert.equal(await title.inputValue(), '保存期间的新标题');
  await page.getByText('未保存', { exact: true }).waitFor();
  await save.click();
  await waitRequest(2);
  assert.equal(requests[1].payload.id, 99);
  assert.equal(requests[1].payload.title, '保存期间的新标题');
  pending[1](true);
  await save.waitFor();
  assert.equal(await title.inputValue(), '保存期间的新标题');
  await save.click();
  await waitRequest(3);
  pending[2](false);
  await page.waitForURL('**entryId=99');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].title, '保存期间的新标题');

  await page.locator('[data-encyclopedia-library-create-return]').click();
  const name = page.getByPlaceholder('例如：雾都纪事');
  await name.fill('初始百科名称');
  const librarySave = page.locator('.encyclopedia-library-form').getByRole('button', { name: /^(创建|保存)$/ });
  await librarySave.click();
  await waitRequest(4);
  await name.fill('稍后修改的百科名称');
  pending[3](false);
  await librarySave.waitFor();
  assert.equal(await name.inputValue(), '稍后修改的百科名称');
  await librarySave.click();
  await waitRequest(5);
  assert.equal(requests[4].payload.id, 2);
  assert.equal(requests[4].payload.name, '稍后修改的百科名称');
  pending[4](false);
  await page.waitForURL('**encId=2');
  assert.equal(libraries.length, 2);
  assert.deepEqual(errors, []);
  console.log('PASS: late entry/library edits survive save, subsequent saves reuse created IDs, failed retry retains edits and image generation is blocked during save.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
