// Isolated production-component regression; all network APIs are mocked.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = 15201;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: fileURLToPath(new URL('../', import.meta.url)), windowsHide: true,
  env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18001/api' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '', browser;
child.stdout.on('data', d => { log += d; }); child.stderr.on('data', d => { log += d; });
try {
  const until = Date.now() + 15000;
  while (!log.includes('Local:')) {
    if (child.exitCode !== null || Date.now() > until) throw Error(log);
    await new Promise(r => setTimeout(r, 100));
  }
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/*', route => route.request().url().startsWith(`http://127.0.0.1:${port}`) ? route.continue() : route.abort());
  await page.route('http://127.0.0.1:18001/api/**', route => route.fulfill({ contentType: 'application/json', body: '[]' }));
  await page.goto(`http://127.0.0.1:${port}/characters`);
  await page.evaluate(async () => {
    const React = (await import('/node_modules/.vite/deps/react.js')).default;
    const { createRoot } = (await import('/node_modules/.vite/deps/react-dom_client.js')).default;
    const { default: MessageList } = await import('/src/components/MessageList.tsx');
    const { ToastProvider } = await import('/src/hooks/useToast.tsx');
    document.getElementById('root').style.display = 'none';
    const host = document.createElement('div'); host.style.cssText = 'height:100vh;display:flex;flex-direction:column'; document.body.append(host);
    window.actions = [];
    const action = name => () => window.actions.push(name);
    const noop = () => {};
    const messages = [{ id: 1, session_id: 1, speaker_type: 'character', character_name: '林汐', content: '灯塔的信号再次亮起。', created_at: '2026-09-11T00:00:00Z' }];
    function Harness() {
      const [busy, setBusy] = React.useState(true); window.setBusy = setBusy;
      return React.createElement(ToastProvider, null, React.createElement(MessageList, {
        messages, isGenerating: busy, loading: false, loadingMore: false, loadingNewer: false, hasMore: false, hasNewer: false,
        onLoadMore: noop, onLoadNewer: noop, onJumpToLatest: noop, onRetry: noop, onRetryLoadMore: noop, onRetryLoadNewer: noop,
        onEditMessage: action('edit'), onDeleteMessage: action('delete'), onSetMessageContext: action('context'),
        onRegenerateBranch: action('regenerate'), onCreateBranch: action('branch'), onQuoteMessage: action('quote'),
        onPlayVoice: action('voice'), onBookmarkMessage: action('bookmark'), showPromptDebug: false,
      }));
    }
    createRoot(host).render(React.createElement(Harness));
  });
  const row = page.locator('[data-chat-message-id="1"]');
  await row.hover();
  assert.equal(await row.getByRole('button', { name: '编辑消息', exact: true }).isDisabled(), true);
  assert.equal(await row.getByRole('button', { name: '重新生成', exact: true }).isDisabled(), true);
  await row.getByRole('button', { name: '更多消息操作', exact: true }).click();
  for (const label of ['排除上下文', '从此创建故事线', '删除消息']) {
    assert.equal(await page.getByRole('menuitem', { name: label, exact: true }).isDisabled(), true);
  }
  assert.equal(await page.getByRole('menuitem', { name: '收藏', exact: true }).isEnabled(), true);
  await page.keyboard.press('Escape');
  await page.setViewportSize({ width: 320, height: 700 });
  await row.getByRole('button', { name: '更多消息操作', exact: true }).click();
  const sheet = page.getByRole('dialog', { name: '消息操作', exact: true });
  await sheet.getByRole('status').waitFor();
  for (const label of ['编辑', '重新生成', '排除上下文', '创建故事线', '删除']) {
    assert.equal(await sheet.getByRole('button', { name: label, exact: true }).isDisabled(), true);
  }
  assert.equal(await sheet.getByRole('button', { name: '复制', exact: true }).isEnabled(), true);
  await page.evaluate(() => window.setBusy(false));
  await page.waitForFunction(() => !document.querySelector('.msg-actions-mobile-primary button:nth-child(2)').disabled);
  assert.equal(await sheet.getByRole('status').count(), 0);
  const branch = sheet.getByRole('button', { name: '创建故事线', exact: true });
  await branch.scrollIntoViewIfNeeded();
  const box = await branch.boundingBox();
  assert.ok(box.x >= 0 && box.x + box.width <= 320 && box.height >= 44);
  await branch.click();
  assert.deepEqual(await page.evaluate(() => window.actions), ['branch']);
  assert.deepEqual(errors, []);
  console.log('PASS: desktop/mobile generation action guards, readonly actions, restored buttons, short-screen branch action.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
