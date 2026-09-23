import type { UsageTotals } from '../types';
import './UsageDisplay.css';

export type UsageStatus = 'all' | 'success' | 'failed';
export type UsageFiltersState = { days: number; status: UsageStatus };

export function readUsageFilters(params: URLSearchParams): UsageFiltersState {
  const days = Number(params.get('days'));
  const status = params.get('status');
  return {
    days: [7, 30, 90, 365].includes(days) ? days : 30,
    status: status === 'success' || status === 'failed' ? status : 'all',
  };
}

export function usageSearch(filters: UsageFiltersState, extra?: Record<string, string>): string {
  return new URLSearchParams({ days: String(filters.days), status: filters.status, ...extra }).toString();
}

export function providerLabel(provider: string): string {
  const labels: Record<string, string> = {
    deepseek: 'DeepSeek', openai: 'OpenAI', siliconflow: '硅基流动', anthropic: 'Anthropic', custom: '自定义',
  };
  return labels[provider.toLowerCase()] || provider || '未记录平台';
}

export function formatTokens(value: number): string {
  return Number.isFinite(value) ? Math.max(0, value).toLocaleString('zh-CN') : '—';
}

export function formatUsd(value: number): string {
  if (!Number.isFinite(value)) return '—';
  return `$${value.toFixed(value !== 0 && Math.abs(value) < 0.01 ? 6 : 4)}`;
}

export function formatCost(value: number | null | undefined, currency = 'USD'): string {
  if (!Number.isFinite(value)) return '费用未知';
  const symbol = currency === 'CNY' ? '¥' : currency === 'USD' ? '$' : `${currency} `;
  return `${symbol}${Number(value).toFixed(value !== 0 && Math.abs(Number(value)) < 0.01 ? 6 : 4)}`;
}

export function formatCurrencyTotals(totals?: Record<string, number>): string {
  if (!totals || Object.keys(totals).length === 0) return '费用未知';
  return Object.entries(totals).map(([currency, value]) => formatCost(value, currency)).join(' · ');
}

export function formatUsageDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value || '时间未记录' : date.toLocaleString('zh-CN', { hour12: false });
}

export function UsageFilters({ value, onChange }: { value: UsageFiltersState; onChange: (next: UsageFiltersState) => void }) {
  return (
    <div className="usage-filters" aria-label="用量筛选">
      <div className="usage-filter-group" role="group" aria-label="统计周期">
        {[7, 30, 90, 365].map((days) => <button
          key={days} type="button" className={value.days === days ? 'is-active' : ''}
          aria-pressed={value.days === days} onClick={() => onChange({ ...value, days })}
        >{days === 365 ? '近一年' : `${days} 天`}</button>)}
      </div>
      <div className="usage-filter-group" role="group" aria-label="请求状态">
        {([['all', '全部'], ['success', '成功'], ['failed', '失败']] as const).map(([status, label]) => <button
          key={status} type="button" className={value.status === status ? 'is-active' : ''}
          aria-pressed={value.status === status} onClick={() => onChange({ ...value, status })}
        >{label}</button>)}
      </div>
    </div>
  );
}

export function UsageMetrics({ totals }: { totals: UsageTotals }) {
  const metrics = [
    ['预估费用', totals.currency_totals ? formatCurrencyTotals(totals.currency_totals) : formatCost(totals.cost_usd)],
    ['使用 Token', formatTokens(totals.total_tokens)],
    ['成功请求', formatTokens(totals.success_calls)],
    ['失败请求', formatTokens(totals.failed_calls)],
  ];
  return <div className="usage-metrics">{metrics.map(([label, value]) => <div className="usage-metric" key={label}>
    <span>{label}</span><strong>{value}</strong>
  </div>)}</div>;
}

export function UsageLoadState({ label, error, onRetry }: { label: string; error?: unknown; onRetry?: () => void }) {
  return <div className="usage-load-state" role={error ? 'alert' : 'status'}>
    <strong>{error ? `${label}读取失败` : `正在读取${label}…`}</strong>
    {error != null && <p>{error instanceof Error ? error.message : '本机服务暂时无法读取记录。'}</p>}
    {error != null && onRetry && <button className="btn btn-secondary" type="button" onClick={onRetry}>重试</button>}
  </div>;
}
