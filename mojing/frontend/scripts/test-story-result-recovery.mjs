// Isolated story result recovery regression: mocked API only, no provider or user service calls.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15183);
const apiPort = 18005;
const key = 'mojing:story-simulation-draft:v1';
const draftId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const savedId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const unsavedId = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const unsavedKey = 'mojing:unsaved-generated-story:v1';
const text = `开篇正文起点\n${'潮声穿过雾港，灯火在雨幕里摇晃。\n'.repeat(1000)}正文终点`;
assert.equal(text.length > 16000, true);
const draftState = { status: 'draft', request_id: draftId, title: '雾港回声', text, chapter_count: 2 };
const savedState = { status: 'saved', request_id: savedId, session_id: 92, title: '已保存的雾港', chapter_count: 2 };
const getAttempts = new Map();
const postAttempts = new Map();
const posts = [];
const recoveryPosts = [];
const copied = [];
const deleted = new Set();
let failDraftReads = true;
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
  await context.exposeFunction('recordCopiedText', (value) => copied.push(value));
  await context.addInitScript(({ storageKey, seed }) => {
    if (seed && window.localStorage.getItem(storageKey) === null) window.localStorage.setItem(storageKey, JSON.stringify(seed));
  }, { storageKey: key, seed: draft });
  await context.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.hostname !== '127.0.0.1' || ![String(port), String(apiPort)].includes(url.port)) return route.abort();
    if (url.port === String(port)) return route.continue();
    const endpoint = url.pathname.replace('/api', '');
    if (route.request().method() === 'DELETE' && endpoint.endsWith('/draft')) {
      deleted.add(endpoint.split('/').at(-2));
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{"deleted":true}' });
    }
    if (route.request().method() === 'GET' && endpoint.startsWith('/story-simulations/requests/')) {
      const id = endpoint.split('/').at(-1);
      const attempt = (getAttempts.get(id) || 0) + 1;
      getAttempts.set(id, attempt);
      if (id === draftId && failDraftReads) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: 'fixture read failure' }) });
      const state = deleted.has(id) || id === unsavedId ? { status: 'missing', request_id: id } : id === savedId ? savedState : draftState;
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state) });
    }
    if (route.request().method() === 'POST' && endpoint.endsWith('/save-generated')) {
      const recovery = JSON.parse(route.request().postData() || '{}');
      recoveryPosts.push(recovery);
      assert.equal(recovery.version, 1);
      assert.equal(recovery.payload.request_id, unsavedId);
      assert.equal(recovery.text, text);
      assert.equal(recovery.draft_json.chapters.length, 2);
      if (recoveryPosts.length === 1) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: 'fixture database still unavailable' }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ session_id: 93, title: '雾港回声', chapter_count: 2, status: 'created' }) });
    }
    if (route.request().method() === 'GET' && endpoint === '/characters') return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    if (route.request().method() === 'GET' && endpoint === '/worlds/templates') return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    if (route.request().method() === 'GET' && endpoint === '/encyclopedia') return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    if (route.request().method() === 'POST' && endpoint === '/story-simulations') {
      const payload = JSON.parse(route.request().postData() || '{}');
      posts.push(payload);
      assert.equal([draftId, savedId, unsavedId].includes(payload.request_id), true, 'recovery must submit its original request id');
      if (payload.request_id === unsavedId) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
        detail: '正文已生成，但数据库暂时无法保存',
        generated_story: {
          version: 1, request_id: unsavedId, text, payload,
          draft_json: { title: '雾港回声', chapters: [
            { number: 1, title: '第一章', content: text.slice(0, 8000) },
            { number: 2, title: '第二章', content: text.slice(8000) },
          ], next_choices: ['进入旧塔', '离开码头'] }, context_text: '生成时的世界背景',
        },
      }) });
      const attempt = (postAttempts.get(payload.request_id) || 0) + 1;
      postAttempts.set(payload.request_id, attempt);
      if (payload.request_id === draftId && attempt === 1) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: 'fixture save failure' }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ session_id: 92, title: '雾港回声', chapter_count: 2, status: 'created' }) });
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
  await page.getByRole('heading', { name: '小说创作', exact: true }).waitFor();
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: (value) => window.recordCopiedText(value) } });
  });
  return page;
}

try {
  await waitForVite();
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });

  {
    const context = await newContext({ width: 320, draft: { premise: '浏览器保留正文', direction: '', tone: '平静', chapter_count: 2, template_id: '', encyclopedia_id: '', character_ids: [], request_id: unsavedId } });
    const page = await openStory(context);
    await page.getByRole('button', { name: '继续本次创作', exact: true }).click();
    await page.getByRole('heading', { name: '正文已生成，但尚未写入会话' }).waitFor();
    assert.equal(await page.evaluate((storageKey) => JSON.parse(localStorage.getItem(storageKey) || '{}').text.length, unsavedKey), text.length);
    await page.getByRole('button', { name: '复制全文', exact: true }).click();
    await page.getByText('已复制全文', { exact: true }).waitFor();
    assert.equal(copied.at(-1), text);
    await page.reload();
    await page.getByRole('button', { name: '继续保存这篇正文' }).waitFor();
    assert.equal(posts.filter((post) => post.request_id === unsavedId).length, 1, 'refresh must not regenerate');
    await page.getByRole('button', { name: '继续保存这篇正文' }).click();
    await page.getByRole('button', { name: '继续保存这篇正文' }).waitFor();
    assert.equal(await page.locator('.world-result-text').textContent(), text);
    await page.getByRole('button', { name: '继续保存这篇正文' }).click();
    await page.waitForURL('**/chat/93');
    assert.equal(recoveryPosts.length, 2);
    assert.equal(posts.filter((post) => post.request_id === unsavedId).length, 1);
    assert.equal(await page.evaluate((storageKey) => localStorage.getItem(storageKey), unsavedKey), null);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext({ width: 320, draft: { premise: '恢复开篇', direction: '', tone: '平静', chapter_count: 2, template_id: '', encyclopedia_id: '', character_ids: [], request_id: draftId } });
    const page = await openStory(context);
    await page.getByText('本次创作结果读取失败', { exact: false }).waitFor();
    assert.equal(posts.filter((post) => post.request_id === draftId).length, 0, 'GET failure must not trigger a model or save POST');
    failDraftReads = false;
    await page.getByRole('button', { name: '重试', exact: true }).click();
    const collapsed = page.locator('.world-result-text');
    await collapsed.waitFor();
    assert.equal((await collapsed.textContent()).length, text.length);
    const collapsedHeight = (await collapsed.boundingBox()).height;
    await page.getByRole('button', { name: '展开阅读', exact: true }).click();
    const expanded = page.getByRole('region', { name: '小说正文全文' });
    const expandedHeight = (await expanded.boundingBox()).height;
    assert.equal(collapsedHeight < 500, true, 'collapsed reader must be bounded');
    assert.equal(expandedHeight <= 500, true, 'expanded reader must keep a bounded viewport');
    await page.getByRole('button', { name: '复制全文', exact: true }).click();
    await page.getByText('已复制全文', { exact: true }).waitFor();
    assert.equal(copied.at(-1), text, 'copy must use the complete 16000+ character text');
    await page.getByRole('button', { name: '继续保存并打开', exact: true }).click();
    await page.getByRole('alert').waitFor();
    assert.equal(posts.filter((post) => post.request_id === draftId).length, 1);
    await page.reload();
    await page.locator('.world-result-text').waitFor();
    assert.equal(posts.filter((post) => post.request_id === draftId).length, 1, 'refresh after save failure must not call the model');
    await page.getByRole('button', { name: '继续保存并打开', exact: true }).click();
    await page.waitForURL('**/chat/92');
    assert.equal(posts.filter((post) => post.request_id === draftId).length, 2);
    assert.equal(await page.evaluate((draftKey) => localStorage.getItem(draftKey), key), null, 'successful recovery must clear the draft');
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext({ draft: { premise: '已保存会话', direction: '', tone: '平静', chapter_count: 2, template_id: '', encyclopedia_id: '', character_ids: [], request_id: savedId } });
    const page = await openStory(context);
    await page.getByRole('button', { name: '打开已保存会话', exact: true }).waitFor();
    await page.getByRole('button', { name: '打开已保存会话', exact: true }).click();
    await page.waitForURL('**/chat/92');
    assert.equal(posts.at(-1).request_id, savedId, 'saved receipt must reopen by reusing its request id');
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }

  {
    const context = await newContext({ width: 320, draft: { premise: '保留输入，放弃正文', direction: '', tone: '', chapter_count: 2, template_id: '', encyclopedia_id: '', character_ids: [], request_id: draftId } });
    const page = await openStory(context);
    await page.getByRole('button', { name: '放弃正文', exact: true }).click();
    assert.equal(deleted.has(draftId), false);
    await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click();
    await page.getByRole('heading', { name: '从一个故事背景开始' }).waitFor();
    assert.equal(deleted.has(draftId), true);
    assert.equal(await page.getByLabel('故事背景与大致设定').inputValue(), '保留输入，放弃正文');
    await page.waitForFunction((key) => !JSON.parse(localStorage.getItem(key) || '{}').request_id, key);
    assert.equal(page.__errors.length, 0, page.__errors.join('\n'));
    await context.close();
  }
  console.log(`PASS: unsaved text preview/copy, refresh and model-free save retry; result GET retry, bounded 16000+ text reader, saved receipt reopen, confirmed discard. Mock POSTs: ${posts.length}.`);
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
