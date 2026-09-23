import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';

import { api } from '../api/client';
import type { Message } from '../types';

export type MessageNavigationResult = {
  ok: boolean;
  latestMessageId: number | null;
  error?: unknown;
  superseded?: boolean;
};

export function useSessionMessages(sessionId: number, branchId: string) {
  const branchRef = useRef(branchId);
  const branchPropRef = useRef(branchId);
  if (branchPropRef.current !== branchId) {
    branchPropRef.current = branchId;
    branchRef.current = branchId;
  }
  const sessionRef = useRef(sessionId);
  sessionRef.current = sessionId;
  const requestVersionRef = useRef(0);
  const nextRequestIdRef = useRef(0);
  const initialScopeRef = useRef('');
  const activeFirstPageRequestRef = useRef<number | null>(null);
  const activeWindowRequestRef = useRef<number | null>(null);

  const [cursor, setCursor] = useState<number | undefined>();
  const [newerCursor, setNewerCursor] = useState<number | undefined>();
  const [messagePages, setMessagePages] = useState<Message[][]>([]);
  const messagePagesRef = useRef<Message[][]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [hasNewer, setHasNewer] = useState(false);
  const hasNewerRef = useRef(false);
  hasNewerRef.current = hasNewer;
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [messagesLoadingMore, setMessagesLoadingMore] = useState(false);
  const [messagesLoadingNewer, setMessagesLoadingNewer] = useState(false);
  const [messagesLocating, setMessagesLocating] = useState(false);
  const [messagesError, setMessagesError] = useState<unknown>(null);
  const [loadMoreError, setLoadMoreError] = useState<unknown>(null);
  const [loadNewerError, setLoadNewerError] = useState<unknown>(null);

  function updateMessagePages(updater: (current: Message[][]) => Message[][]) {
    const next = updater(messagePagesRef.current);
    messagePagesRef.current = next;
    setMessagePages(next);
  }

  type MessageRequest = {
    sessionId: number;
    branchId: string;
    version: number;
    requestId: number;
    kind: 'initial' | 'refresh';
  };

  type WindowSource = 'search' | 'older' | 'newer';
  function isCurrentRequest(request: MessageRequest) {
    return request.version === requestVersionRef.current
      && request.sessionId === sessionRef.current
      && request.branchId === branchRef.current;
  }

  const messagesQuery = useMutation({
    mutationFn: ({ sessionId: requestedSessionId, branchId: requestedBranchId }: MessageRequest) =>
      api.getMessages(requestedSessionId, undefined, requestedBranchId),
    onSuccess: (page, request) => {
      if (!isCurrentRequest(request)) return;
      updateMessagePages(() => [page.items]);
      setCursor(page.next_cursor ?? undefined);
      setHasMore(page.next_cursor !== null);
      setNewerCursor(undefined);
      setHasNewer(false);
      setMessagesError(null);
      setLoadMoreError(null);
      setLoadNewerError(null);
    },
    onError: (error, request) => {
      if (!isCurrentRequest(request)) return;
      setMessagesError(error);
    },
    onSettled: (_data, _error, request) => {
      if (activeFirstPageRequestRef.current === request.requestId) {
        activeFirstPageRequestRef.current = null;
        setMessagesLoading(false);
      }
    },
  });

  function requestFirstPage(clearCurrent: boolean) {
    const requestedSessionId = sessionRef.current;
    const requestedBranchId = branchRef.current;
    const version = ++requestVersionRef.current;
    const requestId = ++nextRequestIdRef.current;
    activeFirstPageRequestRef.current = requestId;
    activeWindowRequestRef.current = null;
    setMessagesLoading(true);
    setMessagesLoadingMore(false);
    setMessagesLoadingNewer(false);
    setMessagesLocating(false);
    setMessagesError(null);
    setLoadMoreError(null);
    if (clearCurrent) {
      updateMessagePages(() => []);
      setCursor(undefined);
      setHasMore(false);
      setNewerCursor(undefined);
      setHasNewer(false);
    }
    return messagesQuery.mutateAsync({
      sessionId: requestedSessionId,
      branchId: requestedBranchId,
      version,
      requestId,
      kind: clearCurrent ? 'initial' : 'refresh',
    }).then((page): MessageNavigationResult => {
      const current = isCurrentRequest({
        sessionId: requestedSessionId,
        branchId: requestedBranchId,
        version,
        requestId,
        kind: clearCurrent ? 'initial' : 'refresh',
      });
      return {
        ok: current,
        latestMessageId: current ? page.items[page.items.length - 1]?.id ?? null : null,
        superseded: !current,
      };
    }, (error): MessageNavigationResult => {
      const current = isCurrentRequest({
        sessionId: requestedSessionId,
        branchId: requestedBranchId,
        version,
        requestId,
        kind: clearCurrent ? 'initial' : 'refresh',
      });
      return {
        ok: false,
        latestMessageId: null,
        error: current ? error : undefined,
        superseded: !current,
      };
    });
  }

  useEffect(() => {
    if (!Number.isFinite(sessionId)) return;
    const scope = `${sessionId}\u0000${branchId}`;
    if (initialScopeRef.current === scope) return;
    initialScopeRef.current = scope;
    void requestFirstPage(true);
  }, [sessionId, branchId]);

  const flatMessages = useMemo(() => messagePages.flat(), [messagePages]);

  function appendOptimisticUserMessage(content: string) {
    const tempUserMessage: Message = {
      id: -Date.now(),
      session_id: sessionId,
      speaker_type: 'user',
      character_id: null,
      branch_id: branchId || 'main',
      content,
      structured_content: {},
      created_at: new Date().toISOString(),
    };
    updateMessagePages((pages) => {
      const next = [...pages];
      const lastIndex = next.length - 1;
      if (lastIndex >= 0) {
        next[lastIndex] = [...next[lastIndex], tempUserMessage];
      } else {
        next.push([tempUserMessage]);
      }
      return next;
    });
    return tempUserMessage.id;
  }

  function confirmUserMessage(savedMessage: Message, optimisticId?: number) {
    updateMessagePages((pages) => {
      const cleaned = pages.map((page) => page.filter((message) => message.id !== optimisticId));
      if (cleaned.some((page) => page.some((message) => message.id === savedMessage.id))) return cleaned;
      if (cleaned.length === 0) return [[savedMessage]];
      const lastIndex = cleaned.length - 1;
      cleaned[lastIndex] = [...cleaned[lastIndex], savedMessage];
      return cleaned;
    });
  }

  function appendPlaceholderMessage(message: Message) {
    updateMessagePages((pages) => {
      const next = [...pages];
      const lastIndex = next.length - 1;
      if (lastIndex >= 0) {
        next[lastIndex] = [...next[lastIndex], message];
      } else {
        next.push([message]);
      }
      return next;
    });
  }

  function applyStreamingDelta(streamKey: string | undefined, characterId: number | null, delta: string) {
    updateMessagePages((pages) =>
      pages.map((page) =>
        page.map((message) => {
          if (
            message.id > 0 ||
            (streamKey ? message.stream_key !== streamKey : message.character_id !== characterId)
          ) {
            return message;
          }
          const nextSpeech = `${String(message.structured_content.speech ?? '')}${delta}`;
          return {
            ...message,
            content: `${message.content}${delta}`,
            structured_content: { ...message.structured_content, speech: nextSpeech },
          };
        }),
      ),
    );
  }

  function finalizePlaceholderMessage(finalMessage: Message, streamKey?: string) {
    updateMessagePages((pages) =>
      pages.map((page) =>
        page.map((message) =>
          message.id < 0 &&
          (streamKey
            ? message.stream_key === streamKey
            : message.character_id === (finalMessage.character_id ?? null))
            ? finalMessage
            : message,
        ),
      ),
    );
  }

  function removeStreamingPlaceholder(streamKey?: string) {
    if (!streamKey) return;
    updateMessagePages((pages) => pages.map((page) => page.filter((message) => message.stream_key !== streamKey)));
  }

  function reloadMessages() {
    return requestFirstPage(true);
  }

  async function switchMessagesToBranch(nextBranchId: string): Promise<MessageNavigationResult> {
    const requestedSessionId = sessionRef.current;
    const requestedBranchId = nextBranchId.trim() || 'main';
    const previousBranchId = branchRef.current;
    if (requestedBranchId === previousBranchId) return requestFirstPage(false);

    const version = ++requestVersionRef.current;
    const requestId = ++nextRequestIdRef.current;
    activeFirstPageRequestRef.current = requestId;
    activeWindowRequestRef.current = null;
    setMessagesLoading(true);
    setMessagesLoadingMore(false);
    setMessagesLoadingNewer(false);
    setMessagesLocating(false);
    setLoadMoreError(null);
    setLoadNewerError(null);

    const isCurrentTransition = () => (
      version === requestVersionRef.current
      && requestedSessionId === sessionRef.current
      && previousBranchId === branchRef.current
      && activeFirstPageRequestRef.current === requestId
    );

    try {
      const page = await api.getMessages(requestedSessionId, undefined, requestedBranchId);
      if (!isCurrentTransition()) {
        return { ok: false, latestMessageId: null, superseded: true };
      }
      branchRef.current = requestedBranchId;
      initialScopeRef.current = `${requestedSessionId}\u0000${requestedBranchId}`;
      updateMessagePages(() => [page.items]);
      setCursor(page.next_cursor ?? undefined);
      setHasMore(page.next_cursor !== null);
      setNewerCursor(undefined);
      setHasNewer(false);
      setMessagesError(null);
      return {
        ok: true,
        latestMessageId: page.items[page.items.length - 1]?.id ?? null,
      };
    } catch (error) {
      if (!isCurrentTransition()) {
        return { ok: false, latestMessageId: null, superseded: true };
      }
      return { ok: false, latestMessageId: null, error };
    } finally {
      if (activeFirstPageRequestRef.current === requestId) {
        activeFirstPageRequestRef.current = null;
        setMessagesLoading(false);
      }
    }
  }

  function refreshMessages() {
    return requestFirstPage(false);
  }

  function applyMessageContext(messageId: number, include: boolean) {
    updateMessagePages((pages) => pages.map((page) => page.map((message) =>
      message.id === messageId ? { ...message, include_in_context: include } : message)));
  }

  function retryMessages() {
    // 首屏失败时先清空残留窗口；后台刷新失败时保留当前可见窗口供用户重试。
    return requestFirstPage(messagePages.length === 0);
  }

  async function loadMore() {
    const requestedCursor = cursor;
    if (
      requestedCursor === undefined
      || !hasMore
      || activeFirstPageRequestRef.current !== null
      || activeWindowRequestRef.current !== null
    ) {
      return null;
    }
    return loadAroundMessage(requestedCursor, 'older');
  }

  async function loadNewer() {
    const requestedCursor = newerCursor;
    if (
      requestedCursor === undefined
      || !hasNewer
      || activeFirstPageRequestRef.current !== null
      || activeWindowRequestRef.current !== null
    ) {
      return null;
    }
    return loadAroundMessage(requestedCursor, 'newer');
  }

  async function loadAroundMessage(anchorId: number, source: WindowSource = 'search') {
    const requestedSessionId = sessionRef.current;
    const requestedBranchId = branchRef.current;
    const version = ++requestVersionRef.current;
    const requestId = ++nextRequestIdRef.current;
    activeFirstPageRequestRef.current = null;
    activeWindowRequestRef.current = requestId;
    setMessagesLoading(false);
    setMessagesLoadingMore(source === 'older');
    setMessagesLoadingNewer(source === 'newer');
    setMessagesLocating(source === 'search');
    setLoadMoreError(null);
    setLoadNewerError(null);
    try {
      const page = await api.getMessageWindow(requestedSessionId, anchorId, requestedBranchId);
      if (
        version !== requestVersionRef.current
        || requestedSessionId !== sessionRef.current
        || requestedBranchId !== branchRef.current
        || activeWindowRequestRef.current !== requestId
      ) {
        return null;
      }
      updateMessagePages(() => [page.items]);
      setCursor(page.older_cursor ?? undefined);
      setHasMore(page.older_cursor !== null);
      setNewerCursor(page.newer_cursor ?? undefined);
      setHasNewer(page.newer_cursor !== null);
      setMessagesError(null);
      return page.items.some((message) => message.id === anchorId) ? anchorId : null;
    } catch (error) {
      if (
        version === requestVersionRef.current
        && requestedSessionId === sessionRef.current
        && requestedBranchId === branchRef.current
        && activeWindowRequestRef.current === requestId
      ) {
        if (source === 'older') setLoadMoreError(error);
        if (source === 'newer') setLoadNewerError(error);
      }
      if (source === 'search') throw error;
      return null;
    } finally {
      if (activeWindowRequestRef.current === requestId) {
        activeWindowRequestRef.current = null;
        setMessagesLoadingMore(false);
        setMessagesLoadingNewer(false);
        setMessagesLocating(false);
      }
    }
  }

  function jumpToLatest() {
    return requestFirstPage(false);
  }

  function ensureLatestMessages() {
    if (hasNewerRef.current) return requestFirstPage(false);
    const currentMessages = messagePagesRef.current.flat();
    return Promise.resolve<MessageNavigationResult>({
      ok: true,
      latestMessageId: currentMessages[currentMessages.length - 1]?.id ?? null,
    });
  }

  // [⚠ 避坑] React 开发环境下 effect 可能触发两次；
  // 首屏 effect 按会话/分支去重；业务刷新仍以 latest-request-wins 重新读取，不能被吞掉。

  return {
    cursor,
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
    retryLoadMore: loadMore,
    retryLoadNewer: loadNewer,
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
  };
}
