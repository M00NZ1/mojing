import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { formatTokens, formatUsd, providerLabel, readUsageFilters, UsageFilters, UsageLoadState, UsageMetrics, usageSearch } from './UsageDisplay';

export function CostPanel() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = readUsageFilters(searchParams);
  const query = useQuery({
    queryKey: ['cost-providers', filters.days, filters.status],
    queryFn: () => api.costProviders(filters),
  });

  return <section className="usage-overview" aria-label="用量汇总">
    <div className="usage-overview-toolbar">
      <div>
        <p className="eyebrow">本机调用记录</p>
        <p>按平台查看费用与 Token，再进入模型和单次请求。</p>
      </div>
      <UsageFilters value={filters} onChange={(next) => {
        const params = new URLSearchParams(searchParams);
        params.set('days', String(next.days));
        params.set('status', next.status);
        setSearchParams(params);
      }} />
    </div>

    {query.isPending && <UsageLoadState label="用量汇总" />}
    {query.isError && <UsageLoadState label="用量汇总" error={query.error} onRetry={() => { void query.refetch(); }} />}
    {query.data && <>
      <UsageMetrics totals={query.data.totals} />
      <div className="usage-section-head">
        <h2>平台明细</h2>
        <span>{query.data.items.length} 个平台 · {formatTokens(query.data.totals.total_calls)} 次请求</span>
      </div>
      <div className="usage-list">
        {[...query.data.items].sort((a, b) => b.cost_usd - a.cost_usd || b.total_tokens - a.total_tokens).map((item) => {
          const content = <>
          <span className="usage-list-symbol" aria-hidden="true">{providerLabel(item.provider).slice(0, 1)}</span>
          <span className="usage-list-primary">
            <strong>{providerLabel(item.provider)}</strong>
            <small>{item.provider ? `${formatTokens(item.calls)} 次请求 · ${item.models_count} 个模型 · 失败 ${formatTokens(item.failed_calls)}` : `${formatTokens(item.calls)} 次请求 · 旧记录缺少平台信息`}</small>
          </span>
          <span className="usage-list-secondary">
            <strong>{formatUsd(item.cost_usd)}</strong>
            <small>{formatTokens(item.total_tokens)} Token</small>
          </span>
          <span className="usage-list-arrow" aria-hidden="true">{item.provider ? '›' : ''}</span>
          </>;
          return item.provider ? <Link
            key={item.provider}
            className="usage-list-row"
            to={`/usage/platform?${usageSearch(filters, { provider: item.provider })}`}
            aria-label={`查看${providerLabel(item.provider)}用量`}
          >{content}</Link> : <div key="unknown-provider" className="usage-list-row usage-list-row-static">{content}</div>;
        })}
      </div>
      {query.data.items.length === 0 && <div className="usage-empty">该周期内还没有调用记录。开始对话后，用量会出现在这里。</div>}
    </>}
  </section>;
}
