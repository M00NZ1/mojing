// Isolated real-component regression for story-line switching while chat generation is active.
// The Vite page and React component are real; every API route is mocked and no provider/database is used.
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
    const { default: ChatRightPanel } = await import('/src/components/ChatRightPanel.tsx');
    const { default: UiIcon } = await import('/src/components/UiIcon.tsx');
    const noopQuery = { data: [], isError: false, isFetching: false, isLoading: false, isSuccess: true, error: null, refetch: async () => {} };
    const branches = [
      { branch_id: 'main', label: '主线剧情', message_count: 3 },
      { branch_id: 'branch_alt', label: '雾港支线', message_count: 2 },
    ];
    window.branchTest = { switches: [], phase: 'running' };
    function Harness() {
      const [busy, setBusy] = React.useState(true);
      const [selected, setSelected] = React.useState('main');
      window.setBranchBusy = setBusy;
      window.setBranchPhase = (phase) => {
        window.branchTest.phase = phase;
        setBusy(phase === 'running');
      };
      const switchBranch = (branchId) => {
        if (busy) return;
        window.branchTest.switches.push(branchId);
        setSelected(branchId);
      };
      return React.createElement(ChatRightPanel, {
        show: true,
        tab: 'config',
        modal: false,
        onClose: () => {},
        onTabChange: () => {},
        sessionOptions: React.createElement('div', null, 'fixture session options'),
        participantsQuery: noopQuery,
        worldTemplateId: '', onWorldTemplateIdChange: () => {},
        encyclopediaId: null, onEncyclopediaIdChange: () => {},
        worldTemplatesQuery: noopQuery, encyclopediasQuery: noopQuery,
        gameplayMode: '', onGameplayModeChange: () => {},
        narratorEnabled: false, onNarratorEnabledChange: () => {},
        narratorName: '', onNarratorNameChange: () => {},
        onSaveWorld: () => {}, worldReady: true, worldLoading: false, worldLoadError: null,
        onRetryWorldLoad: () => {}, worldSaving: false, worldDirty: false, worldSaveError: null,
        selectedBranchId: selected, onBranchChange: switchBranch, branchSwitchDisabled: busy, branchOptions: branches,
        memoryStateQuery: noopQuery, memorySegmentsQuery: noopQuery, memoryCorrectionsQuery: noopQuery,
        onLocateMemorySource: () => {}, onCreateMemoryCorrection: async () => {}, onUpdateMemoryCorrection: async () => {}, onDeleteMemoryCorrection: async () => {},
        promptTraceQuery: noopQuery, tokenUsageQuery: noopQuery, getStorageUrl: () => '',
        speakerTurnMode: 'auto', onSpeakerTurnModeChange: () => {}, maxAutoSpeakers: 2,
        onMaxAutoSpeakersChange: () => {}, onUpdateTalkativeness: () => {}, eventNodesQuery: noopQuery,
      });
    }
    document.getElementById('root').replaceChildren();
    createRoot(document.getElementById('root')).render(React.createElement(Harness));
  });

  const branchSelect = page.getByRole('combobox', { name: '故事线' });
  await branchSelect.waitFor();
  assert.equal(await branchSelect.isDisabled(), true, '生成中故事线选择器必须禁用');
  await page.getByRole('status').filter({ hasText: '回复生成中，请先停止或等待完成后再切换故事线' }).waitFor();

  // The disabled native control cannot accept a user selection.
  assert.deepEqual(await page.evaluate(() => window.branchTest.switches), [], '生成中不应切换故事线');

  await page.evaluate(() => window.setBranchPhase('stopped'));
  await assert.doesNotReject(() => branchSelect.waitFor({ state: 'attached' }));
  await page.waitForFunction(() => !document.querySelector('select[aria-describedby="chat-branch-switch-hint"]'));
  assert.equal(await branchSelect.isEnabled(), true, '停止后故事线选择器应恢复可用');
  await branchSelect.selectOption('branch_alt');
  await page.waitForFunction(() => window.branchTest.switches.includes('branch_alt'));
  assert.deepEqual(await page.evaluate(() => window.branchTest.switches), ['branch_alt'], '停止后应允许切换故事线');
  assert.equal(await page.getByRole('status').filter({ hasText: '回复生成中，请先停止或等待完成后再切换故事线' }).count(), 0, '停止后应移除生成中原因');

  await page.evaluate(() => window.setBranchPhase('success'));
  await page.waitForFunction(() => !document.querySelector('select[aria-describedby="chat-branch-switch-hint"]'));
  await branchSelect.selectOption('main');
  await page.waitForFunction(() => window.branchTest.switches.includes('main'));
  assert.deepEqual(await page.evaluate(() => window.branchTest.switches), ['branch_alt', 'main'], '完成后应允许再次切换故事线');
  assert.deepEqual(errors, [], errors.join('\n'));
  console.log('PASS: ChatRightPanel disables story-line switching with a reason during generation and restores the control after stop/completion (isolated component and mocked APIs).');
} finally {
  await browser?.close();
  if (child.exitCode === null) { child.kill(); await once(child, 'exit'); }
}
