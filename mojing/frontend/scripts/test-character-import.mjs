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
const port = Number(process.env.SMOKE_PORT || 15180);
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
    const character = (id, name, favorite = false) => ({ id, name, favorite, persona_prompt: '已有设定', api_key: '', api_base_url: '', model_name: '', temperature: .9, max_tokens: 1200, avatar_color: '#537e86', created_at: `2026-09-0${id}T00:00:00Z`, updated_at: '2026-09-07T00:00:00Z' });
    let rows = [character(1, '收藏角色', true), character(2, '普通角色')], imports = 0, fail = true, gate = null, waiting = false;
    await context.route('http://127.0.0.1:18001/api/**', async (route) => {
      const req = route.request(), endpoint = new URL(req.url()).pathname.replace('/api', '');
      let status = 200, data = [];
      if (endpoint === '/characters' && req.method() === 'GET') data = rows;
      else if (endpoint === '/characters' && req.method() === 'POST') { data = { ...character(5, req.postDataJSON().name), ...req.postDataJSON(), id: 5 }; rows.push(data); }
      else if (endpoint.startsWith('/characters/import-')) {
        imports++;
        if (waiting) await new Promise((resolve) => { gate = resolve; });
        if (fail) { status = 500; data = { detail: '临时写入失败' }; }
        else { data = character(Math.max(...rows.map((row) => row.id)) + 1, `导入角色${Math.max(...rows.map((row) => row.id)) + 1}`); rows.push(data); }
      } else if (endpoint === '/system/local-config') data = {};
      else if (endpoint.startsWith('/characters/') && endpoint.endsWith('/profile')) data = null;
      else if (endpoint === '/assets') data = [];
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    });
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${port}/characters`);
    await page.getByLabel('按名字搜索角色').fill('收藏');
    await page.locator('.character-library-actions').getByRole('button', { name: '导入角色' }).click();
    let dialog = page.getByRole('dialog', { name: '导入角色', exact: true });
    await dialog.getByLabel('选择文件').setInputFiles({ name: 'card.json', mimeType: 'application/json', buffer: Buffer.from('{}') });
    await dialog.getByRole('button', { name: '导入为新角色' }).click();
    await dialog.getByRole('alert').waitFor();
    assert.equal(imports, 1);
    fail = false;
    waiting = true;
    await dialog.getByRole('button', { name: '重试', exact: true }).click();
    await dialog.getByRole('button', { name: '正在导入…' }).waitFor();
    assert.equal(await dialog.getByRole('button', { name: '关闭导入' }).isDisabled(), true);
    await page.keyboard.press('Escape');
    await dialog.waitFor();
    const deadline = Date.now() + 3000;
    while (!gate && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 20));
    assert.ok(gate); gate(); waiting = false;
    await dialog.getByText('「导入角色3」已加入角色库').waitFor();
    if (output) await page.screenshot({ path: path.join(output, `import-success-${width}.png`), fullPage: true });
    await dialog.getByRole('button', { name: '查看角色' }).click();
    await page.waitForURL('**characterId=3');
    assert.equal(await page.getByPlaceholder('给角色起个名字').inputValue(), '导入角色3');
    if (width === 390) await page.getByRole('button', { name: '返回角色列表' }).click();
    assert.equal(await page.getByLabel('按名字搜索角色').inputValue(), '');
    assert.deepEqual(await page.locator('[data-character-id]').evaluateAll((nodes) => nodes.map((node) => Number(node.dataset.characterId))), [1, 3, 2]);
    // Filtering never makes a successfully created role disappear on return.
    await page.getByLabel('按名字搜索角色').fill('找不到');
    await page.getByRole('button', { name: '新建角色', exact: true }).first().click();
    await page.getByPlaceholder('给角色起个名字').fill('手动新角色');
    await page.getByRole('button', { name: '创建角色', exact: true }).first().click();
    await page.waitForURL('**characterId=5');
    if (width === 390) await page.getByRole('button', { name: '返回角色列表' }).click();
    assert.equal(await page.getByLabel('按名字搜索角色').inputValue(), '');
    await page.locator('[data-character-id="5"]').waitFor();
    assert.deepEqual(await page.locator('[data-character-id]').evaluateAll((nodes) => nodes.map((node) => Number(node.dataset.characterId))), [1, 5, 3, 2]);
    await page.locator('[data-character-id="2"]').click();
    await page.getByPlaceholder('给角色起个名字').fill('尚未保存的名字');
    await page.getByRole('tab', { name: '资料工具' }).click();
    await page.getByRole('tabpanel').getByRole('button', { name: '导入角色', exact: true }).click();
    dialog = page.getByRole('dialog', { name: '导入角色', exact: true });
    await dialog.getByLabel('资料格式').selectOption('portable');
    await dialog.getByLabel('选择文件').setInputFiles({ name: 'portable.txt', mimeType: 'text/plain', buffer: Buffer.from('新角色') });
    if (output) await page.screenshot({ path: path.join(output, `import-form-${width}.png`), fullPage: true });
    const bounds = await dialog.boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width);
    await dialog.getByRole('button', { name: '导入为新角色' }).click();
    await dialog.getByRole('button', { name: '查看角色' }).click();
    const confirm = page.getByRole('dialog', { name: '角色修改尚未保存' });
    await confirm.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('tab', { name: '角色设定' }).click();
    assert.equal(await page.getByPlaceholder('给角色起个名字').inputValue(), '尚未保存的名字');
    assert.equal(imports, 3);
    assert.equal(errors.length, 0, errors.join('\n'));
    await context.close();
  }
  console.log('PASS: desktop/mobile import entry, failure retry, pending single request, success navigation, favorite/created ordering, new role search clearing, and unsaved editor protection. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
