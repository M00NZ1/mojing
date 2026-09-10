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
  let calls = 0, finish, failPage = true, failDetail = true;
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
    if (url.pathname.endsWith('/sediment-page')) {
      const status = url.searchParams.get('status');
      const before = Number(url.searchParams.get('before_id') || 999999);
      if (before !== 999999 && failPage) {
        failPage = false;
        return route.fulfill({ status: 500, json: { detail: 'page fixture failure' } });
      }
      const filtered = rows.filter(row => row.id < before && (status === 'all' || (status === 'confirmed' ? row.confidence === 'confirmed' : row.confidence !== 'confirmed'))).sort((a, b) => b.id - a.id);
      return route.fulfill({ json: { items: filtered.slice(0, 100), next_cursor: filtered.length > 100 ? filtered[99].id : null } });
    }
    if (url.pathname === '/api/encyclopedia') return route.fulfill({ json: [{ id: 1, name: '测试百科', description: '' }] });
    if (/^\/api\/encyclopedia\/entries\/\d+$/.test(url.pathname)) {
      if (failDetail) return route.fulfill({ status: 404, json: { detail: '条目不存在' } });
      const id = Number(url.pathname.split('/').pop());
      return route.fulfill({ json: { entry: { ...rows.find(row => row.id === id), encyclopedia_id: 1, content: '完整资料正文', meta_json: {}, tags: '' }, relations: [] } });
    }
    if (url.pathname.startsWith('/api/')) return route.fulfill({ json: [] });
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
        function Fixture() {
          const [detail, setDetail] = React.useState(false);
          const [location, setLocation] = React.useState(undefined);
          return detail ? React.createElement('button', {onClick: () => setDetail(false)}, '返回沉淀资料') :
            React.createElement(Panel, {encyclopediaId: 1, initialLocation: location, onClose: () => {}, onOpenEntry: (id, type, next) => {window.opened = id; setLocation(next); setDetail(true);}});
        }
        ReactDOM.createRoot(document.getElementById('fixture')).render(React.createElement(QueryClientProvider, {client}, React.createElement(Fixture)));
      </script></body></html>` });
  });
  await page.goto(`http://127.0.0.1:${port}`);
  await page.getByText('本页 100 条 · 已选 0 条', { exact: true }).waitFor();
  await page.getByRole('button', { name: '资料101', exact: true }).click();
  assert.equal(await page.evaluate(() => window.opened), 101);
  await page.getByRole('button', { name: '返回沉淀资料', exact: true }).click();
  await page.getByText('本页 100 条 · 已选 0 条', { exact: true }).waitFor();
  await page.getByRole('button', { name: '选择本页' }).click();
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '读取失败' }).waitFor();
  assert.equal(await page.getByRole('button', { name: '上一页', exact: true }).isEnabled(), true);
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await page.getByText('本页 1 条 · 已选 0 条', { exact: true }).waitFor();
  await page.getByRole('button', { name: '资料1', exact: true }).click();
  await page.getByRole('button', { name: '返回沉淀资料', exact: true }).click();
  await page.getByText('本页 1 条 · 已选 0 条', { exact: true }).waitFor();
  await page.getByText('第 2 页', { exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '待核对', exact: true }).getAttribute('aria-pressed'), 'true');
  await page.waitForFunction(() => document.activeElement?.getAttribute('data-sediment-entry') === '1');
  assert.equal(await page.getByRole('button', { name: '下一页', exact: true }).isDisabled(), true);
  await page.getByRole('button', { name: '上一页', exact: true }).click();
  await page.getByText('本页 100 条 · 已选 0 条', { exact: true }).waitFor();
  assert.ok(await page.locator('.sediment-review-row').count() <= 100);
  await page.getByRole('button', { name: '已确认', exact: true }).click();
  await page.getByText('本页 1 条 · 已选 0 条', { exact: true }).waitFor();
  await page.getByRole('button', { name: '待核对', exact: true }).click();
  await page.getByRole('button', { name: '选择本页' }).click();
  await page.getByRole('button', { name: '确认所选' }).click();
  await page.waitForFunction(() => document.querySelector('.sediment-batch-actions button:last-child').disabled);
  assert.equal(await page.getByRole('button', { name: '关闭', exact: true }).isDisabled(), true);
  assert.equal(await page.getByRole('button', { name: '全部', exact: true }).isDisabled(), true);
  for (let attempt = 0; !finish && attempt < 100; attempt++) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(finish, 'confirmation request reached the fixture');
  finish(); finish = undefined;
  await page.getByRole('alert').filter({ hasText: '选择已保留' }).waitFor();
  await page.getByText('本页 100 条 · 已选 100 条', { exact: true }).waitFor();
  await page.getByRole('button', { name: '确认所选' }).click();
  for (let attempt = 0; !finish && attempt < 100; attempt++) await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(finish, 'retry reached the fixture');
  finish();
  await page.getByText('已确认 100 条资料', { exact: true }).waitFor();
  await page.getByText('本页 1 条 · 已选 0 条', { exact: true }).waitFor();
  assert.equal(calls, 2);
  for (const button of await page.locator('.sediment-batch-actions button').all()) {
    const box = await button.boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= 320);
  }
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  if (process.env.SMOKE_OUTPUT) await page.screenshot({ path: process.env.SMOKE_OUTPUT });
  assert.deepEqual(errors, []);
  rows = rows.map(row => ({ ...row, confidence: 'inferred' }));
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(`http://127.0.0.1:${port}/encyclopedia?encId=1`);
  await page.getByRole('button', { name: '本库沉淀', exact: true }).click();
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await page.getByRole('button', { name: '资料1', exact: true }).click();
  await page.getByRole('button', { name: '返回沉淀资料', exact: true }).click();
  await page.getByText('第 2 页', { exact: true }).waitFor();
  await page.waitForFunction(() => document.activeElement?.getAttribute('data-sediment-entry') === '1');
  failDetail = false;
  await page.getByRole('button', { name: '资料1', exact: true }).click();
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await page.getByRole('button', { name: '编辑', exact: true }).waitFor();
  await page.getByRole('button', { name: '返回沉淀资料', exact: true }).click();
  await page.getByText('第 2 页', { exact: true }).waitFor();
  await page.waitForFunction(() => document.activeElement?.getAttribute('data-sediment-entry') === '1');
  assert.deepEqual(errors, []);
  console.log('PASS: review filters, bounded cursor pages, page retry, detail return with page/filter/focus restoration, saving locks, failure retry, refresh and 320px layout.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
