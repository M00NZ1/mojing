import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { defaultRangeExtractor, useVirtualizer } from '@tanstack/react-virtual';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { confirmModal } from './ConfirmModal';
import { ModelNamePicker } from './ModelNamePicker';
import './ModelPlatforms.css';

import type { ModelPlatform, ModelCatalog, ModelPrice, ModelSelection } from '../types';
const catalogKey = ['model-platforms'];
const emptyPlatform = (): ModelPlatform => ({ id: crypto.randomUUID(), name: '', base_url: '', api_key: '', models: [], selected_model: '' });
export const parseModelNames = (text: string) => [...new Set(text.split(/[,，\n]/).map((s) => s.trim()).filter(Boolean))];
const errorText = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试。';
type PriceDraft = { currency: string; input: string; output: string; cached: string };
const priceToDraft = (price: ModelPrice): PriceDraft => ({ currency: price.currency,
  input: String(price.input_per_million), output: String(price.output_per_million), cached: String(price.cached_input_per_million) });

function PriceEditor({ platformId, models }: { platformId: string; models: string[] }) {
  const queryClient = useQueryClient();
  const prices = useQuery({ queryKey: ['model-prices', platformId], queryFn: () => api.getModelPrices(platformId) });
  const [drafts, setDrafts] = useState<Record<string, PriceDraft>>({});
  const [selectedModel, setSelectedModel] = useState(models[0] ?? '');
  const [syncHistory, setSyncHistory] = useState(false);
  const lookupRef = useRef<AbortController | null>(null);
  const [lookupModel, setLookupModel] = useState('');
  const [lookupState, setLookupState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [lookupMessage, setLookupMessage] = useState('');
  const [lookupSuggestion, setLookupSuggestion] = useState<ModelPrice | null>(null);
  const [priceError, setPriceError] = useState('');
  useEffect(() => { if (!models.includes(selectedModel)) setSelectedModel(models[0] ?? ''); }, [models, selectedModel]);
  useEffect(() => {
    lookupRef.current?.abort();
    setLookupState('idle'); setLookupMessage(''); setLookupSuggestion(null); setPriceError(''); setSyncHistory(false);
    return () => lookupRef.current?.abort();
  }, [selectedModel]);
  const save = useMutation({
    mutationFn: ({ price, sync }: { price: ModelPrice; sync: boolean; draft?: PriceDraft }) => api.saveModelPrice(platformId, { ...price, sync_history: sync }),
    onSuccess: (saved, submitted) => {
      queryClient.setQueryData(['model-prices', platformId], (current: { platform_id: string; items: ModelPrice[] } | undefined) => ({
        platform_id: platformId,
        items: [...(current?.items ?? []).filter((item) => item.model_name !== saved.model_name), saved],
      }));
      setDrafts((current) => {
        if (current[saved.model_name] !== submitted.draft) return current;
        const next = { ...current }; delete next[saved.model_name]; return next;
      });
      setSyncHistory(false);
      setPriceError('');
    },
  });
  const priceFor = (model: string) => drafts[model] ?? priceToDraft(prices.data?.items.find((item) => item.model_name === model) ?? {
    model_name: model, currency: 'USD', input_per_million: 0, output_per_million: 0, cached_input_per_million: 0,
  });
  const isPersistedConfigured = (model: string) => Boolean(prices.data?.items.some((item) => item.model_name === model));
  function update(model: string, patch: Partial<PriceDraft>) {
    setDrafts((current) => ({ ...current, [model]: { ...(current[model] ?? priceFor(model)), ...patch } }));
    setPriceError(''); if (!save.isPending) save.reset();
  }
  const price = selectedModel ? priceFor(selectedModel) : null;
  const persistedConfigured = selectedModel ? isPersistedConfigured(selectedModel) : false;
  const saving = save.isPending && save.variables?.price.model_name === selectedModel;
  function savePrice() {
    if (!selectedModel || !price) return;
    const required = [price.input, price.output];
    const values = [price.input, price.output, price.cached || '0'].map((value) => Number(value));
    if (required.some((value) => !value.trim()) || values.some((value) => !Number.isFinite(value) || value < 0)) {
      setPriceError('请输入大于等于 0 的有效单价；缓存输入价格可以留空。'); return;
    }
    setPriceError('');
    save.mutate({ price: { model_name: selectedModel, currency: price.currency,
      input_per_million: values[0], output_per_million: values[1], cached_input_per_million: values[2] },
      sync: syncHistory, draft: drafts[selectedModel] });
  }
  async function lookupPrice() {
    if (!selectedModel) return;
    lookupRef.current?.abort();
    const controller = new AbortController();
    lookupRef.current = controller;
    setLookupModel(selectedModel); setLookupState('loading'); setLookupMessage(''); setLookupSuggestion(null);
    try {
      const found = await api.discoverModelPrice(platformId, selectedModel, controller.signal);
      if (controller.signal.aborted) return;
      setLookupSuggestion(found);
      setLookupState('ready'); setLookupMessage('已读取平台报价。确认后填入价格表单。');
    } catch (error) {
      if (controller.signal.aborted) return;
      setLookupState('error'); setLookupMessage(errorText(error));
    } finally {
      if (lookupRef.current === controller) lookupRef.current = null;
    }
  }
  return <section className="model-platform-editor-section model-price-editor" aria-labelledby="model-price-title">
    <div className="model-platform-section-heading"><h4 id="model-price-title">模型价格</h4><p>按每百万 Token 填写输入、输出与缓存输入价格。未配置时用量会显示费用未知。</p></div>
    {prices.isPending && <p role="status" className="model-price-state">正在读取价格…</p>}
    {prices.isError && <p role="alert" className="model-platform-error">价格读取失败。<button type="button" className="btn btn-sm" onClick={() => { void prices.refetch(); }}>重试</button></p>}
    {!prices.isPending && <div className="model-price-list">
      {models.length === 0 && <p className="model-price-state">先添加至少一个模型，再配置价格。</p>}
      {models.length > 0 && <>
        <div className="model-price-picker"><span>选择模型</span><ModelNamePicker models={models} value={selectedModel} onChange={(model) => { setSelectedModel(model); save.reset(); }} /></div>
        {price && <div className={`model-price-row${persistedConfigured ? '' : ' is-unconfigured'}`}>
          <div className="model-price-model"><strong>{selectedModel}</strong><span>{drafts[selectedModel] ? '有未保存的修改' : persistedConfigured ? `${price.currency} · 已配置` : '尚未配置价格'}</span></div>
          <div className="model-price-discovery">
            <button type="button" className="btn btn-sm" disabled={lookupState === 'loading' && lookupModel === selectedModel} onClick={() => { void lookupPrice(); }}>{lookupState === 'loading' && lookupModel === selectedModel ? '正在读取…' : '从平台读取价格'}</button>
            {lookupMessage && lookupModel === selectedModel && <span role={lookupState === 'error' ? 'alert' : 'status'}>{lookupMessage}</span>}
            {lookupSuggestion && lookupModel === selectedModel && <div className="model-price-suggestion">
              <span>{lookupSuggestion.currency} · 输入 {lookupSuggestion.input_per_million} / 输出 {lookupSuggestion.output_per_million} / 缓存 {lookupSuggestion.cached_input_per_million}</span>
              <button type="button" className="btn btn-sm" onClick={() => { setDrafts((current) => ({ ...current, [selectedModel]: priceToDraft(lookupSuggestion) })); setLookupSuggestion(null); setLookupMessage('平台报价已填入，请核对并保存。'); setPriceError(''); if (!save.isPending) save.reset(); }}>填入报价</button>
            </div>}
          </div>
          <div className="model-price-fields">
            <label>币种<select value={price.currency} onChange={(e) => update(selectedModel, { currency: e.target.value })}><option value="USD">美元 USD</option><option value="CNY">人民币 CNY</option></select></label>
            <label>输入价格 / 百万 Token<input type="text" inputMode="decimal" value={price.input} onChange={(e) => update(selectedModel, { input: e.target.value })} /></label>
            <label>输出价格 / 百万 Token<input type="text" inputMode="decimal" value={price.output} onChange={(e) => update(selectedModel, { output: e.target.value })} /></label>
            <label>缓存输入价格 / 百万 Token<input type="text" inputMode="decimal" value={price.cached} onChange={(e) => update(selectedModel, { cached: e.target.value })} /></label>
          </div>
          <div className="model-price-actions">
            <div className="model-price-history">
              {persistedConfigured ? <label className="model-price-sync"><input type="checkbox" checked={syncHistory} onChange={(e) => setSyncHistory(e.target.checked)} />同步更新历史费用</label> : <strong>首次配置</strong>}
              <span>{persistedConfigured ? syncHistory ? '仅重算当前平台、此模型的历史记录。' : '旧记录费用保持不变，新单价用于之后的请求。' : '保存后补算当前平台、此模型尚未计价的历史记录。'}</span>
            </div>
            <button type="button" className="btn btn-primary" disabled={saving} onClick={savePrice}>{saving ? '保存中…' : '保存价格'}</button>
          </div>
          {priceError && <p className="model-platform-error" role="alert">{priceError}</p>}
          {save.isError && save.variables?.price.model_name === selectedModel && <p className="model-platform-error" role="alert">{errorText(save.error)}</p>}
          {save.isSuccess && save.data.model_name === selectedModel && <p className="model-price-success" role="status">价格已保存{save.data.recalculated_count != null ? `，已重算 ${save.data.recalculated_count} 条记录` : ''}。</p>}
        </div>}
      </>}
    </div>}
  </section>;
}

export function ModelPlatformsPanel({ onDirtyChange, onEditingChange }: { onDirtyChange: (dirty: boolean) => void; onEditingChange: (editing: boolean) => void }) {
  const queryClient = useQueryClient();
  const catalog = useQuery({ queryKey: catalogKey, queryFn: api.getModelPlatforms });
  const providers = useQuery({ queryKey: ['providers'], queryFn: api.listProviderCatalog });
  const [draft, setDraft] = useState<ModelPlatform | null>(null);
  const [selectedPlatformId, setSelectedPlatformId] = useState('');
  const [original, setOriginal] = useState('');
  const [modelText, setModelText] = useState('');
  const [error, setError] = useState('');
  const [discoveryNotice, setDiscoveryNotice] = useState('');
  const [fetching, setFetching] = useState(false);
  const fetchRef = useRef<AbortController | null>(null);
  const presetChangeRef = useRef<AbortController | null>(null);
  const editorRef = useRef<HTMLDivElement>(null);
  const dirty = draft !== null && JSON.stringify({ ...draft, models: parseModelNames(modelText) }) !== original;
  const editing = draft !== null;
  useEffect(() => { onDirtyChange(dirty); }, [dirty, onDirtyChange]);
  useEffect(() => { onEditingChange(editing); }, [editing, onEditingChange]);
  useEffect(() => () => { fetchRef.current?.abort(); onDirtyChange(false); onEditingChange(false); }, [onDirtyChange, onEditingChange]);
  useEffect(() => () => presetChangeRef.current?.abort(), []);
  function updateSaved(saved: ModelCatalog) {
    queryClient.setQueryData(catalogKey, saved);
    void queryClient.invalidateQueries({ queryKey: ['local-config'] });
  }
  const save = useMutation({
    mutationFn: (value: ModelPlatform) => api.saveModelPlatform(value),
    onSuccess: (saved, value) => { updateSaved(saved); setSelectedPlatformId(value.id); setDraft(null); setError(''); },
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
  async function closeEditor() {
    if (dirty && !await confirmModal('放弃未保存的修改？', '已保存的平台不会受到影响。', 'warning', { confirmLabel: '放弃修改', cancelLabel: '继续编辑' })) return;
    fetchRef.current?.abort();
    setDraft(null); setError(''); setDiscoveryNotice('');
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
  const selectedPlatform = catalog.data?.platforms.find((platform) => platform.id === selectedPlatformId)
    ?? catalog.data?.platforms.find((platform) => platform.id === catalog.data.active_id)
    ?? catalog.data?.platforms[0];
  return <section className="model-platforms">
    {!draft && <>
    <div className="model-platform-heading"><div><h3>文字对话平台</h3><p>每个平台独立保存 Key 与模型。默认平台用于未单独配置的对话和创作。</p></div>
      <button className="btn btn-primary btn-sm" type="button" disabled={save.isPending || activate.isPending} onClick={() => { void edit(null); }}>添加平台</button></div>
    {catalog.isPending && <p role="status">正在读取已保存的平台…</p>}
    {catalog.isError && <p role="alert">{errorText(catalog.error)} <button type="button" className="btn btn-sm" onClick={() => { void catalog.refetch(); }}>重试</button></p>}
    {catalog.data?.platforms.length === 0 && <div className="model-platform-empty">添加第一个平台，填写 Key 后获取模型，或手动输入模型名称。</div>}
    {!!catalog.data?.platforms.length && <div className="model-platform-browser">
      <div className="model-platform-tabs" role="group" aria-label="选择配置平台">
        {catalog.data.platforms.map((platform) => <button type="button" key={platform.id}
          aria-pressed={platform.id === selectedPlatform?.id} title={platform.name}
          onClick={() => { setSelectedPlatformId(platform.id); setError(''); }}>
          <span>{platform.name}</span>{platform.id === catalog.data.active_id && <small>默认</small>}
        </button>)}
      </div>
      {selectedPlatform && <article className="model-platform-row" aria-label="当前平台详情">
        <div className="model-platform-detail-heading"><div><h4>{selectedPlatform.name}</h4><p>{selectedPlatform.id === catalog.data.active_id ? '当前默认平台' : '独立连接配置'}</p></div>
          <div className="model-platform-actions"><button className="btn btn-sm" type="button" disabled={save.isPending || activate.isPending} onClick={() => { void edit(selectedPlatform); }}>编辑</button>
            {selectedPlatform.id !== catalog.data.active_id && <button className="btn btn-ghost btn-sm" type="button" disabled={save.isPending || activate.isPending} onClick={() => activate.mutate(selectedPlatform.id)}>{activate.isPending ? '正在切换…' : '设为默认'}</button>}</div>
        </div>
        <dl className="model-platform-details">
          <div><dt>默认模型</dt><dd>{selectedPlatform.selected_model || '尚未选择模型'}</dd></div>
          <div><dt>可用模型</dt><dd>{selectedPlatform.models.length} 个</dd></div>
          <div className="model-platform-wide"><dt>API 地址</dt><dd>{selectedPlatform.base_url}</dd></div>
          <div><dt>API Key</dt><dd>{selectedPlatform.api_key ? '已配置' : '未配置'}</dd></div>
        </dl>
      </article>}
    </div>}
    </>}
    {!draft && error && <p className="model-platform-error" role="alert">{error}</p>}
    {draft && <div className="model-platform-editor" ref={editorRef}>
      <header className="model-platform-editor-heading">
        <button type="button" className="model-platform-return" disabled={save.isPending} onClick={() => { void closeEditor(); }}>返回平台列表</button>
        <div><h3>{catalog.data?.platforms.some((p) => p.id === draft.id) ? (draft.name.trim() || '编辑平台') : '添加平台'}</h3>
          <p>{draft.id === catalog.data?.active_id ? '当前默认平台' : '每个平台独立保存连接与模型'}</p></div>
      </header>
      {error && <p className="model-platform-error" role="alert">{error}</p>}
      <fieldset disabled={save.isPending}>
      <section className="model-platform-editor-section" aria-labelledby="model-platform-connection-title">
        <div className="model-platform-section-heading"><h4 id="model-platform-connection-title">连接信息</h4><p>名称、地址和 Key 只属于此平台。</p></div>
        <div className="model-platform-fields">
        <label>平台名称<input value={draft.name} maxLength={100} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="例如：我的 DeepSeek" /></label>
        <label>服务商预设<select value="" onChange={(e) => { void applyPreset(e.target.value); }}><option value="">选择预设填写地址</option>{providers.data?.map((p) => <option value={p.provider_id} key={p.provider_id}>{p.label}</option>)}</select></label>
        {providers.isError && <p className="model-platform-wide model-platform-error" role="alert">服务商预设加载失败，仍可手动填写地址。<button type="button" onClick={() => { void providers.refetch(); }}>重试</button></p>}
        <label className="model-platform-wide">API 地址<input value={draft.base_url} onChange={(e) => changeAddress(e.target.value)} placeholder="https://…/v1" /></label>
        <label className="model-platform-wide">API Key<input type="password" autoComplete="off" value={draft.api_key} onChange={(e) => { fetchRef.current?.abort(); setDraft({ ...draft, api_key: e.target.value }); }} placeholder="填写当前平台的 Key" /><small>更改地址会清空 Key 与模型，请重新填写。</small></label>
        <div className="model-platform-wide model-platform-actions"><button type="button" className="btn btn-sm" disabled={fetching || !draft.api_key || !draft.base_url} onClick={() => { void discover(); }}>{fetching ? '正在获取…' : '获取平台全部模型'}</button>
          {fetching && <button type="button" className="btn btn-ghost btn-sm" onClick={() => fetchRef.current?.abort()}>取消获取</button>}</div>
        {discoveryNotice && <p className="model-platform-wide" role="status">{discoveryNotice}</p>}
        </div>
      </section>
      <section className="model-platform-editor-section" aria-labelledby="model-platform-models-title">
        <div className="model-platform-section-heading"><h4 id="model-platform-models-title">可用模型</h4><p>获取模型失败时可手动填写；聊天页能在已保存的平台和模型间切换。</p></div>
        <div className="model-platform-fields">
        <label className="model-platform-wide">模型名称 · {models.length} 个<textarea rows={6} value={modelText} disabled={fetching} onChange={(e) => setModelText(e.target.value)} placeholder="每行一个，也可用逗号分隔；不支持获取时直接填写。" /></label>
        <div className="model-platform-wide model-platform-model-field"><span>默认模型</span><ModelNamePicker models={models} value={draft.selected_model} onChange={(model) => setDraft({ ...draft, selected_model: model })} /></div>
        </div>
      </section>
      {catalog.data?.platforms.some((platform) => platform.id === draft.id) && <PriceEditor
        key={draft.id}
        platformId={draft.id}
        models={catalog.data.platforms.find((platform) => platform.id === draft.id)?.models ?? []}
      />}
      </fieldset>
      <div className="model-platform-editor-actions"><span>{dirty ? '有未保存修改' : '暂无修改'}</span><div className="model-platform-actions"><button type="button" className="btn btn-primary" disabled={save.isPending || fetching || !draft.name.trim() || !draft.api_key.trim() || !models.includes(draft.selected_model)} onClick={() => save.mutate({ ...draft, models })}>{save.isPending ? '正在保存…' : '保存平台'}</button>
        <button type="button" className="btn btn-ghost" disabled={save.isPending} onClick={() => { void closeEditor(); }}>取消</button></div></div>
    </div>}
  </section>;
}

export function ChatModelPicker({ sessionId, onBusyChange }: { sessionId: number; onBusyChange: (busy: boolean) => void }) {
  const queryClient = useQueryClient();
  const catalog = useQuery({ queryKey: catalogKey, queryFn: api.getModelPlatforms });
  const choiceKey = ['chat-model-choice', sessionId];
  const choice = useQuery({ queryKey: choiceKey, queryFn: () => api.getModelChoice(sessionId) });
  const [search, setSearch] = useState('');
  const [platformFilter, setPlatformFilter] = useState('');
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
  useEffect(() => { dialog.current?.close(); setSearch(''); setPlatformFilter(''); choose.reset(); }, [sessionId]);
  const selection = choice.data?.selection;
  const selectedPlatform = catalog.data?.platforms.find((p) => p.id === selection?.platform_id);
  const activeFilter = catalog.data?.platforms.some((p) => p.id === platformFilter) ? platformFilter : '';
  const options = useMemo(() => {
    const query = search.trim().toLowerCase();
    return (catalog.data?.platforms ?? []).filter((p) => !activeFilter || p.id === activeFilter)
      .flatMap((platform) => platform.models.filter((model) => `${platform.name} ${model}`.toLowerCase().includes(query))
        .map((model) => ({ platform, model })));
  }, [catalog.data, search, activeFilter]);
  useLayoutEffect(() => {
    listRef.current?.scrollTo({ top: 0 });
    setKeyboardIndex(null); focusRequested.current = false;
  }, [search, activeFilter]);
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
      <div className="chat-model-platform-filters" role="group" aria-label="平台筛选">
        <button type="button" aria-pressed={!activeFilter} onClick={() => setPlatformFilter('')}>全部平台</button>
        {catalog.data?.platforms.map((platform) => <button type="button" key={platform.id} title={platform.name}
          aria-pressed={activeFilter === platform.id} onClick={(event) => {
            setPlatformFilter(platform.id); event.currentTarget.scrollIntoView({ block: 'nearest', inline: 'nearest' });
          }}>{platform.name} <span>{platform.models.length}</span></button>)}
      </div>
      <small className="chat-model-result-count" role="status">{activeFilter ? '当前平台' : '全部平台'} · {options.length} 个匹配模型</small>
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
        {catalog.data && options.length === 0 && <p>没有匹配模型，请调整关键词、切换平台或在模型服务中添加模型。</p>}
      </div>
      <Link className="btn btn-ghost" to="/settings?tab=api" onClick={(e) => { if (choose.isPending) e.preventDefault(); else dialog.current?.close(); }}>管理平台与模型</Link>
    </dialog>
  </>;
}
