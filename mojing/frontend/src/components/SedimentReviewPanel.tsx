import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import './SedimentReviewPanel.css';

export type SedimentReviewLocation = { filter: string; cursors: (number | null)[]; anchorId?: number };

export default function SedimentReviewPanel({ encyclopediaId, onOpenEntry, onClose, initialLocation }: {
  encyclopediaId: number;
  onOpenEntry: (id: number, type: string, location: SedimentReviewLocation) => void;
  onClose: (location: SedimentReviewLocation) => void;
  initialLocation?: SedimentReviewLocation;
}) {
  const cache = useQueryClient();
  const [filter, setFilter] = useState(initialLocation?.filter ?? 'pending');
  const [cursors, setCursors] = useState<(number | null)[]>(initialLocation?.cursors ?? [null]);
  const cursor = cursors[cursors.length - 1];
  const query = useQuery({ queryKey: ['encyclopedia-sediment', encyclopediaId, filter, cursor],
    queryFn: () => api.listEncyclopediaSedimentPage(encyclopediaId, filter, cursor), gcTime: 0 });
  const panel = useRef<HTMLElement>(null);
  useEffect(() => { panel.current?.scrollIntoView({ block: 'start' }); }, [filter, cursor]);
  const restoreAnchor = useRef(initialLocation?.anchorId);
  useEffect(() => {
    if (!query.isSuccess || restoreAnchor.current == null) return;
    const target = panel.current?.querySelector<HTMLButtonElement>(`[data-sediment-entry="${restoreAnchor.current}"]`);
    restoreAnchor.current = undefined;
    target?.scrollIntoView({ block: 'center' });
    target?.focus({ preventScroll: true });
  }, [query.data, query.isSuccess]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [notice, setNotice] = useState('');
  const submitting = useRef(false);
  const visible = query.data?.items ?? [];
  useEffect(() => {
    if (query.data) setSelected((ids) => new Set([...ids].filter((id) => query.data.items.some((row) => row.id === id && row.confidence !== 'confirmed'))));
  }, [query.data]);
  const confirm = useMutation({
    mutationFn: (ids: number[]) => api.confirmEncyclopediaSedimentEntries(encyclopediaId, ids),
    onSuccess: (result, ids) => {
      setSelected(new Set()); setNotice(`已确认 ${result.confirmed} 条资料`);
      void cache.invalidateQueries({ queryKey: ['encyclopedia-sediment', encyclopediaId] });
      void cache.invalidateQueries({ queryKey: ['encyclopedia-entries', encyclopediaId] });
      ids.forEach((id) => { void cache.invalidateQueries({ queryKey: ['entry-detail', id] }); });
    },
    onSettled: () => { submitting.current = false; },
  });
  return <section ref={panel} className="sediment-review" aria-label="沉淀资料核对">
    <div className="button-row"><h2>沉淀资料</h2><button className="btn btn-ghost btn-sm" type="button" disabled={confirm.isPending} onClick={() => onClose({ filter, cursors })}>关闭</button></div>
    <p className="hint">核对对话整理出的资料，确认后保留正文与来源。点击标题可查看和编辑详情。</p>
    <div className="button-row" aria-label="确认状态筛选">
      {([['all', '全部'], ['pending', '待核对'], ['confirmed', '已确认']] as const).map(([key, label]) =>
        <button key={key} type="button" className="btn btn-sm" aria-pressed={filter === key} disabled={confirm.isPending}
          onClick={() => { setFilter(key); setCursors([null]); setSelected(new Set()); setNotice(''); confirm.reset(); }}>{label}</button>)}
    </div>
    <div className="sediment-batch-actions">
      <span>本页 {visible.length} 条 · 已选 {selected.size} 条</span>
      <button type="button" className="btn btn-sm" disabled={confirm.isPending || !visible.some((row) => row.confidence !== 'confirmed')}
        onClick={() => setSelected(new Set(visible.filter((row) => row.confidence !== 'confirmed').slice(0, 100).map((row) => row.id)))}>选择本页</button>
      <button type="button" className="btn btn-ghost btn-sm" disabled={confirm.isPending || selected.size === 0} onClick={() => setSelected(new Set())}>清空</button>
      <button type="button" className="btn btn-primary btn-sm" disabled={confirm.isPending || selected.size === 0} onClick={() => {
        if (submitting.current) return;
        submitting.current = true; setNotice(''); confirm.mutate([...selected]);
      }}>{confirm.isPending ? '确认中…' : '确认所选'}</button>
    </div>
    {notice && <p role="status">{notice}</p>}
    {confirm.isError && <p role="alert">确认失败，选择已保留，请重试。</p>}
    {query.isPending && <p role="status">正在读取沉淀资料…</p>}
    {query.isError && <div role="alert">沉淀资料读取失败。<button type="button" className="btn btn-sm" onClick={() => { void query.refetch(); }}>重试</button></div>}
    <div className="stack-list">
      {!query.isError && visible.map((row) => <article className="mini-card sediment-review-row" key={row.id}>
        {row.confidence !== 'confirmed' && <input type="checkbox" aria-label={`选择资料：${row.title}`} checked={selected.has(row.id)}
          disabled={confirm.isPending || (!selected.has(row.id) && selected.size >= 100)} onChange={(event) => {
            const checked = event.target.checked;
            setSelected((ids) => { const next = new Set(ids); if (checked) next.add(row.id); else next.delete(row.id); return next; });
          }} />}
        <div><button type="button" className="sediment-entry-title" data-sediment-entry={row.id} disabled={confirm.isPending} onClick={() => onOpenEntry(row.id, row.entry_type, { filter, cursors, anchorId: row.id })}>{row.title}</button>
          <span className="pill pill-sm">{row.confidence === 'confirmed' ? '已确认' : '待核对'}</span>
          {row.source_session_id != null && <small>来源会话 #{row.source_session_id}</small>}
          {row.summary && <p>{row.summary}</p>}
        </div>
      </article>)}
      {query.isSuccess && visible.length === 0 && <p className="hint">当前筛选下没有资料。</p>}
    </div>
    <nav className="sediment-pagination" aria-label="资料翻页">
      <button type="button" className="btn btn-sm" disabled={confirm.isPending || query.isFetching || cursors.length === 1}
        onClick={() => { setSelected(new Set()); setNotice(''); confirm.reset(); setCursors((items) => items.slice(0, -1)); }}>上一页</button>
      <span>第 {cursors.length} 页</span>
      <button type="button" className="btn btn-sm" disabled={confirm.isPending || query.isFetching || query.isError || query.data?.next_cursor == null}
        onClick={() => { const next = query.data?.next_cursor; if (next != null) { setSelected(new Set()); setNotice(''); confirm.reset(); setCursors((items) => [...items, next]); } }}>下一页</button>
    </nav>
  </section>;
}
