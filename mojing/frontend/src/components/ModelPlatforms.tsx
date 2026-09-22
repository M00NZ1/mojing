import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { defaultRangeExtractor, useVirtualizer } from '@tanstack/react-virtual';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { confirmModal } from './ConfirmModal';
import { ModelNamePicker } from './ModelNamePicker';
import './ModelPlatforms.css';

import type { ModelPlatform, ModelCatalog, ModelSelection } from '../types';
const catalogKey = ['model-platforms'];
const emptyPlatform = (): ModelPlatform => ({ id: crypto.randomUUID(), name: '', base_url: '', api_key: '', models: [], selected_model: '' });
export const parseModelNames = (text: string) => [...new Set(text.split(/[,，\n]/).map((s) => s.trim()).filter(Boolean))];
const errorText = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试。';

export function ModelPlatformsPanel({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const queryClient = useQueryClient();
  const catalog = useQuery({ queryKey: catalogKey, queryFn: api.getModelPlatforms });
  const providers = useQuery({ queryKey: ['providers'], queryFn: api.listProviderCatalog });
  const [draft, setDraft] = useState<ModelPlatform | null>(null);
  const [original, setOriginal] = useState('');
  const [modelText, setModelText] = useState('');
  const [error, setError] = useState('');
  const [discoveryNotice, setDiscoveryNotice] = useState('');
  const [fetching, setFetching] = useState(false);
  const fetchRef = useRef<AbortController | null>(null);
  const presetChangeRef = useRef<AbortController | null>(null);
  const editorRef = useRef<HTMLDivElement>(null);
  const dirty = draft !== null && JSON.stringify({ ...draft, models: parseModelNames(modelText) }) !== original;
  useEffect(() => { onDirtyChange(dirty); }, [dirty, onDirtyChange]);
  useEffect(() => () => { fetchRef.current?.abort(); onDirtyChange(false); }, [onDirtyChange]);
  useEffect(() => () => presetChangeRef.current?.abort(), []);
  function updateSaved(saved: ModelCatalog) {
    queryClient.setQueryData(catalogKey, saved);
    void queryClient.invalidateQueries({ queryKey: ['local-config'] });
  }
  const save = useMutation({
    mutationFn: (value: ModelPlatform) => api.saveModelPlatform(value),
    onSuccess: (saved) => { updateSaved(saved); setDraft(null); setError(''); },
    onError: (e) => setError(errorText(e)),
  });
  const activate = useMutation({
    mutationFn: api.setDefaultModelPlatform,
    onSuccess: updateSaved,
    onError: (e) => setError(errorText(e)),
  });
  async function edit(platform: ModelPlatform | null) {
    if (dirty && !await confirmModal('放弃未保存的修改？', '当前平台的修改尚未保存。', 'warning', { confirmLabel: '放弃修改' })) return;
    fetchRef.current?.abort();
    const value = platform ?? emptyPlatform();
    setDraft(value); setOriginal(JSON.stringify(value)); setModelText(value.models.join('\n')); setError(''); setDiscoveryNotice('');
  }
  useEffect(() => { if (draft) editorRef.current?.querySelector<HTMLInputElement>('input')?.focus(); }, [draft?.id]);
  function changeAddress(address: string) {
    fetchRef.current?.abort();
    setDraft((value) => value && ({ ...value, base_url: address, api_key: '', models: [], selected_model: '' }));
    setModelText(''); setError(''); setDiscoveryNotice('');
  }
  async function applyPreset(providerId: string) {
    const preset = providers.data?.find((p) => p.provider_id === providerId);
    if (!preset) return;
    presetChangeRef.current?.abort();
    const controller = new AbortController();
    presetChangeRef.current = controller;
    const confirmed = !dirty || await confirmModal('切换服务商预设？',
      '当前未保存的修改将被替换。已保存的平台保持不变，新平台需要重新填写 Key 和模型。', 'warning',
      { confirmLabel: '切换预设', cancelLabel: '继续编辑', signal: controller.signal });
    if (controller.signal.aborted || !confirmed) return;
    fetchRef.current?.abort();
    setDraft({ ...emptyPlatform(), name: preset.label, base_url: preset.base_url });
    setModelText(''); setError(''); setDiscoveryNotice('');
  }
  async function discover() {
    if (!draft) return;
    const controller = new AbortController();
    fetchRef.current?.abort(); fetchRef.current = controller;
    setFetching(true); setError(''); setDiscoveryNotice('');
    try {
      const result = await api.discoverModels(draft, controller.signal);
      if (controller.signal.aborted) return;
      const existing = parseModelNames(modelText);
      const merged = [...new Set([...existing, ...result.models])];
      setModelText(merged.join('\n'));
      setDraft((value) => value && ({ ...value, selected_model: merged.includes(value.selected_model) ? value.selected_model : merged[0] ?? '' }));
      setDiscoveryNotice(`已补充 ${merged.length - existing.length} 个模型，共 ${merged.length} 个。`);
    } catch (e) { if (!controller.signal.aborted) setError(errorText(e)); }
    finally { if (fetchRef.current === controller) { fetchRef.current = null; setFetching(false); } }
  }
  const models = parseModelNames(modelText);
  return <section className="model-platforms">
    <div className="model-platform-heading"><div><h3>文字对话平台</h3><p>每个平台独立保存 Key 与模型。默认平台用于未单独配置的对话和创作。</p></div>
      <button className="btn btn-primary btn-sm" type="button" disabled={save.isPending || activate.isPending} onClick={() => { void edit(null); }}>添加平台</button></div>
    {catalog.isPending && <p role="status">正在读取已保存的平台…</p>}
    {catalog.isError && <p role="alert">{errorText(catalog.error)} <button type="button" className="btn btn-sm" onClick={() => { void catalog.refetch(); }}>重试</button></p>}
    {catalog.data?.platforms.length === 0 && <div className="model-platform-empty">添加第一个平台，填写 Key 后获取模型，或手动输入模型名称。</div>}
    <div className="model-platform-list">{catalog.data?.platforms.map((platform) => <article className="model-platform-row" key={platform.id}>
      <div><strong>{platform.name}</strong> {platform.id === catalog.data.active_id && <span className="pill pill-green">默认</span>}
        <p>{platform.selected_model || '尚未选择模型'} · {platform.models.length} 个模型</p><small>{platform.base_url}</small></div>
      <div className="model-platform-actions"><button className="btn btn-sm" type="button" disabled={save.isPending || activate.isPending} onClick={() => { void edit(platform); }}>编辑</button>
        {platform.id !== catalog.data.active_id && <button className="btn btn-ghost btn-sm" type="button" disabled={save.isPending || activate.isPending} onClick={() => activate.mutate(platform.id)}>设为默认</button>}</div>
    </article>)}</div>
    {error && <p className="model-platform-error" role="alert">{error}</p>}
    {draft && <div className="model-platform-editor" ref={editorRef}>
      <h4>{catalog.data?.platforms.some((p) => p.id === draft.id) ? '编辑平台' : '添加平台'}</h4>
      <fieldset disabled={save.isPending}><div className="model-platform-fields">
        <label>平台名称<input value={draft.name} maxLength={100} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="例如：我的 DeepSeek" /></label>
        <label>服务商预设<select value="" onChange={(e) => { void applyPreset(e.target.value); }}><option value="">选择预设填写地址</option>{providers.data?.map((p) => <option value={p.provider_id} key={p.provider_id}>{p.label}</option>)}</select></label>
        <label className="model-platform-wide">API 地址<input value={draft.base_url} onChange={(e) => changeAddress(e.target.value)} placeholder="https://…/v1" /></label>
        <label className="model-platform-wide">API Key<input type="password" autoComplete="off" value={draft.api_key} onChange={(e) => { fetchRef.current?.abort(); setDraft({ ...draft, api_key: e.target.value }); }} placeholder="填写当前平台的 Key" /><small>更改地址会清空 Key 与模型，请重新填写。</small></label>
        <div className="model-platform-wide model-platform-actions"><button type="button" className="btn btn-sm" disabled={fetching || !draft.api_key || !draft.base_url} onClick={() => { void discover(); }}>{fetching ? '正在获取…' : '获取平台全部模型'}</button>
          {fetching && <button type="button" className="btn btn-ghost btn-sm" onClick={() => fetchRef.current?.abort()}>取消获取</button>}</div>
        {discoveryNotice && <p className="model-platform-wide" role="status">{discoveryNotice}</p>}
        <label className="model-platform-wide">模型名称 · {models.length} 个<textarea rows={6} value={modelText} disabled={fetching} onChange={(e) => setModelText(e.target.value)} placeholder="每行一个，也可用逗号分隔；不支持获取时直接填写。" /></label>
        <div className="model-platform-wide model-platform-model-field"><span>默认模型</span><ModelNamePicker models={models} value={draft.selected_model} onChange={(model) => setDraft({ ...draft, selected_model: model })} /></div>
      </div></fieldset>
      <div className="model-platform-actions"><button type="button" className="btn btn-primary" disabled={save.isPending || fetching || !draft.name.trim() || !draft.api_key.trim() || !models.includes(draft.selected_model)} onClick={() => save.mutate({ ...draft, models })}>{save.isPending ? '正在保存…' : '保存平台'}</button>
        <button type="button" className="btn btn-ghost" disabled={save.isPending} onClick={async () => { if (!dirty || await confirmModal('放弃未保存的修改？', '已保存的平台不会受到影响。', 'warning', { confirmLabel: '放弃修改' })) { fetchRef.current?.abort(); setDraft(null); setError(''); } }}>取消</button></div>
    </div>}
    {providers.isError && <p role="alert">服务商预设加载失败，仍可手动填写地址。<button type="button" onClick={() => { void providers.refetch(); }}>重试</button></p>}
  </section>;
}

export function ChatModelPicker({ sessionId, onBusyChange }: { sessionId: number; onBusyChange: (busy: boolean) => void }) {
  const queryClient = useQueryClient();
  const catalog = useQuery({ queryKey: catalogKey, queryFn: api.getModelPlatforms });
  const choiceKey = ['chat-model-choice', sessionId];
  const choice = useQuery({ queryKey: choiceKey, queryFn: () => api.getModelChoice(sessionId) });
  const [search, setSearch] = useState('');
  const dialog = useRef<HTMLDialogElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const choosingRef = useRef(false);
  const [keyboardIndex, setKeyboardIndex] = useState<number | null>(null);
  const focusRequested = useRef(false);
  const choose = useMutation({
    mutationFn: (selection: ModelSelection | null) => api.setModelChoice(sessionId, selection),
    onSuccess: (saved) => { queryClient.setQueryData(choiceKey, saved); dialog.current?.close(); },
    onSettled: () => { choosingRef.current = false; onBusyChange(false); },
  });
  function selectModel(selection: ModelSelection | null) {
    if (choosingRef.current) return;
    choosingRef.current = true; onBusyChange(true); choose.mutate(selection);
  }
  useEffect(() => { onBusyChange(choice.isPending || choice.isError || choose.isPending); }, [choice.isPending, choice.isError, choose.isPending, onBusyChange]);
  useEffect(() => { dialog.current?.close(); setSearch(''); choose.reset(); }, [sessionId]);
  const selection = choice.data?.selection;
  const selectedPlatform = catalog.data?.platforms.find((p) => p.id === selection?.platform_id);
  const options = (catalog.data?.platforms ?? []).flatMap((platform) => platform.models
    .filter((model) => `${platform.name} ${model}`.toLowerCase().includes(search.trim().toLowerCase()))
    .map((model) => ({ platform, model })));
  const virtualizer = useVirtualizer({ count: options.length, getScrollElement: () => listRef.current, estimateSize: () => 68, overscan: 6,
    rangeExtractor: (range) => [...new Set([...defaultRangeExtractor(range),
      ...(keyboardIndex !== null && keyboardIndex < options.length ? [keyboardIndex] : [])])].sort((a, b) => a - b),
  });
  useLayoutEffect(() => {
    if (!focusRequested.current || keyboardIndex === null) return;
    listRef.current?.querySelector<HTMLButtonElement>(`[data-model-index="${keyboardIndex}"]`)?.focus({ preventScroll: true });
    focusRequested.current = false;
  }, [keyboardIndex]);
  const label = selection ? `${selectedPlatform?.name ?? '平台不可用'} · ${selection.model}` : '跟随角色与模型设置';
  return <>
    <button type="button" className="chat-model-trigger" title={`切换模型：${label}`} onClick={() => { choose.reset(); setSearch(''); setKeyboardIndex(null); dialog.current?.showModal(); virtualizer.measure(); }}>{choice.isPending ? '正在读取模型…' : label} ▾</button>
    <dialog ref={dialog} className="chat-model-dialog" aria-labelledby="chat-model-title" onKeyDown={(e) => { if (e.key === 'Escape' && (e.nativeEvent.isComposing || e.keyCode === 229)) { e.preventDefault(); e.stopPropagation(); } }} onCancel={(e) => { if (choose.isPending) e.preventDefault(); }}>
      <div className="model-platform-heading"><h3 id="chat-model-title">选择对话模型</h3><button className="btn btn-ghost btn-sm" type="button" disabled={choose.isPending} onClick={() => dialog.current?.close()}>关闭</button></div>
      <p hidden={Boolean(search.trim())}>从下一次发送生效，当前回复保持原模型。手动选择会优先于角色独立配置和思考模式。</p>
      <input aria-label="搜索平台或模型" value={search} onChange={(e) => { setSearch(e.target.value); setKeyboardIndex(null); focusRequested.current = false; }} placeholder="搜索平台或模型名称" />
      {(catalog.isError || choice.isError) && <p role="alert">模型配置加载失败。<button type="button" className="btn btn-sm" onClick={() => { void catalog.refetch(); void choice.refetch(); }}>重试</button></p>}
      {choose.isPending && <p role="status" className="chat-model-saving">正在保存模型选择…</p>}
      {choose.isError && <p role="alert" className="model-platform-error">{errorText(choose.error)}</p>}
      <button className="chat-model-option" type="button" aria-pressed={!selection} disabled={choose.isPending} onClick={() => selectModel(null)}>跟随角色与模型设置 {!selection && <span aria-hidden="true">✓</span>}</button>
      <div className="chat-model-options" ref={listRef} onKeyDown={(event) => {
        if (event.nativeEvent.isComposing || event.keyCode === 229 || choose.isPending) return;
        if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
        const current = Number((event.target as HTMLElement).closest<HTMLElement>('[data-model-index]')?.dataset.modelIndex);
        if (!Number.isInteger(current)) return;
        event.preventDefault();
        const direction = event.key === 'ArrowUp' || event.key === 'End' ? -1 : 1;
        let next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : current + direction;
        while (next >= 0 && next < options.length && !options[next].platform.api_key) next += direction;
        if (next < 0 || next >= options.length || next === keyboardIndex) return;
        focusRequested.current = true;
        setKeyboardIndex(next);
        virtualizer.scrollToIndex(next, { align: 'auto' });
      }} onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setKeyboardIndex(null); }}>
        <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
          {virtualizer.getVirtualItems().map((row) => {
            const { platform, model } = options[row.index];
            return <button className="chat-model-option chat-model-virtual-option" style={{ position: 'absolute', top: 0, transform: `translateY(${row.start}px)`, height: row.size }} type="button" data-model-index={row.index} onFocus={() => setKeyboardIndex(row.index)} aria-pressed={selection?.platform_id === platform.id && selection.model === model} key={`${platform.id}:${model}`} disabled={choose.isPending || !platform.api_key} onClick={() => selectModel({ platform_id: platform.id, model })} title={`${platform.name} · ${model}`}><small>{platform.name}{!platform.api_key && ' · 请先配置 Key'}</small><span>{model}{selection?.platform_id === platform.id && selection.model === model && <span className="chat-model-check" aria-hidden="true">✓</span>}</span></button>;
          })}
        </div>
        {catalog.isPending && <p role="status">正在加载平台…</p>}
        {catalog.data && !catalog.data.platforms.some((p) => p.models.some((m) => `${p.name} ${m}`.toLowerCase().includes(search.trim().toLowerCase()))) && <p>没有可选的匹配模型。请先在模型服务中添加平台和模型。</p>}
      </div>
      <Link className="btn btn-ghost" to="/settings?tab=api" onClick={(e) => { if (choose.isPending) e.preventDefault(); else dialog.current?.close(); }}>管理平台与模型</Link>
    </dialog>
  </>;
}
