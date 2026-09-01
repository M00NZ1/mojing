import { useEffect, useState } from 'react';

import type { CostData } from '../types';
import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import UiIcon from './UiIcon';

type CostRecord = {
  id: number;
  model_name: string;
  provider: string;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  estimated_cost: number;
  duration_ms: number;
  success: boolean;
  created_at: string;
};

type CostSummary = {
  total_cost_usd: number;
  total_tokens: number;
  total_calls: number;
  failed_calls: number;
  by_model: Record<string, { calls: number; tokens: number; cost: number }>;
  records: CostRecord[];
};

type CostApiResult = CostData | CostSummary;

function isCostSummary(value: CostApiResult | null): value is CostSummary {
  return Boolean(value && typeof value === 'object' && 'total_cost_usd' in value && 'records' in value);
}

function normalizeCostData(value: CostApiResult | null): CostSummary | null {
  if (!value) return null;
  if (isCostSummary(value)) return value;
  return {
    total_cost_usd: value.cost ?? 0,
    total_tokens: value.total_tokens ?? 0,
    total_calls: 1,
    failed_calls: 0,
    by_model: {
      [value.model || 'unknown']: {
        calls: 1,
        tokens: value.total_tokens ?? 0,
        cost: value.cost ?? 0,
      },
    },
    records: [
      {
        id: 0,
        model_name: value.model || 'unknown',
        provider: value.model || 'unknown',
        prompt_tokens: value.prompt_tokens ?? 0,
        completion_tokens: value.completion_tokens ?? 0,
        total_tokens: value.total_tokens ?? 0,
        estimated_cost: value.cost ?? 0,
        duration_ms: 0,
        success: true,
        created_at: value.date || new Date().toISOString(),
      },
    ],
  };
}


export function CostPanel() {
  const { showToast } = useToast();
  const [data, setData] = useState<CostSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [days, setDays] = useState(30);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.costs({ days })
      .then((summary) => {
        if (cancelled) return;
        setData(normalizeCostData(summary));
      })
      .catch((err) => {
        if (cancelled) return;
        showToast(String(err), 'error');
        setData(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [days, showToast]);

  if (loading) return <div className="page-card"><div className="skeleton-line" style={{ width: '30%' }} /><div className="skeleton-line" style={{ marginTop: 12 }} /></div>;
  if (!data) return <div className="page-card"><p style={{ color: 'var(--text-2)' }}>暂无成本数据</p></div>;

  return (
    <div className="page-card">
      <div className="card-header">
        <div><p className="eyebrow">LLM 调用统计</p><h2>成本概览</h2></div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <label style={{ fontSize: '0.82rem' }}>统计周期：</label>
          <select value={days} onChange={(e) => setDays(Number(e.target.value))} style={{ width: 'auto' }}>
            <option value={7}>近 7 天</option>
            <option value={30}>近 30 天</option>
            <option value={90}>近 90 天</option>
            <option value={365}>全年</option>
          </select>
        </div>
      </div>

      <div className="sys-grid" style={{ marginBottom: 16 }}>
        <div className="sys-card">
          <div className="sys-card-icon"><UiIcon name="cost" /></div>
          <div className="sys-card-value">${data.total_cost_usd?.toFixed(4) ?? '0'}</div>
          <div className="sys-card-label">总成本</div>
        </div>
        <div className="sys-card">
          <div className="sys-card-icon"><UiIcon name="document" /></div>
          <div className="sys-card-value">{((data.total_tokens ?? 0) / 1000).toFixed(1)}K</div>
          <div className="sys-card-label">总 Token</div>
        </div>
        <div className="sys-card">
          <div className="sys-card-icon"><UiIcon name="chat" /></div>
          <div className="sys-card-value">{data.total_calls ?? 0}</div>
          <div className="sys-card-label">调用次数</div>
        </div>
        <div className="sys-card">
          <div className="sys-card-icon"><UiIcon name="warning" /></div>
          <div className="sys-card-value" style={{ color: (data.failed_calls ?? 0) > 0 ? 'var(--accent-3)' : undefined }}>{data.failed_calls ?? 0}</div>
          <div className="sys-card-label">失败次数</div>
        </div>
      </div>

      {/* 按模型分组 */}
      {Object.keys(data.by_model ?? {}).length > 0 && (
        <>
          <div className="form-section-title" style={{ marginBottom: 8 }}>各模型消耗</div>
          <div style={{ overflowX: 'auto', marginBottom: 16 }}>
            <table className="data-table" style={{ width: '100%', fontSize: '0.82rem' }}>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left' }}>模型</th>
                  <th style={{ textAlign: 'right' }}>调用次数</th>
                  <th style={{ textAlign: 'right' }}>Token 数</th>
                  <th style={{ textAlign: 'right' }}>预估成本 ($)</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(data.by_model).map(([model, stats]) => (
                  <tr key={model}>
                    <td>{model}</td>
                    <td style={{ textAlign: 'right' }}>{stats.calls}</td>
                    <td style={{ textAlign: 'right' }}>{(stats.tokens / 1000).toFixed(1)}K</td>
                    <td style={{ textAlign: 'right' }}>${stats.cost.toFixed(4)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* 最近调用记录 */}
      {data.records && data.records.length > 0 && (
        <>
          <div className="form-section-title" style={{ marginBottom: 8 }}>最近调用记录</div>
          <div style={{ overflowX: 'auto', maxHeight: 300, overflowY: 'auto' }}>
            <table className="data-table" style={{ width: '100%', fontSize: '0.78rem' }}>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left' }}>时间</th>
                  <th style={{ textAlign: 'left' }}>模型</th>
                  <th style={{ textAlign: 'right' }}>Prompt</th>
                  <th style={{ textAlign: 'right' }}>Completion</th>
                  <th style={{ textAlign: 'right' }}>耗时 (ms)</th>
                  <th style={{ textAlign: 'right' }}>成本 ($)</th>
                  <th style={{ textAlign: 'center' }}>状态</th>
                </tr>
              </thead>
              <tbody>
                {data.records.slice(0, 50).map((r) => (
                  <tr key={r.id}>
                    <td style={{ whiteSpace: 'nowrap' }}>{new Date(r.created_at).toLocaleString('zh-CN')}</td>
                    <td>{r.model_name || r.provider}</td>
                    <td style={{ textAlign: 'right' }}>{r.prompt_tokens}</td>
                    <td style={{ textAlign: 'right' }}>{r.completion_tokens}</td>
                    <td style={{ textAlign: 'right' }}>{r.duration_ms}</td>
                    <td style={{ textAlign: 'right' }}>${r.estimated_cost.toFixed(6)}</td>
                    <td style={{ textAlign: 'center' }}>
                      <span className={`cost-status ${r.success ? 'is-success' : 'is-error'}`}>
                        {r.success ? '成功' : '失败'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {(!data.records || data.records.length === 0) && (
        <p style={{ color: 'var(--text-2)', fontSize: '0.85rem', textAlign: 'center', padding: 24 }}>
          还没有 LLM 调用记录，开始聊天后会自动记录。
        </p>
      )}
    </div>
  );
}
