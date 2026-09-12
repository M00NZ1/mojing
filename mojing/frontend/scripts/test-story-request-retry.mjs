// Isolated story request-id regression: mocked API only, no provider or user service calls.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15182);
const apiPort = 18004;
const key = 'mojing:story-simulation-draft:v1';
const requestId = '11111111-1111-4111-8111-111111111111';
const result = { session_id: 91, title: '雾港来信', chapter_count: 1, status: 'created' };
const completed = new Map();
const requests = [];
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root, windowsHide: true,
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

async function newContext({ width = 390, draft = null } = {}) {
  const context = await browser.newContext({ viewport: { width, height: 900 } });
  await context.addInitScript(({ storageKey, seed }) => {
    if (seed && window.localStorage.getItem(storageKey) === null) window.localStorage.setItem(storageKey, JSON.stringify(seed));
  }, { storageKey: key, seed: draft });
  await context.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.hostname !== '127.0.0.1' || ![String(port), String(apiPort)].includes(url.port)) return route.abort();
    if (url.port === String(port)) return route.continue();
    const endpoint = url.pathname.replace('/api', '');
    if (endpoint.startsWith('/story-simulations/requests/')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'missing', request_id: endpoint.split('/').at(-1) }) });
    if (route.request().method() === 'GET' && endpoint === '/characters') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    if (route.request().method() === 'GET' && endpoint === '/worlds/templates') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    if (route.request().method() === 'GET' && endpoint === '/encyclopedia') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    if (route.request().method() === 'POST' && endpoint === '/story-simulations') {
      const payload = JSON.parse(route.request().postData() || '{}');
      requests.push(payload);
      assert.equal(typeof payload.request_id, 'string', 'story submission must carry request_id');
      if (completed.has(payload.request_id)) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(completed.get(payload.request_id)) });
      }
      completed.set(payload.request_id, result);
      return route.abort('failed');
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ detail: 'fixture route missing' }) });
  });
  return context;
}

async function openStory(context) {
  const page = await context.newPage();
  page.__errors = [];
  page.on('pageerror', (error) => page.__errors.push(error.message));
  await page.goto(`http://127.0.0.1:${port}/story-simulation`);
  await page.getByRole('heading', { name: '从一个故事背景开始' }).waitFor();
  return page;
}

async function submit(page) {
  await page.getByRole('button', { name: /生成小说并开始创作|继续本次创作/, exact: true }).click();
}

try {
  await waitForVite();
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });

  {
    const context = await newContext({ width: 320, draft: {
      premise: '雾港的钟在午夜倒走。', direction: '先查钟楼', tone: '克制悬疑', chapter_count: 1,
      template_id: 'harbor', encyclopedia_id: '12', character_ids: [88], request_id: requestId,
    } });
    const page = await openStory(context);
    await page.waitForTimeout(120);
    assert.equal(await page.getByLabel('故事背景与大致设定').inputValue(), '雾港的钟在午夜倒走。');
    assert.equal(await page.evaluate((draftKey) => JSON.parse(localStorage.getItem(draftKey)).template_id, key), 'harbor', 'empty reference list must not rewrite saved template id');
    await submit(page);
    await page.getByRole('alert').waitFor();
    assert.equal(requests.length, 1);
    assert.equal(requests[0].request_id, requestId);
    await page.reload();
    await page.getByRole('button', { name: '继续本次创作', exact: true }).waitFor();
    await submit(page);
    await page.waitForURL('**/chat/91');
    assert.equal(requests.length, 2, 'retry should reach the API once');
    assert.equal(requests[1].request_id, requestId, 'retry must reuse the request id');
    assert.deepEqual(requests[1].character_ids, [88], 'empty character list must not erase submitted snapshot');
    assert.equal(requests[1].template_id, 'harbor', 'empty template list must not erase submitted snapshot');
    assert.equal(await page.evaluate((draftKey) => localStorage.getItem(draftKey), key), null);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext({ draft: {
      premise: '旧设定', direction: '', tone: '平静', chapter_count: 1,
      template_id: '', encyclopedia_id: '', character_ids: [], request_id: requestId,
    } });
    const page = await openStory(context);
    await page.getByLabel('故事背景与大致设定').fill('新设定会生成新的请求');
    await page.getByText('草稿已保存', { exact: true }).waitFor();
    await submit(page);
    await page.getByRole('alert').waitFor();
    const latest = requests.at(-1);
    assert.notEqual(latest.request_id, requestId, 'editing input must clear old request id');
    assert.match(latest.request_id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    await context.close();
  }

  {
    const context = await newContext({ draft: { premise: '普通成功后清除', direction: '', tone: '平静', chapter_count: 1, template_id: '', encyclopedia_id: '', character_ids: [] } });
    const page = await openStory(context);
    await submit(page);
    await page.getByRole('alert').waitFor();
    const submittedId = requests.at(-1).request_id;
    await page.reload();
    await page.getByRole('button', { name: '继续本次创作', exact: true }).waitFor();
    await submit(page);
    await page.waitForURL('**/chat/91');
    assert.equal(requests.at(-1).request_id, submittedId, 'reload must retain the generated request id');
    assert.equal(await page.evaluate((draftKey) => localStorage.getItem(draftKey), key), null);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext({ draft: { premise: '存储暂时不可用', direction: '', tone: '平静', chapter_count: 1, template_id: '', encyclopedia_id: '', character_ids: [] } });
    const page = await openStory(context);
    await page.evaluate(() => {
      window.__originalStorageSetItem = Storage.prototype.setItem;
      Storage.prototype.setItem = function setItemUnavailable() { throw new Error('storage unavailable'); };
    });
    const before = requests.length;
    await submit(page);
    await page.getByText('草稿未能保存，请先重试保存或处理草稿冲突，再继续创作。', { exact: true }).waitFor();
    assert.equal(requests.length, before, 'storage failure must prevent the model request');
    await page.evaluate(() => {
      Storage.prototype.setItem = window.__originalStorageSetItem;
    });
    await page.getByRole('button', { name: '重试保存', exact: true }).click();
    await page.getByText('草稿已保存', { exact: true }).waitFor();
    await submit(page);
    await page.getByRole('alert').waitFor();
    assert.equal(requests.length, before + 1, 'restored storage must allow the saved draft to submit');
    await context.close();
  }

  {
    const context = await newContext({ draft: { premise: '跨页冲突原稿', direction: '', tone: '平静', chapter_count: 1, template_id: '', encyclopedia_id: '', character_ids: [] } });
    const page = await openStory(context);
    const competingDraft = { premise: '其他页面的新稿', direction: '', tone: '平静', chapter_count: 1, template_id: '', encyclopedia_id: '', character_ids: [], storage_revision: 'other-page' };
    await page.evaluate(({ draftKey, draft }) => {
      window.localStorage.setItem(draftKey, JSON.stringify(draft));
    }, { draftKey: key, draft: competingDraft });
    const before = requests.length;
    await submit(page);
    await page.getByText('草稿未能保存，请先重试保存或处理草稿冲突，再继续创作。', { exact: true }).waitFor();
    assert.equal(requests.length, before, 'draft conflict must prevent the model request');
    assert.deepEqual(await page.evaluate((draftKey) => JSON.parse(localStorage.getItem(draftKey)), key), competingDraft, 'conflict must preserve the other page draft');
    await context.close();
  }

  console.log(`PASS: request-id retry, empty reference preservation, edited-id reset, successful cleanup, storage failure, and cross-page conflict. Mock POSTs: ${requests.length}.`);
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
