// Isolated UI regression: all APIs are mocked, no user data or provider requests.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15176);
const output = process.env.SMOKE_OUTPUT;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root, windowsHide: true, env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18001/api' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
child.stdout.on('data', (data) => { log += data; });
child.stderr.on('data', (data) => { log += data; });
let browser;
try {
  const deadline = Date.now() + 15000;
  while (!log.includes('Local:')) {
    if (child.exitCode !== null || Date.now() > deadline) throw new Error(`Vite startup failed: ${log}`);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, channel: process.env.SMOKE_BROWSER || undefined });
  if (output) await mkdir(output, { recursive: true });
  for (const width of [1365, 390]) {
    const context = await browser.newContext({ viewport: { width, height: 900 } });
    const errors = [];
    await context.route('**/*', (route) => {
      const url = new URL(route.request().url());
      return url.hostname === '127.0.0.1' && url.port === String(port) ? route.continue() : route.abort();
    });
    const template = { id: 1, template_id: 'test-world', label: '测试世界', category: '奇幻', summary: '本地世界资料', gameplay_mode: '自由剧情', world_prompt: '完整世界正文', is_builtin: false };
    const archive = { format_version: 1, template, lore_entries: [{ title: '地方志', content: '完整 Lore 正文' }] };
    const bundle = { format_version: 1, package_count: 1, packages: [archive] };
    let failExport = true;
    const exports = [];
    await context.route('http://127.0.0.1:18001/api/**', async (route) => {
      const url = new URL(route.request().url());
      const endpoint = url.pathname.replace('/api', '');
      let data = [];
      let status = 200;
      if (endpoint === '/worlds/templates') data = [template];
      else if (endpoint === '/system/local-config') data = { public_text_base_url: 'https://api.openai.com/v1', public_text_api_key: '', public_text_model: 'test-model' };
      else if (endpoint.includes('/export')) {
        exports.push(url.pathname + url.search);
        if (failExport) { status = 500; data = { detail: '导出失败，请重试' }; }
        else data = endpoint.includes('bundle') ? bundle : archive;
      }
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${port}/workbench`);
    await page.getByRole('button', { name: '我的世界', exact: true }).click();
    const singleButton = page.getByRole('button', { name: '导出', exact: true });
    await singleButton.click();
    await page.locator('.toast-error').filter({ hasText: '导出失败，请重试' }).waitFor();
    assert.equal(await singleButton.isEnabled(), true);
    assert.equal(await page.getByText('测试世界', { exact: true }).count(), 1);
    failExport = false;
    const downloadJson = async (button) => {
      const [download] = await Promise.all([page.waitForEvent('download'), button.click()]);
      const stream = await download.createReadStream();
      const chunks = [];
      for await (const chunk of stream) chunks.push(chunk);
      assert.match(download.suggestedFilename(), /\.json$/);
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    };
    assert.deepEqual(await downloadJson(singleButton), archive);
    await page.getByRole('button', { name: '更多工具', exact: true }).click();
    assert.deepEqual(await downloadJson(page.getByRole('button', { name: '导出所有自定义模板', exact: true })), bundle);
    assert.ok(exports.includes('/api/worlds/templates/export-bundle-file?include_builtin=false'));
    assert.equal(errors.length, 0, errors.join('\n'));
    if (output) await page.screenshot({ path: path.join(output, `world-transfer-${width}.png`), fullPage: true });
    await context.close();
  }
  console.log('PASS: desktop/mobile world export visibility, failed download retry, intact single and bundle downloads through mocked local API.');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
