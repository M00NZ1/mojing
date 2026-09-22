import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { formatTokens, formatUsageDate, formatUsd, providerLabel, readUsageFilters, UsageFilters, UsageLoadState, UsageMetrics, usageSearch } from '../components/UsageDisplay';
import type { UsageModel, UsageRequest, UsageTotals } from '../types';
import './UsagePage.css';

function modelTotals(model: UsageModel): UsageTotals {
  return {
    cost_usd: model.cost_usd, total_tokens: model.total_tokens, total_calls: model.calls,
    success_calls: model.success_calls, failed_calls: model.failed_calls, duration_ms: model.duration_ms,
  };
}

function RequestRow({ record }: { record: UsageRequest }) {
  return <details className="usage-request">
    <summary>
      <span className={`usage-request-status ${record.success ? 'is-success' : 'is-failed'}`}>{record.success ? '成功' : '失败'}</span>
      <span className="usage-request-time">{formatUsageDate(record.created_at)}</span>
      <span className="usage-request-tokens">{formatTokens(record.total_tokens)} Token</span>
      <strong>{formatUsd(record.estimated_cost)}</strong>
      <span className="usage-list-arrow" aria-hidden="true">⌄</span>
    </summary>
    <div className="usage-request-detail">
      <dl>
        <div><dt>输入 Token</dt><dd>{formatTokens(record.prompt_tokens)}</dd></div>
        <div><dt>输出 Token</dt><dd>{formatTokens(record.completion_tokens)}</dd></div>
        <div><dt>合计 Token</dt><dd>{formatTokens(record.total_tokens)}</dd></div>
        <div><dt>耗时</dt><dd>{record.duration_ms > 0 ? `${(record.duration_ms / 1000).toFixed(1)} 秒` : '未记录'}</dd></div>
        <div><dt>请求编号</dt><dd>#{record.id}</dd></div>
        <div><dt>来源对话</dt><dd>{record.session_id ? <Link to={`/chat/${record.session_id}`}>查看对话 #{record.session_id}</Link> : '未关联对话'}</dd></div>
      </dl>
    </div>
  </details>;
}

export default function UsagePage({ level }: { level: 'platform' | 'model' }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = readUsageFilters(searchParams);
  const provider = searchParams.get('provider')?.trim() || '';
  const hasModel = searchParams.has('model');
  const modelName = searchParams.get('model') ?? '';
  const valid = Boolean(provider) && (level === 'platform' || hasModel);
  const summaryUrl = `/settings?${usageSearch(filters, { tab: 'costs' })}`;
  const platformUrl = `/usage/platform?${usageSearch(filters, { provider })}`;
  const modelsQuery = useQuery({
    queryKey: ['cost-models', provider, filters.days, filters.status],
    queryFn: () => api.costModels(provider, filters),
    enabled: valid,
  });
  const currentModel = modelsQuery.data?.items.find((item) => item.model_name === modelName);
  const recordsQuery = useInfiniteQuery({
    queryKey: ['cost-model-records', provider, modelName, filters.days, filters.status],
    queryFn: ({ pageParam }) => api.costModelRecords(provider, modelName, { ...filters, beforeId: pageParam }),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (lastPage) => lastPage.next_cursor ?? undefined,
    enabled: valid && level === 'model' && Boolean(currentModel),
  });
  const records = recordsQuery.data?.pages.flatMap((page) => page.items) ?? [];

  return <main className="usage-page">
    <nav className="usage-breadcrumb" aria-label="用量层级">
      <Link to={summaryUrl}>用量汇总</Link><span aria-hidden="true">/</span>
      {level === 'model' ? <><Link to={platformUrl}>{providerLabel(provider)}</Link><span aria-hidden="true">/</span><strong>模型请求</strong></> : <strong>平台明细</strong>}
    </nav>
    <header className="usage-page-header">
      <div>
        <p className="eyebrow">{level === 'model' ? providerLabel(provider) : '平台用量'}</p>
        <h1>{level === 'model' ? (modelName || '未记录模型') : providerLabel(provider)}</h1>
        <p>{level === 'model' ? '逐次查看请求 Token、费用、耗时与来源对话。' : '查看该平台下每个模型的请求与费用。'}</p>
      </div>
      <Link className="usage-page-back" to={level === 'model' ? platformUrl : summaryUrl}>← 返回{level === 'model' ? '平台' : '汇总'}</Link>
    </header>

    {!valid ? <div className="usage-empty">入口信息不完整。请从用量汇总重新选择平台和模型。<p><Link to={summaryUrl}>返回用量汇总</Link></p></div> : <>
      <UsageFilters value={filters} onChange={(next) => {
        const params = new URLSearchParams(searchParams);
        params.set('days', String(next.days));
        params.set('status', next.status);
        setSearchParams(params);
      }} />
      {modelsQuery.isPending && <UsageLoadState label="平台用量" />}
      {modelsQuery.isError && <UsageLoadState label="平台用量" error={modelsQuery.error} onRetry={() => { void modelsQuery.refetch(); }} />}
      {modelsQuery.data && level === 'platform' && <>
        <UsageMetrics totals={modelsQuery.data.totals} />
        <div className="usage-section-head"><h2>使用过的模型</h2><span>{modelsQuery.data.items.length} 个模型</span></div>
        <div className="usage-list">{[...modelsQuery.data.items].sort((a, b) => b.cost_usd - a.cost_usd || b.total_tokens - a.total_tokens).map((item) => <Link
          key={item.model_name}
          className="usage-list-row"
          to={`/usage/model?${usageSearch(filters, { provider, model: item.model_name })}`}
          aria-label={`查看模型 ${item.model_name || '未记录模型'} 的请求`}
        >
          <span className="usage-list-symbol" aria-hidden="true">M</span>
          <span className="usage-list-primary"><strong>{item.model_name || '未记录模型'}</strong><small>{formatTokens(item.calls)} 次请求 · 成功 {formatTokens(item.success_calls)} · 失败 {formatTokens(item.failed_calls)}</small></span>
          <span className="usage-list-secondary"><strong>{formatUsd(item.cost_usd)}</strong><small>{formatTokens(item.total_tokens)} Token</small></span>
          <span className="usage-list-arrow" aria-hidden="true">›</span>
        </Link>)}</div>
        {modelsQuery.data.items.length === 0 && <div className="usage-empty">该筛选范围内还没有模型请求。</div>}
      </>}
      {modelsQuery.data && level === 'model' && !currentModel && <div className="usage-empty">这个模型在所选周期内没有记录。可调整筛选，或返回平台查看其他模型。</div>}
      {currentModel && level === 'model' && <UsageMetrics totals={modelTotals(currentModel)} />}
      {level === 'model' && currentModel && <>
        <div className="usage-section-head"><h2>单次请求</h2><span>按记录顺序由近到远</span></div>
        {recordsQuery.isPending && <UsageLoadState label="请求记录" />}
        {recordsQuery.isError && !recordsQuery.data && <UsageLoadState label="请求记录" error={recordsQuery.error} onRetry={() => { void recordsQuery.refetch(); }} />}
        <div className="usage-request-list">{records.map((record) => <RequestRow key={record.id} record={record} />)}</div>
        {recordsQuery.data && records.length === 0 && <div className="usage-empty">该筛选范围内没有单次请求记录。</div>}
        {recordsQuery.isError && recordsQuery.data && <UsageLoadState label="下一页记录" error={recordsQuery.error} onRetry={() => { void recordsQuery.fetchNextPage(); }} />}
        {recordsQuery.hasNextPage && !recordsQuery.isError && <button type="button" className="usage-more" disabled={recordsQuery.isFetchingNextPage} onClick={() => { void recordsQuery.fetchNextPage(); }}>{recordsQuery.isFetchingNextPage ? '读取中…' : '加载更多请求'}</button>}
      </>}
    </>}
  </main>;
}
