import { useEffect, useId, useMemo, useRef, useState } from 'react';

const PAGE_SIZE = 10;

export function ModelNamePicker({ models, value, onChange }: {
  models: string[];
  value: string;
  onChange: (model: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const results = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const filtered = useMemo(() => models.filter((model) => model.toLowerCase().includes(query.trim().toLowerCase())), [models, query]);
  const lastPage = Math.max(0, Math.ceil(filtered.length / PAGE_SIZE) - 1);
  const currentPage = Math.min(page, lastPage);
  useEffect(() => { results.current?.scrollTo({ top: 0 }); }, [currentPage, query]);
  useEffect(() => {
    if (!open) return;
    search.current?.focus();
    const dismiss = (event: PointerEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', dismiss);
    return () => document.removeEventListener('pointerdown', dismiss);
  }, [open]);
  return <div className="model-name-picker" ref={root} onKeyDown={(event) => {
    if (event.key !== 'Escape' || event.nativeEvent.isComposing || event.keyCode === 229 || !open) return;
    event.preventDefault(); event.stopPropagation(); setOpen(false); trigger.current?.focus();
  }}>
    <button type="button" ref={trigger} className="model-name-trigger" aria-label="默认模型" aria-expanded={open} aria-controls={panelId}
      title={value || '选择默认模型'} onClick={() => { setQuery(''); setPage(0); setOpen(!open); }}>
      <span>{value || '选择默认模型'}</span><span aria-hidden="true">▾</span>
    </button>
    {open && <div id={panelId} className="model-name-panel" role="region" aria-label="默认模型选项">
      <input ref={search} aria-label="搜索默认模型" placeholder="搜索模型名称" value={query} onChange={(event) => { setQuery(event.target.value); setPage(0); }} />
      <div className="model-name-results" ref={results}>
        {filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE).map((model) =>
          <button type="button" className="chat-model-option" key={model} aria-pressed={value === model} title={model}
            onClick={() => { onChange(model); setOpen(false); trigger.current?.focus(); }}>{model}</button>)}
        {filtered.length === 0 && <p role="status">没有匹配模型，请在模型名称中添加。</p>}
      </div>
      <div className="model-name-pagination">
        <button type="button" className="btn btn-ghost btn-sm" aria-label="上一页模型" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>上一页</button>
        <span aria-live="polite">{filtered.length} 个 · {currentPage + 1}/{lastPage + 1}</span>
        <button type="button" className="btn btn-ghost btn-sm" aria-label="下一页模型" disabled={currentPage === lastPage} onClick={() => setPage(currentPage + 1)}>下一页</button>
      </div>
    </div>}
  </div>;
}
