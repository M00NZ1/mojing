// Standalone UI regression with mocked local API and isolated browser data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15199);
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
  const pending = [], requests = [];
  await context.route('http://127.0.0.1:18001/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
    const method = route.request().method();
    let data = [];
    if (endpoint.startsWith('/worlds/templates') && ['POST', 'PUT', 'DELETE'].includes(method)) {
      const payload = method === 'DELETE' ? {} : route.request().postDataJSON();
      const id = method === 'POST' ? payload.template_id : endpoint.split('/').pop();
      requests.push({ method, id, payload });
      const fail = await new Promise(resolve => pending.push(resolve));
      if (fail) return route.fulfill({ status: 500, json: { detail: '保存失败' } });
      const index = rows.findIndex(row => row.template_id === id);
      if (method === 'DELETE') { rows.splice(index, 1); data = { ok: true }; }
      else {
        data = { ...(rows[index] || { id: 3, is_builtin: false }), ...payload, template_id: id };
        if (index < 0) rows.push(data); else rows[index] = data;
      }
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
  const label = page.getByLabel('世界名称', { exact: true });
  const prompt = page.getByLabel('世界背景设定', { exact: true });
  const save = page.getByRole('button', { name: '保存世界设定', exact: true });
  await row('世界1').getByRole('button', { name: '编辑', exact: true }).click();
  await save.click();
  await waitRequest(1);
  await prompt.fill('保存期间新补充的设定');
  pending[0](false);
  await save.waitFor();
  assert.equal(await prompt.inputValue(), '保存期间新补充的设定');
  await page.getByText('未保存', { exact: true }).waitFor();
  await save.click();
  await waitRequest(2);
  await row('世界2').getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
  pending[1](false);
  await save.waitFor();
  assert.equal(await label.inputValue(), '世界2');
  assert.equal(await prompt.inputValue(), '原世界设定');
  await page.getByRole('button', { name: '新建世界', exact: true }).click();
  await label.fill('新世界');
  await save.click();
  await waitRequest(3);
  const newId = requests[2].id;
  await prompt.fill('创建期间写入的长设定。'.repeat(100));
  pending[2](false);
  await save.waitFor();
  assert.ok((await prompt.inputValue()).startsWith('创建期间'));
  await save.click();
  await waitRequest(4);
  assert.equal(requests[3].method, 'PUT');
  assert.equal(requests[3].id, newId);
  pending[3](true);
  await save.waitFor();
  assert.ok((await prompt.inputValue()).startsWith('创建期间'));
  await save.click();
  await waitRequest(5);
  pending[4](false);
  await save.waitFor();
  assert.equal(await page.getByText('未保存', { exact: true }).count(), 0);
  assert.equal(rows.length, 3);
  await row('世界1').getByRole('button', { name: '删除', exact: true }).click();
  await waitRequest(6);
  pending[5](false);
  await row('世界1').waitFor({ state: 'hidden' });
  assert.equal(await label.inputValue(), '新世界');
  await page.setViewportSize({ width: 320, height: 640 });
  await prompt.scrollIntoViewIfNeeded();
  const bounds = await prompt.boundingBox();
  assert.ok(bounds && bounds.x >= 0 && bounds.x + bounds.width <= 320);
  assert.ok(await prompt.evaluate(el => el.scrollHeight > el.clientHeight));
  await prompt.locator('..').getByRole('button', { name: '展开编辑', exact: true }).click();
  assert.equal(await prompt.getAttribute('rows'), '16');
  assert.deepEqual(errors, []);
  console.log('PASS: world save retains new edits, isolates switched forms, updates created IDs, preserves failed drafts, scopes delete completion and supports narrow long-text editing.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
