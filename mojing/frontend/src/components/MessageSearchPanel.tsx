import { useDeferredValue, useLayoutEffect, useRef, useState, type RefObject } from 'react';
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

export default function MessageSearchPanel({ sessionId, branchId, value, onChange, inputRef, onSelect, locatingId, locating, branchLabel }: {
  sessionId: number; branchId: string; value: string; onChange: (value: string) => void;
  inputRef: RefObject<HTMLInputElement>; onSelect: (hit: MessageSearchHit) => void;
  locatingId: number | null; locating: boolean; branchLabel: (id: string) => string;
}) {
  const query = useDeferredValue(value.trim());
  const scope = `${sessionId}:${branchId}:${query}`;
  const [navigation, setNavigation] = useState<{ scope: string; cursors: (number | undefined)[] }>({ scope: '', cursors: [undefined] });
  const [indexPaused, setIndexPaused] = useState(false);
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
    enabled: Number.isFinite(sessionId) && query.length > 0,
    refetchInterval: (state) => !indexPaused && state.state.status !== 'error' && state.state.data && !state.state.data.index.ready ? 300 : false,
    retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false, gcTime: 30_000 });
  const rebuilding = useMutation({ mutationFn: () => api.rebuildMessageSearchIndex(sessionId), onSuccess: () => {
    setIndexPaused(false); setNavigation({ scope, cursors: [undefined] });
    void client.invalidateQueries({ queryKey: ['session-message-search'] });
  } });
  const stale = value.trim() !== query;
  const progress = search.data?.index;
  return <section className="message-search-panel" aria-label="故事线搜索">
    <input ref={inputRef} type="search" className="chat-message-search" placeholder="搜索当前故事线的消息" aria-label="搜索当前故事线的消息"
      value={value} maxLength={256} onChange={(event) => onChange(event.target.value)} autoComplete="off" />
    {query && <div className="message-search-results" ref={resultsRef}>
      <div className="message-search-heading"><span>当前故事线 · 最近在前</span><button type="button" className="btn btn-ghost btn-sm" disabled={search.isFetching} onClick={() => void search.refetch()}>刷新搜索</button></div>
      {(search.isPending || stale) && <p role="status">搜索中…</p>}
      {progress && !progress.ready && <div className="message-search-index" role="status"><span>{indexPaused ? '索引已暂停' : '正在整理索引'} · 已处理 {progress.indexed_count} 条历史。完成后结果才完整。</span>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setIndexPaused(!indexPaused); if (indexPaused) void search.refetch(); }}>{indexPaused ? '继续索引' : '暂停索引'}</button></div>}
      {search.isError && <InlineQueryError message="搜索失败" error={search.error} retrying={search.isFetching} onRetry={() => void search.refetch()} />}
      {search.isSuccess && !search.data.items.length && <p>{progress?.ready ? '无匹配消息' : '已索引部分暂无匹配消息'}</p>}
      <ul ref={listRef}>{search.data?.items.map((hit) => <li key={hit.id}><button type="button" disabled={locating || stale} onClick={() => onSelect(hit)}>
        <strong>{locatingId === hit.id ? '正在定位…' : hit.character_name || ({ user: '玩家', narrator: '旁白' }[hit.speaker_type] || '角色')}{hit.branch_id !== 'main' ? ` · ${branchLabel(hit.branch_id)}` : ''}</strong>
        <span><SearchSnippet text={hit.snippet} query={query} /></span></button></li>)}</ul>
      <div className="message-search-pagination"><button type="button" className="btn btn-ghost btn-sm" disabled={cursors.length === 1 || search.isFetching || stale} onClick={() => setNavigation({ scope, cursors: cursors.slice(0, -1) })}>较新结果</button>
        <span>第 {cursors.length} 页 · {search.data?.items.length ?? 0} 条</span><button type="button" className="btn btn-ghost btn-sm" disabled={!search.data?.next_cursor || search.isFetching || stale || !progress?.ready} onClick={() => setNavigation({ scope, cursors: [...cursors, search.data!.next_cursor!] })}>更早结果</button></div>
      <details className="message-search-tools"><summary>搜索维护</summary><p>可重建本机搜索索引，不会修改原始对话。关闭搜索会暂停尚未完成的整理。</p>
        <button type="button" className="btn btn-ghost btn-sm" disabled={rebuilding.isPending || search.isFetching} onClick={() => rebuilding.mutate()}>{rebuilding.isPending ? '正在重建…' : '重建本机索引'}</button>
        {rebuilding.isError && <InlineQueryError message="重建失败，原始对话未改变" error={rebuilding.error} onRetry={() => rebuilding.mutate()} />}</details>
    </div>}
  </section>;
}
