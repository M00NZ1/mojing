import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';

import { api } from '../api/client';
import type { Expression, Message, SessionBranch } from '../types';
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
}: ActionButtonProps) {
  return (
    <button
      type="button"
      className={className}
      onClick={onClick}
      title={iconOnly ? label : undefined}
      aria-label={iconOnly ? label : undefined}
      aria-haspopup={menuTrigger ? 'menu' : undefined}
      aria-expanded={menuTrigger ? expanded : undefined}
      role={role}
      data-message-actions-trigger={actionTriggerId}
      data-message-edit-trigger={editTriggerId}
    >
      <UiIcon name={icon} />
      {!iconOnly && <span>{label}</span>}
    </button>
  );
}

export default function MessageList({
  messages,
  branches = [],
  selectedBranchId = 'main',
  onSwitchBranch,
  loading,
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

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setShowMsgMenuId(null);
    };
    const closeOnOutsidePress = (event: PointerEvent) => {
      if (!(event.target instanceof Element)) return;
      if (showMsgMenuId !== null && !event.target.closest('.chat-msg-more-wrap')) {
        setShowMsgMenuId(null);
      }
    };

    document.addEventListener('keydown', closeOnEscape);
    document.addEventListener('pointerdown', closeOnOutsidePress, true);
    return () => {
      document.removeEventListener('keydown', closeOnEscape);
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
    if (msgId <= 0) return;
    if (longPressTimerRef.current) clearTimeout(longPressTimerRef.current);
    longPressTimerRef.current = setTimeout(() => {
      openMobileMenu(msgId);
    }, 500);
  }, [openMobileMenu]);

  const handleTouchEnd = useCallback(() => {
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }
  }, []);

  const toggleMessageMenu = useCallback((messageId: number) => {
    if (window.matchMedia('(max-width: 768px)').matches) {
      setShowMsgMenuId(null);
      if (mobileMenuId === messageId) closeMobileMenu();
      else openMobileMenu(messageId);
      return;
    }
    setMobileMenuId(null);
    setShowMsgMenuId((current) => current === messageId ? null : messageId);
  }, [closeMobileMenu, mobileMenuId, openMobileMenu]);

  const reverseMessages = useMemo(() => messages, [messages]);

  const rowVirtualizer = useVirtualizer({
    count: reverseMessages.length,
    getScrollElement: () => parentRef.current,
    getItemKey: (index) => reverseMessages[index]?.id ?? index,
    estimateSize: () => 180,
    overscan: 8,
  });
  rowVirtualizer.shouldAdjustScrollPositionOnItemSizeChange = () => !focusSettlingRef.current;

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
  }, [focusRequest?.requestId]);

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
        {rowVirtualizer.getVirtualItems().map((virtualRow) => {
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
              {(branchAnchors.length > 0 || canReturnToMain) && onSwitchBranch && (
                <MessageBranchBar
                  anchors={branchAnchors}
                  activeBranchId={selectedBranchId}
                  canReturnToMain={canReturnToMain}
                  onSwitchBranch={onSwitchBranch}
                />
              )}
              {message.include_in_context === false && <div className="hint" style={{ padding: '6px 12px' }} role="note">已排除上下文 · 原文保留</div>}
              {isNarrator ? (
                <div className="chat-msg-system">
                  <div
                    className="chat-msg-system-text"
                    onTouchStart={() => handleTouchStart(message.id)}
                    onTouchMove={handleTouchEnd}
                    onTouchEnd={handleTouchEnd}
                    onTouchCancel={handleTouchEnd}
                  >
                    <MarkdownRenderer content={visibleContent} />
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
                  {showNarratorActions && (
                    <div className="chat-msg-tools chat-msg-system-tools">
                      {onEditMessage && (
                        <ActionButton icon="edit" label="编辑旁白" className="chat-tool-btn" iconOnly editTriggerId={message.id} onClick={() => onEditMessage(message)} />
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
                            {onSetMessageContext && <ActionButton icon="book" label={message.include_in_context === false ? "恢复到上下文" : "排除上下文"} className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onSetMessageContext(message); }} />}
                            {onQuoteMessage && <ActionButton icon="quote" label="引用回复" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onQuoteMessage(message); }} />}
                            {onCreateBranch && <ActionButton icon="branch" label="从此创建故事线" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onCreateBranch(message); }} />}
                          <div className="chat-msg-menu-divider" role="separator" />
                              {onDeleteMessage && <ActionButton icon="delete" label="删除旁白" className="chat-msg-more-item chat-tool-btn-danger" role="menuitem" onClick={() => { setShowMsgMenuId(null); onDeleteMessage(message); }} />}
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
                        <div className="chat-bubble-text"><MarkdownRenderer content={visibleContent} /></div>
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

                    {showActions && (
                      <div className="chat-msg-tools">
                        {onEditMessage && (
                          <ActionButton icon="edit" label="编辑消息" className="chat-tool-btn" iconOnly editTriggerId={message.id} onClick={() => onEditMessage(message)} />
                        )}
                        <ActionButton icon="copy" label="复制" className="chat-tool-btn" iconOnly onClick={() => { void handleCopy(visibleContent); }} />
                        {onRegenerateBranch && !isUser && <ActionButton icon="regenerate" label="重新生成" className="chat-tool-btn" iconOnly onClick={() => onRegenerateBranch(message)} />}
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
                              {onSetMessageContext && <ActionButton icon="book" label={message.include_in_context === false ? "恢复到上下文" : "排除上下文"} className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onSetMessageContext(message); }} />}
                              {onQuoteMessage && <ActionButton icon="quote" label="引用回复" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onQuoteMessage(message); }} />}
                              {onCreateBranch && <ActionButton icon="branch" label="从此创建故事线" className="chat-msg-more-item" role="menuitem" onClick={() => { setShowMsgMenuId(null); onCreateBranch(message); }} />}

                            <div className="chat-msg-menu-divider" role="separator" />
                              {onDeleteMessage && <ActionButton icon="delete" label="删除消息" className="chat-msg-more-item chat-tool-btn-danger" role="menuitem" onClick={() => { setShowMsgMenuId(null); onDeleteMessage(message); }} />}
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
              <strong className="msg-actions-heading">消息操作</strong>
              <div className="msg-actions-mobile-primary">
              <ActionButton icon="copy" label="复制" className="btn btn-sm" onClick={() => { closeMobileMenu(); void handleCopy(visibleContent); }} />
              {onEditMessage && <ActionButton icon="edit" label="编辑" className="btn btn-sm" onClick={() => { closeMobileMenu(); onEditMessage(msg); }} />}
              {onRegenerateBranch && !isUserMessage && !isNarratorMessage && (
                <ActionButton icon="regenerate" label="重新生成" className="btn btn-sm" onClick={() => { closeMobileMenu(); onRegenerateBranch(msg); }} />
              )}
              </div>
              {onSetMessageContext && <ActionButton icon="book" label={msg.include_in_context === false ? '恢复到上下文' : '排除上下文'} className="btn btn-sm" onClick={() => { closeMobileMenu(); onSetMessageContext(msg); }} />}
              {onPlayVoice && !isUserMessage && (
                <ActionButton icon="volume" label="播放语音" className="btn btn-sm" onClick={() => { closeMobileMenu(); onPlayVoice(msg); }} />
              )}
              {onBookmarkMessage && (
                <ActionButton icon="bookmark" label="收藏" className="btn btn-sm" onClick={() => { closeMobileMenu(); onBookmarkMessage(msg); }} />
              )}
              {onQuoteMessage && (
                <ActionButton icon="quote" label="引用" className="btn btn-sm" onClick={() => { closeMobileMenu(); onQuoteMessage(msg); }} />
              )}
              {onCreateBranch && (
                <ActionButton icon="branch" label="创建故事线" className="btn btn-sm" onClick={() => { closeMobileMenu(); onCreateBranch(msg); }} />
              )}
              {onDeleteMessage && <ActionButton icon="delete" label="删除" className="btn btn-sm btn-danger" onClick={() => { closeMobileMenu(); onDeleteMessage(msg); }} />}
              <ActionButton icon="close" label="关闭" className="btn btn-ghost btn-sm" onClick={closeMobileMenu} />
            </div>
          </div>
        );
      })()}
    </div>
  );
}
