import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useInfiniteQuery, useMutation, useQuery, useQueries, useQueryClient } from '@tanstack/react-query';
import { useBeforeUnload, useBlocker, useNavigate, useParams, useSearchParams } from 'react-router-dom';

import { api } from '../api/client';
import { confirmModal } from '../components/ConfirmModal';
import { ChatModelPicker } from '../components/ModelPlatforms';
import ChatInputBar from '../components/ChatInputBar';
import ChatMenu from '../components/ChatMenu';
import BranchTreeGraph from '../components/BranchTreeGraph';
import ChatRightPanel from '../components/ChatRightPanel';
import RoundChoicesRow from '../components/RoundChoicesRow';
import EditMessageModal from '../components/EditMessageModal';
import MessageList from '../components/MessageList';
import SettingConflictDialog from '../components/SettingConflictDialog';
import UiIcon from '../components/UiIcon';
import MessageSearchPanel from '../components/MessageSearchPanel';
import { useSessionMessages } from '../hooks/useSessionMessages';
import { useSessionWorld } from '../hooks/useSessionWorld';
import { useSpeakerPlan } from '../hooks/useSpeakerPlan';
import { useToast } from '../hooks/useToast';
import { COMPACT_LAYOUT_QUERY, useMediaQuery } from '../hooks/useMediaQuery';
import DeleteMessageDialog from '../components/DeleteMessageDialog';
import MessageContextDialog from '../components/MessageContextDialog';
import { friendlyFetchError } from '../utils/userFacingError';
import { hasUnsavedEditMessage } from '../utils/editMessageDraft';
import { extractChoicesFromMessage, mergeRoundChoices, stripChoicesFromMessageContent } from '../utils/chatChoiceParsing';
import { getRegenerationBranchPoint } from '../utils/chatBranching';
import { clearChatDraft, loadChatDraft, saveChatDraft, loadChatQuote, saveChatQuote, clearPendingChatSend, loadPendingChatSend, savePendingChatSend, type ChatQuoteDraft, type PendingChatSend } from '../utils/chatDraftStorage';
import { loadSpeakerTurnMode, saveSpeakerTurnMode, type SpeakerTurnMode } from '../utils/speakerTurnMode';
import { canCreateEntryFromMessage, isPersistedMessageId } from '../utils/messageAvailability';
import { removeSelectedFiles } from '../utils/fileSelection';
import { findStoryLineDisplayLabel, storyLineDisplayLabel } from '../utils/storyLinePresentation';
import {
  encyclopediaEntryPath,
  normalizeSettingConflictResult,
  type SettingConflictEntry,
  type SettingConflictResult,
} from '../utils/settingConflictPresentation';
import type { Message, MessageSearchHit, SessionBranch, VoiceClip } from '../types';

const QUICK_ACTION_LABELS: Record<string, string> = {
  summarize_session_events: '剧情总结',
  check_setting_conflicts: '设定冲突检查',
  create_entry_from_message: '百科条目创建',
};

type RightPanelTab = 'participants' | 'config' | 'states' | 'memory' | 'trace' | 'events';

function toastErrorMessage(e: unknown): string {
  return friendlyFetchError(e);
}

function isAbortError(error: unknown): boolean {
  if (error instanceof DOMException && error.name === 'AbortError') return true;
  if (error instanceof Error && error.name === 'AbortError') return true;
  const message = error instanceof Error ? error.message : String(error ?? '');
  return /\babort(?:ed)?\b/i.test(message);
}

function normalizeBranchId(value: string | null | undefined): string {
  return value?.trim() || 'main';
}

function storyLineSourcePreview(message: Message): string {
  const visibleContent = stripChoicesFromMessageContent(message.content).replace(/\s+/g, ' ').trim();
  if (!visibleContent) return '';
  return visibleContent.length > 18 ? `${visibleContent.slice(0, 18)}…` : visibleContent;
}

function buildTempMessage(
  characterId: number | null,
  characterName: string,
  branchId: string,
  streamKey?: string,
): Message {
  return {
    id: -Date.now() - Math.floor(Math.random() * 1000),
    session_id: 0,
    speaker_type: characterId === null ? 'system' : 'character',
    character_id: characterId,
    branch_id: branchId || 'main',
    character_name: characterName,
    content: '',
    structured_content: { speech: '' },
    created_at: new Date().toISOString(),
    stream_key: streamKey,
  };
}

function formatJsonBlock(value: unknown) {
  if (value === null || value === undefined) return '暂无';
  if (Array.isArray(value) && value.length === 0) return '暂无';
  if (typeof value === 'object' && Object.keys(value as Record<string, unknown>).length === 0) return '暂无';
  return JSON.stringify(value, null, 2);
}

function formatMaybeTime(value?: string | null) {
  if (!value) return '暂无';
  return new Date(value).toLocaleString('zh-CN');
}

export default function ChatPage() {
  const params = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const sessionId = Number(params.sessionId);
  const queryClient = useQueryClient();
  /** 切换会话时 React 复用同一组件，输入框需按 session 隔离 */
  const draftInputBySessionRef = useRef<Record<number, string>>({});
  const prevSessionIdForDraftRef = useRef(sessionId);
  const inputDraftMirrorRef = useRef('');
  const inputDraftRevisionRef = useRef(0);
  const [input, setInput] = useState(() => (Number.isFinite(sessionId) ? loadChatDraft(sessionId) ?? '' : ''));
  const [pendingSend, setPendingSend] = useState<PendingChatSend | null>(() =>
    Number.isFinite(sessionId) ? loadPendingChatSend(sessionId) : null);
  const [pendingSendChecking, setPendingSendChecking] = useState(false);
  const [inputFocusRequestKey, setInputFocusRequestKey] = useState(0);
  const [selectedCharacters, setSelectedCharacters] = useState<number[]>([]);
  const [playingClips, setPlayingClips] = useState<VoiceClip[]>([]);
  const [files, setFiles] = useState<File[]>([]);
  const [speakerTurnMode, setSpeakerTurnMode] = useState<SpeakerTurnMode>(() => loadSpeakerTurnMode());
  const [maxAutoSpeakers, setMaxAutoSpeakers] = useState(2);
  const [manualReplyCharacterId, setManualReplyCharacterId] = useState<number | null>(null);
  const [quotingMessage, setQuoteState] = useState<ChatQuoteDraft | null>(() => loadChatQuote(sessionId));
  const quoteRevisionRef = useRef(0);
  const setQuotingMessage = useCallback((quote: ChatQuoteDraft | null) => {
    quoteRevisionRef.current += 1;
    setQuoteState(quote);
    saveChatQuote(sessionId, quote);
  }, [sessionId]);
  const [showPromptDebug, setShowPromptDebug] = useState(false);
  const selectedBranchId = normalizeBranchId(searchParams.get('branch'));
  const selectedBranchRef = useRef(selectedBranchId);
  const selectedBranchPropRef = useRef(selectedBranchId);
  if (selectedBranchPropRef.current !== selectedBranchId) {
    selectedBranchPropRef.current = selectedBranchId;
    selectedBranchRef.current = selectedBranchId;
  }
  const setSelectedBranchId = useCallback((branchId: string) => {
    const normalized = normalizeBranchId(branchId);
    selectedBranchRef.current = normalized;
    setSearchParams((current) => {
      const next = new URLSearchParams(current);
      if (normalized === 'main') next.delete('branch');
      else next.set('branch', normalized);
      return next;
    }, { replace: true });
  }, [setSearchParams]);
  const [showChatMenu, setShowChatMenu] = useState(false);
  const [isExportingChat, setIsExportingChat] = useState(false);
  const [isExportingSessionArchive, setIsExportingSessionArchive] = useState(false);
  const [showBranchTree, setShowBranchTree] = useState(false);
  const chatMenuToggleRef = useRef<HTMLButtonElement>(null);
  const chatMenuReturnFocusRef = useRef<HTMLElement | null>(null);
  const branchTreeRef = useRef<HTMLDivElement>(null);
  const branchTreeReturnFocusRef = useRef<HTMLElement | null>(null);
  const [showEmojiPicker, setShowEmojiPicker] = useState(false);
  const [showMoreTools, setShowMoreTools] = useState(false);
  const [showRightPanel, setShowRightPanel] = useState(false);
  const [rightPanelTab, setRightPanelTab] = useState<RightPanelTab>('participants');
  const rightPanelToggleRef = useRef<HTMLButtonElement>(null);
  const rightPanelReturnFocusRef = useRef<HTMLElement | null>(null);
  const [sessionSearchDraft, setSessionSearchDraft] = useState('');
  const [searchView, setSearchView] = useState<'closed' | 'results' | 'reading'>('closed');
  const [selectedSearchHit, setSelectedSearchHit] = useState<MessageSearchHit | null>(null);
  const [searchLocateFailure, setSearchLocateFailure] = useState<{
    hit: MessageSearchHit; message: string; sessionId: number; branchId: string; query: string;
  } | null>(null);
  const sessionSearchInputRef = useRef<HTMLInputElement>(null);
  const searchFocusRequestIdRef = useRef(0);
  const pendingBranchMessageFocusRef = useRef<{ branchId: string; messageId: number } | null>(null);
  const [messageFocusRequest, setMessageFocusRequest] = useState<{
    messageId: number;
    requestId: number;
    align: 'center' | 'end';
    moveKeyboardFocus?: boolean;
  } | null>(null);
  const [locatingMessageId, setLocatingMessageId] = useState<number | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [retryReplyBranchId, setRetryReplyBranchId] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const generationGateRef = useRef(false);
  const modelChoiceBusyRef = useRef({ sessionId, busy: true });
  const onModelChoiceBusyChange = useCallback((busy: boolean) => {
    if (sessionIdRef.current === sessionId) modelChoiceBusyRef.current = { sessionId, busy };
  }, [sessionId]);
  const branchCreationGateRef = useRef(false);
  const branchSwitchRequestRef = useRef(0);
  const [switchingBranchId, setSwitchingBranchId] = useState<string | null>(null);
  const [branchSwitchFailure, setBranchSwitchFailure] = useState<{
    branchId: string;
    message: string;
  } | null>(null);
  const [editMessageId, setEditMessageId] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');
  const [editSaveError, setEditSaveError] = useState<string | null>(null);
  const [editSavePending, setEditSavePending] = useState(false);
  const editSaveOwnerRef = useRef<symbol | null>(null);
  const [editOriginalContent, setEditOriginalContent] = useState('');
  const [settingConflictResult, setSettingConflictResult] = useState<SettingConflictResult | null>(null);
  const [activeQuickAction, setActiveQuickAction] = useState<string | null>(null);
  const quickActionGateRef = useRef(false);
  const quickActionRequestRef = useRef(0);
  const settingConflictReturnFocusRef = useRef<HTMLElement | null>(null);
  const { showToast } = useToast();
  const [deleteTarget, setDeleteTarget] = useState<{ sessionId: number; branchId: string; message: Message } | null>(null);
  const [contextTarget, setContextTarget] = useState<{ sessionId: number; branchId: string; message: Message } | null>(null);
  const isCompactLayout = useMediaQuery(COMPACT_LAYOUT_QUERY);
  const sessionIdRef = useRef(sessionId);
  sessionIdRef.current = sessionId;

  useEffect(() => { setDeleteTarget(null); setContextTarget(null); }, [sessionId, selectedBranchId]);

  function reserveGeneration(showBusyMessage = true) {
    if (deleteTarget && deleteTarget.sessionId === sessionId && deleteTarget.branchId === selectedBranchRef.current) {
      if (showBusyMessage) showToast('请先完成或关闭删除窗口', 'warn');
      return false;
    }
    if (contextTarget && contextTarget.sessionId === sessionId && contextTarget.branchId === selectedBranchRef.current) {
      if (showBusyMessage) showToast('请先完成或关闭上下文设置窗口', 'warn');
      return false;
    }
    if (modelChoiceBusyRef.current.sessionId !== sessionId || modelChoiceBusyRef.current.busy) {
      if (showBusyMessage) showToast('模型配置尚未就绪，请在模型选择中等待或重试', 'warn');
      return false;
    }
    if (generationGateRef.current) {
      if (showBusyMessage) showToast('当前回复仍在处理中，请先停止或等待完成', 'warn');
      return false;
    }
    generationGateRef.current = true;
    return true;
  }

  function releaseGeneration() {
    generationGateRef.current = false;
  }

  useEffect(() => {
    setEditMessageId(null);
    setEditSaveError(null);
    setEditSavePending(false);
    return () => {
      if (editSaveOwnerRef.current !== null) {
        editSaveOwnerRef.current = null;
        releaseGeneration();
      }
    };
  }, [sessionId]);

  inputDraftMirrorRef.current = input;

  const updateInput = useCallback((value: string) => {
    inputDraftRevisionRef.current += 1;
    inputDraftMirrorRef.current = value;
    setInput(value);
    if (Number.isFinite(sessionId)) {
      draftInputBySessionRef.current[sessionId] = value;
      saveChatDraft(sessionId, value);
    }
  }, [sessionId]);

  // 会话切换：保存上一会话草稿、恢复当前会话草稿；附件不跨会话携带
  useEffect(() => {
    inputDraftRevisionRef.current += 1;
    const prev = prevSessionIdForDraftRef.current;
    if (Number.isFinite(prev) && Number.isFinite(sessionId) && prev !== sessionId) {
      draftInputBySessionRef.current[prev] = inputDraftMirrorRef.current;
      abortRef.current?.abort();
      abortRef.current = null;
      setRetryReplyBranchId(null);
      setShowChatMenu(false);
      setShowBranchTree(false);
      setSettingConflictResult(null);
      settingConflictReturnFocusRef.current = null;
      quickActionRequestRef.current += 1;
      quickActionGateRef.current = false;
      setActiveQuickAction(null);
      setSwitchingBranchId(null);
      setBranchSwitchFailure(null);
    }
    if (Number.isFinite(sessionId)) {
      prevSessionIdForDraftRef.current = sessionId;
      const restoredDraft = draftInputBySessionRef.current[sessionId] ?? loadChatDraft(sessionId) ?? '';
      draftInputBySessionRef.current[sessionId] = restoredDraft;
      setInput(restoredDraft);
      setPendingSend(loadPendingChatSend(sessionId));
    } else {
      setInput('');
      setPendingSend(null);
    }
    quoteRevisionRef.current += 1;
    setQuoteState(loadChatQuote(sessionId));
    setFiles([]);
    setSessionSearchDraft('');
    setSearchView('closed');
    setSelectedSearchHit(null);
  }, [sessionId]);

  // 组件卸载/路由离开时中止 SSE 请求
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
      abortRef.current = null;
      if (Number.isFinite(sessionId)) saveChatDraft(sessionId, inputDraftMirrorRef.current);
    };
  }, [sessionId]);

  // 当用户从其他标签页/页面切回时，自动刷新消息列表
  // 确保后台已落库的 AI 回复（生成中切页面的场景）不会丢失
  const visibilityRefreshRef = useRef<() => void>(() => {});
  useEffect(() => {
    visibilityRefreshRef.current = refreshMessages;
  });
  useEffect(() => {
    function handleVisibilityChange() {
      if (document.visibilityState === 'visible' && !isGenerating && Number.isFinite(sessionId)) {
        visibilityRefreshRef.current();
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, [sessionId, isGenerating]);

  // 文件预览 URL 管理（防止内存泄漏）
  const fileUrls = useMemo(() => files.map((f) => URL.createObjectURL(f)), [files]);
  useEffect(() => {
    return () => { fileUrls.forEach((u) => URL.revokeObjectURL(u)); };
  }, [fileUrls]);

  const localConfigQuery = useQuery({
    queryKey: ['local-config'],
    queryFn: api.getLocalConfig,
    staleTime: 20_000,
  });

  const sessionDetailQuery = useQuery({
    queryKey: ['session', sessionId],
    queryFn: () => api.getSession(sessionId),
    enabled: Number.isFinite(sessionId),
  });

  const updateSessionThinkMutation = useMutation({
    mutationFn: (enabled: boolean) => api.updateSession(sessionId, { think_max_enabled: enabled }),
    onSuccess: async (_data, enabled) => {
      await queryClient.invalidateQueries({ queryKey: ['session', sessionId] });
      showToast(enabled ? '本会话已启用思考/Max' : '本会话已关闭思考/Max', 'success');
    },
    onError: (e) => showToast(toastErrorMessage(e), 'error'),
  });

  const participantsQuery = useQuery({
    queryKey: ['participants', sessionId],
    queryFn: () => api.listParticipants(sessionId),
    enabled: Number.isFinite(sessionId),
  });

  const charactersQuery = useQuery({
    queryKey: ['characters'],
    queryFn: api.listCharacters,
    enabled: showChatMenu,
    staleTime: 30_000,
  });

  const participantCharacterIds = participantsQuery.data?.map((p) => p.character.id) ?? [];

  const expressionQueries = useQueries({
    queries: participantCharacterIds.map((charId) => ({
      queryKey: ['expressions', charId],
      queryFn: () => api.listExpressions(charId),
      enabled: charId > 0,
      staleTime: 30000,
    })),
  });

  const expressionMap = useMemo(() => {
    const map: Record<number, any[]> = {};
    participantCharacterIds.forEach((charId, idx) => {
      const data = expressionQueries[idx]?.data;
      if (data && data.length > 0) map[charId] = data;
    });
    return map;
  }, [participantCharacterIds, expressionQueries]);

  const worldTemplatesQuery = useQuery({
    queryKey: ['world-templates'],
    queryFn: () => api.listWorldTemplates(),
  });
  const encyclopediasQuery = useQuery({
    queryKey: ['encyclopedias'],
    queryFn: api.listEncyclopedias,
    enabled: showRightPanel && rightPanelTab === 'config',
  });

  const memoryStateQuery = useQuery({
    queryKey: ['character-states', sessionId],
    queryFn: () => api.listCharacterStates(sessionId),
    enabled: Number.isFinite(sessionId) && showRightPanel && rightPanelTab === 'states',
  });

  const memorySegmentsQuery = useQuery({
    queryKey: ['memory-segments', sessionId, selectedBranchId],
    queryFn: () => api.listMemorySegments(sessionId, selectedBranchId),
    enabled: Number.isFinite(sessionId) && showRightPanel && rightPanelTab === 'memory',
    refetchInterval: (query) => query.state.status === 'error' ? false : 10_000,
  });
  const memoryCompactionQuery = useQuery({
    queryKey: ['memory-compaction', sessionId, selectedBranchId],
    queryFn: () => api.getMemoryCompactionStatus(sessionId, selectedBranchId),
    enabled: Number.isFinite(sessionId) && showRightPanel && rightPanelTab === 'memory',
    retry: false,
    refetchInterval: (query) => query.state.status === 'error' ? false : query.state.data?.running ? 3_000 : 10_000,
  });
  const continueMemoryMutation = useMutation({
    mutationFn: ({ currentSessionId, branchId }: { currentSessionId: number; branchId: string }) =>
      api.continueMemoryCompaction(currentSessionId, branchId),
    onSuccess: (result, { currentSessionId, branchId }) => {
      if (result.started) {
        queryClient.setQueryData(['memory-compaction', currentSessionId, branchId], { ...result, running: true });
        showToast('已继续后台记忆整理', 'success');
      } else {
        void queryClient.invalidateQueries({ queryKey: ['memory-compaction', currentSessionId, branchId] });
      }
    },
    onError: (error) => showToast(toastErrorMessage(error), 'error'),
  });

  const memoryCorrectionsQuery = useQuery({
    queryKey: ['memory-corrections', sessionId, selectedBranchId],
    queryFn: () => api.listMemoryCorrections(sessionId, selectedBranchId),
    enabled: Number.isFinite(sessionId) && showRightPanel && rightPanelTab === 'memory',
  });

  const tokenUsageQuery = useQuery({
    queryKey: ['token-usage', sessionId, selectedBranchId],
    queryFn: () => api.getTokenUsage(sessionId, selectedBranchId || 'main'),
    enabled: Number.isFinite(sessionId) && showRightPanel,
    retry: false,
    refetchInterval: (query) => query.state.status === 'error' ? false : 10_000,
  });

  const promptTraceQuery = useQuery({
    queryKey: ['prompt-trace', sessionId, selectedBranchId],
    queryFn: () => api.get(`/sessions/${sessionId}/prompt-trace/latest?branch_id=${encodeURIComponent(selectedBranchId)}`),
    enabled: Boolean(sessionId) && showRightPanel && rightPanelTab === 'trace',
    retry: false,
    refetchInterval: (query) => query.state.status === 'error' ? false : 30_000,
  });

  const branchesQuery = useQuery({
    queryKey: ['session-branches', sessionId],
    queryFn: () => api.listSessionBranches(sessionId),
    enabled: Number.isFinite(sessionId),
  });

  const eventNodesQuery = useInfiniteQuery({
    queryKey: ['session-event-tree', sessionId, selectedBranchId],
    queryFn: ({ pageParam }) => api.listSessionEventTree(sessionId, selectedBranchId || 'main', pageParam),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (lastPage) => lastPage.next_cursor ?? undefined,
    enabled: Number.isFinite(sessionId) && showRightPanel && rightPanelTab === 'events',
    refetchInterval: (query) => query.state.status !== 'error' && query.state.data?.pages.length === 1 ? 10_000 : false,
  });

  const {
    hasMore,
    hasNewer,
    flatMessages,
    messagesLoading,
    messagesLoadingMore,
    messagesLoadingNewer,
    messagesLocating,
    messagesError,
    loadMoreError,
    loadNewerError,
    loadMore,
    loadNewer,
    loadAroundMessage,
    jumpToLatest,
    ensureLatestMessages,
    retryMessages,
    appendOptimisticUserMessage,
    confirmUserMessage,
    appendPlaceholderMessage,
    applyStreamingDelta,
    finalizePlaceholderMessage,
    removeStreamingPlaceholder,
    reloadMessages,
    switchMessagesToBranch,
    refreshMessages,
    applyMessageContext,
  } = useSessionMessages(sessionId, selectedBranchId);

  async function activateBranch(branchId: string, isOwnerCurrent: () => boolean = () => true) {
    const normalizedBranchId = normalizeBranchId(branchId);
    if (normalizedBranchId === selectedBranchRef.current) return refreshMessages();

    const requestId = ++branchSwitchRequestRef.current;
    setSwitchingBranchId(normalizedBranchId);
    setBranchSwitchFailure(null);
    const result = await switchMessagesToBranch(normalizedBranchId);
    if (!isOwnerCurrent()) return { ...result, ok: false, superseded: true };
    if (requestId !== branchSwitchRequestRef.current) return result;

    setSwitchingBranchId(null);
    if (result.ok) {
      setSelectedBranchId(normalizedBranchId);
    } else if (!result.superseded) {
      setBranchSwitchFailure({
        branchId: normalizedBranchId,
        message: toastErrorMessage(result.error),
      });
    }
    return result;
  }

  const {
    worldPrompt,
    setWorldPrompt,
    worldTemplateId,
    setWorldTemplateId,
    encyclopediaId,
    setEncyclopediaId,
    gameplayMode,
    setGameplayMode,
    narratorEnabled,
    setNarratorEnabled,
    narratorName,
    setNarratorName,
    choiceGenerationEnabled,
    setChoiceGenerationEnabled,
    maxChoiceCount,
    setMaxChoiceCount,
    antiCheatEnabled,
    setAntiCheatEnabled,
    antiCheatPrompt,
    setAntiCheatPrompt,
    saveWorld,
    prepareWorldForGeneration,
    worldReady,
    worldLoading,
    worldLoadError,
    retryWorldLoad,
    worldSaving,
    worldSaveError,
    isWorldDirty,
  } = useSessionWorld(sessionId);

  const messageEditDirty = editMessageId !== null && hasUnsavedEditMessage(editContent, editOriginalContent);
  const worldNavigationBlocker = useBlocker(({ currentLocation, nextLocation }) =>
    (isWorldDirty || messageEditDirty || editSavePending) && currentLocation.pathname !== nextLocation.pathname,
  );

  useEffect(() => {
    if (worldNavigationBlocker.state !== 'blocked') return;
    if (editSavePending) {
      worldNavigationBlocker.reset();
      return;
    }
    // 消息编辑使用原面板确认，避免叠加弹窗及焦点陷阱。
    if (messageEditDirty) return;
    let active = true;
    void confirmModal(
      '世界设置尚未保存',
      '离开当前会话会丢弃这些修改。请选择取消并先保存，或确认放弃修改后离开。',
      'warning',
    ).then((leave) => {
      if (!active || worldNavigationBlocker.state !== 'blocked') return;
      if (leave) worldNavigationBlocker.proceed();
      else worldNavigationBlocker.reset();
    });
    return () => { active = false; };
  }, [worldNavigationBlocker, messageEditDirty, editSavePending]);

  useBeforeUnload(useCallback((event) => {
    if (!isWorldDirty && !messageEditDirty && !editSavePending) return;
    event.preventDefault();
    event.returnValue = '';
  }, [isWorldDirty, messageEditDirty, editSavePending]));

  async function handleSaveWorld() {
    try {
      await saveWorld();
      showToast('世界配置已保存', 'success');
    } catch (error) {
      showToast(toastErrorMessage(error), 'error');
    }
  }

  const openRightPanel = useCallback((tab?: RightPanelTab) => {
    const active = document.activeElement instanceof HTMLElement && document.activeElement !== document.body
      ? document.activeElement
      : null;
    rightPanelReturnFocusRef.current = active ?? rightPanelToggleRef.current;
    if (tab) setRightPanelTab(tab);
    setShowRightPanel(true);
  }, []);

  const closeRightPanel = useCallback((restoreFocus = true) => {
    setShowRightPanel(false);
    const preferredTarget = rightPanelReturnFocusRef.current;
    rightPanelReturnFocusRef.current = null;
    if (!restoreFocus) return;
    window.requestAnimationFrame(() => {
      const target = preferredTarget?.isConnected ? preferredTarget : rightPanelToggleRef.current;
      target?.focus();
    });
  }, []);

  const requestCloseRightPanel = useCallback(() => {
    closeRightPanel();
  }, [closeRightPanel]);

  useEffect(() => {
    if (showRightPanel) return;
    rightPanelReturnFocusRef.current = null;
  }, [showRightPanel]);

  const openChatMenu = useCallback(() => {
    const active = document.activeElement instanceof HTMLElement && document.activeElement !== document.body
      ? document.activeElement
      : null;
    chatMenuReturnFocusRef.current = active ?? chatMenuToggleRef.current;
    setShowChatMenu(true);
  }, []);

  const closeChatMenu = useCallback((restoreFocus = true) => {
    setShowChatMenu(false);
    const preferredTarget = chatMenuReturnFocusRef.current;
    chatMenuReturnFocusRef.current = null;
    if (!restoreFocus) return;
    window.requestAnimationFrame(() => {
      const target = preferredTarget?.isConnected ? preferredTarget : chatMenuToggleRef.current;
      target?.focus();
    });
  }, []);

  const openBranchTree = useCallback(() => {
    const active = document.activeElement instanceof HTMLElement && document.activeElement !== document.body
      ? document.activeElement
      : null;
    branchTreeReturnFocusRef.current = active ?? chatMenuToggleRef.current;
    setShowBranchTree(true);
  }, []);

  const closeBranchTree = useCallback((restoreFocus = true) => {
    setShowBranchTree(false);
    const preferredTarget = branchTreeReturnFocusRef.current;
    branchTreeReturnFocusRef.current = null;
    if (!restoreFocus) return;
    window.requestAnimationFrame(() => {
      const target = preferredTarget?.isConnected ? preferredTarget : chatMenuToggleRef.current;
      target?.focus();
    });
  }, []);

  useEffect(() => {
    if (showChatMenu) return;
    chatMenuReturnFocusRef.current = null;
  }, [showChatMenu]);

  useEffect(() => {
    if (!showBranchTree) {
      branchTreeReturnFocusRef.current = null;
      return undefined;
    }
    const dialog = branchTreeRef.current;
    const frame = window.requestAnimationFrame(() => {
      dialog?.querySelector<HTMLElement>('[data-branch-tree-close]')?.focus();
    });
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        closeBranchTree();
        return;
      }
      if (event.key !== 'Tab' || !dialog) return;
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href], [tabindex]:not([tabindex="-1"])',
      )).filter((element) => (
        element.getAttribute('aria-hidden') !== 'true'
        && element.getClientRects().length > 0
        && window.getComputedStyle(element).visibility !== 'hidden'
      ));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!dialog.contains(document.activeElement)) {
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
  }, [closeBranchTree, showBranchTree]);

  useEffect(() => {
    const ids = participantsQuery.data?.map((item) => item.character.id) ?? [];
    setSelectedCharacters(ids);
  }, [participantsQuery.data]);

  useEffect(() => {
    if (!branchesQuery.isSuccess || branchesQuery.isFetching) return;
    const branchIds = new Set((branchesQuery.data ?? []).map((item) => item.branch_id));
    if (selectedBranchId === 'main') return;
    if (!branchIds.has(selectedBranchId)) {
      void activateBranch('main');
    }
  }, [branchesQuery.data, branchesQuery.isFetching, branchesQuery.isSuccess, selectedBranchId, setSelectedBranchId]);

  /** 驱动 MessageList 在贴底时对流式增量追加贴底（条数不变、末条 content 变长） */
  const messageScrollNudgeKey = useMemo(() => {
    const last = flatMessages[flatMessages.length - 1];
    if (!last) return `n:${flatMessages.length}:g:${isGenerating ? 1 : 0}`;
    return `id:${last.id}:len:${(last.content ?? '').length}:g:${isGenerating ? 1 : 0}`;
  }, [flatMessages, isGenerating]);

  useEffect(() => {
    setMessageFocusRequest(null);
    const pendingFocus = pendingBranchMessageFocusRef.current;
    if (pendingFocus?.branchId !== selectedBranchId) return;
    pendingBranchMessageFocusRef.current = null;
    setMessageFocusRequest({
      messageId: pendingFocus.messageId,
      requestId: ++searchFocusRequestIdRef.current,
      align: 'center',
      moveKeyboardFocus: true,
    });
  }, [sessionId, selectedBranchId]);

  function focusMessage(
    messageId: number,
    align: 'center' | 'end' = 'center',
    moveKeyboardFocus = false,
  ) {
    setMessageFocusRequest({
      messageId,
      requestId: ++searchFocusRequestIdRef.current,
      align,
      moveKeyboardFocus,
    });
  }

  async function locateMemorySource(messageId: number) {
    try {
      const located = await loadAroundMessage(messageId);
      if (!located) throw new Error('当前故事线中找不到这条来源消息');
      focusMessage(messageId);
      closeRightPanel(false);
    } catch (error) {
      showToast(`来源定位失败：${toastErrorMessage(error)}`, 'error');
    }
  }

  async function loadOlderWindow() {
    const anchorId = await loadMore();
    if (anchorId !== null) focusMessage(anchorId);
  }

  async function loadNewerWindow() {
    const anchorId = await loadNewer();
    if (anchorId !== null) focusMessage(anchorId);
  }

  async function returnToLatest() {
    const result = await jumpToLatest();
    if (result.ok && result.latestMessageId !== null) focusMessage(result.latestMessageId, 'end');
  }

  async function switchBranch(branchId: string) {
    if (branchId === selectedBranchRef.current) {
      if (showBranchTree) closeBranchTree();
      setShowChatMenu(false);
      return;
    }
    if (generationGateRef.current) {
      showToast('回复生成中，请先停止或等待完成后再切换故事线', 'warn');
      return;
    }
    if (switchingBranchId) {
      showToast('正在打开另一条故事线，请稍候', 'warn');
      return;
    }
    const result = await activateBranch(branchId || 'main');
    if (result.ok) {
      if (showBranchTree) closeBranchTree();
      setShowChatMenu(false);
    }
  }

  async function goToSearchHit(hit: MessageSearchHit) {
    setShowChatMenu(false);
    // Search is scoped to the selected branch context. Ancestor messages keep
    // their original branch_id but remain visible here, so do not switch away.
    const ownerSession = sessionId;
    const ownerBranch = selectedBranchRef.current;
    const ownerQuery = sessionSearchDraft;
    setSearchLocateFailure(null);
    setLocatingMessageId(hit.id);
    setSelectedSearchHit(hit);
    try {
      const located = await loadAroundMessage(hit.id);
      if (!located) throw new Error('搜索结果已变化，请重新搜索');
      if (sessionIdRef.current !== ownerSession || selectedBranchRef.current !== ownerBranch) return;
      focusMessage(hit.id);
      closeRightPanel(false);
      if (showBranchTree) closeBranchTree();
      setSearchView('reading');
    } catch (error) {
      if (sessionIdRef.current === ownerSession && selectedBranchRef.current === ownerBranch) {
        setSearchLocateFailure({ hit, message: toastErrorMessage(error), sessionId: ownerSession, branchId: ownerBranch, query: ownerQuery });
      }
    } finally {
      setLocatingMessageId(null);
    }
  }

  const {
    speakerPlanReason,
    plannedSpeakerNames,
    pendingRoundSpeakers,
    previewSpeakerPlan,
    applySpeakerPlanEvent,
    onCharacterMessageEnded,
    clearSpeakerPlan,
  } = useSpeakerPlan(sessionId, participantsQuery.data);

  const updateTalkativenessMutation = useMutation({
    mutationFn: ({ characterId, talkativeness }: { characterId: number; talkativeness: number }) =>
      api.updateParticipantTalkativeness(sessionId, characterId, talkativeness),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['participants', sessionId] }),
    onError: (e) => showToast(toastErrorMessage(e), 'error'),
  });

  const addParticipantMutation = useMutation({
    mutationFn: (characterId: number) => api.addParticipant(sessionId, characterId),
    onSuccess: () => {
      showToast('角色已加入本会话', 'success');
      queryClient.invalidateQueries({ queryKey: ['participants', sessionId] });
    },
    onError: (e) => showToast(toastErrorMessage(e), 'error'),
  });

  const createMemoryCorrectionMutation = useMutation({
    mutationFn: (body: { content: string; branch_id: string | null; source_message_id: number | null }) =>
      api.createMemoryCorrection(sessionId, body),
    onSuccess: () => Promise.all([
      queryClient.invalidateQueries({ queryKey: ['memory-corrections', sessionId] }),
      queryClient.invalidateQueries({ queryKey: ['prompt-trace', sessionId] }),
    ]),
  });
  const updateMemoryCorrectionMutation = useMutation({
    mutationFn: ({ id, body }: { id: number; body: { content: string; branch_id: string | null; source_message_id: number | null } }) =>
      api.updateMemoryCorrection(sessionId, id, body),
    onSuccess: () => Promise.all([
      queryClient.invalidateQueries({ queryKey: ['memory-corrections', sessionId] }),
      queryClient.invalidateQueries({ queryKey: ['prompt-trace', sessionId] }),
    ]),
  });
  const deleteMemoryCorrectionMutation = useMutation({
    mutationFn: (id: number) => api.deleteMemoryCorrection(sessionId, id),
    onSuccess: () => Promise.all([
      queryClient.invalidateQueries({ queryKey: ['memory-corrections', sessionId] }),
      queryClient.invalidateQueries({ queryKey: ['prompt-trace', sessionId] }),
    ]),
  });

  const removeParticipantMutation = useMutation({
    mutationFn: (characterId: number) => api.removeParticipant(sessionId, characterId),
    onSuccess: () => {
      showToast('角色已移出本会话', 'success');
      queryClient.invalidateQueries({ queryKey: ['participants', sessionId] });
    },
    onError: (e) => showToast(toastErrorMessage(e), 'error'),
  });

  async function requestRemoveParticipant(characterId: number, characterName: string) {
    if (removeParticipantMutation.isPending) return;
    const confirmed = await confirmModal(
      '移出会话角色',
      `确定将“${characterName}”移出当前会话吗？角色资料和已有消息不会被删除。`,
      'warning',
    );
    if (confirmed) removeParticipantMutation.mutate(characterId);
  }

  const tavernImportFileRef = useRef<HTMLInputElement>(null);

  const importTavernChatMutation = useMutation({
    mutationFn: ({ file, characterId, branchId }: { file: File; characterId: number; branchId: string }) =>
      api.importTavernChat(sessionId, characterId, file, branchId),
    onSuccess: (data, variables) => {
      const branchLabel = findStoryLineDisplayLabel(branchesQuery.data ?? [], data.branch_id);
      showToast(`已导入“${branchLabel}”故事线 ${data.imported} 条`, 'success');
      if (selectedBranchId === variables.branchId) reloadMessages();
      queryClient.invalidateQueries({ queryKey: ['session-branches', sessionId] });
      queryClient.invalidateQueries({ queryKey: ['sessions'] });
    },
    onError: (e) => showToast(toastErrorMessage(e), 'error'),
  });

  function openTavernChatImport() {
    if (isGenerating) {
      showToast('请先停止当前回复，再导入聊天记录', 'warn');
      return;
    }
    if (participantsQuery.data?.[0]?.character?.id == null) {
      showToast('请先为本会话添加至少一名角色，再导入聊天记录', 'warn');
      return;
    }
    tavernImportFileRef.current?.click();
  }

  async function exportCurrentBranch() {
    if (isExportingChat) return;
    setIsExportingChat(true);
    try {
      await api.downloadSessionChatHtml(sessionId, selectedBranchId);
      showToast('已下载当前故事线对话 HTML', 'success');
    } catch (e) {
      showToast(toastErrorMessage(e), 'error');
    } finally {
      setIsExportingChat(false);
    }
  }

  async function exportSessionArchive() {
    if (isExportingSessionArchive) return;
    setIsExportingSessionArchive(true);
    try {
      await api.openSessionExport(sessionId);
      showToast('已下载完整会话包，可在另一台墨境中导入为新会话', 'success');
    } catch (e) {
      showToast(toastErrorMessage(e), 'error');
    } finally {
      setIsExportingSessionArchive(false);
    }
  }

  async function finishMessageDeletion() {
    if (!deleteTarget) return;
    const { sessionId: ownerSession, branchId: ownerBranch, message } = deleteTarget;
    const neighbors = flatMessages.filter((item) => item.id !== message.id);
    const neighbor = [...neighbors].reverse().find((item) => item.id < message.id) ?? neighbors[0];
    setDeleteTarget((current) => current === deleteTarget ? null : current);
    if (sessionIdRef.current !== ownerSession || selectedBranchRef.current !== ownerBranch) return;
    showToast('消息已删除', 'success');
    try {
      if (neighbor && await loadAroundMessage(neighbor.id)) { focusMessage(neighbor.id); return; }
    } catch { /* Fall back to the existing retryable first-page loader. */ }
    if (sessionIdRef.current === ownerSession && selectedBranchRef.current === ownerBranch) await reloadMessages();
  }

  const editMessageMutation = useMutation({
    mutationFn: ({ messageId, content, branchId }: { messageId: number; content: string; branchId: string }) =>
      api.updateMessage(sessionId, messageId, content, branchId),
  });

  const createBranchMutation = useMutation({
    mutationFn: (source: { messageId: number; label: string; parentBranchId: string }) => {
      const branchToken = Date.now().toString(36);
      const branchId = `branch_${source.messageId}_${branchToken}`;
      return api.createSessionBranch(sessionId, {
        source_message_id: source.messageId,
        branch_id: branchId,
        label: source.label,
        parent_branch_id: source.parentBranchId,
      });
    },
    onError: (e) => showToast(toastErrorMessage(e), 'error'),
  });

  async function runStreamGeneration(payload: {
    branchId: string;
    userMessage?: string;
    filesToSend?: File[];
    quoteMessage?: ChatQuoteDraft | null;
    streamIntoCurrentList?: boolean;
    narratorOnly?: boolean;
    useStoryModeNarrator?: boolean;
  }) {
    const {
      branchId,
      userMessage,
      filesToSend = [],
      quoteMessage = null,
      streamIntoCurrentList = true,
      narratorOnly = false,
      useStoryModeNarrator = false,
    } = payload;
    const outboundUserMessage = userMessage?.trim()
      ? (quoteMessage ? buildQuotePrefix(quoteMessage) + userMessage.trim() : userMessage.trim())
      : undefined;
    const submittedQuoteRevision = quoteRevisionRef.current;
    const submittedInputRevision = inputDraftRevisionRef.current;
    let outboundPersisted = false;
    let generationRequestStarted = false;
    let activeClientMessageId: string | null = null;
    let submittedMessageId: number | undefined;
    const markOutboundPersisted = () => {
      if (outboundPersisted) return;
      outboundPersisted = true;
      if (activeClientMessageId) {
        clearPendingChatSend(sessionId, activeClientMessageId);
        setPendingSend((current) => current?.clientMessageId === activeClientMessageId ? null : current);
      }
      if (userMessage !== undefined && sessionIdRef.current === sessionId
        && quoteRevisionRef.current === submittedQuoteRevision
        && (quotingMessage?.id ?? null) === (quoteMessage?.id ?? null)
        && (quotingMessage?.content ?? null) === (quoteMessage?.content ?? null)) setQuotingMessage(null);
      if (
        userMessage !== undefined
        && sessionIdRef.current === sessionId
        && inputDraftRevisionRef.current === submittedInputRevision
        && inputDraftMirrorRef.current === userMessage
      ) {
        inputDraftRevisionRef.current += 1;
        setInput('');
        inputDraftMirrorRef.current = '';
        draftInputBySessionRef.current[sessionId] = '';
        clearChatDraft(sessionId);
      }
      if (filesToSend.length > 0) {
        setFiles((current) => removeSelectedFiles(current, filesToSend));
      }
    };

    const abortController = new AbortController();
    let replySaved = false;
    abortRef.current = abortController;
    setIsGenerating(true);
    clearSpeakerPlan();

    try {
      const runtimeWorld = await prepareWorldForGeneration();
      const effectiveNarratorOnly = narratorOnly
        || (useStoryModeNarrator && runtimeWorld.gameplay_mode === '小说创作');
      if (streamIntoCurrentList) {
        const readyForLiveUpdates = await ensureLatestMessages();
        if (!readyForLiveUpdates.ok) {
          throw new Error('无法回到最新消息，请检查本机服务后重试');
        }
        if (readyForLiveUpdates.latestMessageId !== null) {
          focusMessage(readyForLiveUpdates.latestMessageId, 'end');
        }
      }
      generationRequestStarted = true;
      if (filesToSend.length > 0) {
        const savedMessage = await api.createUserMessageWithFiles(sessionId, {
          content: outboundUserMessage ?? '',
          files: filesToSend,
          branch_id: branchId,
        }, abortController.signal);
        submittedMessageId = savedMessage.id;
        markOutboundPersisted();
      } else if (outboundUserMessage) {
        const existingSend = loadPendingChatSend(sessionId)
          ?? (pendingSend?.sessionId === sessionId ? pendingSend : null);
        if (existingSend && (existingSend.branchId !== branchId || existingSend.content !== outboundUserMessage)) {
          throw new Error('上次消息的发送结果尚未确认，请先核对或重试该条消息。');
        }
        const send = existingSend ?? {
          clientMessageId: crypto.randomUUID(), sessionId, branchId,
          content: outboundUserMessage, input: userMessage ?? '', quote: quoteMessage,
        };
        activeClientMessageId = send.clientMessageId;
        if (!existingSend && !savePendingChatSend(send)) {
          showToast('浏览器无法保存发送恢复信息，刷新前请核对对话记录。', 'warn');
        }
        setPendingSend(send);
        const optimisticId = !existingSend && streamIntoCurrentList
          ? appendOptimisticUserMessage(outboundUserMessage) : undefined;
        let savedMessage: Message;
        try {
          savedMessage = await api.addUserMessage(sessionId, outboundUserMessage, branchId, send.clientMessageId);
        } catch (sendError) {
          try {
            savedMessage = await api.getUserMessageByClientId(sessionId, send.clientMessageId);
            if (savedMessage.content !== outboundUserMessage || savedMessage.branch_id !== branchId) throw sendError;
          } catch {
            throw sendError;
          }
        }
        if (streamIntoCurrentList && sessionIdRef.current === sessionId && selectedBranchRef.current === branchId) {
          confirmUserMessage(savedMessage, optimisticId);
        }
        submittedMessageId = savedMessage.id;
        markOutboundPersisted();
        if (abortController.signal.aborted) throw new DOMException('已停止生成', 'AbortError');
      }

      await api.streamGenerate(
        sessionId,
        {
          user_message: undefined,
          existing_user_message_id: submittedMessageId,
          character_ids: effectiveNarratorOnly ? [] : resolveStreamCharacterIds(),
          include_narrator: effectiveNarratorOnly || runtimeWorld.narrator_enabled,
          narrator_only: effectiveNarratorOnly,
          auto_select_speakers: !effectiveNarratorOnly && speakerTurnMode === 'auto',
          max_auto_speakers: maxAutoSpeakers,
          branch_id: branchId,
        },
        (event) => {
          const type = String(event.type ?? '');
          if (type === 'session' && outboundUserMessage) {
            markOutboundPersisted();
          }
          if (type === 'error') {
            const streamKey = typeof event.stream_key === 'string' ? event.stream_key : undefined;
            const savedMessage = event.saved_message;
            if (event.reply_saved === true && savedMessage && typeof savedMessage === 'object') {
              replySaved = true;
              if (streamIntoCurrentList) finalizePlaceholderMessage(savedMessage as Message, streamKey);
            } else {
              removeStreamingPlaceholder(streamKey);
            }
            throw new Error(toastErrorMessage(event.message ?? '发生错误'));
          }
          if (type === 'message_end') replySaved = true;
          if (!streamIntoCurrentList) return;
          if (sessionIdRef.current !== sessionId || selectedBranchRef.current !== branchId) return;
          if (type === 'speaker_plan') {
            const ids = Array.isArray(event.character_ids)
              ? (event.character_ids as number[]).map((x) => Number(x))
              : [];
            applySpeakerPlanEvent(String(event.reason ?? ''), ids);
          }
          if (type === 'message_start') {
            const rawCharacterId = event.character_id as number | null | undefined;
            const streamKey = typeof event.stream_key === 'string' ? event.stream_key : undefined;
            appendPlaceholderMessage(
              buildTempMessage(rawCharacterId ?? null, String(event.character_name ?? '系统'), branchId, streamKey),
            );
          }
          if (type === 'delta') {
            applyStreamingDelta(
              typeof event.stream_key === 'string' ? event.stream_key : undefined,
              (event.character_id as number | null | undefined) ?? null,
              String(event.delta ?? ''),
            );
          }
          if (type === 'message_end') {
            const ended = event.message as Message;
            finalizePlaceholderMessage(ended, typeof event.stream_key === 'string' ? event.stream_key : undefined);
            onCharacterMessageEnded(ended.character_id);
          }
        },
        abortController.signal,
      );
    } catch (error) {
      // SSE errors/aborts must not be reported as a successful send. Re-read the
      // database so optimistic and partial placeholders cannot remain on screen.
      if (generationRequestStarted) reloadMessages();
      if (outboundPersisted && !replySaved) {
        setRetryReplyBranchId(branchId);
      } else if (replySaved) setRetryReplyBranchId(null);
      if (outboundPersisted || replySaved) void Promise.allSettled([
        queryClient.invalidateQueries({ queryKey: ['sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['session-branches', sessionId] }),
      ]);
      throw error;
    } finally {
      setIsGenerating(false);
      if (abortRef.current === abortController) abortRef.current = null;
    }
  }

  const sendMutation = useMutation({
    mutationFn: async (variables: { sessionId: number; branchId: string; userMessage: string; filesToSend: File[]; quoteMessage: ChatQuoteDraft | null }) =>
      runStreamGeneration({
        branchId: variables.branchId,
        userMessage: variables.userMessage,
        filesToSend: variables.filesToSend,
        quoteMessage: variables.quoteMessage,
        streamIntoCurrentList: true,
        useStoryModeNarrator: true,
      }),
    onSuccess: async (_result, variables) => {
      setRetryReplyBranchId(null);
      setFiles((current) => removeSelectedFiles(current, variables.filesToSend));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['participants', sessionId] }),
        queryClient.invalidateQueries({ queryKey: ['character-states', sessionId] }),
        queryClient.invalidateQueries({ queryKey: ['session-branches', sessionId] }),
        queryClient.invalidateQueries({ queryKey: ['prompt-trace', variables.sessionId, variables.branchId] }),
      ]);
      refreshMessages();
    },
    onError: (error) => {
      if (!isAbortError(error)) showToast(toastErrorMessage(error), 'error');
    },
    onSettled: releaseGeneration,
  });

  const generateBranchReplyMutation = useMutation({
    mutationFn: async (payload: { branchId: string; streamIntoCurrentList?: boolean }) =>
      runStreamGeneration({
        branchId: payload.branchId,
        userMessage: undefined,
        streamIntoCurrentList: payload.streamIntoCurrentList ?? payload.branchId === selectedBranchId,
      }),
    onSuccess: async (_value, variables) => {
      setRetryReplyBranchId(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['character-states', sessionId] }),
        queryClient.invalidateQueries({ queryKey: ['session-branches', sessionId] }),
        queryClient.invalidateQueries({ queryKey: ['prompt-trace', sessionId, variables.branchId] }),
      ]);
      await activateBranch(variables.branchId);
    },
    onError: (error) => {
      if (!isAbortError(error)) showToast(toastErrorMessage(error), 'error');
    },
    onSettled: releaseGeneration,
  });

  async function saveEditedMessage(messageId: number, content: string) {
    const original = flatMessages.find((message) => message.id === messageId);
    if (!original) {
      setEditSaveError('当前页面已找不到这条消息，请关闭编辑后重新定位');
      return;
    }
    if (!reserveGeneration()) {
      setEditSaveError('当前有操作正在进行，请稍后重试');
      return;
    }
    const owner = Symbol('message-edit');
    editSaveOwnerRef.current = owner;
    const ownerSession = sessionId;
    const ownerBranch = selectedBranchRef.current;
    const isCurrent = () => editSaveOwnerRef.current === owner && sessionIdRef.current === ownerSession;
    setEditSaveError(null);
    setEditSavePending(true);
    let regenerationStarted = false;
    let editCommitted = false;
    try {
      const replacement = await editMessageMutation.mutateAsync({
        messageId,
        content,
        branchId: ownerBranch,
      });
      editCommitted = true;
      if (!isCurrent() || selectedBranchRef.current !== ownerBranch) return;
      setEditMessageId(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['session-branches', sessionId] }),
      ]);
      if (!isCurrent() || selectedBranchRef.current !== ownerBranch) return;
      pendingBranchMessageFocusRef.current = {
        branchId: replacement.branch_id,
        messageId: replacement.id,
      };
      const activation = await activateBranch(replacement.branch_id, isCurrent);
      if (!isCurrent()) return;
      if (!activation.ok) {
        pendingBranchMessageFocusRef.current = null;
        showToast('编辑故事线已经保存，但暂时无法打开；当前故事线保持不变，可从故事线入口重试', 'warn');
        return;
      }
      if (replacement.speaker_type === 'user') {
        showToast('已创建编辑故事线，正在重新生成角色回复', 'success');
        generateBranchReplyMutation.mutate({ branchId: replacement.branch_id, streamIntoCurrentList: true });
        regenerationStarted = true;
      } else {
        showToast('已创建编辑故事线，原剧情保持不变', 'success');
      }
    } catch (error) {
      if (!isCurrent()) return;
      pendingBranchMessageFocusRef.current = null;
      if (editCommitted) showToast('编辑故事线已保存，后续加载失败，请从故事线入口重新打开', 'warn');
      else setEditSaveError(toastErrorMessage(error));
    } finally {
      if (editSaveOwnerRef.current === owner) {
        editSaveOwnerRef.current = null;
        setEditSavePending(false);
        if (!regenerationStarted) releaseGeneration();
      }
    }
  }

  async function playVoice(message: Message) {
    try {
      const result = await api.synthesizeMessage(message.id);
      setPlayingClips(result.clips);
      for (const clip of result.clips) {
        await new Promise<void>((resolve, reject) => {
          const audio = new Audio(api.storageUrl(clip.url));
          audio.onended = () => resolve();
          audio.onerror = () => reject(new Error('音频播放失败'));
          audio.play().catch(reject);
        });
      }
    } catch (e) {
      showToast(`语音合成或播放失败：${toastErrorMessage(e)}`, 'error');
    }
  }

  function handleSend(event: FormEvent) {
    event.preventDefault();
    if (!input.trim() && files.length === 0) {
      showToast('请先输入消息或选择附件后再发送', 'warn');
      return;
    }
    const unresolvedSend = loadPendingChatSend(sessionId)
      ?? (pendingSend?.sessionId === sessionId ? pendingSend : null);
    const outbound = input.trim()
      ? (quotingMessage ? buildQuotePrefix(quotingMessage) + input.trim() : input.trim())
      : '';
    if (unresolvedSend && (files.length > 0 || unresolvedSend.branchId !== selectedBranchId
      || unresolvedSend.content !== outbound)) {
      showToast('上次发送结果未确认，请先检查或重试该条消息。', 'warn');
      return;
    }
    const needsParticipant = !participantsQuery.isLoading
      && !participantsQuery.isError
      && (participantsQuery.data?.length ?? 0) === 0
      && gameplayMode !== '小说创作'
      && !narratorEnabled;
    if (needsParticipant) {
      openRightPanel('participants');
      showToast('先为会话添加一名角色，再开始对话', 'warn');
      return;
    }
    if (!reserveGeneration()) return;
    setRetryReplyBranchId(null);
    sendMutation.mutate({
      sessionId,
      branchId: selectedBranchId,
      userMessage: input,
      filesToSend: [...files],
      quoteMessage: quotingMessage,
    });
  }

  async function checkPendingSend() {
    const send = pendingSend;
    if (!send || send.sessionId !== sessionId) return;
    setPendingSendChecking(true);
    try {
      const saved = await api.getUserMessageByClientId(sessionId, send.clientMessageId);
      if (saved.content !== send.content || saved.branch_id !== send.branchId) {
        throw new Error('已保存的消息与待确认内容不一致，请检查对话记录。');
      }
      clearPendingChatSend(sessionId, send.clientMessageId);
      setPendingSend((current) => current?.clientMessageId === send.clientMessageId ? null : current);
      if (inputDraftMirrorRef.current === send.input && quotingMessage?.id === (send.quote?.id ?? undefined)) {
        updateInput('');
        setQuotingMessage(null);
      }
      if (selectedBranchRef.current === send.branchId) void reloadMessages();
      setRetryReplyBranchId(send.branchId);
      showToast('消息已保存，可从对话记录继续生成回复。', 'success');
    } catch (error) {
      showToast(`尚未确认消息已保存：${toastErrorMessage(error)}。沿用本次编号重试不会重复写入。`, 'warn');
    } finally {
      setPendingSendChecking(false);
    }
  }

  async function retryPendingSend() {
    const send = pendingSend;
    if (!send || send.sessionId !== sessionId) return;
    if (send.branchId !== selectedBranchRef.current) {
      const result = await activateBranch(send.branchId);
      if (!result.ok) return;
    }
    if (!reserveGeneration()) return;
    setRetryReplyBranchId(null);
    sendMutation.mutate({
      sessionId, branchId: send.branchId, userMessage: send.input,
      filesToSend: [], quoteMessage: send.quote,
    });
  }

  function forgetPendingSend() {
    const send = pendingSend;
    if (!send || send.sessionId !== sessionId) return;
    clearPendingChatSend(sessionId, send.clientMessageId);
    setPendingSend(null);
    showToast('已清除待确认标记；原请求仍可能写入，请先核对对话记录。', 'warn');
  }

  function useChoice(choice: string, sourceMessageId: number) {
    const normalized = choice.trim();
    const currentChoices = currentChoiceMessage ? extractChoicesFromMessage(currentChoiceMessage) : [];
    if (
      !normalized
      || !currentChoiceMessage
      || currentChoiceMessage.id !== sourceMessageId
      || !currentChoices.includes(normalized)
    ) {
      showToast('该选项已不属于当前回合，请选择最新回复中的选项', 'warn');
      return;
    }
    updateInput(input.trim() ? `${input.trim()}\n${normalized}` : normalized);
    setInputFocusRequestKey((value) => value + 1);
  }

  async function requestNarrator() {
    if (!narratorEnabled) {
      showToast('请先在右侧面板开启「旁白推进」', 'warn');
      return;
    }
    if (!reserveGeneration()) return;
    try {
      await runStreamGeneration({
        branchId: selectedBranchId,
        streamIntoCurrentList: true,
        narratorOnly: true,
      });
      await refreshMessages();
    } catch (e) {
      if (!isAbortError(e)) showToast(toastErrorMessage(e), 'error');
    } finally {
      releaseGeneration();
    }
  }

  function onSpeakerTurnModeChange(mode: SpeakerTurnMode) {
    setSpeakerTurnMode(mode);
    saveSpeakerTurnMode(mode);
    setManualReplyCharacterId(null);
  }

  async function handleQuickAction(actionName: string, returnFocusTarget?: HTMLElement | null) {
    const actionLabel = QUICK_ACTION_LABELS[actionName] ?? '快捷操作';
    const lastMsg = flatMessages[flatMessages.length - 1];
    if (actionName === 'create_entry_from_message' && !isPersistedMessageId(lastMsg?.id)) {
      showToast('请等待当前消息生成并保存完成后再沉淀百科条目', 'warn');
      return;
    }
    if (actionName === 'check_setting_conflicts' && isWorldDirty) {
      openRightPanel('config');
      showToast('请先保存世界设置，再检查设定冲突', 'warn');
      return;
    }
    if (actionName === 'check_setting_conflicts' && !encyclopediaId) {
      openRightPanel('config');
      showToast('请先为会话绑定百科，再检查设定冲突', 'warn');
      return;
    }
    if (quickActionGateRef.current) {
      showToast('当前整理操作仍在处理中，请稍候', 'warn');
      return;
    }
    quickActionGateRef.current = true;
    const requestId = ++quickActionRequestRef.current;
    setActiveQuickAction(actionName);
    if (actionName === 'check_setting_conflicts') {
      settingConflictReturnFocusRef.current = returnFocusTarget ?? null;
    }
    try {
      const worldRes = await api.get(`/sessions/${sessionId}/world`);
      const worldData = worldRes?.encyclopedia_id ? worldRes : null;
      const res = await api.post('/actions/execute', {
        name: actionName,
        params: {
          session_id: Number(sessionId),
          encyclopedia_id: worldData?.encyclopedia_id || undefined,
          message_id: lastMsg?.id,
          branch_id: selectedBranchId,
        },
      });
      if (res?.ok === false) {
        throw new Error(res.message || `${actionLabel}未完成`);
      }
      if (requestId !== quickActionRequestRef.current || sessionIdRef.current !== sessionId) return;
      if (actionName === 'check_setting_conflicts') {
        const result = normalizeSettingConflictResult(res);
        setSettingConflictResult(result);
        showToast(result.conflicts.length > 0 ? `发现 ${result.conflicts.length} 项整理提示` : '未发现明显冲突', 'success');
      } else {
        showToast(`${actionLabel}已完成`, 'success');
      }
      await queryClient.invalidateQueries({ queryKey: ['encyclopedia-entries'] });
    } catch (e) {
      if (requestId === quickActionRequestRef.current && sessionIdRef.current === sessionId) {
        if (actionName === 'check_setting_conflicts') settingConflictReturnFocusRef.current = null;
        showToast(toastErrorMessage(e), 'error');
      }
    } finally {
      if (requestId === quickActionRequestRef.current) {
        quickActionGateRef.current = false;
        setActiveQuickAction(null);
      }
    }
  }

  const closeSettingConflictResult = useCallback(() => {
    setSettingConflictResult(null);
    const returnFocusTarget = settingConflictReturnFocusRef.current;
    settingConflictReturnFocusRef.current = null;
    window.requestAnimationFrame(() => {
      if (returnFocusTarget?.isConnected) returnFocusTarget.focus();
    });
  }, []);

  function openConflictEncyclopedia() {
    settingConflictReturnFocusRef.current = null;
    setSettingConflictResult(null);
    navigate(`/encyclopedia?encId=${encodeURIComponent(String(encyclopediaId))}`);
  }

  function openConflictEntry(entry: SettingConflictEntry) {
    settingConflictReturnFocusRef.current = null;
    setSettingConflictResult(null);
    navigate(encyclopediaEntryPath(Number(encyclopediaId), entry));
  }

  function handleRegenerateFromMessage(message: Message) {
    const branchPoint = getRegenerationBranchPoint(message, selectedBranchId);

    if (!reserveGeneration()) return;
    if (branchCreationGateRef.current) {
      releaseGeneration();
      showToast('故事线正在创建，请稍候', 'warn');
      return;
    }
    branchCreationGateRef.current = true;
    let regenerationStarted = false;

    createBranchMutation.mutate({
      messageId: branchPoint.sourceMessageId,
      label: storyLineSourcePreview(message)
        ? `重新生成：${storyLineSourcePreview(message)}`
        : '重新生成的故事线',
      parentBranchId: branchPoint.parentBranchId,
    }, {
      onSuccess: async (branch) => {
        await queryClient.invalidateQueries({ queryKey: ['session-branches', sessionId] });
        const activation = await activateBranch(branch.branch_id);
        if (!activation.ok) {
          showToast('新故事线已经创建，但暂时无法打开；当前故事线保持不变', 'warn');
          return;
        }
        generateBranchReplyMutation.mutate({ branchId: branch.branch_id });
        regenerationStarted = true;
      },
      onSettled: () => {
        branchCreationGateRef.current = false;
        if (!regenerationStarted) releaseGeneration();
      },
    });
  }

  function retryLastReply() {
    const branchId = retryReplyBranchId;
    if (!branchId || branchId !== selectedBranchRef.current || !reserveGeneration()) return;
    generateBranchReplyMutation.mutate({ branchId });
  }

  function createBranch(message: Message) {
    if (generationGateRef.current) {
      showToast('请先停止或等待当前回复完成，再创建故事线', 'warn');
      return;
    }
    if (branchCreationGateRef.current) {
      showToast('故事线正在创建，请稍候', 'warn');
      return;
    }
    branchCreationGateRef.current = true;
    const sourcePreview = storyLineSourcePreview(message);
    createBranchMutation.mutate({
      messageId: message.id,
      label: sourcePreview ? `从“${sourcePreview}”继续` : '新故事线',
      parentBranchId: message.branch_id || selectedBranchId || 'main',
    }, {
      onSuccess: async (branch) => {
        await queryClient.invalidateQueries({ queryKey: ['session-branches', sessionId] });
        const activation = await activateBranch(branch.branch_id);
        if (activation.ok) {
          showToast('已创建并打开新故事线', 'success');
        } else {
          showToast('新故事线已经创建，但暂时无法打开；当前故事线保持不变', 'warn');
        }
      },
      onSettled: () => { branchCreationGateRef.current = false; },
    });
  }

  async function continueBranch(branchId: string) {
    if (!reserveGeneration()) return;
    try {
      const targetBranchId = normalizeBranchId(branchId);
      if (targetBranchId !== selectedBranchRef.current) {
        const activation = await activateBranch(targetBranchId);
        if (!activation.ok) throw new Error('无法打开目标故事线，请重试');
      }
      closeBranchTree();
      generateBranchReplyMutation.mutate({ branchId: targetBranchId, streamIntoCurrentList: true });
    } catch (error) {
      releaseGeneration();
      showToast(toastErrorMessage(error), 'error');
    }
  }

  const bookmarkMutation = useMutation({
    mutationFn: (message: Message) => api.toggleBookmark(sessionId, message.id),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['bookmarks', sessionId] });
      showToast(result.bookmarked ? '已收藏消息' : '已取消收藏', 'success');
    },
    onError: (error) => showToast(toastErrorMessage(error), 'error'),
  });

  const currentChoiceMessage = useMemo(() => {
    if (hasNewer) return undefined;
    const tail = flatMessages[flatMessages.length - 1];
    return tail && tail.include_in_context !== false && (tail.speaker_type === 'character' || tail.speaker_type === 'narrator')
      ? tail
      : undefined;
  }, [flatMessages, hasNewer]);

  const roundChoiceOptions = useMemo(() => {
    if (!choiceGenerationEnabled || !currentChoiceMessage) return [];
    const fromMessage = extractChoicesFromMessage(currentChoiceMessage);
    return mergeRoundChoices(fromMessage, maxChoiceCount);
  }, [choiceGenerationEnabled, currentChoiceMessage, maxChoiceCount]);

  const branchAnchorsByMessageId = useMemo(() => {
    const map: Record<number, { branch_id: string; label: string }[]> = {};
    for (const b of branchesQuery.data ?? []) {
      if (!b.source_message_id) continue;
      const list = map[b.source_message_id] ?? [];
      list.push({ branch_id: b.branch_id, label: storyLineDisplayLabel(b.branch_id, b.label) });
      map[b.source_message_id] = list;
    }
    return map;
  }, [branchesQuery.data]);

  function buildQuotePrefix(message: ChatQuoteDraft): string {
    const label =
      message.speaker_type === 'user'
        ? '你'
        : message.speaker_type === 'narrator'
          ? narratorName || '旁白'
          : message.character_name || '角色';
    const snippet = stripChoicesFromMessageContent(message.content).trim().split('\n')[0]?.slice(0, 120) ?? '';
    return snippet ? `> ${label}：${snippet}\n\n` : `> ${label}\n\n`;
  }

  function resolveStreamCharacterIds(): number[] | undefined {
    if (speakerTurnMode === 'manual') {
      if (manualReplyCharacterId != null) return [manualReplyCharacterId];
      return selectedCharacters.length > 0 ? selectedCharacters : undefined;
    }
    return undefined;
  }

  const branchOptions = useMemo(() => {
    const items = branchesQuery.data ?? [];
    if (items.some((item) => item.branch_id === 'main')) return items;
    return [
      {
        branch_id: 'main',
        label: '主线剧情',
        parent_branch_id: null,
        source_message_id: null,
        source_message_preview: '当前会话主线',
        source_created_at: null,
        latest_created_at: flatMessages.length > 0 ? flatMessages[flatMessages.length - 1].created_at : null,
        depth: 0,
        message_count: flatMessages.filter((item) => item.branch_id === 'main').length,
        latest_message_id: flatMessages.length > 0 ? flatMessages[flatMessages.length - 1].id : null,
      },
      ...items,
    ];
  }, [branchesQuery.data, flatMessages]);

  const branchLabel = (branchId: string) => findStoryLineDisplayLabel(branchOptions, branchId);
  const activeBranchLabel = branchLabel(selectedBranchId);
  const sessionTitle = sessionDetailQuery.data?.title?.trim() || '未命名对话';

  const visibleSearchLocateFailure = searchLocateFailure?.sessionId === sessionId
    && searchLocateFailure.branchId === selectedBranchId
    && searchLocateFailure.query === sessionSearchDraft ? searchLocateFailure : null;

  return (
    <section className="chat-layout">
      {/* ================= 移动端切换器 ================= */}
      <button type="button" className="mobile-session-toggle" onClick={() => { window.dispatchEvent(new CustomEvent('toggle-mobile-sessions')); }} aria-label="打开对话列表">
        <UiIcon name="menu" />
        <span className="truncate">{sessionTitle}</span>
        <span style={{ marginLeft: 'auto', fontSize: '0.7rem', color: 'var(--text-2)' }}>{flatMessages.length} 条</span>
      </button>

      {/* ================= 中央聊天区 ================= */}
      <div className={`chat-main${searchView !== 'closed' ? ' is-searching' : ''}`}>
        {/* 顶部栏 */}
        <div className="chat-topbar">
          <button
            type="button"
            className="chat-topbar-back"
            onClick={() => navigate('/chat', { replace: true })}
            title="返回会话主页"
            aria-label="返回会话主页"
          >
            <UiIcon name="back" />
          </button>
          <input
            ref={tavernImportFileRef}
            type="file"
            accept=".json,.txt,application/json,text/plain"
            style={{ display: 'none' }}
            onChange={(event) => {
              const picked = event.target.files?.[0];
              event.target.value = '';
              if (!picked) return;
              if (isGenerating) {
                showToast('请先停止当前回复，再导入聊天记录', 'warn');
                return;
              }
              const first = participantsQuery.data?.[0]?.character?.id;
              if (first == null) {
                showToast('请先在本会话添加至少一名角色参与者', 'error');
                return;
              }
              importTavernChatMutation.mutate({ file: picked, characterId: first, branchId: selectedBranchId });
            }}
          />
          <div className="chat-topbar-info">
            <div className="chat-topbar-name">{sessionTitle}</div>
            {searchView === 'closed' && <ChatModelPicker key={sessionId} sessionId={sessionId} onBusyChange={onModelChoiceBusyChange} />}
          </div>
          <button
            type="button"
            ref={chatMenuToggleRef}
            className="chat-topbar-menu"
            disabled={searchView !== 'closed'}
            onClick={() => { if (showChatMenu) closeChatMenu(); else openChatMenu(); }}
            title="更多选项"
            aria-label={showChatMenu ? '关闭会话菜单' : '打开会话菜单'}
            aria-expanded={showChatMenu}
            aria-controls="chat-menu"
          >
            <UiIcon name="more" />
          </button>
          <button type="button"
            ref={rightPanelToggleRef}
            className={`chat-topbar-btn ${showRightPanel ? 'active' : ''}`}
            disabled={searchView !== 'closed'}
            onClick={() => { if (showRightPanel) requestCloseRightPanel(); else openRightPanel(); }}
            title={isCompactLayout ? '会话详情（发言、世界、记忆等）' : '会话详情：发言调度、世界配置、记忆与追踪'}
            aria-label={showRightPanel ? '关闭会话详情' : '打开会话详情'}
            aria-expanded={showRightPanel}
            aria-controls="chat-right-panel"
          >
            <UiIcon name="panel" />
          </button>
        </div>

        <nav className="chat-contextbar" aria-label="当前会话资料">
          <button type="button" disabled={searchView !== 'closed'} onClick={openBranchTree} aria-label={`故事线：${activeBranchLabel}`}>
            <UiIcon name="branch" /><span>{activeBranchLabel}</span>
          </button>
          <button type="button" disabled={searchView !== 'closed'} onClick={() => openRightPanel('participants')} aria-label="查看参与角色">
            <span>{participantsQuery.data?.map((item) => item.character.name).join('、') || '参与角色'}</span>
            <span className="chat-contextbar-count">{participantsQuery.data?.length ?? 0}</span>
          </button>
        </nav>

        {switchingBranchId && (
          <div className="branch-switch-notice is-loading" role="status" aria-live="polite">
            正在打开“{branchLabel(switchingBranchId)}”… 当前故事线会保留到读取完成。
          </div>
        )}
        {!switchingBranchId && branchSwitchFailure && (
          <div className="branch-switch-notice is-error" role="alert">
            <div className="branch-switch-copy">
              <strong>未能打开“{branchLabel(branchSwitchFailure.branchId)}”</strong>
              <span>仍停留在“{activeBranchLabel}”。{branchSwitchFailure.message}</span>
            </div>
            <div className="branch-switch-actions">
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => { void switchBranch(branchSwitchFailure.branchId); }}>重试</button>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setBranchSwitchFailure(null)}>关闭</button>
            </div>
          </div>
        )}

        <MessageSearchPanel sessionId={sessionId} branchId={selectedBranchId} value={sessionSearchDraft} onChange={(value) => { setSessionSearchDraft(value); setSearchLocateFailure(null); }}
          inputRef={sessionSearchInputRef} onSelect={(hit) => { void goToSearchHit(hit); }} locatingId={locatingMessageId} locating={messagesLocating} branchLabel={branchLabel}
          open={searchView !== 'closed'} reading={searchView === 'reading'} selectedHit={selectedSearchHit}
          locateFailure={visibleSearchLocateFailure} onRetryLocate={() => { if (visibleSearchLocateFailure) void goToSearchHit(visibleSearchLocateFailure.hit); }}
          onOpen={() => setSearchView('results')}
          onClose={() => { setSearchView('closed'); setSessionSearchDraft(''); setSelectedSearchHit(null); setSearchLocateFailure(null); }}
          onExitReading={() => setSearchView('results')} />

        {showChatMenu && (
          <ChatMenu
            participantsQuery={participantsQuery}
            charactersQuery={charactersQuery}
            branches={branchOptions}
            activeBranchId={selectedBranchId}
            onAddParticipant={(charId) => addParticipantMutation.mutate(charId)}
            onRemoveParticipant={(charId, name) => { void requestRemoveParticipant(charId, name); }}
            addingParticipant={addParticipantMutation.isPending}
            removingCharacterId={removeParticipantMutation.isPending ? removeParticipantMutation.variables : undefined}
            onSwitchBranch={switchBranch}
            onSearch={() => {
              setSearchView('results');
              window.requestAnimationFrame(() => {
                sessionSearchInputRef.current?.focus();
                sessionSearchInputRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
              });
            }}
            onWorldSettings={() => {
              window.requestAnimationFrame(() => openRightPanel('config'));
            }}
            onOpenBranchTree={() => {
              openBranchTree();
            }}
            onImportChat={openTavernChatImport}
            onExportSessionArchive={() => { void exportSessionArchive(); }}
            onExportChatHtml={() => { void exportCurrentBranch(); }}
            importingChat={importTavernChatMutation.isPending}
            exportingSessionArchive={isExportingSessionArchive}
            exportingChat={isExportingChat}
            onClose={closeChatMenu}
          />
        )}

        {showBranchTree && (
          <>
            <div className="chat-menu-overlay" aria-hidden="true" onClick={() => closeBranchTree()} />
            <div
              ref={branchTreeRef}
              className="chat-menu"
              role="dialog"
              aria-modal="true"
              aria-labelledby="branch-tree-title"
              style={{ width: 'min(900px, calc(100vw - 32px))', maxWidth: '900px' }}
            >
              <div className="chat-menu-header">
                <strong id="branch-tree-title">故事线总览</strong>
                <button type="button" className="chat-menu-close" data-branch-tree-close onClick={() => closeBranchTree()} aria-label="关闭故事线总览">
                  <UiIcon name="close" />
                </button>
              </div>
              <div className="chat-menu-body">
                <p className="hint" style={{ marginTop: 0 }}>选择故事线查看对应对话，也可以从故事线末尾继续生成。</p>
                <BranchTreeGraph
                  items={branchesQuery.data ?? []}
                  activeBranchId={selectedBranchId}
                  onSelect={switchBranch}
                  onContinue={continueBranch}
                />
              </div>
            </div>
          </>
        )}

        {(speakerPlanReason || pendingRoundSpeakers.length > 0) && (
          <div className="speaker-plan-banner">
            {speakerPlanReason || `待回复：${pendingRoundSpeakers.join('、')}`}
          </div>
        )}

        <MessageList
          messages={flatMessages}
          isGenerating={isGenerating}
          branches={branchesQuery.data ?? []}
          selectedBranchId={selectedBranchId}
          onSwitchBranch={switchBranch}
          branchAnchorsByMessageId={branchAnchorsByMessageId}
          loading={messagesLoading}
          loadingMore={messagesLoadingMore}
          error={messagesError}
          loadMoreError={loadMoreError}
          loadNewerError={loadNewerError}
          hasMore={hasMore}
          hasNewer={hasNewer}
          onLoadMore={() => { void loadOlderWindow(); }}
          onLoadNewer={() => { void loadNewerWindow(); }}
          onJumpToLatest={() => { void returnToLatest(); }}
          onRetry={retryMessages}
          onRetryLoadMore={() => { void loadOlderWindow(); }}
          onRetryLoadNewer={() => { void loadNewerWindow(); }}
          loadingNewer={messagesLoadingNewer}
          onPlayVoice={playVoice}
          currentChoiceMessageId={!isGenerating ? currentChoiceMessage?.id : undefined}
          onQuoteMessage={(message) => {
            const visibleContent = stripChoicesFromMessageContent(message.content);
            if (!visibleContent.trim()) {
              showToast('这条消息没有可引用的正文', 'warn');
              return;
            }
            setQuotingMessage({ ...message, content: visibleContent });
          }}
          onCreateBranch={createBranch}
          onRegenerateBranch={handleRegenerateFromMessage}
          showPromptDebug={showPromptDebug}
          onBookmarkMessage={(message) => bookmarkMutation.mutate(message)}
          onSetMessageContext={(message) => {
            if (generationGateRef.current) { showToast('请先停止或等待当前回复完成，再调整上下文', 'warn'); return; }
            setContextTarget({ sessionId, branchId: selectedBranchId, message });
          }}
          onDeleteMessage={(message) => {
            if (generationGateRef.current) { showToast('请先停止或等待当前回复完成，再删除消息', 'warn'); return; }
            setDeleteTarget({ sessionId, branchId: selectedBranchId, message });
          }}
          onEditMessage={(message) => {
            const visibleContent = stripChoicesFromMessageContent(message.content);
            if (!visibleContent.trim()) {
              showToast('这条消息没有可编辑的正文', 'warn');
              return;
            }
            setEditContent(visibleContent);
            setEditOriginalContent(visibleContent);
            setEditSaveError(null);
            setEditMessageId(message.id);
          }}
          expressionMap={expressionMap}
          scrollNudgeKey={messageScrollNudgeKey}
          focusRequest={messageFocusRequest}
          readOnly={searchView !== 'closed'}
          searchQuery={searchView === 'reading' ? sessionSearchDraft : undefined}
          searchHit={searchView === 'reading' ? selectedSearchHit : null}
        />

        {searchView === 'closed' && speakerTurnMode === 'manual' && (participantsQuery.data?.length ?? 0) > 0 && (
          <div className="manual-speaker-chips">
            {participantsQuery.data?.map((p) => (
              <button
                key={p.id}
                type="button"
                className={`manual-speaker-chip ${manualReplyCharacterId === p.character.id ? 'active' : ''}`}
                onClick={() =>
                  setManualReplyCharacterId((cur) => (cur === p.character.id ? null : p.character.id))
                }
              >
                {p.character.name}
              </button>
            ))}
          </div>
        )}
        {searchView === 'closed' && !isGenerating && roundChoiceOptions.length > 0 && (
          <RoundChoicesRow
            choices={roundChoiceOptions}
            onSelect={(choice) => currentChoiceMessage && useChoice(choice, currentChoiceMessage.id)}
          />
        )}

        {searchView === 'closed' && !participantsQuery.isLoading
          && !participantsQuery.isError
          && (participantsQuery.data?.length ?? 0) === 0
          && gameplayMode !== '小说创作'
          && !narratorEnabled && (
            <div className="guide-inline chat-setup-guide" role="status">
              <span>这个会话还没有角色。添加一名角色后即可开始对话。</span>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={() => {
                  openRightPanel('participants');
                }}
              >
                添加角色
              </button>
            </div>
          )}

        {searchView === 'closed' && <ChatInputBar
          key={sessionId}
          input={input}
          setInput={updateInput}
          files={files}
          setFiles={setFiles}
          fileUrls={fileUrls}
          submittingFiles={sendMutation.isPending ? sendMutation.variables?.filesToSend : undefined}
          isGenerating={isGenerating}
          isPending={sendMutation.isPending}
          isError={sendMutation.isError && !isAbortError(sendMutation.error) && retryReplyBranchId === null}
          errorMessage={sendMutation.error?.message}
          retryReplyAvailable={retryReplyBranchId === selectedBranchId}
          onRetryReply={retryLastReply}
          pendingSendPreview={pendingSend?.sessionId === sessionId && !isGenerating ? pendingSend.input.trim().slice(0, 100) : undefined}
          pendingSendChecking={pendingSendChecking}
          onCheckPendingSend={() => { void checkPendingSend(); }}
          onRetryPendingSend={() => { void retryPendingSend(); }}
          onForgetPendingSend={forgetPendingSend}
          onRefreshReplies={() => { void reloadMessages(); }}
          refreshingReplies={messagesLoading}
          onSend={handleSend}
          onStop={() => { abortRef.current?.abort(); }}
          onQuickAction={handleQuickAction}
          quickActionPendingLabel={activeQuickAction ? `${QUICK_ACTION_LABELS[activeQuickAction] ?? '整理操作'}处理中…` : null}
          canCreateEntryFromMessage={canCreateEntryFromMessage(flatMessages, isGenerating)}
          settingConflictHint={isWorldDirty ? '保存世界设置后检查冲突' : encyclopediaId ? undefined : '绑定百科后检查冲突'}
          quotingAuthor={quotingMessage?.speaker_type === 'user' ? '你'
            : quotingMessage?.speaker_type === 'narrator' ? narratorName || '旁白'
              : quotingMessage?.character_name || '角色'}
          quotingPreview={
            quotingMessage
              ? stripChoicesFromMessageContent(quotingMessage.content).trim().split('\n')[0]?.slice(0, 120) ?? ''
              : null
          }
          onClearQuote={() => setQuotingMessage(null)}
          onRequestNarrator={requestNarrator}
          narratorEnabled={narratorEnabled}
          maxUploadMb={localConfigQuery.data?.max_upload_mb}
          inputPlaceholder={gameplayMode === '小说创作' ? '输入下一段剧情走向，发送后由小说作者续写…' : undefined}
          narratorActionLabel={gameplayMode === '小说创作' ? '直接续写下一章' : undefined}
          focusRequestKey={inputFocusRequestKey}
        />}
      </div>

      {/* ================= 右侧面板 ================= */}
      {showRightPanel && isCompactLayout && (
        <div
          className="chat-right-backdrop"
          aria-hidden="true"
          onClick={requestCloseRightPanel}
        />
      )}
      <ChatRightPanel
        sessionOptions={
          <div className="mini-card" style={{ marginTop: 12, padding: 10 }}>
          <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8, cursor: 'pointer', fontSize: '0.85rem' }}>
            <input
              type="checkbox"
              checked={Boolean(sessionDetailQuery.data?.think_max_enabled)}
              disabled={!localConfigQuery.data?.allow_session_think_max || updateSessionThinkMutation.isPending}
              onChange={(e) => {
                if (!localConfigQuery.data?.allow_session_think_max) {
                  showToast('请先在「设置 → 公共 API」中勾选「允许在对话页…思考/Max」', 'warn');
                  return;
                }
                updateSessionThinkMutation.mutate(e.target.checked);
              }}
              style={{ marginTop: 2 }}
            />
            <span>
              <strong>本会话思考 / Max</strong>
              <div style={{ color: 'var(--text-2)', fontSize: '0.78rem', marginTop: 4 }}>
                {localConfigQuery.data?.allow_session_think_max
                  ? '开启后本会话内回复走思考线路（仍受角色「思考」开关或模型映射影响）。'
                  : '需先在设置里允许对话页使用此项。'}
              </div>
            </span>
          </label>
        </div>
        }
        show={showRightPanel}
        onClose={requestCloseRightPanel}
        modal={isCompactLayout}
        tab={rightPanelTab}
        onTabChange={setRightPanelTab}
        participantsQuery={participantsQuery}
        worldTemplateId={worldTemplateId}
        onWorldTemplateIdChange={(id) => { setWorldTemplateId(id); const t = worldTemplatesQuery.data?.find((item) => item.template_id === id); setWorldPrompt(t?.world_prompt ?? ""); setAntiCheatPrompt(t?.anti_cheat_prompt ?? ""); setGameplayMode(t?.gameplay_mode ?? "自由剧情"); }}
        encyclopediaId={encyclopediaId}
        onEncyclopediaIdChange={(id) => { setEncyclopediaId(id); const world = encyclopediasQuery.data?.find((item) => item.id === id); if (world) { setWorldPrompt(world.world_prompt ?? ""); setGameplayMode(world.gameplay_mode ?? "自由剧情"); setAntiCheatPrompt(world.anti_cheat_prompt ?? ""); } }}
        worldTemplatesQuery={worldTemplatesQuery}
        encyclopediasQuery={encyclopediasQuery}
        gameplayMode={gameplayMode}
        onGameplayModeChange={setGameplayMode}
        narratorEnabled={narratorEnabled}
        onNarratorEnabledChange={setNarratorEnabled}
        narratorName={narratorName}
        onNarratorNameChange={setNarratorName}
        onSaveWorld={() => { void handleSaveWorld(); }}
        worldReady={worldReady}
        worldLoading={worldLoading}
        worldLoadError={worldLoadError}
        onRetryWorldLoad={() => { void retryWorldLoad(); }}
        worldSaving={worldSaving}
        worldDirty={isWorldDirty}
        worldSaveError={worldSaveError}
        selectedBranchId={selectedBranchId}
        onBranchChange={switchBranch}
        branchSwitchDisabled={isGenerating}
        branchOptions={branchOptions}
        memoryStateQuery={memoryStateQuery}
        memorySegmentsQuery={memorySegmentsQuery}
        memoryCompactionQuery={memoryCompactionQuery}
        memoryContinuePending={continueMemoryMutation.isPending}
        onContinueMemory={() => continueMemoryMutation.mutate({ currentSessionId: sessionId, branchId: selectedBranchId })}
        memoryCorrectionsQuery={memoryCorrectionsQuery}
        onLocateMemorySource={(messageId) => { void locateMemorySource(messageId); }}
        onCreateMemoryCorrection={(body) => createMemoryCorrectionMutation.mutateAsync(body).then(() => undefined)}
        onUpdateMemoryCorrection={(id, body) => updateMemoryCorrectionMutation.mutateAsync({ id, body }).then(() => undefined)}
        onDeleteMemoryCorrection={(id) => deleteMemoryCorrectionMutation.mutateAsync(id).then(() => undefined)}
        promptTraceQuery={promptTraceQuery}
        tokenUsageQuery={tokenUsageQuery}
        getStorageUrl={(path) => api.storageUrl(path)}
        speakerTurnMode={speakerTurnMode}
        onSpeakerTurnModeChange={onSpeakerTurnModeChange}
        maxAutoSpeakers={maxAutoSpeakers}
        onMaxAutoSpeakersChange={setMaxAutoSpeakers}
        onUpdateTalkativeness={(characterId, value) =>
          updateTalkativenessMutation.mutate({ characterId, talkativeness: value })
        }
        eventNodesQuery={eventNodesQuery}
      />

      {/* 编辑消息 Modal */}
      <EditMessageModal
        messageId={editMessageId}
        content={editContent}
        originalContent={editOriginalContent}
        regenerateAfterSave={flatMessages.find((message) => message.id === editMessageId)?.speaker_type === 'user'}
        isSaving={editSavePending}
        saveError={editSaveError}
        navigationPending={worldNavigationBlocker.state === 'blocked' && messageEditDirty && !editSavePending}
        worldDirty={isWorldDirty}
        onCancelNavigation={() => { if (worldNavigationBlocker.state === 'blocked') worldNavigationBlocker.reset(); }}
        onDiscardNavigation={() => { if (worldNavigationBlocker.state === 'blocked') worldNavigationBlocker.proceed(); }}
        onContentChange={(content) => { setEditContent(content); setEditSaveError(null); }}
        onSave={saveEditedMessage}
        onClose={() => setEditMessageId(null)}
      />
      {settingConflictResult && (
        <SettingConflictDialog
          result={settingConflictResult}
          onClose={closeSettingConflictResult}
          onOpenEncyclopedia={openConflictEncyclopedia}
          onOpenEntry={openConflictEntry}
        />
      )}
    {contextTarget && contextTarget.sessionId === sessionId && contextTarget.branchId === selectedBranchId && <MessageContextDialog
      key={`${sessionId}:${selectedBranchId}:${contextTarget.message.id}`}
      sessionId={sessionId} branchId={selectedBranchId} message={contextTarget.message}
      onClose={() => setContextTarget((current) => current === contextTarget ? null : current)}
      onSaved={async () => {
        if (sessionIdRef.current !== contextTarget.sessionId || selectedBranchRef.current !== contextTarget.branchId) return;
        applyMessageContext(contextTarget.message.id, contextTarget.message.include_in_context === false);
        if (!await loadAroundMessage(contextTarget.message.id)) throw new Error('请重新读取当前消息');
        focusMessage(contextTarget.message.id);
      }} />}
    {deleteTarget && deleteTarget.sessionId === sessionId && deleteTarget.branchId === selectedBranchId && <DeleteMessageDialog
      key={`${deleteTarget.sessionId}:${deleteTarget.branchId}:${deleteTarget.message.id}`} sessionId={deleteTarget.sessionId} branchId={deleteTarget.branchId}
      message={deleteTarget.message} onClose={() => setDeleteTarget(null)} onDeleted={() => { void finishMessageDeletion(); }} />}
    </section>
  );
}
