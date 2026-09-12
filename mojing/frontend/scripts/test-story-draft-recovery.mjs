// Isolated story-draft UI regression: mocked API only, no provider or user service calls.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15179);
const apiPort = 18003;
const key = 'mojing:story-simulation-draft:v1';
const oldDraft = {
  premise: '旧草稿：雾港的钟在午夜倒走。', direction: '先查钟楼',
  tone: '克制悬疑', chapter_count: 2, template_id: '', encyclopedia_id: '', character_ids: [],
};
const result = { session_id: 77 };
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

async function newContext({ width = 390, initialDraft = null, initialRaw = null, failStorage = false } = {}) {
  const context = await browser.newContext({ viewport: { width, height: 900 } });
  await context.addInitScript(({ storageKey, seed, initialRaw, fail }) => {
    window.__draftStorageFail = fail;
    if (seed && window.localStorage.getItem(storageKey) === null) {
      const original = window.localStorage.setItem.bind(window.localStorage);
      original(storageKey, JSON.stringify(seed));
    }
    if (initialRaw !== null && window.localStorage.getItem(storageKey) === null) {
      window.localStorage.setItem(storageKey, initialRaw);
    }
    const originalSetItem = window.localStorage.setItem.bind(window.localStorage);
    Object.defineProperty(window.localStorage, 'setItem', {
      configurable: true,
      value: (key, value) => {
        if (key === storageKey && window.__draftStorageFail) throw new Error('fixture storage failure');
        return originalSetItem(key, value);
      },
    });
  }, { storageKey: key, seed: initialDraft, initialRaw, fail: failStorage });
  await context.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.hostname !== '127.0.0.1' || ![String(port), String(apiPort)].includes(url.port)) return route.abort();
    if (url.port === String(port)) return route.continue();
    const endpoint = url.pathname.replace('/api', '');
    if (endpoint.startsWith('/story-simulations/requests/')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'missing', request_id: endpoint.split('/').at(-1) }) });
    if (route.request().method() === 'GET' && endpoint === '/characters') return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    if (route.request().method() === 'GET' && endpoint === '/worlds/templates') return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    if (route.request().method() === 'GET' && endpoint === '/encyclopedia') return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    if (route.request().method() === 'POST' && endpoint === '/story-simulations') {
      await new Promise((resolve) => { context.__releaseStoryRequest = resolve; });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(result) });
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

async function fillPremise(page, value) {
  const field = page.getByLabel('故事背景与大致设定');
  await field.fill(value);
  await page.waitForTimeout(80);
}

async function release(context) {
  for (let i = 0; !context.__releaseStoryRequest && i < 150; i++) await new Promise((resolve) => setTimeout(resolve, 20));
  assert.ok(context.__releaseStoryRequest, 'story request was not observed');
  context.__releaseStoryRequest();
  context.__releaseStoryRequest = null;
}

try {
  await waitForVite();
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });

  {
    const context = await newContext({ initialDraft: oldDraft });
    const page = await openStory(context);
    assert.equal(await page.getByLabel('故事背景与大致设定').inputValue(), oldDraft.premise);
    await page.waitForFunction((draftKey) => typeof JSON.parse(localStorage.getItem(draftKey) || '{}').storage_revision === 'string', key);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext({ failStorage: true });
    const page = await openStory(context);
    await fillPremise(page, '存储暂时不可用的故事背景');
    await page.getByText('草稿尚未保存', { exact: true }).waitFor();
    await page.evaluate(() => { window.__draftStorageFail = false; });
    await page.getByRole('button', { name: '重试保存', exact: true }).click();
    await page.waitForFunction((draftKey) => JSON.parse(localStorage.getItem(draftKey) || '{}').premise === '存储暂时不可用的故事背景', key);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext({ initialDraft: oldDraft });
    const first = await openStory(context);
    await first.getByRole('button', { name: '生成小说并开始创作', exact: true }).scrollIntoViewIfNeeded();
    await first.getByRole('button', { name: '生成小说并开始创作', exact: true }).click();
    const second = await openStory(context);
    await fillPremise(second, '另一页面写入的新草稿');
    await release(context);
    await first.waitForURL('**/chat/77');
    assert.equal(await second.evaluate((draftKey) => JSON.parse(localStorage.getItem(draftKey)).premise, key), '另一页面写入的新草稿');
    assert.equal(first.__errors.length + second.__errors.length, 0, [...first.__errors, ...second.__errors].join('\n'));
    await context.close();
  }

  {
    const corrupt = '{"version":1,"premise":';
    const context = await newContext({ initialRaw: corrupt });
    const page = await openStory(context);
    await page.getByText('已保存的草稿暂时无法读取，本页输入仍然保留。', { exact: true }).waitFor();
    assert.equal(await page.evaluate((draftKey) => localStorage.getItem(draftKey), key), corrupt);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext({ initialDraft: oldDraft });
    const first = await openStory(context);
    const second = await openStory(context);
    await fillPremise(second, '第二页面的新输入');
    await second.getByText('草稿已保存', { exact: true }).waitFor();
    await fillPremise(first, '第一页面的当前输入');
    await first.getByText('其他页面已更新草稿，本页输入尚未覆盖已保存内容。', { exact: true }).waitFor();
    assert.equal(await first.getByLabel('故事背景与大致设定').inputValue(), '第一页面的当前输入');
    assert.equal(await first.evaluate((draftKey) => JSON.parse(localStorage.getItem(draftKey)).premise, key), '第二页面的新输入');
    assert.equal(first.__errors.length + second.__errors.length, 0);
    await context.close();
  }

  {
    const context = await newContext({ initialDraft: oldDraft });
    const page = await openStory(context);
    try {
      await page.getByText('草稿已保存', { exact: true }).waitFor();
      await fillPremise(page, '锁占用期间仍应完成会话');
      await page.getByRole('button', { name: '生成小说并开始创作', exact: true }).click();
      for (let i = 0; !context.__releaseStoryRequest && i < 150; i++) await new Promise((resolve) => setTimeout(resolve, 20));
      assert.ok(context.__releaseStoryRequest);
      await page.evaluate((draftKey) => {
        window.__lockRelease = null;
        window.__lockHeld = false;
        void navigator.locks.request(draftKey, (lock) => {
          window.__lockHeld = Boolean(lock);
          return new Promise((resolve) => { window.__lockRelease = resolve; });
        });
      }, key);
      await page.waitForFunction(() => window.__lockHeld === true);
      await release(context);
      await page.waitForURL('**/chat/77');
      assert.ok(await page.evaluate((draftKey) => localStorage.getItem(draftKey), key));
      assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    } finally {
      await page.evaluate(() => window.__lockRelease?.()).catch(() => {});
      await context.close();
    }
  }

  {
    const context = await newContext({ initialDraft: oldDraft, width: 320 });
    const page = await openStory(context);
    await fillPremise(page, '普通成功后应清理草稿');
    const submit = page.getByRole('button', { name: '生成小说并开始创作', exact: true });
    const bounds = await submit.boundingBox();
    assert.ok(bounds && bounds.x >= 0 && bounds.x + bounds.width <= 320, JSON.stringify(bounds));
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await submit.click();
    await release(context);
    await page.waitForURL('**/chat/77');
    assert.equal(await page.evaluate((draftKey) => localStorage.getItem(draftKey), key), null);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }
  console.log('PASS: 7 scenarios covering legacy restore, storage retry, cross-page cleanup/conflict, corrupt JSON, busy lock, success cleanup, narrow click reachability and page errors. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
