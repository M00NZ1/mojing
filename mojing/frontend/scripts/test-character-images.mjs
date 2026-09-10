// Standalone UI regression with mocked local API and isolated browser data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15194);
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
  const rows = [1, 2].map(id => ({ id, name: `角色${id}`, persona_prompt: `原人设${id}`, avatar_image_path: '', card_image_path: '', temperature: .9, max_tokens: 1200, avatar_color: '#537e86' }));
  const pending = [];
  await context.route('http://127.0.0.1:18001/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
    let data = [];
    if (/\/characters\/\d+\/(avatar|generate-card-image)$/.test(endpoint)) {
      const id = Number(endpoint.split('/')[2]);
      const fail = await new Promise(resolve => pending.push(resolve));
      if (fail) return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ detail: '图片处理失败' }) });
      const row = rows.find(row => row.id === id);
      const field = endpoint.endsWith('/avatar') ? 'avatar_image_path' : 'card_image_path';
      row[field] = `/media/${field}-${id}.png`;
      data = row;
    } else if (endpoint === '/characters') data = rows;
    else if (endpoint === '/system/local-config') data = {};
    else if (endpoint.endsWith('/profile')) data = null;
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
  });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`http://127.0.0.1:${port}/characters?characterId=1`);
  const upload = page.getByLabel('上传角色头像');
  const name = page.getByPlaceholder('给角色起个名字');
  const file = { name: 'avatar.png', mimeType: 'image/png', buffer: Buffer.from('test image') };
  const waitForRequest = async count => {
    const deadline = Date.now() + 3000;
    while (pending.length < count && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20));
    assert.equal(pending.length, count);
  };
  await upload.setInputFiles(file);
  await waitForRequest(1);
  assert.equal(await upload.isDisabled(), true);
  await name.fill('等待上传时编辑');
  pending[0](true);
  await page.getByRole('alert').filter({ hasText: '头像上传失败' }).waitFor();
  assert.equal(await upload.isEnabled(), true);
  await upload.setInputFiles(file);
  await waitForRequest(2);
  pending[1](false);
  await page.waitForFunction(() => document.querySelector('.avatar-preview.large').style.backgroundImage.includes('avatar_image_path-1'));
  assert.equal(await name.inputValue(), '等待上传时编辑');

  await upload.setInputFiles(file);
  await waitForRequest(3);
  await page.locator('.secondary-nav-name').filter({ hasText: '角色2' }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
  await page.waitForURL('**characterId=2');
  pending[2](false);
  await page.waitForFunction(() => !document.querySelector('input[aria-label="上传角色头像"]').disabled);
  assert.equal(await page.locator('.avatar-preview.large').evaluate(el => el.style.backgroundImage), '');
  assert.equal(await name.inputValue(), '角色2');

  await page.getByRole('tab', { name: '图片', exact: true }).click();
  await page.getByRole('button', { name: '生成列表封面图' }).click();
  await waitForRequest(4);
  await page.locator('.secondary-nav-name').filter({ hasText: '角色1' }).click();
  await page.waitForURL('**characterId=1');
  pending[3](false);
  await page.getByRole('tab', { name: '图片', exact: true }).click();
  await page.getByRole('button', { name: '生成列表封面图' }).waitFor();
  assert.equal(await page.locator('.character-card-thumb-preview').count(), 0);
  assert.equal(await page.getByRole('heading', { name: '角色1', exact: true }).count(), 1);
  assert.deepEqual(errors, []);
  console.log('PASS: avatar pending state, failure retry, concurrent text edits and avatar/cover response isolation after character switches. Mock image APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
