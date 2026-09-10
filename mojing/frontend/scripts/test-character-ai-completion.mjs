// Standalone UI regression with mocked local API and isolated browser data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15193);
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
  const rows = [1, 2].map(id => ({ id, name: `角色${id}`, persona_prompt: `原人设${id}`, api_key: '', api_base_url: '', model_name: '', temperature: .9, max_tokens: 1200, avatar_color: '#537e86' }));
  const pending = [];
  await context.route('http://127.0.0.1:18001/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
    let data = [];
    if (endpoint === '/ai/complete') {
      data = await new Promise(resolve => pending.push(resolve));
    } else if (endpoint === '/characters') data = rows;
    else if (endpoint === '/system/local-config') data = {};
    else if (endpoint.endsWith('/profile')) data = null;
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) }).catch(() => {});
  });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`http://127.0.0.1:${port}/characters?characterId=1`);
  const name = page.getByPlaceholder('给角色起个名字');
  const persona = page.getByRole('textbox', { name: '角色人设' });
  const start = page.getByRole('button', { name: 'AI 补全', exact: true });
  const waitForRequest = async count => {
    const deadline = Date.now() + 3000;
    while (pending.length < count && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(pending.length, count);
  };
  const complete = (index, text) => pending[index]({ completed_fields: { persona_prompt: text } });
  await start.click();
  await waitForRequest(1);
  await name.fill('手动修改的名字');
  complete(0, 'AI 新人设');
  await page.waitForFunction(() => document.querySelector('textarea[aria-label="角色人设"]').value === 'AI 新人设');
  assert.equal(await name.inputValue(), '手动修改的名字');

  await start.click();
  await waitForRequest(2);
  await persona.fill('手动更新的人设');
  complete(1, '不应覆盖的 AI 结果');
  await page.getByText('当前人设已修改，补全结果未覆盖草稿').waitFor();
  assert.equal(await persona.inputValue(), '手动更新的人设');

  await start.click();
  await waitForRequest(3);
  await page.getByRole('button', { name: '停止补全', exact: true }).click();
  await start.click();
  await waitForRequest(4);
  complete(2, '已停止的旧结果');
  complete(3, '重试后的新结果');
  await page.waitForFunction(() => document.querySelector('textarea[aria-label="角色人设"]').value === '重试后的新结果');

  await start.click();
  await waitForRequest(5);
  await page.locator('.secondary-nav-name').filter({ hasText: '角色2' }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
  await page.waitForURL('**characterId=2');
  complete(4, '来自旧角色的结果');
  await start.waitFor();
  assert.equal(await name.inputValue(), '角色2');
  assert.equal(await persona.inputValue(), '原人设2');
  assert.deepEqual(errors, []);
  console.log('PASS: actual character page preserves concurrent edits, rejects persona conflicts, stops/retries completion and isolates character switches.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
