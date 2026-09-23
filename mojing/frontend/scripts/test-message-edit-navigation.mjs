// Isolated real-component regression for message edit departure confirmation.
// The real editor runs in a browser harness; no provider or database is used.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15223);
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root,
  windowsHide: true,
  env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18023/api' },
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

try {
  await waitForVite();
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/*', (route) => route.request().url().startsWith(`http://127.0.0.1:${port}`) ? route.continue() : route.abort());
  await page.goto(`http://127.0.0.1:${port}/`);

  await page.evaluate(async () => {
    const React = (await import('/node_modules/.vite/deps/react.js')).default;
    const { createRoot } = (await import('/node_modules/.vite/deps/react-dom_client.js')).default;
    const { default: Editor } = await import('/src/components/EditMessageModal.tsx');
    window.editResult = { cancelled: 0, left: 0, saved: 0 };
    function Harness() {
      const [pending, setPending] = React.useState(false);
      const [saving, setSaving] = React.useState(false);
      const [content, setContent] = React.useState('保留这段改写');
      window.requestLeave = () => setPending(true);
      window.setSaving = setSaving;
      return React.createElement(Editor, {
        messageId: 1, content, originalContent: '原文', regenerateAfterSave: false,
        isSaving: saving, navigationPending: pending, worldDirty: true,
        onContentChange: setContent,
        onSave: () => window.editResult.saved++, onClose: () => window.editResult.left++,
        onCancelNavigation: () => { window.editResult.cancelled++; setPending(false); },
        onDiscardNavigation: () => { window.editResult.left++; setPending(false); },
      });
    }
    document.getElementById('root').replaceChildren();
    createRoot(document.getElementById('root')).render(React.createElement(Harness));
  });
  const editor = page.getByRole('dialog', { name: '编辑消息' });
  await editor.waitFor();
  await page.evaluate(() => window.requestLeave());
  await page.getByRole('button', { name: '继续编辑' }).waitFor();
  assert.match(await editor.innerText(), /消息改写和世界设置/);
  assert.equal(await editor.locator('textarea').isDisabled(), true);
  await page.keyboard.press('Escape');
  assert.equal(await editor.locator('textarea').inputValue(), '保留这段改写');
  await page.waitForFunction(() => window.editResult.cancelled === 1);
  await page.evaluate(() => window.requestLeave());
  await page.getByRole('button', { name: '继续编辑' }).click();
  await page.waitForFunction(() => window.editResult.cancelled === 2);
  assert.equal(await editor.locator('textarea').inputValue(), '保留这段改写');
  await page.evaluate(() => window.requestLeave());
  await page.getByRole('button', { name: '放弃并离开' }).click();
  await page.waitForFunction(() => window.editResult.left === 1);
  await page.evaluate(() => window.setSaving(true));
  await page.waitForFunction(() => document.querySelector('textarea').disabled);
  await page.keyboard.press('Escape');
  assert.equal(await page.evaluate(() => window.editResult.left), 1);
  assert.equal(await page.evaluate(() => window.editResult.saved), 0);
  assert.deepEqual(errors, []);
  console.log('PASS: edit navigation confirmation retains drafts on Escape/cancel, confirms departure, and locks dismissal while saving.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
