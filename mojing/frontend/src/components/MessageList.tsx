import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useVirtualizer } from '@tanstack/react-virtual';

import { api } from '../api/client';
import type { Expression, Message, MessageUsage, SessionBranch } from '../types';
import { extractChoicesFromMessage, stripChoicesFromMessageContent } from '../utils/chatChoiceParsing';
import { copyText } from '../utils/clipboard';
import { useToast } from '../hooks/useToast';
import MarkdownRenderer from './MarkdownRenderer';
import MessageBranchBar, { type BranchAnchor } from './MessageBranchBar';
import InlineQueryError from './InlineQueryError';
import UiIcon, { type UiIconName } from './UiIcon';

type Props = {
  messages: Message[];
  branches?: SessionBranch[];
  selectedBranchId?: string;
  onSwitchBranch?: (branchId: string) => void;
  loading: boolean;
  isGenerating?: boolean;
  loadingMore: boolean;
  error?: unknown;
  loadMoreError?: unknown;
  loadNewerError?: unknown;
  hasMore: boolean;
  hasNewer: boolean;
  onLoadMore: () => void;
  onLoadNewer: () => void;
  onJumpToLatest: () => void;
  onRetry: () => void;
  onRetryLoadMore: () => void;
  onRetryLoadNewer: () => void;
  loadingNewer: boolean;
  onPlayVoice: (message: Message) => void;
  currentChoiceMessageId?: number;
  onCreateBranch: (message: Message) => void;
  onRegenerateBranch: (message: Message) => void;
  onQuoteMessage?: (message: Message) => void;
  branchAnchorsByMessageId?: Record<number, BranchAnchor[]>;
  onEditMessage?: (message: Message) => void;
  onDeleteMessage?: (message: Message) => void;
  onSetMessageContext?: (message: Message) => void;
  onBookmarkMessage?: (message: Message) => void;
  showPromptDebug: boolean;
  expressionMap?: Record<number, Expression[]>;
  /** 末条内容长度 + 生成态等；变化时在「已贴底」下追加贴底，缓解 SSE 流式不跟底 */
  scrollNudgeKey?: string;
  focusRequest?: {
    messageId: number;
    requestId: number;
    align: 'center' | 'end';
    moveKeyboardFocus?: boolean;
  } | null;
  readOnly?: boolean;
  searchQuery?: string;
  searchHit?: { id: number; snippet: string } | null;
};

function SegmentBlock({ title, text }: { title: string; text?: string }) {
  if (!text) return null;
  return (
    <div className="segment-block">
      <span>{title}</span>
      <p>{text}</p>
    </div>
  );
}

function DebugBlock({ debug }: { debug?: Record<string, unknown> }) {
  const promptDebug = (debug?.prompt_debug as Record<string, unknown> | undefined) ?? undefined;
  if (!promptDebug) return null;
  const loreHits = (promptDebug.lore_hits as Array<Record<string, unknown>> | undefined) ?? [];
  const memoryHits = (promptDebug.memory_hits as Record<string, Array<Record<string, unknown>>> | undefined) ?? {};
  const correctionRefs = Array.isArray(promptDebug.memory_corrections) ? promptDebug.memory_corrections : [];
  const correctionRefIds = correctionRefs.flatMap((ref) => {
    if (typeof ref === 'number' || typeof ref === 'string') return [String(ref)];
    if (ref && typeof ref === 'object' && 'id' in ref) return [String((ref as { id: unknown }).id)];
    return [];
  });
  return (
    <details className="debug-card">
      <summary>查看提示命中调试</summary>
      <div className="guide-inline">
        <div>类型：{String(promptDebug.kind ?? '未知')}</div>
        <div>世界模板：{String(promptDebug.world_template_label ?? promptDebug.world_template_id ?? 'custom')}</div>
        <div>玩法模式：{String(promptDebug.gameplay_mode ?? '未指定')}</div>
      </div>
      {Object.entries(memoryHits).map(([key, value]) => (
        <div className="guide-inline" key={key}>
          <strong>{key}</strong>
          <ul className="compact-list">
            {value.map((item, index) => (
              <li key={`${key}-${index}`}>{String(item.text ?? '')}</li>
            ))}
          </ul>
        </div>
      ))}
      {loreHits.length > 0 && (
        <div className="guide-inline">
          <strong>世界 Lore 命中</strong>
          <ul className="compact-list">
            {loreHits.map((item, index) => (
              <li key={`lore-${index}`}>
                {String(item.entry_type ?? '设定')} / {String(item.title ?? '未命名')} / {String(item.content ?? '')}
              </li>
            ))}
          </ul>
        </div>
      )}
      {correctionRefs.length > 0 && (
        <div className="guide-inline">纠正引用：{correctionRefIds.length > 0 ? correctionRefIds.map((id) => `#${id}`).join('、') : '仅记录数量'}（共 {correctionRefs.length} 条）</div>
      )}
    </details>
  );
}

function MessageUsageLine({ usage }: { usage?: MessageUsage }) {
  if (!usage) return null;
  const cost = usage.cost_known && usage.estimated_cost != null
    ? `${usage.currency === 'CNY' ? '¥' : usage.currency === 'USD' ? '$' : `${usage.currency ?? ''} `}${usage.estimated_cost.toFixed(usage.estimated_cost !== 0 && Math.abs(usage.estimated_cost) < 0.01 ? 6 : 4)}`
    : '费用未知';
  const source = usage.usage_source === 'estimated' || usage.usage_source === 'approximate' ? '约' : '';
  return <div className="message-usage-line" aria-label="消息用量">
    <span>{usage.duration_ms > 0 ? `${(usage.duration_ms / 1000).toFixed(1)} 秒` : '耗时未知'}</span>
    <span>{usage.total_tokens.toLocaleString('zh-CN')} Token</span>
    <span>{source}{cost}</span>
  </div>;
}

type ActionButtonProps = {
  icon: UiIconName;
  label: string;
  className: string;
  onClick: () => void;
  iconOnly?: boolean;
  menuTrigger?: boolean;
  expanded?: boolean;
  role?: 'menuitem';
  actionTriggerId?: number;
  editTriggerId?: number;
  disabled?: boolean;
  description?: string;
};

function ActionButton({
  icon,
  label,
  className,
  onClick,
  iconOnly = false,
  menuTrigger = false,
  expanded,
  role,
  actionTriggerId,
  editTriggerId,
  disabled = false,
  description,
}: ActionButtonProps) {
  return (
    <button
      type="button"
      className={className}
      onClick={onClick}
      disabled={disabled}
      title={disabled ? '回复生成中，请稍候' : iconOnly ? label : undefined}
      aria-label={iconOnly || description ? label : undefined}
      aria-description={description}
      aria-haspopup={menuTrigger ? 'menu' : undefined}
      aria-expanded={menuTrigger ? expanded : undefined}
      role={role}
      data-message-actions-trigger={actionTriggerId}
      data-message-edit-trigger={editTriggerId}
    >
      <UiIcon name={icon} />
      {!iconOnly && <span className={description ? "message-action-description" : undefined}>{label}{description && <small>{description}</small>}</span>}
    </button>
  );
}

export default function MessageList({
  messages,
  branches = [],
  selectedBranchId = 'main',
  onSwitchBranch,
  loading,
  isGenerating = false,
  loadingMore,
  error,
  loadMoreError,
  loadNewerError,
  hasMore,
  hasNewer,
  onLoadMore,
  onLoadNewer,
  onJumpToLatest,
  onRetry,
  onRetryLoadMore,
  onRetryLoadNewer,
  loadingNewer,
  onPlayVoice,
  currentChoiceMessageId,
  onCreateBranch,
  onRegenerateBranch,
  onQuoteMessage,
  branchAnchorsByMessageId = {},
  onEditMessage,
  onDeleteMessage,
  onSetMessageContext,
  onBookmarkMessage,
  showPromptDebug,
  expressionMap,
  scrollNudgeKey,
  focusRequest,
  readOnly = false,
  searchQuery = '',
  searchHit = null,
}: Props) {
  const { showToast } = useToast();
  const [showMsgMenuId, setShowMsgMenuId] = useState<number | null>(null);
  const [activeExpressions, setActiveExpressions] = useState<Record<number, number>>({});
  const [mobileMenuId, setMobileMenuId] = useState<number | null>(null);
  const longPressTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mobileMenuRef = useRef<HTMLDivElement | null>(null);
  const mobileMenuReturnFocusRef = useRef<HTMLElement | null>(null);
  const parentRef = useRef<HTMLDivElement | null>(null);
  const [isAtBottom, setIsAtBottom] = useState(true);
  const [focusedMessageId, setFocusedMessageId] = useState<number | null>(null);
  const prevLenRef = useRef(0);
  const initialScrollDoneRef = useRef(false);
  const focusSettlingRef = useRef(false);
  const appendScrollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const sizeCorrectionTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const openMobileMenu = useCallback((messageId: number) => {
    const messageTrigger = document.querySelector<HTMLElement>(
      `[data-message-actions-trigger="${messageId}"]`,
    );
    const active = document.activeElement instanceof HTMLElement && document.activeElement !== document.body
      ? document.activeElement
      : null;
    mobileMenuReturnFocusRef.current = messageTrigger ?? active;
    setMobileMenuId(messageId);
  }, []);

  const closeMobileMenu = useCallback(() => setMobileMenuId(null), []);

  useEffect(() => {
    if (showMsgMenuId === null) return;
    const trigger = parentRef.current?.querySelector<HTMLElement>(`[data-message-actions-trigger="${showMsgMenuId}"]`);
    const menu = trigger?.closest('.chat-msg-more-wrap')?.querySelector<HTMLElement>('[role="menu"]');
    const items = () => Array.from(menu?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') ?? []);
    const frame = window.requestAnimationFrame(() => items()[0]?.focus());
    const handleMenuKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        setShowMsgMenuId(null);
        if (menu?.contains(document.activeElement)) trigger?.focus();
        return;
      }
      if (!menu?.contains(document.activeElement)) return;
      if (event.key === 'Tab') {
        event.preventDefault();
        const outside = Array.from(document.querySelectorAll<HTMLElement>(
          'button:not(:disabled), a[href], input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex="0"]',
        )).filter(element => element.tabIndex >= 0 && element.getClientRects().length > 0 && !menu.contains(element));
        const index = trigger ? outside.indexOf(trigger) : -1;
        (outside[index + (event.shiftKey ? -1 : 1)] ?? trigger)?.focus();
        setShowMsgMenuId(null);
        return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const options = items();
      if (!options.length) return;
      const current = options.indexOf(document.activeElement as HTMLElement);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
        : (current + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
      options[next].focus();
    };
    const closeOnOutsidePress = (event: PointerEvent) => {
      if (!(event.target instanceof Element)) return;
      if (showMsgMenuId !== null && !event.target.closest('.chat-msg-more-wrap')) {
        setShowMsgMenuId(null);
      }
    };

    document.addEventListener('keydown', handleMenuKeyDown);
    document.addEventListener('pointerdown', closeOnOutsidePress, true);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('keydown', handleMenuKeyDown);
      document.removeEventListener('pointerdown', closeOnOutsidePress, true);
    };
  }, [showMsgMenuId]);

  useEffect(() => {
    if (mobileMenuId === null) return undefined;
    const dialog = mobileMenuRef.current;
    const frame = window.requestAnimationFrame(() => {
      dialog?.querySelector<HTMLElement>('button:not(:disabled)')?.focus();
    });
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        closeMobileMenu();
        return;
      }
      if (event.key !== 'Tab' || !dialog) return;
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>('button:not(:disabled)'));
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
      const returnTarget = mobileMenuReturnFocusRef.current;
      mobileMenuReturnFocusRef.current = null;
      window.requestAnimationFrame(() => {
        const active = document.activeElement;
        if (
          returnTarget?.isConnected
          && (!(active instanceof HTMLElement) || active === document.body || !active.isConnected)
        ) {
          returnTarget.focus();
        }
      });
    };
  }, [closeMobileMenu, mobileMenuId]);

  useEffect(() => {
    const media = window.matchMedia('(max-width: 768px)');
    const closeAfterLeavingCompactLayout = (event: MediaQueryListEvent) => {
      if (!event.matches) closeMobileMenu();
    };
    media.addEventListener('change', closeAfterLeavingCompactLayout);
    return () => media.removeEventListener('change', closeAfterLeavingCompactLayout);
  }, [closeMobileMenu]);

  const handleCopy = useCallback(async (content: string) => {
    if (!content.trim()) {
      showToast('这条消息没有可复制的正文', 'warn');
      return;
    }
    try {
      await copyText(content);
      showToast('\u590d\u5236\u6210\u529f', 'success');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '\u590d\u5236\u5931\u8d25', 'error');
    }
  }, [showToast]);

  const handleTouchStart = useCallback((msgId: number) => {
    if (readOnly) return;
    if (msgId <= 0) return;
    if (longPressTimerRef.current) clearTimeout(longPressTimerRef.current);
    longPressTimerRef.current = setTimeout(() => {
      openMobileMenu(msgId);
    }, 500);
  }, [openMobileMenu, readOnly]);

  const handleTouchEnd = useCallback(() => {
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }
  }, []);

  const toggleMessageMenu = useCallback((messageId: number) => {
    if (readOnly) return;
    if (window.matchMedia('(max-width: 768px)').matches) {
      setShowMsgMenuId(null);
      if (mobileMenuId === messageId) closeMobileMenu();
      else openMobileMenu(messageId);
      return;
    }
    setMobileMenuId(null);
    setShowMsgMenuId((current) => current === messageId ? null : messageId);
  }, [closeMobileMenu, mobileMenuId, openMobileMenu, readOnly]);

  const reverseMessages = useMemo(() => messages, [messages]);

  const rowVirtualizer = useVirtualizer({
    count: reverseMessages.length,
    getScrollElement: () => parentRef.current,
    getItemKey: (index) => reverseMessages[index]?.id ?? index,
    estimateSize: () => 180,
    overscan: 8,
  });
  rowVirtualizer.shouldAdjustScrollPositionOnItemSizeChange = () => !focusSettlingRef.current;
  const visibleVirtualItems = rowVirtualizer.getVirtualItems();
  const visibleUsageIds = useMemo(() => visibleVirtualItems
    .map((item) => reverseMessages[item.index])
    .filter((message): message is Message => Boolean(message && message.id > 0 && (message.speaker_type === 'character' || message.speaker_type === 'narrator' || message.speaker_type === 'system')))
    .map((message) => message.id), [reverseMessages, visibleVirtualItems]);
  const messageUsageQuery = useQuery({
    queryKey: ['message-usage', visibleUsageIds],
    queryFn: () => api.messageUsage(visibleUsageIds),
    enabled: visibleUsageIds.length > 0,
    staleTime: 30_000,
  });
  const messageUsage = messageUsageQuery.data?.items ?? {};
  const previousGeneratingRef = useRef(isGenerating);
  useEffect(() => {
    const wasGenerating = previousGeneratingRef.current;
    previousGeneratingRef.current = isGenerating;
    if (wasGenerating && !isGenerating && visibleUsageIds.length > 0) void messageUsageQuery.refetch();
  }, [isGenerating, messageUsageQuery.refetch, visibleUsageIds.length]);

  // 检查是否在底部
  const checkAtBottom = useCallback(() => {
    if (!parentRef.current) return;
    const el = parentRef.current;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
    setIsAtBottom(atBottom);
  }, []);

  // 滚动到底部
  const scrollToBottom = useCallback(() => {
    if (reverseMessages.length === 0) return;
    const lastIndex = reverseMessages.length - 1;
    rowVirtualizer.scrollToIndex(lastIndex, { align: 'end', behavior: 'instant' });
    // 虚拟列表scrollToIndex有时不准，加fallback
    setTimeout(() => {
      if (parentRef.current) {
        parentRef.current.scrollTop = parentRef.current.scrollHeight;
      }
    }, 50);
    setIsAtBottom(true);
  }, [reverseMessages.length, rowVirtualizer]);

  const scrollToBottomRef = useRef(scrollToBottom);
  scrollToBottomRef.current = scrollToBottom;

  // 流式输出：条数不变但末条 content 变长时，在已贴底状态下追加贴底
  useEffect(() => {
    if (scrollNudgeKey === undefined) return;
    if (!initialScrollDoneRef.current || !isAtBottom) return;
    const id = requestAnimationFrame(() => scrollToBottomRef.current());
    return () => cancelAnimationFrame(id);
  }, [scrollNudgeKey, isAtBottom]);

  // 初始加载时定位到底部
  useEffect(() => {
    if (focusRequest) return;
    if (reverseMessages.length > 0 && !initialScrollDoneRef.current) {
      initialScrollDoneRef.current = true;
      const timer = setTimeout(() => {
        rowVirtualizer.scrollToIndex(reverseMessages.length - 1, { align: 'end', behavior: 'instant' });
        if (parentRef.current) {
          parentRef.current.scrollTop = parentRef.current.scrollHeight;
        }
        setIsAtBottom(true);
      }, 100);
      return () => clearTimeout(timer);
    }
  }, [focusRequest, reverseMessages.length, rowVirtualizer]);

  // 搜索定位必须驱动虚拟列表索引；目标未挂载时 DOM scrollIntoView 无法工作。
  useEffect(() => {
    if (!focusRequest) return;
    const index = reverseMessages.findIndex((message) => message.id === focusRequest.messageId);
    if (index < 0) return;
    if (appendScrollTimerRef.current) {
      clearTimeout(appendScrollTimerRef.current);
      appendScrollTimerRef.current = null;
    }
    if (sizeCorrectionTimerRef.current) {
      clearTimeout(sizeCorrectionTimerRef.current);
      sizeCorrectionTimerRef.current = null;
    }
    initialScrollDoneRef.current = true;
    focusSettlingRef.current = true;
    setIsAtBottom(focusRequest.align === 'end');
    setFocusedMessageId(focusRequest.messageId);
    const settleFocus = () => {
      rowVirtualizer.scrollToIndex(index, { align: focusRequest.align, behavior: 'instant' });
      if (focusRequest.align === 'end' && parentRef.current) {
        parentRef.current.scrollTop = parentRef.current.scrollHeight;
      }
    };
    settleFocus();
    const firstSettleTimer = setTimeout(settleFocus, 80);
    const finalSettleTimer = setTimeout(() => {
      settleFocus();
      if (readOnly && searchQuery.trim() && searchHit?.id === focusRequest.messageId && parentRef.current) {
        const match = parentRef.current.querySelector<HTMLElement>(`[data-chat-message-id="${focusRequest.messageId}"] mark.message-search-match`);
        if (match) {
          const container = parentRef.current;
          const matchBounds = match.getBoundingClientRect();
          const containerBounds = container.getBoundingClientRect();
          container.scrollTop += matchBounds.top - containerBounds.top - (containerBounds.height - matchBounds.height) / 2;
        }
      }
      if (focusRequest.moveKeyboardFocus) {
        const editTrigger = document.querySelector<HTMLElement>(
          `[data-message-edit-trigger="${focusRequest.messageId}"]`,
        );
        const messageRow = document.querySelector<HTMLElement>(
          `[data-chat-message-id="${focusRequest.messageId}"]`,
        );
        const visibleEditTrigger = editTrigger && editTrigger.getClientRects().length > 0
          ? editTrigger
          : null;
        (visibleEditTrigger ?? messageRow)?.focus({ preventScroll: true });
      }
      focusSettlingRef.current = false;
    }, 280);
    const highlightTimer = setTimeout(() => setFocusedMessageId(null), 1800);
    return () => {
      focusSettlingRef.current = false;
      clearTimeout(firstSettleTimer);
      clearTimeout(finalSettleTimer);
      clearTimeout(highlightTimer);
    };
  }, [focusRequest?.requestId, readOnly, searchQuery, searchHit?.id]);

  // 新消息到达时，只在用户位于底部时才自动滚下去
  useEffect(() => {
    const len = reverseMessages.length;
    const previousLen = prevLenRef.current;
    prevLenRef.current = len;
    if (
      len > previousLen
      && isAtBottom
      && initialScrollDoneRef.current
    ) {
      appendScrollTimerRef.current = setTimeout(() => {
        rowVirtualizer.scrollToIndex(len - 1, { align: 'end', behavior: 'instant' });
        if (parentRef.current) {
          parentRef.current.scrollTop = parentRef.current.scrollHeight;
        }
        appendScrollTimerRef.current = null;
      }, 50);
      return () => {
        if (appendScrollTimerRef.current) {
          clearTimeout(appendScrollTimerRef.current);
          appendScrollTimerRef.current = null;
        }
      };
    }
  }, [reverseMessages.length, isAtBottom, rowVirtualizer]);

  // 列表渲染后检查是否需调整
  useEffect(() => {
    if (!initialScrollDoneRef.current) return;
    if (!isAtBottom) return;
    if (focusSettlingRef.current) return;
    sizeCorrectionTimerRef.current = setTimeout(() => {
      checkAtBottom();
      if (parentRef.current && !(parentRef.current.scrollHeight - parentRef.current.scrollTop - parentRef.current.clientHeight < 100)) {
        rowVirtualizer.scrollToIndex(reverseMessages.length - 1, { align: 'end', behavior: 'instant' });
        if (parentRef.current) {
          parentRef.current.scrollTop = parentRef.current.scrollHeight;
        }
      }
      sizeCorrectionTimerRef.current = null;
    }, 200);
    return () => {
      if (sizeCorrectionTimerRef.current) {
        clearTimeout(sizeCorrectionTimerRef.current);
        sizeCorrectionTimerRef.current = null;
      }
    };
  }, [rowVirtualizer.getTotalSize()]);

  return (
    <div className="chat-msgs" ref={parentRef} onScroll={checkAtBottom}>
      {loadMoreError ? (
        <InlineQueryError
          message="更早消息加载失败"
          error={loadMoreError}
          retrying={loadingMore}
          onRetry={onRetryLoadMore}
        />
      ) : hasMore && (
        <button
          type="button"
          className="btn btn-ghost btn-sm more-button"
          disabled={loading || loadingMore}
          onClick={onLoadMore}
        >
          {loadingMore ? '正在加载…' : '加载更早消息'}
        </button>
      )}
      {loading && <div className="loading-bar">正在读取消息...</div>}
      {error != null && (
        <InlineQueryError message="消息加载失败" error={error} retrying={loading} onRetry={onRetry} />
      )}
      {reverseMessages.length === 0 && !loading && error == null && (
        <div className="chat-empty">暂无消息，发送第一条消息开始对话吧</div>
      )}
      <div
        style={{
          height: `${rowVirtualizer.getTotalSize()}px`,
          flex: '0 0 auto',
          position: 'relative',
        }}
      >
        {visibleVirtualItems.map((virtualRow) => {
          const message = reverseMessages[virtualRow.index];
          const structured = message.structured_content || {};
          const choices = extractChoicesFromMessage(message);
          const isCurrentChoiceMessage = message.id === currentChoiceMessageId;
          const visibleContent = stripChoicesFromMessageContent(message.content ?? '');
          const branchAnchors = branchAnchorsByMessageId[message.id] ?? [];
          const canReturnToMain =
            selectedBranchId !== 'main' &&
            branches.some((b) => b.branch_id === selectedBranchId && b.source_message_id === message.id);
          const isUser = message.speaker_type === 'user';
          const isNarrator = message.speaker_type === 'narrator' || message.speaker_type === 'system';
          const hasAvatar = message.character_avatar_path && !isUser;
          const authorName = isUser ? '你' : (message.character_name || message.speaker_type);
          const showActions = !isNarrator && message.id > 0;
          const showNarratorActions = message.speaker_type === 'narrator' && message.id > 0;

          return (
            <div
              key={message.id}
              className={focusedMessageId === message.id ? 'chat-message-focus' : undefined}
              ref={rowVirtualizer.measureElement}
              data-index={virtualRow.index}
              data-chat-message-id={message.id}
              tabIndex={-1}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${virtualRow.start}px)`,
              }}
            >
              {!readOnly && (branchAnchors.length > 0 || canReturnToMain) && onSwitchBranch && (
                <MessageBranchBar
                  anchors={branchAnchors}
                  activeBranchId={selectedBranchId}
                  canReturnToMain={canReturnToMain}
                  onSwitchBranch={onSwitchBranch}
                />
              )}
              {message.include_in_context === false && <div className="hint" style={{ padding: '6px 12px' }} role="note">已排除上下文 · 原文保留</div>}
              {structured.interrupted === true && <div className="hint" style={{ padding: '6px 12px' }} role="note">回复已中断 · 收到的正文已保留</div>}
              {isNarrator ? (
                <div className="chat-msg-system">
                  <div
                    className="chat-msg-system-text"
                    onTouchStart={() => handleTouchStart(message.id)}
                    onTouchMove={handleTouchEnd}
                    onTouchEnd={handleTouchEnd}
                    onTouchCancel={handleTouchEnd}
                  >
                    <MarkdownRenderer content={visibleContent} highlightQuery={readOnly && searchHit?.id === message.id ? searchQuery : ''} />
                    {choices.length > 0 && !isCurrentChoiceMessage && (
                      <div className="chat-choices">
                        {choices.map((choice) => (
                          <button
                            className="btn btn-sm"
                            type="button"
                            key={choice}
                            disabled
                            title="历史选项仅供回看"
                          >
                            {choice}
                          </button>
                        ))}
                      </div>
                      )}
                  </div>
                  <MessageUsageLine usage={messageUsage[String(message.id)]} />
                  {!readOnly && showNarratorActions && (
                    <div className="chat-msg-tools chat-msg-system-tools">
                      {onEditMessage && (
                        <ActionButton disabled={isGenerating} icon="edit" label="编辑旁白" className="chat-tool-btn" iconOnly editTriggerId={message.id} onClick={() => onEditMessage(message)} />
                      )}
                      <ActionButton icon="copy" label="复制" className="chat-tool-btn" iconOnly onClick={() => { void handleCopy(visibleContent); }} />
                      <div className="chat-msg-more-wrap">
                        <ActionButton
                          icon="more"
                          label="更多旁白操作"
                          className="chat-tool-btn"
                          iconOnly
                          menuTrigger
                          expanded={showMsgMenuId === message.id || mobileMenuId === message.id}
                          actionTriggerId={message.id}
                          onClick={() => toggleMessageMenu(message.id)}
                        />
                        {showMsgMenuId === message.id && (
                          <div className="chat-msg-more-menu" role="menu" aria-label="旁白操作">

                            {onPlayVoice && <ActionButton icon="volume" label="播放语音" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onPlayVoice(message); }} />}
                            {onBookmarkMessage && <ActionButton icon="bookmark" label="收藏" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onBookmarkMessage(message); }} />}
                            {onSetMessageContext && <ActionButton disabled={isGenerating} icon="book" label={message.include_in_context === false ? "恢复到上下文" : "排除上下文"} className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onSetMessageContext(message); }} />}
                            {onQuoteMessage && <ActionButton icon="quote" label="引用回复" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onQuoteMessage(message); }} />}
                            {onCreateBranch && <ActionButton disabled={isGenerating} icon="branch" label="从此创建故事线" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onCreateBranch(message); }} />}
                          <div className="chat-msg-menu-divider" role="separator" />
                              {onDeleteMessage && <ActionButton disabled={isGenerating} icon="delete" label="删除旁白" className="chat-msg-more-item chat-tool-btn-danger" role="menuitem" onClick={() => { setShowMsgMenuId(null); onDeleteMessage(message); }} />}
                            </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              ) : (
                <div className={`chat-msg ${isUser ? 'chat-msg-self' : 'chat-msg-other'}`}>
                  {!isUser && (() => {
                    const charExprs = expressionMap?.[message.character_id ?? -1];
                    const activeIdx = activeExpressions[message.character_id ?? -1] ?? 0;
                    const activeExpr = charExprs?.[activeIdx];
                    const exprImage = activeExpr?.image_path;

                    return (
                      <div style={{ position: 'relative' }}>
                        <div
                          className="chat-msg-avatar"
                          style={{
                            cursor: charExprs?.length ? 'pointer' : undefined,
                            background: exprImage
                              ? `url(${api.storageUrl(exprImage)}) center/cover`
                              : hasAvatar
                                ? `url(${api.mediaRefUrl(message.character_avatar_path)}) center/cover`
                                : message.character_name?.[0]
                                  ? 'var(--accent)'
                                  : 'var(--muted)',
                          }}
                          onClick={() => {
                            if (!charExprs?.length) return;
                            const nextIdx = (activeIdx + 1) % charExprs.length;
                            setActiveExpressions((prev) => ({ ...prev, [message.character_id!]: nextIdx }));
                          }}
                          title={activeExpr ? `${activeExpr.label || activeExpr.expression} (点击切换)` : undefined}
                        >
                          {!hasAvatar && !exprImage && <span>{message.character_name?.[0] || '?'}</span>}
                        </div>
                        {charExprs && charExprs.length > 1 && (
                          <span style={{
                            position: 'absolute', bottom: -2, right: -2,
                            background: 'var(--accent)', color: '#fff',
                            borderRadius: '50%', width: 14, height: 14,
                            fontSize: '0.55rem', display: 'flex',
                            alignItems: 'center', justifyContent: 'center',
                            lineHeight: 1, pointerEvents: 'none',
                          }}>{charExprs.length}</span>
                        )}
                      </div>
                    );
                  })()}

                  <div className="chat-msg-body">
                    {!isUser && (
                      <div className="chat-msg-name">{authorName}</div>
                    )}

                    <div className={`chat-bubble ${isUser ? 'bubble-self' : 'bubble-other'}`}
                      onTouchStart={() => handleTouchStart(message.id)}
                      onTouchMove={handleTouchEnd}
                      onTouchEnd={handleTouchEnd}
                      onTouchCancel={handleTouchEnd}
                    >
                      {visibleContent && (
                        <div className="chat-bubble-text">
                          <MarkdownRenderer content={visibleContent} highlightQuery={readOnly && searchHit?.id === message.id ? searchQuery : ''} />
                        </div>
                      )}
                      {choices.length > 0 && !isCurrentChoiceMessage && (
                        <div className="chat-choices" style={{ marginTop: 8 }}>
                          {choices.map((choice) => (
                            <button
                              className="btn btn-sm"
                              type="button"
                              key={choice}
                              disabled
                              title="历史选项仅供回看"
                            >
                              {choice}
                            </button>
                          ))}
                        </div>
                      )}
                      {showPromptDebug && <DebugBlock debug={structured} />}
                    </div>
                    {!isUser && <MessageUsageLine usage={messageUsage[String(message.id)]} />}

                    {!readOnly && showActions && (
                      <div className="chat-msg-tools">
                        {onEditMessage && (
                          <ActionButton disabled={isGenerating} icon="edit" label="编辑消息" className="chat-tool-btn" iconOnly editTriggerId={message.id} onClick={() => onEditMessage(message)} />
                        )}
                        <ActionButton icon="copy" label="复制" className="chat-tool-btn" iconOnly onClick={() => { void handleCopy(visibleContent); }} />
                        {onRegenerateBranch && !isUser && <ActionButton disabled={isGenerating} icon="regenerate" label="重新生成" className="chat-tool-btn" iconOnly onClick={() => onRegenerateBranch(message)} />}
                        <div className="chat-msg-more-wrap">
                          <ActionButton
                            icon="more"
                            label="更多消息操作"
                            className="chat-tool-btn"
                            iconOnly
                            menuTrigger
                            expanded={showMsgMenuId === message.id || mobileMenuId === message.id}
                            actionTriggerId={message.id}
                            onClick={() => toggleMessageMenu(message.id)}
                          />
                          {showMsgMenuId === message.id && (
                            <div className="chat-msg-more-menu" role="menu" aria-label="消息操作">

                              {onPlayVoice && message.speaker_type !== 'user' && <ActionButton icon="volume" label="播放语音" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onPlayVoice(message); }} />}
                              {onBookmarkMessage && <ActionButton icon="bookmark" label="收藏" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onBookmarkMessage(message); }} />}
                              {onSetMessageContext && <ActionButton disabled={isGenerating} icon="book" label={message.include_in_context === false ? "恢复到上下文" : "排除上下文"} className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onSetMessageContext(message); }} />}
                              {onQuoteMessage && <ActionButton icon="quote" label="引用回复" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onQuoteMessage(message); }} />}
                              {onCreateBranch && <ActionButton disabled={isGenerating} icon="branch" label="从此创建故事线" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onCreateBranch(message); }} />}

                            <div className="chat-msg-menu-divider" role="separator" />
                              {onDeleteMessage && <ActionButton disabled={isGenerating} icon="delete" label="删除消息" className="chat-msg-more-item chat-tool-btn-danger" role="menuitem" onClick={() => { setShowMsgMenuId(null); onDeleteMessage(message); }} />}
                            </div>
                          )}
                        </div>
                      </div>
                    )}
                  </div>

                  {isUser && (
                    <div className="chat-msg-avatar chat-msg-avatar-self">
                      <span>我</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {loadNewerError ? (
        <InlineQueryError
          message="较新消息加载失败"
          error={loadNewerError}
          retrying={loadingNewer}
          onRetry={onRetryLoadNewer}
        />
      ) : hasNewer && (
        <div className="history-window-actions">
          <button type="button" className="btn btn-ghost btn-sm" disabled={loadingNewer} onClick={onLoadNewer}>
            {loadingNewer ? '正在加载…' : '加载较新消息'}
          </button>
          <button type="button" className="btn btn-ghost btn-sm" disabled={loadingNewer} onClick={onJumpToLatest}>
            回到最新消息
          </button>
        </div>
      )}

      {/* 快速返回底部按钮 */}
      {!isAtBottom && reverseMessages.length > 0 && (
        <button type="button"
          className="scroll-bottom-btn"
          onClick={hasNewer ? onJumpToLatest : scrollToBottom}
          title={hasNewer ? '回到最新消息' : '滚动到底部'}
        >
          ↓
        </button>
      )}
      {/* 移动端长按操作栏 */}
      {mobileMenuId !== null && (() => {
        const msg = reverseMessages.find(m => m.id === mobileMenuId);
        if (!msg) return null;
        const isUserMessage = msg.speaker_type === 'user';
        const isNarratorMessage = msg.speaker_type === 'narrator' || msg.speaker_type === 'system';
        const visibleContent = stripChoicesFromMessageContent(msg.content ?? '');
        return (
          <div className="msg-actions-mobile-layer" onPointerDown={(event) => {
            if (event.target === event.currentTarget) closeMobileMenu();
          }}>
            <div ref={mobileMenuRef} className="msg-actions-mobile" role="dialog" aria-label="消息操作" aria-modal="true">
              <div className="msg-actions-header">
                <strong className="msg-actions-heading">消息操作</strong>
                <ActionButton icon="close" label="关闭" className="chat-tool-btn" iconOnly onClick={closeMobileMenu} />
              </div>
              <div className="msg-actions-preview" aria-label="所选消息" data-selected-message-id={msg.id}>
                <strong>{isUserMessage ? '你' : isNarratorMessage ? '旁白' : msg.character_name || '角色'}</strong>
                <p>{visibleContent.trim().slice(0, 240) || '此消息包含非文本内容'}</p>
              </div>
              <div className="msg-actions-body">
              {isGenerating && <p className="message-action-busy" role="status">回复生成中，修改类操作暂不可用。</p>}
              <div className="msg-actions-mobile-primary">
              <ActionButton icon="copy" label="复制" className="btn btn-sm" onClick={() => { closeMobileMenu(); void handleCopy(visibleContent); }} />
              {onEditMessage && <ActionButton disabled={isGenerating} icon="edit" label="编辑" className="btn btn-sm" onClick={() => { closeMobileMenu(); onEditMessage(msg); }} />}
              {onRegenerateBranch && !isUserMessage && !isNarratorMessage && (
                <ActionButton disabled={isGenerating} icon="regenerate" label="重新生成" className="btn btn-sm" onClick={() => { closeMobileMenu(); onRegenerateBranch(msg); }} />
              )}
              </div>
              {onSetMessageContext && <ActionButton disabled={isGenerating} icon="book" description="保留原文，调整后续回复使用的内容" label={msg.include_in_context === false ? '恢复到上下文' : '排除上下文'} className="btn btn-sm" onClick={() => { closeMobileMenu(); onSetMessageContext(msg); }} />}
              {onPlayVoice && !isUserMessage && (
                <ActionButton icon="volume" label="播放语音" className="btn btn-sm" onClick={() => { closeMobileMenu(); onPlayVoice(msg); }} />
              )}
              {onBookmarkMessage && (
                <ActionButton icon="bookmark" label="收藏" className="btn btn-sm" onClick={() => { closeMobileMenu(); onBookmarkMessage(msg); }} />
              )}
              {onQuoteMessage && (
                <ActionButton icon="quote" description="带上这条原文继续对话" label="引用" className="btn btn-sm" onClick={() => { closeMobileMenu(); onQuoteMessage(msg); }} />
              )}
              {onCreateBranch && (
                <ActionButton disabled={isGenerating} icon="branch" description="从这里展开另一条故事线" label="创建故事线" className="btn btn-sm" onClick={() => { closeMobileMenu(); onCreateBranch(msg); }} />
              )}
              {onDeleteMessage && <ActionButton disabled={isGenerating} icon="delete" label="删除" className="btn btn-sm btn-danger" onClick={() => { closeMobileMenu(); onDeleteMessage(msg); }} />}
              </div>
            </div>
          </div>
        );
      })()}
    </div>
  );
}
