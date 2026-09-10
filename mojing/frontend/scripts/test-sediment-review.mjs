import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const port = 15193;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: fileURLToPath(new URL('../', import.meta.url)), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...process.env, VITE_API_BASE: `http://127.0.0.1:${port}/api` },
});
let log = '', browser;
child.stdout.on('data', data => { log += data; });
child.stderr.on('data', data => { log += data; });
try {
  await new Promise((resolve, reject) => {
    const deadline = Date.now() + 15000;
    const timer = setInterval(() => {
      if (log.includes('Local:')) { clearInterval(timer); resolve(); }
      else if (child.exitCode !== null || Date.now() > deadline) { clearInterval(timer); reject(new Error(log)); }
    }, 100);
  });
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  const transformed = await (await fetch(`http://127.0.0.1:${port}/src/components/SedimentReviewPanel.tsx`)).text();
  const queryModule = transformed.match(/from "([^"]*@tanstack_react-query[^\"]*)"/)[1];
  const page = await browser.newPage({ viewport: { width: 320, height: 640 } });
  const errors = [];
  page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
  let rows = Array.from({ length: 102 }, (_, index) => ({ id: index + 1, title: `资料${index + 1}`, entry_type: 'event', confidence: index === 101 ? 'confirmed' : 'inferred', source_session_id: 8, summary: '雾港的旧事与人物关系。'.repeat(30) }));
  let calls = 0, finish;
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.hostname !== '127.0.0.1' || url.port !== String(port)) return route.abort();
    if (url.pathname.endsWith('/sediment-entries/confirm')) {
      calls++;
      const ids = route.request().postDataJSON().entry_ids;
      assert.equal(ids.length, 100);
      await new Promise(resolve => { finish = resolve; });
      if (calls === 1) return route.fulfill({ status: 500, json: { detail: 'fixture failure' } });
      rows = rows.map(row => ids.includes(row.id) ? { ...row, confidence: 'confirmed' } : row);
      return route.fulfill({ json: { confirmed: ids.length } });
    }
    if (url.pathname.endsWith('/sediment-entries')) return route.fulfill({ json: rows });
    if (url.pathname !== '/') return route.continue();
    return route.fulfill({ contentType: 'text/html; charset=utf-8', body: `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>
      <div id="fixture"></div>
      <script type="module">
        import RefreshRuntime from '/@react-refresh';
        RefreshRuntime.injectIntoGlobalHook(window);
        window.$RefreshReg$ = () => {};
        window.$RefreshSig$ = () => type => type;
        window.__vite_plugin_react_preamble_installed__ = true;
      </script>
      <script type="module">
        import React from '/node_modules/.vite/deps/react.js';
        import ReactDOM from '/node_modules/.vite/deps/react-dom_client.js';
        import { QueryClient, QueryClientProvider } from '${queryModule}';
        import Panel from '/src/components/SedimentReviewPanel.tsx';
        import '/src/styles.css';
        const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
        ReactDOM.createRoot(document.getElementById('fixture')).render(React.createElement(QueryClientProvider, {client}, React.createElement(Panel, {encyclopediaId: 1, onClose: () => {}, onOpenEntry: id => {window.opened = id;}})));
      </script></body></html>` });
  });
  await page.goto(`http://127.0.0.1:${port}`);
  await page.getByText('当前列表 101 条 · 已选 0 条', { exact: true }).waitFor();
  await page.getByRole('button', { name: '资料1', exact: true }).click();
  assert.equal(await page.evaluate(() => window.opened), 1);
  await page.getByRole('button', { name: '选择前100条' }).click();
  assert.equal(await page.getByRole('checkbox', { name: '选择资料：资料101', exact: true }).isDisabled(), true);
  await page.getByRole('button', { name: '已确认', exact: true }).click();
  await page.getByText('当前列表 1 条 · 已选 0 条', { exact: true }).waitFor();
  await page.getByRole('button', { name: '待核对', exact: true }).click();
  await page.getByRole('button', { name: '选择前100条' }).click();
  await page.getByRole('button', { name: '确认所选' }).click();
  await page.waitForFunction(() => document.querySelector('.sediment-batch-actions button:last-child').disabled);
  assert.equal(await page.getByRole('button', { name: '关闭', exact: true }).isDisabled(), true);
  assert.equal(await page.getByRole('button', { name: '全部', exact: true }).isDisabled(), true);
  for (let attempt = 0; !finish && attempt < 100; attempt++) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(finish, 'confirmation request reached the fixture');
  finish(); finish = undefined;
  await page.getByRole('alert').filter({ hasText: '选择已保留' }).waitFor();
  await page.getByText('当前列表 101 条 · 已选 100 条', { exact: true }).waitFor();
  await page.getByRole('button', { name: '确认所选' }).click();
  for (let attempt = 0; !finish && attempt < 100; attempt++) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(finish, 'retry reached the fixture');
  finish();
  await page.getByText('已确认 100 条资料', { exact: true }).waitFor();
  await page.getByText('当前列表 1 条 · 已选 0 条', { exact: true }).waitFor();
  assert.equal(calls, 2);
  for (const button of await page.locator('.sediment-batch-actions button').all()) {
    const box = await button.boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= 320);
  }
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  if (process.env.SMOKE_OUTPUT) await page.screenshot({ path: process.env.SMOKE_OUTPUT });
  assert.deepEqual(errors, []);
  console.log('PASS: review filters, 100-entry limit, source entry navigation, saving locks, failure retry, refresh and 320px layout.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
