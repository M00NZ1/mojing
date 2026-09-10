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
const port = Number(process.env.SMOKE_PORT || 15191);
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
  await page.goto(`http://127.0.0.1:${port}`);
  await page.locator('.app-shell').waitFor();
  for (const viewport of [{ width: 1365, height: 900 }, { width: 320, height: 480 }]) {
    await page.setViewportSize(viewport);
    await page.evaluate(async () => {
      const { confirmModal } = await import('/src/components/ConfirmModal.tsx');
      window.confirmOutcome = null;
      confirmModal('删除所选内容？', '请检查以下内容。\n'.repeat(180) + '说明末尾', 'danger')
        .then(value => { window.confirmOutcome = value; });
    });
    const dialog = page.getByRole('dialog');
    await dialog.waitFor();
    const confirm = dialog.getByRole('button', { name: '确认删除', exact: true });
    const cancel = dialog.getByRole('button', { name: '取消', exact: true });
    for (const button of [confirm, cancel]) {
      const bounds = await button.boundingBox();
      assert.ok(bounds && bounds.y >= 0 && bounds.y + bounds.height <= viewport.height && bounds.x >= 0 && bounds.x + bounds.width <= viewport.width);
    }
    const description = dialog.getByLabel('确认说明');
    assert.ok(await description.evaluate(el => el.scrollHeight > el.clientHeight));
    await description.focus();
    await page.keyboard.press('End');
    await page.waitForTimeout(250);
    assert.ok(await description.evaluate(el => el.scrollTop > 0));
    const input = dialog.getByRole('textbox');
    await input.fill('DELETE');
    await input.evaluate(el => el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', isComposing: true, bubbles: true })));
    assert.equal(await dialog.count(), 1);
    assert.equal(await input.inputValue(), 'DELETE');
    assert.equal(await confirm.isEnabled(), true);
    if (output) {
      await mkdir(output, { recursive: true });
      await page.screenshot({ path: path.join(output, `confirm-${viewport.width}.png`) });
    }
    await cancel.click();
    await page.waitForFunction(() => window.confirmOutcome === false);
    assert.equal(await page.getByRole('dialog').count(), 0);
  }
  await page.evaluate(async () => {
    const { confirmModal } = await import('/src/components/ConfirmModal.tsx');
    window.confirmResults = [];
    window.oldConfirmationAbort = new AbortController();
    window.newConfirmationAbort = new AbortController();
    confirmModal('第一个确认', '旧请求', 'default', { signal: window.oldConfirmationAbort.signal })
      .then(value => window.confirmResults.push(['old', value]));
    confirmModal('第二个确认', '新请求', 'default', { signal: window.newConfirmationAbort.signal })
      .then(value => window.confirmResults.push(['new', value]));
  });
  await page.getByRole('heading', { name: '第二个确认' }).waitFor();
  await page.waitForFunction(() => window.confirmResults.length === 1);
  assert.deepEqual(await page.evaluate(() => window.confirmResults), [['old', false]]);
  await page.evaluate(() => window.oldConfirmationAbort.abort());
  assert.equal(await page.getByRole('heading', { name: '第二个确认' }).count(), 1);
  await page.evaluate(() => window.newConfirmationAbort.abort());
  await page.waitForFunction(() => window.confirmResults.length === 2);
  assert.deepEqual(await page.evaluate(() => window.confirmResults), [['old', false], ['new', false]]);
  await page.evaluate(async () => {
    const { confirmModal } = await import('/src/components/ConfirmModal.tsx');
    confirmModal('最终确认', '继续执行').then(value => window.confirmResults.push(['final', value]));
  });
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await page.waitForFunction(() => window.confirmResults.length === 3);
  assert.deepEqual(await page.evaluate(() => window.confirmResults), [['old', false], ['new', false], ['final', true]]);
  console.log('PASS: long confirmation desktop/mobile, keyboard/IME, replacement settles old request, stale abort preserves new dialog, active abort and confirmation.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
  const errors = [];
