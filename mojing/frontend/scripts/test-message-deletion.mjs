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
const port = Number(process.env.SMOKE_PORT || 15183);
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
  const world = { template_id: 'custom', world_prompt: '', narrator_enabled: false, narrator_name: '旁白', gameplay_mode: '自由剧情', suggested_choices_json: [], choice_generation_enabled: true, max_choice_count: 3, anti_cheat_enabled: false, anti_cheat_prompt: '' };
  const chat = { id: 1, title: '雾港 · 长篇历史', summary: '', created_at: '2026-09-07T00:00:00Z', world };
  let history = Array.from({ length: 6000 }, (_, i) => ({ id: i + 1, session_id: 1, branch_id: 'main', speaker_type: 'user', character_id: null, content: `第 ${i + 1} 夜。${i === 2 ? '旧信封的秘密。' : ''}${(i + 1) % 100 === 0 ? '线索：' : ''}${'雨声渐密，沈照与林汐对照航海日志，准备去灯塔寻找失踪的守夜人。'.repeat(8)}`, structured_content: {}, created_at: '2026-09-07T00:00:00Z' }));
  let editRequests = 0;
  let staleBranchRequests = 0;
  let releaseEdit;
  let previewFailure = false, deleteFailure = '', deletionRequests = 0;
  let memoryRemoved = false, memoryReads = 0;
  let contextFailure = false, windowFailure = false, contextRequests = 0;
  const blocked = new Set([3]);
  const windowRequests = [];
  await context.route('http://127.0.0.1:18001/api/**', async (route) => {
    const url = new URL(route.request().url());
    const endpoint = url.pathname.replace('/api', '');
    if (route.request().url().includes('edit_stale') || route.request().postData()?.includes('edit_stale')) staleBranchRequests++;
    let data = [], status = 200;
    const matched = endpoint.match(/^\/sessions\/1\/messages\/(\d+)(?:\/(deletion-impact))?$/);
    const contextChange = endpoint.match(/^\/sessions\/1\/messages\/(\d+)\/context$/);
    if (contextChange) {
      contextRequests++;
      const id = Number(contextChange[1]);
      const payload = route.request().postDataJSON();
      assert.equal(payload.branch_id, 'main');
      assert.equal(typeof payload.include_in_context, 'boolean');
      if (contextFailure) { status = 500; data = { detail: '保存暂不可用' }; }
      else {
        const item = history.find((row) => row.id === id);
        item.include_in_context = payload.include_in_context;
        memoryRemoved = true;
        data = { id, include_in_context: item.include_in_context, changed: true };
      }
    } else if (matched) {
      const id = Number(matched[1]);
      if (route.request().method() === 'PUT') {
        editRequests++;
        if (editRequests === 1 || editRequests === 3) await new Promise((resolve) => { releaseEdit = resolve; });
        if (editRequests === 3) { data = { ...history.at(-1), id: 7000, branch_id: 'edit_stale' }; }
        else { status = 500; data = { detail: '编辑保存暂不可用' }; }
      } else if (matched[2]) {
        if (previewFailure) { status = 500; data = { detail: '检查暂不可用' }; }
        else data = { can_delete: !blocked.has(id), memory_segments_removed: 2, memory_events_removed: 2, summary_reset: true, reason: blocked.has(id) ? '这条消息是故事线或编辑版本的来源，删除会使相关剧情无法读取。请保留原文，使用编辑创建新的故事线。' : '', reference_count: blocked.has(id) ? 1 : 0, branches: blocked.has(id) ? [{ branch_id: 'A', label: '灯塔的另一种结局', is_checkpoint: false }] : [] };
      } else if (route.request().method() === 'DELETE') {
        deletionRequests++;
        if (deleteFailure) {
          status = deleteFailure === 'conflict' ? 409 : 500;
          data = { detail: deleteFailure === 'conflict' ? '消息刚被新的故事线引用，请保留原文' : '删除暂不可用' };
          if (deleteFailure === 'conflict') blocked.add(id);
        } else { history = history.filter((item) => item.id !== id); memoryRemoved = true; data = { ok: true }; }
      }
    } else if (endpoint.endsWith('/messages/search-page')) {
      const query = url.searchParams.get('q');
      data = { items: history.filter((item) => item.content.includes(query)).slice(0, 25).map((item) => ({ ...item, snippet: `第 ${item.id} 夜 · 旧信封` })), next_cursor: null, index: { ready: true, indexed_count: 6000 } };
    } else if (endpoint.endsWith('/messages/window')) {
      const anchor = Number(url.searchParams.get('anchor_id'));
      windowRequests.push(anchor);
      const items = history.filter((item) => item.id >= anchor - 20 && item.id <= anchor + 20);
      data = { items, older_cursor: items[0].id > 1 ? items[0].id : null, newer_cursor: items.at(-1).id < 6000 ? items.at(-1).id : null };
      if (windowFailure) { status = 500; data = { detail: '消息窗口暂不可用' }; }
    } else if (endpoint === '/sessions/1/messages') data = { items: history.slice(-40), next_cursor: history.at(-40).id };
    else if (endpoint === '/sessions') data = [chat];
    else if (endpoint === '/sessions/1') data = chat;
    else if (endpoint === '/sessions/2') data = { ...chat, id: 2, title: '另一段故事' };
    else if (endpoint === '/sessions/2/messages') data = { items: [], next_cursor: null };
    else if (endpoint === '/sessions/2/world') data = world;
    else if (endpoint === '/sessions/1/world') data = world;
    else if (endpoint === '/sessions/1/branches') data = [{ branch_id: 'main', label: '主线' }];
    else if (endpoint === '/sessions/1/memory-segments') { memoryReads++; data = memoryRemoved ? [] : [{ id: 1, summary: '旧信封中的自动记忆', key_facts: [], start_message_id: 1, end_message_id: 12 }]; }
    else if (endpoint === '/sessions/1/memory-corrections') data = [{ id: 1, content: '玩家锁定：灯塔仍然亮着', branch_id: null, source_message_id: null }];
    else if (endpoint === '/system/model-platforms') data = { version: 1, active_id: null, platforms: [] };
    else if (endpoint === '/sessions/1/model-selection') data = { version: 1, selection: null };
    else if (endpoint === '/personas/active') data = { id: 1, name: '玩家', avatar_color: '#53c7a8' };
    else if (endpoint === '/system/local-config') data = { max_auto_speakers: 2 };
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
  });
  const dialog = page.getByRole('dialog', { name: '删除这条消息？' });
  let keyboardMenuChecked = false;
  async function openDelete(id) {
    const row = page.locator(`[data-chat-message-id="${id}"]`);
    await row.scrollIntoViewIfNeeded();
    await row.hover();
    await row.getByRole('button', { name: '更多消息操作', exact: true }).click();
    if (page.viewportSize().width > 768) {
      assert.equal(await row.locator('.chat-msg-tools').evaluate((el) => getComputedStyle(el).opacity), '1');
      if (!keyboardMenuChecked) {
        const menu = row.getByRole('menu');
        const options = menu.getByRole('menuitem');
        await page.waitForFunction(() => document.activeElement?.getAttribute('role') === 'menuitem');
        assert.equal(await options.first().evaluate(el => el === document.activeElement), true);
        await page.keyboard.press('End');
        assert.equal(await options.last().evaluate(el => el === document.activeElement), true);
        await page.keyboard.press('ArrowDown');
        assert.equal(await options.first().evaluate(el => el === document.activeElement), true);
        await page.keyboard.press('ArrowUp');
        assert.equal(await options.last().evaluate(el => el === document.activeElement), true);
        await page.keyboard.press('Home');
        await options.first().evaluate(el => el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', isComposing: true, bubbles: true })));
        assert.equal(await menu.count(), 1);
        await page.keyboard.press('Escape');
        assert.equal(await menu.count(), 0);
        const trigger = row.getByRole('button', { name: '更多消息操作', exact: true });
        assert.equal(await trigger.evaluate(el => el === document.activeElement), true);
        await page.keyboard.press('Enter');
        await page.waitForFunction(() => document.activeElement?.getAttribute('role') === 'menuitem');
        await page.keyboard.press('Tab');
        assert.equal(await menu.count(), 0);
        assert.equal(await page.evaluate(() => document.activeElement === document.body), false);
        await trigger.click();
        keyboardMenuChecked = true;
      }
    }
    if (output) {
      await mkdir(output, { recursive: true });
      await page.screenshot({ path: path.join(output, `message-actions-${page.viewportSize().width}.png`) });
    }
    if (page.viewportSize().width <= 768) {
      const actions = page.getByRole('dialog', { name: '消息操作', exact: true });
      const originalViewport = page.viewportSize();
      await page.setViewportSize({ width: 320, height: 480 });
      assert.equal(await actions.getByLabel('所选消息').getAttribute('data-selected-message-id'), String(id));
      const close = actions.getByRole('button', { name: '关闭', exact: true });
      const closeBeforeScroll = await close.boundingBox();
      assert.ok(await actions.locator('.msg-actions-body').evaluate(el => el.scrollHeight > el.clientHeight));
      await actions.locator('.msg-actions-body').evaluate(el => { el.scrollTop = el.scrollHeight; });
      const closeAfterScroll = await close.boundingBox();
      assert.equal(closeBeforeScroll.y, closeAfterScroll.y);
      assert.ok(closeAfterScroll.y >= 0 && closeAfterScroll.y + closeAfterScroll.height <= page.viewportSize().height);
      await close.evaluate(el => el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', isComposing: true, bubbles: true })));
      assert.equal(await actions.count(), 1);
      await actions.getByRole('button', { name: '删除', exact: true }).click();
      await page.setViewportSize(originalViewport);
    } else {
      await row.getByRole('menuitem', { name: '删除消息', exact: true }).click();
    }
    await dialog.waitFor();
  }
  await page.goto(`http://127.0.0.1:${port}/chat/1`, { waitUntil: 'domcontentloaded' });
  const quoteRow = page.locator('[data-chat-message-id="6000"]');
  await quoteRow.hover();
  await quoteRow.getByRole('button', { name: '更多消息操作', exact: true }).click();
  await quoteRow.getByRole('menuitem', { name: '引用回复', exact: true }).click();
  await page.getByRole('textbox', { name: '消息内容', exact: true }).fill('引用草稿正文');
  const savedQuoteText = await page.locator('.quote-reply-text').textContent();
  await page.reload();
  await page.locator('.quote-reply-text').waitFor();
  assert.equal(await page.locator('.quote-reply-text').textContent(), savedQuoteText);
  assert.equal(await page.getByRole('textbox', { name: '消息内容', exact: true }).inputValue(), '引用草稿正文');
  const persistedQuote = await page.evaluate(() => JSON.parse(localStorage.getItem('mojing:chat-quote:v1:1')));
  assert.equal(persistedQuote.id, 6000);
  assert.ok(persistedQuote.content.length <= 120, 'quote storage must not retain a full long message');
  await page.evaluate(() => { history.pushState({}, '', '/chat/2'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await page.waitForFunction(() => !document.querySelector('.quote-reply-text'));
  await page.evaluate(() => { history.pushState({}, '', '/chat/1'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await page.locator('.quote-reply-text').waitFor();
  assert.equal(await page.locator('.quote-reply-text').textContent(), savedQuoteText);
  await page.locator('.quote-reply-clear').click();
  await page.reload();
  await page.locator('[data-chat-message-id="6000"]').waitFor();
  assert.equal(await page.locator('.quote-reply-text').count(), 0);
  assert.equal(await page.getByRole('textbox', { name: '消息内容', exact: true }).inputValue(), '引用草稿正文');
  await page.getByRole('textbox', { name: '消息内容', exact: true }).fill('');
  await page.locator('[data-chat-message-id="6000"]').hover();
  await page.locator('[data-message-edit-trigger="6000"]').click();
  const editor = page.getByRole('dialog', { name: '编辑消息', exact: true });
  const editedDraft = '保留我的编辑草稿。'.repeat(100);
  await editor.getByRole('textbox').fill(editedDraft);
  await editor.getByRole('textbox').evaluate((element) => {
    element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', isComposing: true, bubbles: true, cancelable: true }));
  });
  assert.equal(await editor.locator('[data-edit-discard-confirm]').count(), 0, 'IME Escape must not open discard confirmation');
  await editor.getByRole('textbox').evaluate((element) => {
    element.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 229, bubbles: true, cancelable: true }));
  });
  assert.equal(await editor.locator('[data-edit-discard-confirm]').count(), 0, 'IME compatibility key must not discard');
  await page.keyboard.press('Escape');
  await editor.getByRole('button', { name: '继续编辑', exact: true }).click();
  assert.equal(await editor.getByRole('textbox').inputValue(), editedDraft);


  await editor.getByRole('button', { name: '创建编辑故事线', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('[aria-busy="true"].edit-message-dialog'));
  await page.keyboard.press('Escape');
  assert.ok(await editor.isVisible());
  assert.ok(await editor.getByRole('textbox').isDisabled());
  assert.equal(editRequests, 1);
  releaseEdit();
  await editor.getByRole('alert').filter({ hasText: '编辑保存暂不可用' }).waitFor();
  assert.equal(await editor.getByRole('textbox').inputValue(), editedDraft);
  await editor.getByRole('button', { name: '创建编辑故事线', exact: true }).click();
  await editor.getByRole('alert').filter({ hasText: '编辑保存暂不可用' }).waitFor();
  assert.equal(editRequests, 2);
  await page.setViewportSize({ width: 390, height: 640 });
  const retryButton = editor.getByRole('button', { name: '创建编辑故事线', exact: true });
  await retryButton.scrollIntoViewIfNeeded();
  const retryBounds = await retryButton.boundingBox();
  assert.ok(retryBounds && retryBounds.x >= 0 && retryBounds.x + retryBounds.width <= 390 && retryBounds.y >= 0 && retryBounds.y + retryBounds.height <= 640);

  await editor.getByRole('button', { name: '取消', exact: true }).click();
  await editor.getByRole('button', { name: '放弃修改', exact: true }).click();
  await editor.waitFor({ state: 'hidden' });
  await page.setViewportSize({ width: 1365, height: 900 });
  await page.getByRole('button', { name: '打开会话详情', exact: true }).click();
  await page.getByRole('button', { name: '记忆', exact: true }).click();
  await page.getByText('旧信封中的自动记忆', { exact: true }).waitFor();
  await page.getByRole('searchbox', { name: '搜索当前故事线的消息' }).fill('旧信封');
  await page.locator('.message-search-results').getByRole('button', { name: /第 3 夜/ }).click();
  await page.locator('[data-chat-message-id="3"].chat-message-focus').waitFor();
  await openDelete(3);
  await dialog.getByText('需要保留这条消息', { exact: true }).waitFor();
  assert.ok(await dialog.getByRole('button', { name: '确认删除' }).isDisabled());
  await dialog.getByText('故事线 · 灯塔的另一种结局', { exact: true }).waitFor();
  if (output) { await mkdir(output, { recursive: true }); await page.screenshot({ path: path.join(output, 'delete-protected-desktop.png') }); }
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'hidden' });
  assert.equal(deletionRequests, 0);
  // Preview errors disable deletion; retry does not itself delete anything.
  previewFailure = true;
  await openDelete(4);
  await dialog.getByRole('alert').filter({ hasText: '暂时无法确认删除影响' }).waitFor();
  assert.ok(await dialog.getByRole('button', { name: '确认删除' }).isDisabled());
  previewFailure = false;
  await dialog.getByRole('button', { name: '重试', exact: true }).click();
  await dialog.getByText(/删除后无法撤销/).waitFor();
  await dialog.getByText('相关自动记忆也会更新', { exact: true }).waitFor();
  await dialog.getByText(/2 段自动摘要、2 项事件/).waitFor();
  deleteFailure = 'failure';
  await dialog.getByRole('button', { name: '确认删除' }).click();
  await dialog.getByRole('alert').filter({ hasText: '删除未完成' }).waitFor();
  assert.ok(history.some((item) => item.id === 4));
  deleteFailure = '';
  await dialog.getByRole('button', { name: '重试删除' }).click();
  await dialog.waitFor({ state: 'hidden' });
  await page.locator('[data-chat-message-id="3"].chat-message-focus').waitFor();
  assert.ok(!history.some((item) => item.id === 4));
  assert.equal(windowRequests.at(-1), 3, 'deletion should stay near old history');
  await page.getByText('旧信封中的自动记忆', { exact: true }).waitFor({ state: 'hidden' });
  await page.getByText('玩家锁定：灯塔仍然亮着', { exact: true }).waitFor();
  assert.ok(memoryReads >= 2, 'open memory panel must refresh after deletion');
  await page.locator('.chat-right-close').click();
  // A new reference can appear between preview and actual deletion.
  await openDelete(5);
  await dialog.getByText(/删除后无法撤销/).waitFor();
  deleteFailure = 'conflict';
  await dialog.getByRole('button', { name: '确认删除' }).click();
  await dialog.getByText('需要保留这条消息', { exact: true }).waitFor();
  assert.ok(await dialog.getByRole('button', { name: '重试删除' }).isDisabled());
  assert.ok(history.some((item) => item.id === 5));
  await dialog.getByRole('button', { name: '保留并返回' }).click();
  deleteFailure = '';
  for (const close of await page.locator('.toast-close').all()) await close.click();
  await page.setViewportSize({ width: 390, height: 844 });
  // Focus an old item via search again after viewport remeasurement.
  await page.getByRole('searchbox', { name: '搜索当前故事线的消息' }).fill('旧信封');
  await page.locator('.message-search-results').getByRole('button', { name: /第 3 夜/ }).click();
  await openDelete(3);
  await dialog.getByText('需要保留这条消息', { exact: true }).waitFor();
  const bounds = await dialog.boundingBox();
  assert.ok(bounds.x >= 10 && bounds.x + bounds.width <= 380);
  assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= 844);
  if (output) await page.screenshot({ path: path.join(output, 'delete-protected-mobile.png') });
  await dialog.getByRole('button', { name: '保留并返回' }).click();
  await openDelete(6);
  await dialog.getByText(/删除后无法撤销/).waitFor();
  await page.waitForFunction(() => {
    const button = [...document.querySelectorAll('dialog button')].find((item) => item.textContent === '确认删除');
    return button && !button.disabled && getComputedStyle(button).backgroundColor === 'rgb(180, 35, 24)' && getComputedStyle(button).color === 'rgb(255, 255, 255)';
  });
  await dialog.getByText('相关自动记忆也会更新', { exact: true }).waitFor();
  if (output) await page.screenshot({ path: path.join(output, 'delete-confirm-mobile.png') });
  const countBeforeCancel = deletionRequests;
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  assert.equal(deletionRequests, countBeforeCancel);
  assert.ok(history.some((item) => item.id === 6));
  // Mobile context controls preserve text and support retrying readback without resending the mutation.
  const contextDialog = page.getByRole('dialog', { name: '排除上下文？', exact: true });
  const contextRow = () => page.locator('[data-chat-message-id="6"]');
  await contextRow().scrollIntoViewIfNeeded();
  await contextRow().locator('[data-message-actions-trigger="6"]').click();
  await page.getByRole('dialog', { name: '消息操作', exact: true }).getByRole('button', { name: '排除上下文', exact: true }).click();
  await contextDialog.getByText(/共享这条原文的故事线/).waitFor();
  if (output) await page.screenshot({ path: path.join(output, 'context-confirm-mobile.png') });
  await page.keyboard.press('Escape');
  assert.equal(contextRequests, 0);
  await contextRow().locator('[data-message-actions-trigger="6"]').click();
  await page.getByRole('dialog', { name: '消息操作', exact: true }).getByRole('button', { name: '排除上下文', exact: true }).click();
  contextFailure = true;
  await contextDialog.getByRole('button', { name: '排除上下文', exact: true }).click();
  await contextDialog.getByRole('alert').filter({ hasText: '设置未完成' }).waitFor();
  assert.notEqual(history.find((item) => item.id === 6).include_in_context, false);
  contextFailure = false;
  windowFailure = true;
  await contextDialog.getByRole('button', { name: '排除上下文', exact: true }).click();
  await contextDialog.getByRole('alert').filter({ hasText: '设置已保存' }).waitFor();
  const requestsBeforeRefresh = contextRequests;
  windowFailure = false;
  await contextDialog.getByRole('button', { name: '重试刷新', exact: true }).click();
  await contextDialog.waitFor({ state: 'hidden' });
  assert.equal(contextRequests, requestsBeforeRefresh);
  await contextRow().getByText('已排除上下文 · 原文保留', { exact: true }).waitFor();
  assert.ok(history.find((item) => item.id === 6).content.startsWith('第 6 夜'));
  await page.reload();
  await page.getByRole('searchbox', { name: '搜索当前故事线的消息' }).fill('第 6 夜');
  await page.locator('.message-search-results').getByRole('button', { name: /第 6 夜/ }).click();
  await contextRow().getByText('已排除上下文 · 原文保留', { exact: true }).waitFor();
  await page.setViewportSize({ width: 1365, height: 900 });
  await page.getByRole('searchbox', { name: '搜索当前故事线的消息' }).fill('第 6 夜');
  await page.locator('.message-search-results').getByRole('button', { name: /第 6 夜/ }).click();
  await page.locator('[data-chat-message-id="6"].chat-message-focus').waitFor();
  await contextRow().hover();
  await contextRow().locator('[data-message-actions-trigger="6"]').click();
  await page.getByRole('menuitem', { name: '恢复到上下文', exact: true }).click();
  const restoreDialog = page.getByRole('dialog', { name: '恢复到上下文？', exact: true });
  await restoreDialog.getByText(/是否实际发送仍取决于当前故事线/).waitFor();
  if (output) await page.screenshot({ path: path.join(output, 'context-restore-desktop.png') });
  await restoreDialog.getByRole('button', { name: '恢复到上下文', exact: true }).click();
  await restoreDialog.waitFor({ state: 'hidden' });
  await contextRow().getByText('已排除上下文 · 原文保留', { exact: true }).waitFor({ state: 'hidden' });
  assert.equal(history.find((item) => item.id === 6).include_in_context, true);
  history.at(-1).speaker_type = 'narrator';
  history.at(-1).content = '守夜人留下一条新的线索。';
  history.at(-1).structured_content = { choices: ['前往旧灯塔'] };
  await page.reload();
  await page.getByRole('group', { name: '本回合可选行动' }).getByRole('button', { name: '前往旧灯塔' }).waitFor();
  const tailRow = page.locator('[data-chat-message-id="6000"]');
  await tailRow.hover();
  await tailRow.locator('[data-message-actions-trigger="6000"]').click();
  await page.getByRole('menuitem', { name: '排除上下文', exact: true }).click();
  await contextDialog.getByRole('button', { name: '排除上下文', exact: true }).click();
  await contextDialog.waitFor({ state: 'hidden' });
  await page.getByRole('group', { name: '本回合可选行动' }).waitFor({ state: 'hidden' });
  assert.ok(await tailRow.getByRole('button', { name: '前往旧灯塔' }).isDisabled());
  await tailRow.hover();
  await tailRow.locator('[data-message-edit-trigger="6000"]').click();
  await editor.getByRole('textbox').fill('旧页面提交');
  await editor.getByRole('button', { name: '创建编辑故事线', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('[aria-busy="true"].edit-message-dialog'));
  await page.evaluate(() => { history.pushState({}, '', '/'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await editor.waitFor({ state: 'hidden' });
  await page.evaluate(() => { history.pushState({}, '', '/chat/1'); window.dispatchEvent(new PopStateEvent('popstate')); });
  await page.locator('[data-chat-message-id="6000"]').hover();
  await page.locator('[data-message-edit-trigger="6000"]').click();
  await editor.getByRole('textbox').fill('新页面草稿');
  const oldResponse = page.waitForResponse((response) => response.request().method() === 'PUT' && response.url().includes('/messages/6000'));
  releaseEdit();
  await oldResponse;
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await editor.getByRole('textbox').inputValue(), '新页面草稿');
  assert.ok(await editor.getByRole('button', { name: '创建编辑故事线', exact: true }).isEnabled());
  await page.waitForLoadState('networkidle');
  assert.equal(editRequests, 3);
  assert.equal(staleBranchRequests, 0, 'old edit response must not activate or generate in its branch');
  assert.equal(errors.length, 0, errors.join('\n'));
  console.log('PASS: departed edit response cannot activate branch or close new draft; IME Escape protection and narrow edit actions; edit failure keeps draft and inline error, saving dismissal guard and retry; deletion protection and memory refresh; context exclude/restore, original retained, strict request, cancel, save/readback retry, reload/search, desktop/mobile. Mock APIs only.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
