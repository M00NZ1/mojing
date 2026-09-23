import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { MemoryCompactionStatus, MemoryCorrection, MemorySegment, Participant, SessionCharacterState, SessionEventPage, WorldTemplate } from '../types';
import type { SpeakerTurnMode } from '../utils/speakerTurnMode';
import { findStoryLineDisplayLabel, storyLineDisplayLabel } from '../utils/storyLinePresentation';
import InlineQueryError, { type RefreshableQuery } from './InlineQueryError';
import { confirmModal } from './ConfirmModal';
import UiIcon from './UiIcon';
import { buildWorldSelectorItems, resolveWorldSelectorValue, worldSelectorValue } from '../utils/worldSelector';

interface ChatRightPanelProps {
  sessionOptions?: ReactNode;
  show: boolean;
  onClose: () => void;
  modal?: boolean;
  tab: string;
  onTabChange: (tab: 'participants' | 'config' | 'states' | 'memory' | 'trace' | 'events') => void;
  speakerTurnMode: SpeakerTurnMode;
  onSpeakerTurnModeChange: (mode: SpeakerTurnMode) => void;
  maxAutoSpeakers: number;
  onMaxAutoSpeakersChange: (n: number) => void;
  onUpdateTalkativeness: (characterId: number, value: number) => void;
  eventNodesQuery: EventNodesQuery;
  participantsQuery: RefreshableQuery<Participant[]>;
  worldTemplateId: string;
  onWorldTemplateIdChange: (id: string) => void;
  encyclopediaId: number | null;
  onEncyclopediaIdChange: (id: number | null) => void;
  worldTemplatesQuery: RefreshableQuery<WorldTemplate[]>;
  encyclopediasQuery: RefreshableQuery<{ id: number; name: string; entry_count: number }[]>;
  gameplayMode: string;
  onGameplayModeChange: (mode: string) => void;
  narratorEnabled: boolean;
  onNarratorEnabledChange: (enabled: boolean) => void;
  narratorName: string;
  onNarratorNameChange: (name: string) => void;
  onSaveWorld: () => void;
  worldReady: boolean;
  worldLoading: boolean;
  worldLoadError?: unknown;
  onRetryWorldLoad: () => void;
  worldSaving: boolean;
  worldDirty: boolean;
  worldSaveError?: unknown;
  selectedBranchId: string;
  onBranchChange: (branchId: string) => void;
  branchSwitchDisabled?: boolean;
  branchOptions: { branch_id: string; label?: string; message_count: number }[];
  memoryStateQuery: RefreshableQuery<SessionCharacterState[]>;
  memorySegmentsQuery: RefreshableQuery<MemorySegment[]>;
  memoryCompactionQuery: RefreshableQuery<MemoryCompactionStatus>;
  memoryContinuePending: boolean;
  onContinueMemory: () => void;
  memoryCorrectionsQuery: RefreshableQuery<MemoryCorrection[]>;
  onLocateMemorySource: (messageId: number) => void;
  onCreateMemoryCorrection: (body: MemoryCorrectionPayload) => Promise<void>;
  onUpdateMemoryCorrection: (id: number, body: MemoryCorrectionPayload) => Promise<void>;
  onDeleteMemoryCorrection: (id: number) => Promise<void>;
  promptTraceQuery: RefreshableQuery<Record<string, unknown>>;
  tokenUsageQuery: RefreshableQuery<{ total_tokens: number; model_context_limit: number }>;
  getStorageUrl?: (path: string) => string;
}

type MemoryCorrectionPayload = {
  content: string;
  branch_id: string | null;
  source_message_id: number | null;
};

type CorrectionDraft = MemoryCorrectionPayload & { id: number | null };

type PromptTraceMemoryCorrection = {
  id: number;
  order: number;
  content: string | null;
  branch_id: string | null;
  source_message_id: number | null;
  status: 'current' | 'changed' | 'deleted' | 'unknown_revision';
  reason: string;
};

type EventNodesQuery = RefreshableQuery<{ pages: SessionEventPage[] }> & {
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  isFetchNextPageError: boolean;
  fetchNextPage: () => Promise<unknown>;
};

function getPromptTraceCorrections(trace: Record<string, unknown>): PromptTraceMemoryCorrection[] {
  const corrections = trace.memory_corrections;
  return Array.isArray(corrections) ? corrections as PromptTraceMemoryCorrection[] : [];
}

function correctionStatusLabel(status: PromptTraceMemoryCorrection['status']): string {
  switch (status) {
    case 'current': return '当前版本';
    case 'changed': return '已变更';
    case 'deleted': return '已删除';
    case 'unknown_revision': return '版本未知';
  }
}

export default function ChatRightPanel({
  sessionOptions, show, onClose, modal = false, tab, onTabChange,
  participantsQuery,
  worldTemplateId, onWorldTemplateIdChange,
  encyclopediaId, onEncyclopediaIdChange,
  worldTemplatesQuery, encyclopediasQuery,
  gameplayMode, onGameplayModeChange,
  narratorEnabled, onNarratorEnabledChange,
  narratorName, onNarratorNameChange,
  onSaveWorld, worldReady, worldLoading, worldLoadError, onRetryWorldLoad,
  worldSaving, worldDirty, worldSaveError,
  selectedBranchId, onBranchChange, branchSwitchDisabled = false, branchOptions,
  memoryStateQuery, memorySegmentsQuery, memoryCompactionQuery, memoryContinuePending, onContinueMemory,
  memoryCorrectionsQuery, onLocateMemorySource,
  onCreateMemoryCorrection, onUpdateMemoryCorrection, onDeleteMemoryCorrection,
  promptTraceQuery, tokenUsageQuery, getStorageUrl,
  speakerTurnMode, onSpeakerTurnModeChange, maxAutoSpeakers, onMaxAutoSpeakersChange,
  onUpdateTalkativeness, eventNodesQuery,
}: ChatRightPanelProps) {
  const worldInputsDisabled = worldSaving || !worldReady;
  const worldItems = buildWorldSelectorItems(worldTemplatesQuery.data ?? [], encyclopediasQuery.data ?? [], worldTemplateId, encyclopediaId);
  const visibleEvents = eventNodesQuery.data?.pages.flatMap((page) => page.items) ?? [];
  const [correctionDraft, setCorrectionDraft] = useState<CorrectionDraft | null>(null);
  const [correctionError, setCorrectionError] = useState<string | null>(null);
  const [savingCorrection, setSavingCorrection] = useState(false);
  const panelRef = useRef<HTMLElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!show || !modal) return undefined;
    const panel = panelRef.current;
    const frame = window.requestAnimationFrame(() => {
      panel?.querySelector<HTMLElement>('[data-chat-right-close]')?.focus();
    });
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;
      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href], [tabindex]:not([tabindex="-1"])',
      )).filter((element) => (
        element.getAttribute('aria-hidden') !== 'true'
        && element.getClientRects().length > 0
        && window.getComputedStyle(element).visibility !== 'hidden'
      ));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!panel.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [modal, show]);

  useEffect(() => {
    if (!correctionDraft || correctionDraft.branch_id === null || correctionDraft.branch_id === selectedBranchId) return;
    setCorrectionDraft({
      ...correctionDraft,
      id: null,
      branch_id: selectedBranchId,
    });
    setCorrectionError('已切换故事线；草稿已保留，将另存为当前故事线纠正。');
  }, [correctionDraft, selectedBranchId]);

  if (!show) return null;

  function beginCorrection(content = '', sourceMessageId: number | null = null, id: number | null = null, branchId: string | null = selectedBranchId) {
    setCorrectionError(null);
    setCorrectionDraft({ id, content, source_message_id: sourceMessageId, branch_id: branchId });
  }

  async function saveCorrection() {
    if (!correctionDraft || savingCorrection) return;
    const content = correctionDraft.content.trim();
    if (!content) {
      setCorrectionError('请先填写纠正内容');
      return;
    }
    const payload: MemoryCorrectionPayload = { content, branch_id: correctionDraft.branch_id, source_message_id: correctionDraft.source_message_id };
    setSavingCorrection(true);
    setCorrectionError(null);
    try {
      if (correctionDraft.id === null) await onCreateMemoryCorrection(payload);
      else await onUpdateMemoryCorrection(correctionDraft.id, payload);
      setCorrectionDraft(null);
    } catch (error) {
      setCorrectionError(`未保存，本轮不会生效：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setSavingCorrection(false);
    }
  }

  async function deleteCorrection(correction: MemoryCorrection) {
    if (savingCorrection) return;
    const confirmed = await confirmModal('删除记忆纠正', '删除后这条纠正将不再参与后续记忆构建，确定删除吗？', 'warning');
    if (!confirmed) return;
    setSavingCorrection(true);
    setCorrectionError(null);
    try {
      await onDeleteMemoryCorrection(correction.id);
      if (correctionDraft?.id === correction.id) setCorrectionDraft(null);
    } catch (error) {
      setCorrectionError(`未保存，本轮不会生效：${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setSavingCorrection(false);
    }
  }

  const correctionForm = correctionDraft && (
    <div className="mini-card" style={{ marginBottom: 8 }}>
      <div style={{ fontWeight: 700, marginBottom: 5 }}>{correctionDraft.id === null ? '新增记忆纠正' : '编辑记忆纠正'}</div>
      <textarea value={correctionDraft.content} onChange={(event) => setCorrectionDraft({ ...correctionDraft, content: event.target.value })} rows={4} placeholder="写下应优先遵循的事实或设定…" style={{ width: '100%', resize: 'vertical' }} disabled={savingCorrection} />
      <label style={{ display: 'block', marginTop: 6 }}>作用域
        <select value={correctionDraft.branch_id === null ? '__session__' : correctionDraft.branch_id} onChange={(event) => setCorrectionDraft({ ...correctionDraft, branch_id: event.target.value === '__session__' ? null : selectedBranchId })} disabled={savingCorrection} style={{ width: '100%' }}>
          <option value={selectedBranchId}>仅当前故事线</option>
          <option value="__session__">整个会话</option>
        </select>
      </label>
      {correctionDraft.source_message_id !== null && correctionDraft.branch_id === null && <div className="hint" style={{ marginTop: 5 }}>来源消息保留；它可能只在原故事线可见。</div>}
      {correctionError && <div className="inline-query-error" role="alert" style={{ marginTop: 6 }}>{correctionError}</div>}
      <div className="button-row" style={{ marginTop: 6 }}>
        <button type="button" className="ghost-button" onClick={() => setCorrectionDraft(null)} disabled={savingCorrection}>取消</button>
        <button type="button" className="primary-button" onClick={() => { void saveCorrection(); }} disabled={savingCorrection}>{savingCorrection ? '保存中…' : '保存纠正'}</button>
      </div>
    </div>
  );

  return (
    <aside
      id="chat-right-panel"
      ref={panelRef}
      className="chat-right"
      role={modal ? 'dialog' : 'complementary'}
      aria-modal={modal ? 'true' : undefined}
      aria-labelledby="chat-right-heading"
    >
      <div className="chat-right-header">
        <span id="chat-right-heading">会话详情</span>
        <button type="button" className="chat-right-close" data-chat-right-close onClick={onClose} aria-label="关闭会话详情"><UiIcon name="close" /></button>
      </div>
      <div className="chat-right-tabs">
        <button type="button" className={`chat-right-tab ${tab === 'participants' ? 'active' : ''}`} onClick={() => onTabChange('participants')}>发言</button>
        <button type="button" className={`chat-right-tab ${tab === 'config' ? 'active' : ''}`} onClick={() => onTabChange('config')}>世界配置</button>
        <button type="button" className={`chat-right-tab ${tab === 'states' ? 'active' : ''}`} onClick={() => onTabChange('states')}>角色状态</button>
        <button type="button" className={`chat-right-tab ${tab === 'memory' ? 'active' : ''}`} onClick={() => onTabChange('memory')}>记忆</button>
        <button type="button" className={`chat-right-tab ${tab === 'trace' ? 'active' : ''}`} onClick={() => onTabChange('trace')}>追踪</button>
        <button type="button" className={`chat-right-tab ${tab === 'events' ? 'active' : ''}`} onClick={() => onTabChange('events')}>事件</button>
      </div>

      {tab === 'participants' && (
        <div style={{padding: '4px 0'}} onClick={(e) => e.stopPropagation()}>
          {participantsQuery.isError && (
            <InlineQueryError
              message="会话角色加载失败"
              error={participantsQuery.error}
              retrying={participantsQuery.isFetching}
              onRetry={() => { void participantsQuery.refetch(); }}
            />
          )}
          {participantsQuery.isLoading && (
            <div style={{fontSize: '0.78rem', color: 'var(--text-2)', padding: 4}}>正在加载会话角色…</div>
          )}
          <div className="mini-card" style={{ marginBottom: 8, padding: 8 }}>
            <div style={{ fontWeight: 600, fontSize: '0.82rem', marginBottom: 6 }}>发言调度</div>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.8rem' }}>
              <input type="radio" checked={speakerTurnMode === 'auto'} onChange={() => onSpeakerTurnModeChange('auto')} />
              自动（按发言率）
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.8rem', marginTop: 4 }}>
              <input type="radio" checked={speakerTurnMode === 'manual'} onChange={() => onSpeakerTurnModeChange('manual')} />
              手动点选回复角色
            </label>
            {speakerTurnMode === 'auto' && (
              <label style={{ display: 'block', fontSize: '0.78rem', marginTop: 8 }}>
                每轮最多自动发言人数
                <input
                  type="number"
                  min={1}
                  max={6}
                  value={maxAutoSpeakers}
                  onChange={(e) => onMaxAutoSpeakersChange(Number(e.target.value) || 2)}
                  style={{ width: '100%', marginTop: 4 }}
                />
              </label>
            )}
          </div>
          {participantsQuery.data?.map((item) => (
            <div key={item.id} className="mini-card" style={{ marginBottom: 6, padding: '6px 8px' }}>
              <div style={{ fontWeight: 600, fontSize: '0.82rem' }}>{item.character.name}</div>
              <label style={{ fontSize: '0.75rem', display: 'block', marginTop: 4 }}>
                发言率 {(Math.round((item.talkativeness ?? 0.7) * 100))}%
                <input
                  type="range"
                  min={5}
                  max={100}
                  value={Math.round((item.talkativeness ?? 0.7) * 100)}
                  onChange={(e) => onUpdateTalkativeness(item.character.id, Number(e.target.value) / 100)}
                  style={{ width: '100%' }}
                />
              </label>
            </div>
          ))}
          {!participantsQuery.isError && !participantsQuery.isLoading && (participantsQuery.data?.length ?? 0) === 0 && (
            <div style={{fontSize: '0.78rem', color: 'var(--text-2)', padding: 4}}>请先从“更多 → 群成员”添加角色</div>
          )}
        </div>
      )}

      {tab === 'config' && (
        <div style={{padding: '4px 0'}} onClick={(e) => e.stopPropagation()}>
          {sessionOptions}
          {worldLoadError != null && !worldReady && (
            <InlineQueryError
              message="会话世界配置加载失败"
              error={worldLoadError}
              retrying={worldLoading}
              onRetry={onRetryWorldLoad}
            />
          )}
          {worldLoading && !worldReady && (
            <div style={{fontSize: '0.78rem', color: 'var(--text-2)', padding: 4}}>正在加载会话世界配置…</div>
          )}
          {worldTemplatesQuery.isError && (
            <InlineQueryError
              message="世界模板加载失败"
              error={worldTemplatesQuery.error}
              retrying={worldTemplatesQuery.isFetching}
              onRetry={() => { void worldTemplatesQuery.refetch(); }}
            />
          )}
          {encyclopediasQuery.isError && (
            <InlineQueryError
              message="百科列表加载失败"
              error={encyclopediasQuery.error}
              retrying={encyclopediasQuery.isFetching}
              onRetry={() => { void encyclopediasQuery.refetch(); }}
            />
          )}
          <div className="form-grid compact-grid">
            <label>世界
              <select disabled={worldInputsDisabled} value={worldSelectorValue(worldTemplateId, encyclopediaId, worldItems)} onChange={(event) => {
                const selected = resolveWorldSelectorValue(event.target.value, worldItems);
                onWorldTemplateIdChange(selected.templateId);
                onEncyclopediaIdChange(selected.encyclopediaId);
                const template = worldTemplatesQuery.data?.find((item) => item.template_id === selected.templateId);
                if (template) onGameplayModeChange(template.gameplay_mode);
              }}>
                <option value="">不绑定世界</option>
                {worldItems.map((item) => (<option value={item.value} key={item.value}>{item.label}</option>))}
              </select>
            </label>
            <label>玩法模式<input disabled={worldInputsDisabled} value={gameplayMode} onChange={(e) => onGameplayModeChange(e.target.value)} /></label>
          </div>
          <div className="button-row">
            <label><input disabled={worldInputsDisabled} type="checkbox" checked={narratorEnabled} onChange={(e) => onNarratorEnabledChange(e.target.checked)} />旁白推进</label>
            <input disabled={worldInputsDisabled} value={narratorName} onChange={(e) => onNarratorNameChange(e.target.value)} placeholder="旁白名称" />
            <button className="ghost-button" type="button" disabled={worldInputsDisabled || !worldDirty} onClick={onSaveWorld}>
              {worldLoading && !worldReady ? '加载中…' : !worldReady ? '未加载' : worldSaving ? '保存中…' : worldDirty ? '保存' : '已保存'}
            </button>
          </div>
          {worldSaveError != null && (
            <InlineQueryError
              message="世界配置保存失败"
              error={worldSaveError}
              retrying={worldSaving}
              onRetry={onSaveWorld}
            />
          )}
          <label>故事线
            <select
              value={selectedBranchId}
              disabled={branchSwitchDisabled}
              aria-describedby={branchSwitchDisabled ? 'chat-branch-switch-hint' : undefined}
              onChange={(e) => onBranchChange(e.target.value)}
            >
              {branchOptions.map((item) => (<option key={item.branch_id} value={item.branch_id}>{storyLineDisplayLabel(item.branch_id, item.label)} · {item.message_count} 条</option>))}
            </select>
            {branchSwitchDisabled && <span id="chat-branch-switch-hint" className="hint" role="status" style={{ marginTop: 5 }}>回复生成中，请先停止或等待完成后再切换故事线。</span>}
          </label>
        </div>
      )}

      {tab === 'states' && (
        <div style={{padding: '4px 0'}} onClick={(e) => e.stopPropagation()}>
          {memoryStateQuery.isError ? (
            <InlineQueryError
              message="角色状态加载失败"
              error={memoryStateQuery.error}
              retrying={memoryStateQuery.isFetching}
              onRetry={() => { void memoryStateQuery.refetch(); }}
            />
          ) : memoryStateQuery.isLoading ? (
            <div style={{fontSize: '0.82rem', color: 'var(--text-2)', padding: 8}}>正在加载角色状态…</div>
          ) : memoryStateQuery.data && memoryStateQuery.data.length > 0 ? (
            memoryStateQuery.data.map((state) => {
              const character = participantsQuery.data?.find((item) => item.character.id === state.character_id)?.character;
              const events = (state.event_log_json ?? []) as Array<Record<string, unknown>>;
              const recentEvents = events.slice(-3);
              return (
                <div key={state.id} className="mini-card" style={{marginBottom: 8}}>
                  <div style={{fontWeight: 700, marginBottom: 4}}>{character?.name ?? `人物 ${state.character_id}`}</div>
                  {recentEvents.length > 0 && <div style={{fontSize: '0.78rem', color: 'var(--text-2)'}}>最近事件：{recentEvents.map((e) => String(e.title ?? e.event ?? '')).join(' → ')}</div>}
                  {recentEvents.length === 0 && <div style={{fontSize: '0.78rem', color: 'var(--text-2)'}}>暂无事件记录</div>}
                </div>
              );
            })
          ) : (
            <div style={{fontSize: '0.82rem', color: 'var(--text-2)', padding: 8}}>暂无角色状态记录</div>
          )}
        </div>
      )}

      {tab === 'memory' && (
        <div style={{padding: '4px 0', fontSize: '0.8rem'}} onClick={(e) => e.stopPropagation()}>
          <div className="mini-card" style={{marginBottom: 8}}>
            <div className="button-row" style={{ alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
              <strong>记忆面板</strong>
              <button type="button" className="ghost-button" disabled={memorySegmentsQuery.isFetching}
                onClick={() => { void memorySegmentsQuery.refetch(); }}>刷新摘要</button>
            </div>
            <div className="hint">自动摘要用于辅助回顾；已锁定的纠正不会被自动摘要或重建改写。</div>
          </div>
          {memoryCompactionQuery.isError ? (
            <InlineQueryError message="整理状态读取失败" error={memoryCompactionQuery.error}
              retrying={memoryCompactionQuery.isFetching}
              onRetry={() => { void memoryCompactionQuery.refetch(); }} />
          ) : memoryCompactionQuery.isLoading ? (
            <div className="hint" style={{ marginBottom: 8 }}>正在读取整理状态…</div>
          ) : memoryCompactionQuery.data && (
            <div className="mini-card" style={{ marginBottom: 8 }}>
              <div style={{ fontWeight: 700, marginBottom: 4 }}>自动整理</div>
              <div className="hint" role="status">
                {memoryCompactionQuery.data.running ? '后台正在整理，完成后会更新本页摘要。'
                  : memoryCompactionQuery.data.checkpoint_phase ? `发现未完成进度：${memoryCompactionQuery.data.checkpoint_phase === 'summary' ? '摘要' : '事件'}已处理 ${memoryCompactionQuery.data.processed_chunks} 段，继续时会复核原文。`
                    : memoryCompactionQuery.data.ready ? '已有足够的未整理消息，可以继续整理。'
                      : `尚未达到 ${memoryCompactionQuery.data.threshold} 条消息的自动整理间隔。`}
              </div>
              {memoryCompactionQuery.data.ready && !memoryCompactionQuery.data.running && (
                <button type="button" className="ghost-button" style={{ marginTop: 8 }}
                  disabled={memoryContinuePending} onClick={onContinueMemory}>
                  {memoryContinuePending ? '启动中…' : memoryCompactionQuery.data.checkpoint_phase ? '继续整理' : '整理未归档消息'}
                </button>
              )}
            </div>
          )}
          {correctionForm}
          {correctionError && !correctionDraft && <div className="inline-query-error" role="alert" style={{ marginBottom: 8 }}>{correctionError}</div>}
          {!correctionDraft && <button type="button" className="ghost-button" style={{ marginBottom: 8 }} onClick={() => beginCorrection()}>新增记忆纠正</button>}
          <details className="debug-card" open>
            <summary>已锁定纠正</summary>
            {memoryCorrectionsQuery.isError ? (
              <InlineQueryError message="记忆纠正加载失败" error={memoryCorrectionsQuery.error} retrying={memoryCorrectionsQuery.isFetching} onRetry={() => { void memoryCorrectionsQuery.refetch(); }} />
            ) : memoryCorrectionsQuery.isLoading ? (
              <div className="hint">正在加载已锁定纠正…</div>
            ) : memoryCorrectionsQuery.data && memoryCorrectionsQuery.data.length > 0 ? (
              memoryCorrectionsQuery.data.map((correction) => (
                <div key={correction.id} className="mini-card" style={{ marginBottom: 6, padding: '6px 8px' }}>
                  <div style={{ marginBottom: 4 }}><span className="pill">{correction.branch_id === null ? '全会话' : '仅当前故事线'}</span></div>
                  <div style={{ whiteSpace: 'pre-wrap' }}>{correction.content}</div>
                  {correction.source_message_id !== null && <button type="button" className="link-button" onClick={() => onLocateMemorySource(correction.source_message_id!)}>来源 #{correction.source_message_id}</button>}
                  <div className="button-row" style={{ marginTop: 5 }}>
                    <button type="button" className="ghost-button" disabled={savingCorrection} onClick={() => beginCorrection(correction.content, correction.source_message_id, correction.id, correction.branch_id)}>编辑</button>
                    <button type="button" className="ghost-button" disabled={savingCorrection} onClick={() => { void deleteCorrection(correction); }}>删除</button>
                  </div>
                </div>
              ))
            ) : <div className="hint">暂无已锁定纠正。它们将在后续记忆处理中优先保留。</div>}
          </details>
          <details className="debug-card" open>
            <summary>最近分段记忆（最多 100 段）</summary>
            {memorySegmentsQuery.isError ? (
              <InlineQueryError
                message="记忆加载失败"
                error={memorySegmentsQuery.error}
                retrying={memorySegmentsQuery.isFetching}
                onRetry={() => { void memorySegmentsQuery.refetch(); }}
              />
            ) : memorySegmentsQuery.isLoading ? (
              <div style={{fontSize: '0.78rem', color: 'var(--text-2)', padding: 4}}>正在加载记忆…</div>
            ) : memorySegmentsQuery.data && memorySegmentsQuery.data.length > 0 ? (
              memorySegmentsQuery.data.map((segment) => {
                const hasValidSource = segment.start_message_id > 0 && segment.end_message_id >= segment.start_message_id;
                return (
                <div key={segment.id} className="mini-card" style={{marginBottom: 4, padding: '6px 8px'}}>
                  <div style={{fontWeight: 600}}>{segment.summary || '无摘要'}</div>
                  {segment.key_facts.length > 0 && <div style={{ marginTop: 4 }}>关键事实：{segment.key_facts.join('；')}</div>}
                  <div style={{fontSize: '0.72rem', color: 'var(--text-2)', marginTop: 4}}>情感：{segment.emotional_tone || '中性'}</div>
                  <div style={{fontSize: '0.72rem', color: 'var(--text-2)', marginTop: 4}}>{hasValidSource ? `来源：#${segment.start_message_id}–#${segment.end_message_id}` : '未记录有效来源'}</div>
                  <div className="button-row" style={{ marginTop: 5 }}>
                    {hasValidSource && <button type="button" className="link-button" onClick={() => onLocateMemorySource(segment.start_message_id)}>定位来源</button>}
                    <button type="button" className="ghost-button" onClick={() => beginCorrection(segment.summary, hasValidSource ? segment.start_message_id : null)}>纠正这段记忆</button>
                  </div>
                </div>
                );
              })
            ) : (
              <div style={{fontSize: '0.78rem', color: 'var(--text-2)', padding: 4}}>暂无分段记忆，发送消息后将自动生成</div>
            )}
          </details>
        </div>
      )}

      {tab === 'trace' && (
        <div style={{padding: '4px 0', fontSize: '0.8rem'}} onClick={(e) => e.stopPropagation()}>
          {promptTraceQuery.isError ? (
            <InlineQueryError
              message="Prompt 追踪加载失败"
              error={promptTraceQuery.error}
              retrying={promptTraceQuery.isFetching}
              onRetry={() => { void promptTraceQuery.refetch(); }}
            />
          ) : promptTraceQuery.data ? (
            <div>
              {(() => {
                const trace = promptTraceQuery.data;
                const corrections = getPromptTraceCorrections(trace);
                const correctionsRecorded = trace.memory_corrections_recorded !== false;
                return (
                  <div className="mini-card" style={{marginBottom: 8}}>
                    <div style={{fontWeight: 700, marginBottom: 4}}>本轮采用的用户纠正</div>
                    {!correctionsRecorded ? (
                      <div style={{color: 'var(--text-2)'}}>该生成记录未包含用户纠正信息</div>
                    ) : corrections.length === 0 ? (
                      <div style={{color: 'var(--text-2)'}}>本轮未采用用户纠正</div>
                    ) : corrections.map((correction) => (
                      <div key={`${correction.id}-${correction.order}`} style={{padding: '6px 0', borderTop: '1px solid var(--line-soft)'}}>
                        <div style={{fontWeight: 600}}>#{correction.order} · {correctionStatusLabel(correction.status)}</div>
                        <div style={{marginTop: 2}}>{correction.content ?? '内容不可用'}</div>
                        <div className="hint" style={{marginTop: 3}}>
                          {correction.branch_id === null ? '整个会话' : `故事线：${findStoryLineDisplayLabel(branchOptions, correction.branch_id)}`} · 来源：{correction.source_message_id === null ? '未记录' : `消息 #${correction.source_message_id}`}
                        </div>
                        <div className="hint" style={{marginTop: 2}}>原因：{correction.reason}</div>
                      </div>
                    ))}
                  </div>
                );
              })()}
              <div className="mini-card" style={{marginBottom: 8}}>
                <div style={{fontWeight: 700, marginBottom: 4}}>生成元数据</div>
                {(promptTraceQuery.data as any).kind && <div style={{color: 'var(--text-2)'}}>类型: {(promptTraceQuery.data as any).kind === 'character_reply' ? '角色回复' : '旁白推进'}</div>}
                {(promptTraceQuery.data as any).character_name && <div style={{color: 'var(--text-2)'}}>角色: {(promptTraceQuery.data as any).character_name}</div>}
                {(promptTraceQuery.data as any).world_template_label && <div style={{color: 'var(--text-2)'}}>世界模板: {(promptTraceQuery.data as any).world_template_label}</div>}
                {(promptTraceQuery.data as any).encyclopedia_name && <div style={{color: 'var(--text-2)'}}>百科库: {(promptTraceQuery.data as any).encyclopedia_name}</div>}
                {(promptTraceQuery.data as any).gameplay_mode && <div style={{color: 'var(--text-2)'}}>玩法模式: {(promptTraceQuery.data as any).gameplay_mode}</div>}
              </div>
              {(promptTraceQuery.data as any).memory_hits && (
                <details className="debug-card">
                  <summary>记忆命中 ({Object.values((promptTraceQuery.data as any).memory_hits).flat().filter(Boolean).length} 条)</summary>
                  {Object.entries((promptTraceQuery.data as any).memory_hits as Record<string, any[]>).map(([key, items]) => items && items.length > 0 && (
                    <div key={key} style={{marginTop: 4}}>
                      <div style={{fontWeight: 600, color: 'var(--text-2)', fontSize: '0.72rem'}}>{key}:</div>
                      {items.slice(0, 5).map((item: any, i: number) => (
                        <div key={i} style={{padding: '2px 0', borderBottom: '1px solid var(--line-soft)', fontSize: '0.72rem'}}>
                          {item.text?.slice(0, 80)} {item.score > 0 && <span style={{color: 'var(--accent)'}}>(+{item.score})</span>}
                        </div>
                      ))}
                    </div>
                  ))}
                </details>
              )}
              {(promptTraceQuery.data as any).encyclopedia_hits && (promptTraceQuery.data as any).encyclopedia_hits.length > 0 && (
                <details className="debug-card" open>
                  <summary>百科命中 ({(promptTraceQuery.data as any).encyclopedia_hits.length} 条)</summary>
                  {(promptTraceQuery.data as any).encyclopedia_hits.map((h: any, i: number) => (
                    <div key={i} className="mini-card" style={{marginBottom: 4, padding: '6px 8px', fontSize: '0.72rem'}}>
                      <strong>{h.entry_type}/{h.title}</strong> {h.explicit_hit && <span className="pill pill-green" style={{fontSize: '0.65rem'}}>关键词命中</span>}
                      {h.activation_mode && h.activation_mode !== 'normal' && <span className="pill" style={{fontSize: '0.65rem', marginLeft: 4}}>{h.activation_mode}</span>}
                      <div style={{color: 'var(--text-2)'}}>{h.content?.slice(0, 100)}...</div>
                      <div className="hint">来源：{h.trust_level}</div>
                    </div>
                  ))}
                </details>
              )}
              {(promptTraceQuery.data as any).lore_hits && (promptTraceQuery.data as any).lore_hits.length > 0 && (
                <details className="debug-card">
                  <summary>Lore 命中 ({(promptTraceQuery.data as any).lore_hits.length} 条)</summary>
                  {(promptTraceQuery.data as any).lore_hits.map((h: any, i: number) => (
                    <div key={i} style={{padding: '2px 0', fontSize: '0.72rem', borderBottom: '1px solid var(--line-soft)'}}>
                      {h.title || h.entry_type}: {h.text?.slice(0, 60)}
                    </div>
                  ))}
                </details>
              )}
            </div>
          ) : (
            <div style={{fontSize: '0.82rem', color: 'var(--text-2)', padding: 8}}>
              {promptTraceQuery.isFetching ? '正在加载 Prompt 追踪…' : '暂无 Prompt 追踪数据。请先发送一条消息让 AI 回应。'}
            </div>
          )}
        </div>
      )}

      {tab === 'events' && (
        <div style={{ padding: '4px 0', fontSize: '0.82rem' }} onClick={(e) => e.stopPropagation()}>
          <div className="button-row" style={{ alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <span className="hint">最近事件 · 已载入 {visibleEvents.length} 条</span>
            <button type="button" className="ghost-button" disabled={eventNodesQuery.isFetching}
              onClick={() => { void eventNodesQuery.refetch(); }}>刷新</button>
          </div>
          {eventNodesQuery.isError && (
            <InlineQueryError
              message={eventNodesQuery.isFetchNextPageError ? '较早事件加载失败' : '会话事件加载失败'}
              error={eventNodesQuery.error}
              retrying={eventNodesQuery.isFetching}
              onRetry={() => { void (eventNodesQuery.isFetchNextPageError ? eventNodesQuery.fetchNextPage() : eventNodesQuery.refetch()); }}
            />
          )}
          {eventNodesQuery.isLoading && visibleEvents.length === 0 && <div>加载事件…</div>}
          {!eventNodesQuery.isLoading && !eventNodesQuery.isError && visibleEvents.length === 0 && (
            <div style={{ color: 'var(--text-2)', padding: 8 }}>暂无会话事件节点</div>
          )}
          {visibleEvents.map((ev) => (
              <div key={ev.id} className="mini-card" style={{ marginBottom: 6, padding: '6px 8px' }}>
                <div style={{ fontWeight: 600 }}>{ev.title || ev.event_type}</div>
                <div style={{ fontSize: '0.72rem', color: 'var(--text-2)' }}>
                  {new Date(ev.created_at).toLocaleString('zh-CN')}
                  {ev.resolved ? ' · 已解决' : ''}
                </div>
                {ev.description ? (
                  <div style={{ marginTop: 4, color: 'var(--text-2)' }}>{ev.description.slice(0, 160)}</div>
                ) : null}
                {ev.message_id !== null && ev.message_id > 0 && (
                  <button type="button" className="link-button" onClick={() => onLocateMemorySource(ev.message_id!)}>
                    定位来源 #{ev.message_id}
                  </button>
                )}
              </div>
            ))}
          {eventNodesQuery.hasNextPage && (
            <button type="button" className="ghost-button" disabled={eventNodesQuery.isFetchingNextPage}
              onClick={() => { void eventNodesQuery.fetchNextPage(); }}>
              {eventNodesQuery.isFetchingNextPage ? '加载中…' : '加载更早事件'}
            </button>
          )}
        </div>
      )}

      {tokenUsageQuery.isError && (
        <InlineQueryError
          message="Token 用量加载失败"
          error={tokenUsageQuery.error}
          retrying={tokenUsageQuery.isFetching}
          onRetry={() => { void tokenUsageQuery.refetch(); }}
        />
      )}
      {!tokenUsageQuery.isError && tokenUsageQuery.data && (() => {
        const tu = tokenUsageQuery.data;
        const total = tu.total_tokens || 0;
        const maxVal = tu.model_context_limit || 0;
        const pct = maxVal > 0 ? Math.min((total / maxVal) * 100, 100) : 0;
        return (
          <div className="mini-card" style={{marginTop: 8}}>
            <div style={{fontSize: '0.72rem', color: 'var(--text-2)'}}>Token 用量</div>
            <div className="progress-bar"><div className="progress-fill" style={{width: `${pct}%`}} /></div>
            <div style={{fontSize: '0.72rem', color: 'var(--text-2)'}}>{total.toLocaleString()} / {maxVal.toLocaleString()}</div>
          </div>
        );
      })()}
    </aside>
  );
}
