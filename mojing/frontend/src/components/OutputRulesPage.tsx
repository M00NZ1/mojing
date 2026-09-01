import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useToast } from '../hooks/useToast';

type Rule = {
  id: string;
  pattern: string;
  replacement: string;
  enabled: boolean;
  label: string;
  ignore_case?: boolean;
  multiline?: boolean;
};

function compileOutputRuleRegex(pattern: string, ignoreCase?: boolean, multiline?: boolean): { ok: true } | { ok: false; message: string } {
  const flags = `${ignoreCase ? 'i' : ''}${multiline ? 'm' : ''}`;
  try {
    void new RegExp(pattern, flags);
    return { ok: true };
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : String(e) };
  }
}

export default function OutputRulesPage() {
  const { showToast } = useToast();
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [editRule, setEditRule] = useState<Partial<Rule>>({ pattern: '', replacement: '', enabled: true, label: '', ignore_case: false, multiline: false });
  const [previewText, setPreviewText] = useState('');
  const [previewResult, setPreviewResult] = useState('');

  const rulesQuery = useQuery({
    queryKey: ['output-rules'],
    queryFn: () => api.get('/output-rules') as Promise<Rule[]>,
  });

  const saveMutation = useMutation({
    mutationFn: (rule: Partial<Rule>) => api.post('/output-rules', rule),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['output-rules'] });
      setShowForm(false);
      setEditRule({ pattern: '', replacement: '', enabled: true, label: '', ignore_case: false, multiline: false });
      showToast('规则已保存', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.delete(`/output-rules/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['output-rules'] });
      showToast('规则已删除', 'info');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const previewMutation = useMutation({
    mutationFn: (data: { text: string; pattern: string; replacement: string; ignore_case?: boolean }) =>
      api.post('/output-rules/preview', data) as Promise<{ original: string; result: string }>,
    onSuccess: (data) => setPreviewResult(data.result),
    onError: (e) => showToast(String(e), 'error'),
  });

  const rules = rulesQuery.data ?? [];

  return (
    <div className="page-card">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ margin: 0 }}>输出后处理规则</h3>
        <button type="button" className="btn btn-sm" onClick={() => { setShowForm(true); setEditRule({ pattern: '', replacement: '', enabled: true, label: '', ignore_case: false, multiline: false }); }}>
          + 新增规则
        </button>
      </div>
      <p style={{ fontSize: '0.82rem', color: 'var(--text-2)', marginBottom: 12 }}>
        定义正则替换规则，模型生成的消息在推送到前端前会自动应用这些替换。
      </p>

      {showForm && (
        <div className="world-box" style={{ padding: 16, marginBottom: 16 }}>
          <div className="form-grid compact-grid">
            <div className="form-group">
              <label>规则名称</label>
              <input value={editRule.label || ''} onChange={(e) => setEditRule({ ...editRule, label: e.target.value })} placeholder="例如：去除加粗" />
            </div>
            <div className="form-group">
              <label>查找模式（正则）</label>
              <input value={editRule.pattern || ''} onChange={(e) => setEditRule({ ...editRule, pattern: e.target.value })} placeholder={'例如: \\*\\*(.*?)\\*\\*'} />
            </div>
            <div className="form-group">
              <label>替换为</label>
              <input value={editRule.replacement || ''} onChange={(e) => setEditRule({ ...editRule, replacement: e.target.value })} placeholder="例如: $1" />
            </div>
            <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '0.82rem' }}>
                <input type="checkbox" checked={!!editRule.ignore_case} onChange={(e) => setEditRule({ ...editRule, ignore_case: e.target.checked })} />忽略大小写
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '0.82rem' }}>
                <input type="checkbox" checked={!!editRule.multiline} onChange={(e) => setEditRule({ ...editRule, multiline: e.target.checked })} />多行模式
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '0.82rem' }}>
                <input type="checkbox" checked={!!editRule.enabled} onChange={(e) => setEditRule({ ...editRule, enabled: e.target.checked })} />启用
              </label>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            <button type="button"
              className="btn btn-primary btn-sm"
              onClick={() => {
                const pat = (editRule.pattern || '').trim();
                if (!pat) {
                  showToast('请填写查找模式（正则）', 'warn');
                  return;
                }
                const chk = compileOutputRuleRegex(pat, editRule.ignore_case, editRule.multiline);
                if (!chk.ok) {
                  showToast(`正则无效：${chk.message}`, 'error');
                  return;
                }
                saveMutation.mutate(editRule);
              }}
              disabled={!editRule.pattern || saveMutation.isPending}
            >
              {saveMutation.isPending ? '保存中...' : '保存'}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setShowForm(false)}>取消</button>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', gap: 16, marginBottom: 16, padding: 12, background: 'var(--surface)', borderRadius: 'var(--radius-sm)' }}>
        <textarea
          value={previewText}
          onChange={(e) => setPreviewText(e.target.value)}
          placeholder="输入测试文本..."
          rows={3}
          style={{ flex: 1, padding: 8, fontSize: '0.82rem', border: '1px solid var(--line)', borderRadius: 6, background: 'var(--input-bg)', color: 'var(--text)' }}
        />
        <div style={{ flex: 1 }}>
          <textarea
            readOnly
            value={previewResult}
            placeholder="替换结果..."
            rows={3}
            style={{ width: '100%', padding: 8, fontSize: '0.82rem', border: '1px solid var(--line)', borderRadius: 6, background: 'var(--input-bg)', color: 'var(--text)' }}
          />
          <button type="button"
            className="btn btn-ghost btn-sm"
            style={{ marginTop: 4 }}
            onClick={() => {
              const pat = (editRule.pattern || '').trim() || '**';
              const chk = compileOutputRuleRegex(pat, editRule.ignore_case, editRule.multiline);
              if (!chk.ok) {
                showToast(`正则无效：${chk.message}`, 'error');
                return;
              }
              previewMutation.mutate({ text: previewText, pattern: pat, replacement: editRule.replacement || '', ignore_case: editRule.ignore_case });
            }}
            disabled={!previewText || previewMutation.isPending}
          >
            {previewMutation.isPending ? '测试中...' : '测试'}
          </button>
        </div>
      </div>

      {rules.length === 0 ? (
        <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-2)', fontSize: '0.82rem' }}>暂无规则</div>
      ) : (
        rules.map((rule) => (
          <div key={rule.id} className="mini-card" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6, opacity: rule.enabled ? 1 : 0.5 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 600, fontSize: '0.85rem' }}>{rule.label || '未命名'}</div>
              <code style={{ fontSize: '0.75rem', color: 'var(--text-2)' }}>{rule.pattern} → {rule.replacement}</code>
            </div>
            <button type="button" className="btn-icon" style={{ color: 'var(--accent-3)', flexShrink: 0 }} onClick={() => deleteMutation.mutate(rule.id)} title="删除">✕</button>
          </div>
        ))
      )}
    </div>
  );
}
