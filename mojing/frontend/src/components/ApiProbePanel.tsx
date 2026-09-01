import { useCallback, useState } from 'react';

import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import type { PublicApiProbeChannel, PublicApiProbeResult } from '../types';

type LiveRow = { base: string; ok: boolean; error: string | null };

type LiveState = {
  hint: string;
  total: number;
  /** 当前尝试序号（来自服务端 attempt 事件） */
  index: number;
  rows: LiveRow[];
};

export type ApiProbePanelProps = {
  channel: PublicApiProbeChannel;
  baseUrl: string;
  apiKey: string;
  model: string;
  characterId?: number;
  /** 为 true 时不发起请求（如本地免费无 Base） */
  disabled?: boolean;
  disabledReason?: string;
  buttonLabel?: string;
};

function channelOkToast(ch: PublicApiProbeChannel): string {
  if (ch === 'text') return '文字 API 已连通';
  if (ch === 'image') return '生图 API 已连通';
  return '语音转写 API 已连通';
}

export function ApiProbePanel({
  channel,
  baseUrl,
  apiKey,
  model,
  characterId,
  disabled,
  disabledReason,
  buttonLabel,
}: ApiProbePanelProps) {
  const { showToast } = useToast();
  const [busy, setBusy] = useState(false);
  const [live, setLive] = useState<LiveState | null>(null);
  const [result, setResult] = useState<PublicApiProbeResult | null>(null);

  const run = useCallback(async () => {
    if (disabled) {
      showToast(disabledReason || '当前不可测试', 'warn');
      return;
    }
    setBusy(true);
    setResult(null);
    setLive({ hint: '正在连接服务器…', total: 0, index: 0, rows: [] });
    try {
      const res = await api.probePublicApiStream(
        { channel, base_url: baseUrl, api_key: apiKey, model, character_id: characterId },
        (ev) => {
          if (ev.type === 'start') {
            const total = Number(ev.total) || 0;
            setLive((p) =>
              p
                ? {
                    ...p,
                    total,
                    hint: total > 1 ? `共 ${total} 条候选线路，将依次尝试` : '正在探测…',
                  }
                : p,
            );
          }
          if (ev.type === 'attempt') {
            setLive((p) =>
              p
                ? {
                    ...p,
                    index: Number(ev.index) || 0,
                    total: Number(ev.total) || p.total,
                    hint: `第 ${ev.index}/${ev.total} 条：${String(ev.display_base ?? ev.base_url ?? '')}`,
                  }
                : p,
            );
          }
          if (ev.type === 'attempt_result') {
            setLive((p) => {
              if (!p) return p;
              return {
                ...p,
                rows: [
                  ...p.rows,
                  {
                    base: String(ev.base_url ?? ''),
                    ok: Boolean(ev.ok),
                    error: (ev.error as string | null) ?? null,
                  },
                ],
              };
            });
          }
        },
      );
      setResult(res);
      if (res.ok) showToast(channelOkToast(channel), 'success');
      else showToast(res.error || '测试未通过', 'error');
    } catch (e) {
      showToast(String(e), 'error');
    } finally {
      setBusy(false);
      setLive(null);
    }
  }, [apiKey, baseUrl, channel, characterId, disabled, disabledReason, model, showToast]);

  const inflight = busy && live && live.rows.length < live.index;
  const pct =
    live && live.total > 0
      ? Math.min(100, Math.round(((live.rows.length + (inflight ? 0.45 : 0)) / live.total) * 100))
      : live
        ? 8
        : 0;

  return (
    <div className="full-row" style={{ marginTop: 6 }}>
      <button type="button" className="btn btn-secondary btn-sm" disabled={busy || disabled} onClick={() => void run()}>
        {busy ? '测试中…' : buttonLabel || '测试连接'}
      </button>
      {live && (
        <div
          style={{
            marginTop: 8,
            padding: '8px 10px',
            borderRadius: 8,
            border: '1px solid var(--border, rgba(255,255,255,0.12))',
            background: 'var(--surface-2, rgba(0,0,0,0.15))',
          }}
        >
          <div style={{ fontSize: 12, color: 'var(--text-2)' }}>{live.hint}</div>
          {live.total > 0 && (
            <div
              style={{
                marginTop: 6,
                height: 4,
                borderRadius: 2,
                background: 'rgba(127,127,127,0.25)',
                overflow: 'hidden',
              }}
            >
              <div
                style={{
                  width: `${pct}%`,
                  maxWidth: '100%',
                  height: '100%',
                  background: 'var(--accent, #3b82f6)',
                  transition: 'width 0.25s ease',
                }}
              />
            </div>
          )}
          {live.rows.length > 0 && (
            <ul style={{ margin: '6px 0 0 0', paddingLeft: 18, fontSize: 12, color: 'var(--text-2)' }}>
              {live.rows.slice(-6).map((row, i) => {
                const b = row.base || '';
                return (
                <li key={`${b}-${i}`}>
                  {(b || '（空）').slice(0, 72)}
                  {b.length > 72 ? '…' : ''}
                  {' — '}
                  {row.ok ? '✓' : <span style={{ color: 'var(--danger)' }}>✗ {row.error || '失败'}</span>}
                </li>
              );})}
            </ul>
          )}
        </div>
      )}
      {result && (
        <div
          style={{
            marginTop: 8,
            padding: '8px 10px',
            borderRadius: 8,
            border: `1px solid ${result.ok ? 'rgba(34,197,94,0.55)' : 'rgba(248,113,113,0.65)'}`,
            background: result.ok ? 'rgba(34,197,94,0.07)' : 'rgba(248,113,113,0.08)',
            fontSize: 13,
            lineHeight: 1.45,
          }}
        >
          <div>
            <strong>{result.ok ? '通过' : '未通过'}</strong>
            {result.detail ? ` — ${result.detail}` : ''}
          </div>
          {result.error && <div style={{ color: 'var(--danger)', marginTop: 4 }}>{result.error}</div>}
          {result.warnings && result.warnings.length > 0 && (
            <ul style={{ margin: '6px 0 0 18px', color: 'var(--text-2)' }}>
              {result.warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          )}
          {result.ok && result.used_base_url !== undefined && result.used_base_url !== null && (
            <div style={{ fontSize: 12, marginTop: 6 }}>
              最终线路：<code>{result.used_base_url || '（SDK 默认根）'}</code>
            </div>
          )}
          {result.attempts && result.attempts.length > 1 && (
            <ul style={{ margin: '8px 0 0 18px', fontSize: 12, color: 'var(--text-2)' }}>
              {result.attempts.map((a, i) => {
                const b = a.base_url || '';
                return (
                  <li key={i}>
                    {b.slice(0, 64)}
                    {b.length > 64 ? '…' : ''}
                    {a.ok ? ' ✓' : ` ✗ ${a.error || ''}`}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
