import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15192);
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
child.stdout.on('data', data => { log += data; });
child.stderr.on('data', data => { log += data; });
let browser;
try {
  await new Promise((resolve, reject) => {
    const deadline = Date.now() + 15000;
    const timer = setInterval(() => {
      if (log.includes('Local:')) { clearInterval(timer); resolve(); }
      else if (child.exitCode !== null || Date.now() > deadline) { clearInterval(timer); reject(new Error(log)); }
    }, 100);
  });
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  const page = await browser.newPage();
  page.on('pageerror', error => console.error(error.message));
  await page.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.hostname !== '127.0.0.1' || url.port !== String(port)) return route.abort();
    if (url.pathname !== '/') return route.continue();
    return route.fulfill({ contentType: 'text/html; charset=utf-8', body: `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>
      <div id="fixture" style="padding:16px;max-width:700px"></div>
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
        import Field from '/src/components/ExpandableTextArea.tsx';
        import '/src/styles.css';
        window.submissions = 0;
        function Fixture() {
          const [text, setText] = React.useState('雾港角色设定。\\n'.repeat(200));
          return React.createElement('form', {onSubmit: e => {e.preventDefault(); window.submissions++;}},
            React.createElement(Field, {'aria-label': '角色人设', value: text, maxLength: 10000, onChange: e => setText(e.target.value)}),
            React.createElement('button', {type: 'submit'}, '保存'));
        }
        ReactDOM.createRoot(document.getElementById('fixture')).render(React.createElement(Fixture));
      </script></body></html>` });
  });
  for (const viewport of [{ width: 1280, height: 900 }, { width: 320, height: 480 }]) {
    await page.setViewportSize(viewport);
    await page.goto(`http://127.0.0.1:${port}`);
    const field = page.getByRole('textbox', { name: '角色人设' });
    await field.waitFor();
    const original = await field.inputValue();
    assert.equal(original, '雾港角色设定。\n'.repeat(200));
    const collapsed = await field.boundingBox();
    const expand = page.getByRole('button', { name: '展开编辑' });
    await expand.focus();
    await page.keyboard.press('Enter');
    const collapse = page.getByRole('button', { name: '收起编辑' });
    await collapse.waitFor();
    assert.equal(await collapse.getAttribute('aria-expanded'), 'true');
    assert.equal(await collapse.getAttribute('aria-controls'), await field.getAttribute('id'));
    const expanded = await field.boundingBox();
    assert.ok(expanded.height > collapsed.height);
    assert.ok(expanded.height <= viewport.height * 0.65 + 1);
    assert.ok(expanded.x >= 0 && expanded.x + expanded.width <= viewport.width);
    assert.ok(await field.evaluate(el => el.scrollHeight > el.clientHeight));
    await field.fill('新的角色设定\n保留新的经历');
    await collapse.click();
    assert.equal(await field.inputValue(), '新的角色设定\n保留新的经历');
    assert.equal(await page.evaluate(() => window.submissions), 0);
    await page.getByRole('button', { name: '保存', exact: true }).click();
    assert.equal(await page.evaluate(() => window.submissions), 1);
  }
  console.log('PASS: desktop/mobile long text bounds, keyboard toggle, ARIA linkage, draft preservation and no unintended form submission.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
