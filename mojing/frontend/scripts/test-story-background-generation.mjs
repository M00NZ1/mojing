// Isolated cross-page story-generation regression: mocked API only, no provider or user service calls.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15191);
const apiPort = 18011;
const draftKey = 'mojing:story-simulation-draft:v1';
const result = {
  session_id: 177,
  title: '雾港长夜',
  chapter_count: 2,
  mode: 'story_writing',
  status: 'created',
};
const session = {
  id: result.session_id,
  title: result.title,
  summary: '潮声穿过雾港。',
  created_at: '2026-09-23T12:00:00Z',
  updated_at: '2026-09-23T12:00:00Z',
  message_count: 3,
  participant_count: 0,
  world: null,
};
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root,
  windowsHide: true,
  env: { ...process.env, VITE_API_BASE: `http://127.0.0.1:${apiPort}/api` },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
child.stdout.on('data', (data) => { log += data; });
child.stderr.on('data', (data) => { log += data; });
let browser;

async function waitForVite() {
  const deadline = Date.now() + 15000;
  while (!log.includes('Local:')) {
    if (child.exitCode !== null || Date.now() > deadline) throw new Error(`Vite startup failed: ${log}`);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

async function waitFor(check, message) {
  const deadline = Date.now() + 5000;
  while (!check()) {
    if (Date.now() > deadline) throw new Error(message);
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}

async function newContext() {
  const context = await browser.newContext({ viewport: { width: 390, height: 900 } });
  context.__storyRequests = [];
  context.__failedStoryRequests = [];
  context.__pendingStoryRequests = [];
  context.on('requestfailed', (request) => {
    if (new URL(request.url()).pathname.endsWith('/api/story-simulations')) {
      context.__failedStoryRequests.push(request.failure()?.errorText || 'request failed');
    }
  });
  await context.route('**/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.hostname !== '127.0.0.1' || ![String(port), String(apiPort)].includes(url.port)) return route.abort();
    if (url.port === String(port)) return route.continue();
    const endpoint = url.pathname.replace('/api', '');

    if (request.method() === 'POST' && endpoint === '/story-simulations') {
      const payload = JSON.parse(request.postData() || '{}');
      context.__storyRequests.push(payload);
      assert.equal(typeof payload.request_id, 'string', 'story submission must carry a request id');
      await new Promise((resolve) => { context.__pendingStoryRequests.push(resolve); });
      try {
        return await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(result) });
      } catch {
        return undefined;
      }
    }
    if (request.method() === 'GET' && endpoint.startsWith('/story-simulations/requests/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'missing', request_id: endpoint.split('/').at(-1) }) });
    }
    if (request.method() === 'GET' && endpoint === `/sessions/${result.session_id}`) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(session) });
    }
    if (request.method() === 'GET' && endpoint === '/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([session]) });
    }
    if (request.method() === 'GET' && ['/characters', '/worlds/templates', '/encyclopedia'].includes(endpoint)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    if (request.method() === 'GET' && endpoint === '/personas/active') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ id: 1, name: '玩家', description: '', avatar_color: '#53c7a8', avatar_image_path: '' }) });
    }
    if (request.method() === 'GET' && endpoint === '/system/voice-service-config') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ mode: 'disabled', external_base_url: '', external_api_key: '', clone_endpoint: '', timeout_seconds: 30, enabled: false }) });
    }
    if (request.method() === 'GET' && endpoint === '/system/local-config') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ detail: `fixture route missing: ${request.method()} ${endpoint}` }) });
  });
  return context;
}

async function openStory(context, premise) {
  const page = await context.newPage();
  page.__errors = [];
  page.on('pageerror', (error) => page.__errors.push(error.message));
  await page.goto(`http://127.0.0.1:${port}/story-simulation`);
  await page.getByRole('heading', { name: '从一个故事背景开始' }).waitFor();
  await page.getByLabel('故事背景与大致设定').fill(premise);
  await page.getByText('草稿已保存', { exact: true }).waitFor();
  await page.getByRole('button', { name: '生成小说并开始创作', exact: true }).click();
  await waitFor(() => context.__storyRequests.length === 1, 'story request was not observed');
  return page;
}

function releaseNext(context) {
  const release = context.__pendingStoryRequests.shift();
  assert.ok(release, 'pending story request was not found');
  release();
}

try {
  await waitForVite();
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });

  {
    const context = await newContext();
    const page = await openStory(context, '雾港的灯塔在午夜熄灭。');
    const submittedRaw = await page.evaluate((key) => localStorage.getItem(key), draftKey);
    assert.ok(submittedRaw, 'submitted draft must remain until generation completes');
    if (process.env.SMOKE_OUTPUT) {
      const output = resolve(process.env.SMOKE_OUTPUT);
      await mkdir(output, { recursive: true });
      await page.screenshot({ path: resolve(output, 'story-running-editor-390.png') });
    }

    await page.locator('nav.sidebar').getByRole('link', { name: '设置', exact: true }).click();
    await page.waitForURL('**/settings');
    await page.getByRole('heading', { name: '设置', exact: true }).waitFor();
    const status = page.locator('.story-generation-status');
    await status.getByText('正在写作', { exact: false }).waitFor();
    await status.getByRole('link', { name: '返回创作', exact: true }).waitFor();
    if (process.env.SMOKE_OUTPUT) {
      const output = resolve(process.env.SMOKE_OUTPUT);
      await mkdir(output, { recursive: true });
      await page.screenshot({ path: resolve(output, 'story-running-settings-390.png') });
    }
    await page.locator('nav.sidebar').getByRole('link', { name: '对话', exact: true }).click();
    await page.waitForURL('**/chat');
    await status.getByText('正在写作', { exact: false }).waitFor();
    if (process.env.SMOKE_OUTPUT) {
      await page.screenshot({ path: resolve(process.env.SMOKE_OUTPUT, 'story-running-chat-390.png') });
    }
    await page.waitForTimeout(150);
    assert.equal(context.__storyRequests.length, 1, 'route navigation must not submit a duplicate story request');
    assert.equal(context.__failedStoryRequests.length, 0, 'route navigation must not abort the story request');

    releaseNext(context);
    await status.getByRole('button', { name: '打开会话', exact: true }).waitFor();
    assert.equal(await page.evaluate((key) => localStorage.getItem(key), draftKey), submittedRaw, 'completion must wait for explicit open before clearing the submitted revision');
    await status.getByRole('button', { name: '打开会话', exact: true }).click();
    await page.waitForURL(`**/chat/${result.session_id}`);
    await page.waitForFunction((key) => localStorage.getItem(key) === null, draftKey);
    assert.equal(context.__storyRequests.length, 1, 'opening the completed session must not resubmit generation');
    assert.equal(context.__failedStoryRequests.length, 0);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext();
    const page = await openStory(context, '停止后仍要保留这份故事输入。');
    const savedBeforeStop = JSON.parse(await page.evaluate((key) => localStorage.getItem(key), draftKey));
    await page.getByRole('button', { name: '停止生成', exact: true }).click();
    await page.locator('.story-generation-status.is-stopped').getByText('生成已停止', { exact: false }).waitFor();
    await waitFor(() => context.__failedStoryRequests.length === 1, 'explicit stop did not abort the story request');
    releaseNext(context);
    const savedAfterStop = JSON.parse(await page.evaluate((key) => localStorage.getItem(key), draftKey));
    assert.equal(savedAfterStop.premise, savedBeforeStop.premise);
    assert.equal(savedAfterStop.request_id, savedBeforeStop.request_id);
    assert.equal(context.__storyRequests.length, 1);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  console.log('PASS: story generation survives route navigation, exposes global progress/completion actions, opens one completed session with exact draft cleanup, and explicit stop preserves the submitted draft. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
