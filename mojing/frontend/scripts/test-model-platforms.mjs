// Isolated browser regression. No real backend, user database, or provider calls.
// PLAYWRIGHT_MODULE may point to a preinstalled Playwright package.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15175);
const output = process.env.SMOKE_OUTPUT;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root, windowsHide: true, env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18001/api' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
child.stdout.on('data', (data) => { log += data; });
child.stderr.on('data', (data) => { log += data; });
let browser;
try {
  await Promise.race([
    new Promise((resolve, reject) => {
      const timer = setInterval(() => {
        if (log.includes('Local:')) { clearInterval(timer); resolve(); }
        else if (child.exitCode !== null) { clearInterval(timer); reject(new Error(log)); }
      }, 100);
      timer.unref();
    }),
    new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error(`Vite startup timeout: ${log}`)), 15000); timer.unref(); }),
  ]);
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  const context = await browser.newContext({ viewport: { width: 1365, height: 900 } });
  await context.route('**/*', (route) => {
    const url = new URL(route.request().url());
    return url.hostname === '127.0.0.1' && url.port === String(port) ? route.continue() : route.abort();
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const mask = '••••••••(已保存)';
  let catalog = { version: 1, active_id: null, platforms: [] };
  let choice = { version: 1, selection: null };
  let discoveryMode = 'success';
  let selectionFailure = false;
  const requests = [];
  const providers = [
    ['deepseek_official', 'DeepSeek', 'https://api.deepseek.com'], ['openai', 'OpenAI', 'https://api.openai.com/v1'],
    ['siliconflow', '硅基流动', 'https://api.siliconflow.cn/v1'], ['anthropic', 'Anthropic', 'https://api.anthropic.com'], ['custom', '自定义', ''],
  ].map(([provider_id, label, base_url]) => ({ provider_id, label, base_url, models: [], notes: '' }));
  const world = { template_id: 'custom', world_prompt: '', narrator_enabled: false, narrator_name: '旁白', gameplay_mode: '自由剧情', suggested_choices_json: [], choice_generation_enabled: true, max_choice_count: 3, anti_cheat_enabled: false, anti_cheat_prompt: '' };
  const chat = { id: 1, title: '隔离测试会话', summary: '', created_at: '2026-09-07T00:00:00Z', updated_at: '2026-09-07T00:00:00Z', world };
  await context.route('http://127.0.0.1:18001/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const endpoint = url.pathname.replace('/api', '');
    const method = request.method();
    const payload = method === 'PUT' || method === 'POST' ? request.postDataJSON() : null;
    requests.push({ endpoint, method });
    let data = [];
    let status = 200;
    if (endpoint === '/system/model-platforms') data = catalog;
    else if (endpoint === '/system/model-platforms/discover') {
      if (discoveryMode === 'slow') await new Promise((resolve) => setTimeout(resolve, 500));
      if (discoveryMode === 'failure') { status = 502; data = { detail: '获取模型失败，可手动填写。' }; }
      else data = { models: ['chat', 'reason', 'image-model'] };
    } else if (endpoint.startsWith('/system/model-platforms/')) {
      const id = endpoint.split('/')[3];
      if (endpoint.endsWith('/default')) catalog.active_id = id;
      else {
        const saved = { ...payload, api_key: mask };
        catalog.platforms = [...catalog.platforms.filter((p) => p.id !== id), saved];
        catalog.active_id ||= id;
      }
      data = catalog;
    } else if (endpoint === '/sessions/1/model-choice') {
      if (method === 'PUT' && selectionFailure) { status = 500; data = { detail: '保存失败，请重试' }; }
      else { if (method === 'PUT') choice = { version: 1, ...payload }; data = choice; }
    } else if (endpoint === '/providers/catalog') data = providers;
    else if (endpoint === '/system/local-config') data = { public_text_api_key: '', public_text_base_url: '', public_text_model: '', public_image_api_key: '', public_voice_api_key: '', max_upload_mb: 20, max_auto_speakers: 2 };
    else if (endpoint === '/system/voice-service-config') data = { mode: 'builtin_only', external_api_key: '', external_base_url: '', enabled: false };
    else if (endpoint === '/personas/active') data = { id: 1, name: '玩家', description: '', avatar_color: '#53c7a8', avatar_image_path: '' };
    else if (endpoint === '/sessions') data = [chat];
    else if (endpoint === '/sessions/1') data = chat;
    else if (endpoint === '/sessions/1/world') data = world;
    else if (endpoint === '/sessions/1/messages') data = { items: [], next_cursor: null };
    else if (endpoint === '/sessions/1/branches') data = [{ branch_id: 'main', label: '主线' }];
    else if (endpoint === '/sessions/1/participants') data = [{ id: 1, character: { id: 1, name: '沈照', avatar_path: '', persona_prompt: '守灯人', talkativeness: 0.5 }, character_id: 1, sort_order: 0 }];
    else if (endpoint === '/sessions/1/speaker-plan') data = { character_ids: [1], reason: '' };
    else if (endpoint === '/sessions/1/generate/stream') {
      return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"session","session_id":1}\n\ndata: {"type":"done"}\n\n' });
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
  });
  await page.goto(`http://127.0.0.1:${port}/settings?tab=api`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: '添加平台', exact: true }).click();
  await page.getByLabel('服务商预设').selectOption('deepseek_official');
  await page.getByLabel('API Key', { exact: false }).fill('fake-browser-key');
  await page.getByRole('button', { name: '获取平台全部模型' }).click();
  await page.getByLabel(/模型名称 ·/).filter({ hasNot: page.locator('select') }).waitFor();
  await page.getByRole('button', { name: '保存平台', exact: true }).click();
  await page.locator('.model-platform-row').filter({ hasText: 'DeepSeek' }).waitFor();
  assert.equal(catalog.platforms[0].models.length, 3);
  const firstId = catalog.platforms[0].id;
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByLabel('服务商预设').selectOption('openai');
  assert.equal(await page.getByLabel('API Key', { exact: false }).inputValue(), '');
  assert.equal(await page.getByLabel(/模型名称 ·/).inputValue(), '');
  await page.getByLabel('API Key', { exact: false }).fill('fake-second-key');
  await page.getByLabel(/模型名称 ·/).fill('manual-a，manual-b\nmanual-a');
  discoveryMode = 'failure';
  await page.getByRole('button', { name: '获取平台全部模型' }).click();
  await page.getByRole('alert').filter({ hasText: '获取模型失败' }).waitFor();
  assert.equal(await page.getByLabel(/模型名称 ·/).inputValue(), 'manual-a，manual-b\nmanual-a');
  discoveryMode = 'slow';
  await page.getByRole('button', { name: '获取平台全部模型' }).click();
  await page.getByRole('button', { name: '取消获取' }).click();
  await page.getByRole('button', { name: '获取平台全部模型' }).waitFor();
  await page.getByLabel(/^默认模型/).selectOption('manual-b');
  await page.getByRole('button', { name: '保存平台', exact: true }).click();
  await page.locator('.model-platform-row').filter({ hasText: 'OpenAI' }).waitFor();
  assert.equal(catalog.platforms.length, 2);
  assert.equal(catalog.platforms[0].id, firstId);
  assert.deepEqual(catalog.platforms[1].models, ['manual-a', 'manual-b']);
  await page.reload();
  await page.locator('.model-platform-row').filter({ hasText: 'OpenAI' }).waitFor();
  if (output) { await mkdir(output, { recursive: true }); await page.screenshot({ path: path.join(output, 'platforms-desktop.png') }); }
  await page.setViewportSize({ width: 390, height: 844 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.waitForFunction(() => {
    const tabs = document.querySelector('.settings-tabs').getBoundingClientRect();
    const active = document.querySelector('.settings-tab[aria-selected=true]').getBoundingClientRect();
    return active.left >= tabs.left - 1 && active.right <= tabs.right + 1;
  });
  if (output) await page.screenshot({ path: path.join(output, 'platforms-mobile.png') });
  await page.locator('.model-platform-row').first().getByRole('button', { name: '编辑' }).click();
  await page.getByLabel('平台名称', { exact: true }).fill('未保存改动');
  await page.getByRole('tab', { name: '外观', exact: true }).click();
  await page.getByText('设置修改尚未保存', { exact: true }).waitFor();
  await page.getByRole('button', { name: '取消', exact: true }).last().click();
  assert.equal(await page.getByLabel('平台名称', { exact: true }).inputValue(), '未保存改动');
  await page.getByRole('button', { name: '取消', exact: true }).first().click();
  await page.getByRole('button', { name: '放弃修改', exact: true }).click();
  // Thousands of configured models must not create thousands of DOM buttons.
  catalog.platforms[0].models = Array.from({ length: 5000 }, (_, i) => `large-model-${i}`);
  catalog.platforms[0].selected_model = 'large-model-0';
  console.log('PASS: platform editor and responsive layout');
  await page.goto(`http://127.0.0.1:${port}/chat/1`, { waitUntil: 'domcontentloaded' });
  await page.locator('.chat-model-trigger').click();
  await page.getByRole('dialog').waitFor();
  await page.getByPlaceholder('搜索平台或模型名称').fill('manual-b');
  await page.getByRole('button', { name: 'OpenAI manual-b', exact: true }).click();
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  assert.equal(choice.selection.model, 'manual-b');
  await page.reload();
  await page.locator('.chat-model-trigger').filter({ hasText: 'manual-b' }).waitFor();
  await page.locator('.chat-model-trigger').click();
  await page.locator('.chat-model-virtual-option').first().waitFor();
  assert.ok(await page.locator('.chat-model-virtual-option').count() < 30);
  await page.getByPlaceholder('搜索平台或模型名称').fill('large-model-4999');
  selectionFailure = true;
  await page.getByRole('button', { name: 'DeepSeek large-model-4999', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '保存失败' }).waitFor();
  assert.equal(choice.selection.model, 'manual-b');
  selectionFailure = false;
  await page.getByRole('button', { name: 'DeepSeek large-model-4999', exact: true }).click();
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  assert.equal(choice.selection.model, 'large-model-4999');
  await page.getByLabel('消息内容', { exact: true }).fill('测试下一次发送');
  await Promise.all([page.waitForResponse((r) => r.url().includes('/generate/stream')), page.getByRole('button', { name: '发送', exact: true }).click()]);
  assert.ok(requests.some((r) => r.endpoint === '/sessions/1/generate/stream'));
  await page.locator('.chat-model-trigger').click();
  const dialogBounds = await page.getByRole('dialog').boundingBox();
  assert.ok(dialogBounds.x >= 10 && dialogBounds.y >= 10);
  if (output) await page.screenshot({ path: path.join(output, 'model-picker-mobile.png') });
  await page.keyboard.press('Escape');
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  assert.equal(errors.length, 0, errors.join('\n'));
  console.log('PASS: platform save/reload, Key isolation, manual models, failed/cancelled discovery, unsaved navigation, mobile layout, 5000-model virtualization/search, selection retry/reload, chat send, Escape. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
