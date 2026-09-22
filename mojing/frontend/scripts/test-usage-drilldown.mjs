// Isolated browser regression for the three-level usage reader.
// The API is fully mocked; this never reads a user database or calls a provider.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../', import.meta.url));
const port = Number(process.env.SMOKE_PORT || 15183);
const output = process.env.SMOKE_OUTPUT;
const child = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
  cwd: root,
  windowsHide: true,
  env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:18001/api' },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let log = '';
child.stdout.on('data', (data) => { log += data; });
child.stderr.on('data', (data) => { log += data; });

const totals = (cost, tokens, calls = 2, failed = 0) => ({
  cost_usd: cost, total_tokens: tokens, total_calls: calls,
  success_calls: calls - failed, failed_calls: failed, duration_ms: 1200,
});
const providerData = {
  deepseek: {
    totals: totals(0.1234, 12000, 3),
    items: [
      { provider: 'deepseek', model_name: 'deepseek-chat', calls: 2, success_calls: 2, failed_calls: 0, prompt_tokens: 7000, completion_tokens: 3000, total_tokens: 10000, cost_usd: 0.1, duration_ms: 900, models_count: 2 },
      { provider: 'deepseek', model_name: 'shared/chat', calls: 1, success_calls: 1, failed_calls: 0, prompt_tokens: 1000, completion_tokens: 1000, total_tokens: 2000, cost_usd: 0.0234, duration_ms: 300, models_count: 1 },
    ],
  },
  openai: {
    totals: totals(0.4567, 8000, 2),
    items: [
      { provider: 'openai', model_name: 'gpt-4o-mini', calls: 1, success_calls: 1, failed_calls: 0, prompt_tokens: 4000, completion_tokens: 500, total_tokens: 4500, cost_usd: 0.3, duration_ms: 600, models_count: 2 },
      { provider: 'openai', model_name: 'shared/chat', calls: 1, success_calls: 1, failed_calls: 0, prompt_tokens: 2500, completion_tokens: 1000, total_tokens: 3500, cost_usd: 0.1567, duration_ms: 500, models_count: 1 },
    ],
  },
};

const records = [
  { id: 101, session_id: 7, character_id: 2, prompt_tokens: 80, completion_tokens: 120, total_tokens: 200, estimated_cost: 0.0023, duration_ms: 850, success: true, created_at: '2026-09-23T10:11:12Z' },
  { id: 100, session_id: 7, character_id: 2, prompt_tokens: 70, completion_tokens: 90, total_tokens: 160, estimated_cost: 0.0019, duration_ms: 720, success: true, created_at: '2026-09-23T10:01:12Z' },
  { id: 99, session_id: 7, character_id: 2, prompt_tokens: 20, completion_tokens: 0, total_tokens: 20, estimated_cost: 0, duration_ms: 100, success: false, created_at: '2026-09-23T09:51:12Z' },
];

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
  let failModelsOnce = false;
  let recordPage = 0;
  await context.route('http://127.0.0.1:18001/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const endpoint = url.pathname.replace('/api', '');
    let status = 200;
    let data = [];
    if (endpoint === '/costs/providers') {
      data = { period: { days: Number(url.searchParams.get('days') || 30), since: '2026-08-24', until: '2026-09-23' }, totals: totals(0.5801, 20000, 5), items: Object.entries(providerData).map(([provider, value]) => ({ provider, calls: value.totals.total_calls, success_calls: value.totals.success_calls, failed_calls: value.totals.failed_calls, prompt_tokens: value.items.reduce((sum, item) => sum + item.prompt_tokens, 0), completion_tokens: value.items.reduce((sum, item) => sum + item.completion_tokens, 0), total_tokens: value.totals.total_tokens, cost_usd: value.totals.cost_usd, duration_ms: value.totals.duration_ms, models_count: value.items.length })) };
    } else if (endpoint.match(/^\/costs\/providers\/[^/]+\/models$/)) {
      const provider = decodeURIComponent(endpoint.split('/')[3]);
      if (provider === 'empty' && failModelsOnce) { failModelsOnce = false; status = 503; data = { detail: '用量服务暂时不可用' }; }
      else { const value = providerData[provider]; data = { period: { days: 30, since: '2026-08-24', until: '2026-09-23' }, totals: value?.totals || totals(0, 0, 0), provider, items: value?.items || [] }; }
    } else if (endpoint.match(/^\/costs\/providers\/[^/]+\/records$/)) {
      const before = url.searchParams.get('before_id');
      const pageItems = before ? records.slice(2) : records.slice(0, 2);
      recordPage += 1;
      data = { provider: decodeURIComponent(endpoint.split('/')[3]), model_name: url.searchParams.get('model'), items: pageItems, next_cursor: before ? null : 99 };
    } else if (endpoint === '/system/local-config') data = { max_auto_speakers: 2 };
    else if (endpoint === '/system/voice-service-config') data = { mode: 'builtin_only', enabled: false };
    else if (endpoint === '/system/model-platforms') data = { version: 1, active_id: null, platforms: [] };
    else if (endpoint === '/providers/catalog') data = [];
    else if (endpoint === '/personas/active') data = null;
    else if (endpoint === '/world-templates') data = [];
    else if (endpoint === '/settings') data = {};
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) }).catch(() => {});
  });

  const summary = `http://127.0.0.1:${port}/settings?tab=costs&days=30&status=all`;
  await page.goto(summary, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '平台明细' }).waitFor();
  const deepseekRow = page.getByRole('link', { name: /查看DeepSeek用量/ });
  const openaiRow = page.getByRole('link', { name: /查看OpenAI用量/ });
  await deepseekRow.waitFor();
  assert.ok(await openaiRow.isVisible(), 'summary includes OpenAI');
  await deepseekRow.click();
  await page.getByRole('heading', { name: 'DeepSeek' }).waitFor();
  assert.ok(await page.getByText('deepseek-chat', { exact: true }).isVisible());
  assert.ok(await page.getByText('shared/chat', { exact: true }).isVisible());
  assert.equal(await page.getByText('gpt-4o-mini', { exact: true }).count(), 0, 'provider rows are isolated');
  if (output) {
    await mkdir(output, { recursive: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'usage-platform-detail-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1365, height: 900 });
  }
  const sharedDeepseek = page.getByRole('link', { name: /查看模型 shared\/chat 的请求/ });
  assert.ok(await sharedDeepseek.getAttribute('href') .then((href) => href.includes('model=shared%2Fchat')));
  await sharedDeepseek.click();
  await page.getByRole('heading', { name: 'shared/chat' }).waitFor();
  assert.equal(await page.getByRole('heading', { name: 'shared/chat' }).count(), 1);
  await page.getByRole('group', { name: '请求状态' }).getByRole('button', { name: '成功', exact: true }).click();
  await page.waitForURL(/status=success/);
  assert.equal(new URL(page.url()).searchParams.get('provider'), 'deepseek');
  const firstRequest = page.locator('.usage-request').first();
  await firstRequest.locator('summary').click();
  await firstRequest.getByText('输入 Token', { exact: true }).waitFor();
  assert.ok(await firstRequest.getByText('来源对话', { exact: true }).isVisible());
  if (output) {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, 'usage-model-detail-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1365, height: 900 });
    await page.screenshot({ path: path.join(output, 'usage-model-detail-desktop.png'), fullPage: true });
  }
  await page.getByRole('button', { name: '加载更多请求' }).click();
  const loadedRequest = page.locator('.usage-request').nth(2);
  await loadedRequest.locator('summary').click();
  await loadedRequest.getByText('#99', { exact: true }).waitFor();
  assert.ok(recordPage >= 2, 'cursor loads the next record page');
  await page.getByRole('link', { name: '用量汇总' }).click();
  await page.getByRole('heading', { name: '平台明细' }).waitFor();
  await page.getByRole('link', { name: /查看OpenAI用量/ }).click();
  await page.getByRole('heading', { name: 'OpenAI' }).waitFor();
  await page.getByText('gpt-4o-mini', { exact: true }).waitFor();
  assert.ok(await page.getByText('gpt-4o-mini', { exact: true }).isVisible());
  assert.equal(await page.getByText('deepseek-chat', { exact: true }).count(), 0, 'same model catalog is isolated by provider');
  await page.reload();
  await page.getByRole('heading', { name: 'OpenAI' }).waitFor();
  await page.getByRole('link', { name: '用量汇总' }).click();
  await page.getByRole('heading', { name: '平台明细' }).waitFor();

  failModelsOnce = true;
  await page.goto(`http://127.0.0.1:${port}/usage/platform?days=30&status=all&provider=empty`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('alert').filter({ hasText: '平台用量读取失败' }).waitFor();
  await page.getByRole('alert').getByRole('button', { name: '重试' }).click();
  await page.getByText('该筛选范围内还没有模型请求。', { exact: true }).waitFor();
  assert.ok(page.url().includes('provider=empty'), 'empty state preserves deep-link provider');

  if (output) {
    await mkdir(output, { recursive: true });
    await page.screenshot({ path: path.join(output, 'usage-empty-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(summary, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '平台明细' }).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'summary fits narrow viewport');
    await page.screenshot({ path: path.join(output, 'usage-summary-mobile.png'), fullPage: true });
    const lastPlatform = page.locator('.usage-list-row').last();
    await lastPlatform.scrollIntoViewIfNeeded();
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    assert.ok(await lastPlatform.isVisible(), 'last platform remains reachable after scrolling');
    await page.screenshot({ path: path.join(output, 'usage-summary-mobile-bottom.png'), fullPage: false });
  }
  assert.deepEqual(errors, [], `browser errors: ${errors.join('; ')}`);
  console.log('PASS: usage summary, provider/model isolation, deep links, record cursor, empty and retry');
} finally {
  await browser?.close();
  child.kill();
}
