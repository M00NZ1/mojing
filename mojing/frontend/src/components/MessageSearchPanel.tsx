import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { MessageSearchHit } from '../types';
import InlineQueryError from './InlineQueryError';
import './MessageSearchPanel.css';

function SearchSnippet({ text, query }: { text: string; query: string }) {
  if (!query) return <>{text}</>;
  const escaped = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return <>{text.split(new RegExp(`(${escaped})`, 'giu')).map((part, index) =>
    index % 2 ? <mark key={index}>{part}</mark> : part)}</>;
}

function searchResultDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
}

export default function MessageSearchPanel({ sessionId, branchId, value, onChange, inputRef, onSelect, locatingId, locating, branchLabel, open = false, reading = false, selectedHit, locateFailure, onRetryLocate, onOpen, onClose, onExitReading }: {
  sessionId: number; branchId: string; value: string; onChange: (value: string) => void;
  inputRef: RefObject<HTMLInputElement>; onSelect: (hit: MessageSearchHit) => void;
  locatingId: number | null; locating: boolean; branchLabel: (id: string) => string;
  open?: boolean; reading?: boolean; selectedHit?: MessageSearchHit | null;
  locateFailure?: { hit: MessageSearchHit; message: string } | null; onRetryLocate?: () => void;
  onOpen?: () => void; onClose?: () => void; onExitReading?: () => void;
}) {
  const [query, setQuery] = useState('');
  const [recentQueries, setRecentQueries] = useState<string[]>([]);
  const [composing, setComposing] = useState(false);
  const stale = composing || value.trim() !== query;
  useEffect(() => {
    if (composing) return;
    if (!value.trim()) { setQuery(''); return; }
    const timer = window.setTimeout(() => setQuery(value.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [value, composing]);
  const scope = `${sessionId}:${branchId}:${query}`;
  const [navigation, setNavigation] = useState<{ scope: string; cursors: (number | undefined)[] }>({ scope: '', cursors: [undefined] });
  const [indexPaused, setIndexPaused] = useState(false);
  const [knownTotal, setKnownTotal] = useState<{ scope: string; count: number } | null>(null);
  const cursors = navigation.scope === scope ? navigation.cursors : [undefined];
  const listRef = useRef<HTMLUListElement>(null);
  const resultsRef = useRef<HTMLDivElement>(null);
  const pageCursor = cursors[cursors.length - 1];
  useLayoutEffect(() => {
    if (listRef.current) listRef.current.scrollTop = 0;
    if (resultsRef.current) resultsRef.current.scrollTop = 0;
  }, [scope, pageCursor]);
  const client = useQueryClient();
  const search = useQuery({ queryKey: ['session-message-search', sessionId, branchId, query, cursors[cursors.length - 1]],
    queryFn: ({ signal }) => api.searchMessagePage(sessionId, query, branchId, cursors[cursors.length - 1], signal, !indexPaused),
    enabled: Number.isFinite(sessionId) && query.length > 0 && !stale,
    refetchInterval: (state) => !stale && !indexPaused && state.state.status !== 'error' && state.state.data && !state.state.data.index.ready ? 300 : false,
    retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false, gcTime: 30_000 });
  useEffect(() => {
    const key = `mojing:message-search-history:${sessionId}:${branchId}`;
    try {
      const stored = JSON.parse(localStorage.getItem(key) || '[]');
      setRecentQueries(Array.isArray(stored) ? stored.filter((item): item is string => typeof item === 'string').slice(0, 8) : []);
    } catch { setRecentQueries([]); }
  }, [sessionId, branchId]);
  const rememberQuery = useCallback((term: string) => {
    const committed = term.trim();
    if (!committed) return;
    setRecentQueries((previous) => {
      const next = [committed, ...previous.filter((item) => item !== committed)].slice(0, 8);
      try { localStorage.setItem(`mojing:message-search-history:${sessionId}:${branchId}`, JSON.stringify(next)); } catch { /* storage is optional */ }
      return next;
    });
  }, [sessionId, branchId]);
  const closeSearch = useCallback(() => {
    rememberQuery(value);
    onClose?.();
  }, [rememberQuery, value, onClose]);
  const rebuilding = useMutation({ mutationFn: () => api.rebuildMessageSearchIndex(sessionId), onSuccess: () => {
    setIndexPaused(false); setKnownTotal(null); setNavigation({ scope, cursors: [undefined] });
    void client.invalidateQueries({ queryKey: ['session-message-search'] });
  } });
  const progress = search.data?.index;
  useEffect(() => {
    if (progress?.ready && typeof search.data?.total_count === 'number')
      setKnownTotal({ scope, count: search.data.total_count });
  }, [scope, progress?.ready, search.data?.total_count]);
  const selectedPageIndex = selectedHit ? search.data?.items.findIndex((hit) => hit.id === selectedHit.id) : undefined;
  const selectedPosition = selectedPageIndex !== undefined && selectedPageIndex >= 0
    ? (cursors.length - 1) * 25 + selectedPageIndex + 1 : null;
  const totalCount = progress?.ready
    ? typeof search.data?.total_count === 'number' ? search.data.total_count : knownTotal?.scope === scope ? knownTotal.count : null
    : null;
  const readingCount = selectedPosition !== null && totalCount !== null
    ? `第 ${selectedPosition} / ${totalCount} 条命中`
    : selectedPosition !== null ? `本页第 ${selectedPosition - (cursors.length - 1) * 25} 条 · 索引未完成`
      : totalCount !== null ? `共 ${totalCount} 条命中` : '匹配数量整理中';
  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229 || event.key !== 'Escape') return;
      event.preventDefault();
      if (reading) onExitReading?.();
      else closeSearch();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, reading, closeSearch, onExitReading]);

  return <section className={`message-search-panel${open ? ' is-open' : ''}${reading ? ' is-reading' : ''}`} aria-label="故事线搜索">
    <div className="message-search-input-row">
      {open && !reading && <button type="button" className="message-search-back" onClick={closeSearch} aria-label="关闭搜索" title="返回对话">←</button>}
      <input ref={inputRef} type="search" className="chat-message-search" placeholder="搜索当前故事线的消息" aria-label="搜索当前故事线的消息"
        onFocus={() => onOpen?.()}
        onKeyDown={(event) => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) rememberQuery(value); }}
        onCompositionStart={() => setComposing(true)} onCompositionEnd={() => setComposing(false)}
        value={value} maxLength={256} onChange={(event) => { onOpen?.(); onChange(event.target.value); }} autoComplete="off" />
    </div>
    {reading ? <div className="message-search-reading-bar" role="status">
      <div><strong>原文阅读 <small className="message-search-reading-count">{readingCount}</small></strong><span>{selectedHit?.snippet || '已定位到搜索命中消息'}</span></div>
      <button type="button" className="btn btn-ghost btn-sm" onClick={onExitReading}>返回搜索结果</button>
      <button type="button" className="btn btn-ghost btn-sm" onClick={closeSearch} aria-label="关闭搜索">关闭</button>
    </div> : open && <div className="message-search-results" ref={resultsRef}>
      <div className="message-search-heading"><span>当前故事线 · 最近在前</span><button type="button" className="btn btn-ghost btn-sm" disabled={search.isFetching || stale} onClick={() => void search.refetch()}>刷新搜索</button></div>
      {!value.trim() && <div className="message-search-history" aria-label="最近搜索">
        <strong>最近搜索</strong>
        {recentQueries.length ? recentQueries.map((item) => <button type="button" className="message-search-history-item" key={item} onClick={() => { rememberQuery(item); onChange(item); }}>{item}</button>) : <p>暂无搜索记录</p>}
      </div>}
      {value.trim() && <>
      {(search.isPending || stale) && <p role="status">搜索中…</p>}
      {progress && !progress.ready && <div className="message-search-index" role="status"><span>{indexPaused ? '索引已暂停' : '正在整理索引'} · 已处理 {progress.indexed_count} 条历史。完成后结果才完整。</span>
        <button type="button" className="btn btn-ghost btn-sm" disabled={stale} onClick={() => { setIndexPaused(!indexPaused); if (indexPaused) void search.refetch(); }}>{indexPaused ? '继续索引' : '暂停索引'}</button></div>}
      {!stale && search.isError && <InlineQueryError message="搜索失败" error={search.error} retrying={search.isFetching} onRetry={() => void search.refetch()} />}
      {!stale && search.isSuccess && !search.data.items.length && <p>{progress?.ready ? '无匹配消息' : '已索引部分暂无匹配消息'}</p>}
      {locateFailure && !search.data?.items.some((hit) => hit.id === locateFailure.hit.id) && <InlineQueryError message="原文定位失败" error={locateFailure.message} retrying={locating} onRetry={() => onRetryLocate?.()} />}
      <ul ref={listRef}>{search.data?.items.map((hit) => <li key={hit.id}><button type="button" disabled={locating || stale} onClick={() => { rememberQuery(value); onSelect(hit); }}>
        <span className="message-search-result-meta"><strong>{locatingId === hit.id ? '正在定位…' : hit.character_name || ({ user: '玩家', narrator: '旁白' }[hit.speaker_type] || '角色')}{hit.branch_id !== 'main' ? ` · ${branchLabel(hit.branch_id)}` : ''}</strong><time dateTime={hit.created_at}>{searchResultDate(hit.created_at)}</time></span>
        <span className="message-search-result-snippet"><SearchSnippet text={hit.snippet} query={query} /></span></button>
        {locateFailure?.hit.id === hit.id && <div className="message-search-locate-error"><InlineQueryError message="原文定位失败" error={locateFailure.message} retrying={locating} onRetry={() => onRetryLocate?.()} /></div>}
      </li>)}</ul>
      <div className="message-search-pagination"><button type="button" className="btn btn-ghost btn-sm" disabled={cursors.length === 1 || search.isFetching || stale} onClick={() => setNavigation({ scope, cursors: cursors.slice(0, -1) })}>较新结果</button>
        <span>第 {cursors.length} 页 · {search.data?.items.length ?? 0} 条</span><button type="button" className="btn btn-ghost btn-sm" disabled={!search.data?.next_cursor || search.isFetching || stale || !progress?.ready} onClick={() => setNavigation({ scope, cursors: [...cursors, search.data!.next_cursor!] })}>更早结果</button></div>
      <details className="message-search-tools"><summary>搜索维护</summary><p>可重建本机搜索索引，不会修改原始对话。关闭搜索会暂停尚未完成的整理。</p>
        <button type="button" className="btn btn-ghost btn-sm" disabled={rebuilding.isPending || search.isFetching || stale} onClick={() => rebuilding.mutate()}>{rebuilding.isPending ? '正在重建…' : '重建本机索引'}</button>
        {!stale && rebuilding.isError && <InlineQueryError message="重建失败，原始对话未改变" error={rebuilding.error} onRetry={() => rebuilding.mutate()} />}</details>
      </>}
    </div>}
  </section>;
}
