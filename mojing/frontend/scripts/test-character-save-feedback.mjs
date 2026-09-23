// Standalone character-save UI regression with mocked local API and isolated browser data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = Number(process.env.SMOKE_PORT || 15199);
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: fileURLToPath(new URL('../', import.meta.url)),
  windowsHide: true,
  env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18001/api' },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let log = '';
let browser;
try {
  const deadline = Date.now() + 15000;
  child.stdout.on('data', (data) => { log += data; });
  child.stderr.on('data', (data) => { log += data; });
  while (!log.includes('Local:')) {
    if (child.exitCode !== null || Date.now() > deadline) throw new Error(log || 'Vite did not start');
    await new Promise((resolve) => setTimeout(resolve, 100));
  }

  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  const context = await browser.newContext({ viewport: { width: 390, height: 760 } });
  await context.route('**/*', (route) => route.request().url().startsWith(`http://127.0.0.1:${port}`) ? route.continue() : route.abort());

  const character = (id, name) => ({
    id, name, persona_prompt: '已有角色设定', api_key: '', api_base_url: '', model_name: '',
    temperature: 0.9, max_tokens: 1200, avatar_color: '#537e86', avatar_image_path: '',
    voice_profile_id: null, voice_provider: '', voice_api_base_url: '', voice_api_key: '', voice_model: '',
    image_gen_enabled: false, image_gen_api_key: '', image_gen_base_url: '', image_gen_model: 'dall-e-3',
    think_max_enabled: false, think_max_model_name: '', card_image_path: '', favorite: false,
    created_at: '2026-09-20T00:00:00Z', updated_at: '2026-09-20T00:00:00Z',
  });
  let rows = [character(1, '已有角色')];
  const requests = [];
  let nextSaveFailure = true;

  await context.route('http://127.0.0.1:18001/api/**', async (route) => {
    const request = route.request();
    const endpoint = new URL(request.url()).pathname.replace('/api', '');
    const method = request.method();
    let data = [];
    if (endpoint === '/characters' && method === 'GET') {
      data = rows;
    } else if (endpoint === '/characters' && method === 'POST') {
      const payload = request.postDataJSON();
      requests.push({ method, endpoint, payload });
      if (nextSaveFailure) {
        nextSaveFailure = false;
        await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ detail: '保存服务暂时不可用' }) });
        return;
      }
      const saved = { ...character(2, payload.name), ...payload, id: 2 };
      rows = [...rows, saved];
      data = saved;
    } else if (/^\/characters\/\d+$/.test(endpoint) && method === 'PUT') {
      const id = Number(endpoint.split('/').pop());
      const payload = request.postDataJSON();
      requests.push({ method, endpoint, payload });
      if (nextSaveFailure) {
        nextSaveFailure = false;
        await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ detail: '保存服务暂时不可用' }) });
        return;
      }
      const saved = { ...rows.find((row) => row.id === id), ...payload, id };
      delete saved.clear_api_key;
      delete saved.clear_voice_api_key;
      delete saved.clear_image_gen_api_key;
      rows = rows.map((row) => row.id === id ? saved : row);
      data = saved;
    } else if (endpoint === '/system/local-config') {
      data = {};
    } else if (endpoint === '/voices' || endpoint === '/providers/catalog') {
      data = [];
    } else if (/^\/characters\/\d+\/profile$/.test(endpoint)) {
      data = null;
    } else if (/^\/assets\//.test(endpoint)) {
      data = [];
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) }).catch(() => {});
  });

  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));

  const saveError = () => page.getByRole('alert').filter({ hasText: '角色保存失败' });

  await page.goto(`http://127.0.0.1:${port}/characters?characterId=1`);
  const name = page.getByPlaceholder('给角色起个名字');
  await name.fill('编辑失败后的草稿');
  const editSave = page.locator('.character-save-actions').getByRole('button', { name: '保存修改', exact: true });
  await editSave.click();
  await saveError().waitFor();
  assert.match(await saveError().textContent(), /保存服务暂时不可用/);
  assert.equal(requests.filter((request) => request.endpoint === '/characters/1').length, 1, '编辑失败只能发起一次请求');

  await name.fill('编辑失败后重试的当前草稿');
  await saveError().getByRole('button', { name: '重试', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.secondary-detail-meta')?.textContent === '人设已填写');
  const editRequests = requests.filter((request) => request.endpoint === '/characters/1');
  assert.equal(editRequests.length, 2, '编辑重试应只追加一次请求');
  assert.equal(editRequests[1].payload.name, '编辑失败后重试的当前草稿', '编辑重试必须提交当前草稿');

  await page.goto(`http://127.0.0.1:${port}/characters?characterId=new`);
  await page.waitForURL('**characterId=new');
  const newName = page.getByPlaceholder('给角色起个名字');
  await newName.fill('新建失败后的草稿');
  nextSaveFailure = true;
  await page.locator('.character-save-actions').getByRole('button', { name: '创建角色', exact: true }).click();
  await saveError().waitFor();
  assert.match(await saveError().textContent(), /保存服务暂时不可用/);
  assert.equal(requests.filter((request) => request.endpoint === '/characters' && request.method === 'POST').length, 1, '新建失败只能发起一次请求');

  await newName.fill('新建失败后重试的当前草稿');
  await saveError().getByRole('button', { name: '重试', exact: true }).click();
  await page.waitForURL('**characterId=2');
  const createRequests = requests.filter((request) => request.endpoint === '/characters' && request.method === 'POST');
  assert.equal(createRequests.length, 2, '新建重试应只追加一次请求');
  assert.equal(createRequests[1].payload.name, '新建失败后重试的当前草稿', '新建重试必须提交当前草稿');
  assert.equal(rows.filter((row) => row.id === 2).length, 1, '成功重试不得重复创建角色');

  const actionRegion = page.locator('.character-save-actions');
  await actionRegion.scrollIntoViewIfNeeded();
  const bounds = await actionRegion.boundingBox();
  assert.ok(bounds && bounds.y >= 0 && bounds.y + bounds.height <= 760, '390px 下保存操作区应在可视范围内');
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, '390px 下页面不应横向溢出');
  for (const viewport of [{ width: 390, height: 760 }, { width: 1440, height: 900 }]) {
    await page.setViewportSize(viewport);
    const body = page.locator('.character-editor .secondary-detail-body');
    await body.evaluate((element) => { element.scrollTop = 0; });
    const before = await actionRegion.boundingBox();
    await body.evaluate((element) => { element.scrollTop = element.scrollHeight; });
    const after = await actionRegion.boundingBox();
    assert.ok(before && after && Math.abs(before.y - after.y) < 1, '滚动正文时保存栏应保持原位');
    assert.ok(after.y >= 0 && after.y + after.height <= viewport.height, '保存栏应完整可见');
    assert.equal(await body.evaluate((element) => element.scrollHeight > element.clientHeight), true, '长表单应在正文区域内滚动');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, '编辑页不应横向溢出');
    if (process.env.UI_SCREENSHOT_DIR) {
      await page.locator('.toast-close').evaluateAll((buttons) => buttons.forEach((button) => button.click()));
      await mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
      await body.evaluate((element) => { element.scrollTop = 0; });
      await page.screenshot({ path: join(process.env.UI_SCREENSHOT_DIR, `character-editor-${viewport.width}.png`) });
    }
  }
  assert.deepEqual(pageErrors, []);
  console.log('PASS: character edit/create save failures show inline feedback, retry the current draft, avoid duplicate creation, and keep 390px save actions reachable.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
