import { useEffect, useState } from 'react';

import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import { friendlyFetchError } from '../utils/userFacingError';

interface TimelineEntry {
  title: string;
  description: string;
  entry_type: string;
  timestamp: string;
  era: string;
}

export default function TimelinePanel({ sessionId }: { sessionId: number }) {
  const { showToast } = useToast();
  const [entries, setEntries] = useState<TimelineEntry[]>([]);
  const [gameplayMode, setGameplayMode] = useState('自由剧情');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deletingIndex, setDeletingIndex] = useState<number | null>(null);
  const [showAdd, setShowAdd] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newDesc, setNewDesc] = useState('');
  const [newType, setNewType] = useState('事件');
  const [newEra, setNewEra] = useState('');

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    setLoading(true);
    api.sessionTimeline(sessionId)
      .then((data) => {
        if (cancelled) return;
        setEntries(data.entries ?? []);
        setGameplayMode(data.gameplay_mode || '\u81ea\u7531\u5267\u60c5');
      })
      .catch((err) => {
        if (cancelled) return;
        showToast(String(err), 'error');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [sessionId, showToast]);

  async function addEntry() {
    if (!newTitle.trim()) {
      showToast('\u8bf7\u586b\u5199\u4e8b\u4ef6\u6807\u9898\uFF08Web\uFF09', 'warn');
      return;
    }
    setSaving(true);
    try {
      const data = await api.addSessionTimelineEntry(sessionId, {
        title: newTitle.trim(),
        description: newDesc.trim(),
        entry_type: newType,
        era: newEra.trim(),
      });
      setEntries(data.entries ?? []);
      setGameplayMode(data.gameplay_mode || gameplayMode);
      setNewTitle('');
      setNewDesc('');
      setNewEra('');
      setShowAdd(false);
      showToast('\u5df2\u6dfb\u52a0\u65f6\u95f4\u7ebf\u4e8b\u4ef6\uFF08Web\uFF09', 'success');
    } catch (e) {
      showToast(friendlyFetchError(e), 'error');
    } finally {
      setSaving(false);
    }
  }

  async function deleteEntry(index: number) {
    setDeletingIndex(index);
    try {
      await api.deleteSessionTimelineEntry(sessionId, index);
      setEntries((prev) => prev.filter((_, i) => i !== index));
      showToast('\u5df2\u5220\u9664\u8be5\u65f6\u95f4\u7ebf\u4e8b\u4ef6\uFF08Web\uFF09', 'success');
    } catch (e) {
      showToast(friendlyFetchError(e), 'error');
    } finally {
      setDeletingIndex(null);
    }
  }

  if (loading) return <div className="skeleton-line" />;
  if (!sessionId) return null;

  return (
    <div className="page-card" style={{ marginTop: 12 }}>
      <div className="card-header">
        <div><p className="eyebrow">{gameplayMode}</p><h2>世界时间线</h2></div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setShowAdd(!showAdd)}>{showAdd ? '取消' : '+ 添加'}</button>
      </div>

      {showAdd && (
        <div className="world-box" style={{ marginBottom: 12 }}>
          <div className="form-group">
            <label className="required">事件标题</label>
            <input value={newTitle} onChange={(e) => setNewTitle(e.target.value)} placeholder="例如：主角踏入苍玄大陆" maxLength={100} />
          </div>
          <div className="form-row">
            <div className="form-group">
              <label>类型</label>
              <select value={newType} onChange={(e) => setNewType(e.target.value)}>
                <option value="事件">事件</option>
                <option value="剧情">剧情</option>
                <option value="战斗">战斗</option>
                <option value="发现">发现</option>
              </select>
            </div>
            <div className="form-group">
              <label>纪元</label>
              <input value={newEra} onChange={(e) => setNewEra(e.target.value)} placeholder="例如：苍玄历 103 年" maxLength={60} />
            </div>
          </div>
          <div className="form-group">
            <label>描述</label>
            <textarea rows={2} value={newDesc} onChange={(e) => setNewDesc(e.target.value)} placeholder="事件详情..." />
          </div>
          <button type="button" className="btn btn-primary btn-sm" onClick={addEntry} disabled={saving || !newTitle.trim()}>{saving ? 'Saving...' : '\u4fdd\u5b58'}</button>
        </div>
      )}

      {entries.length === 0 && <p className="guide-text" style={{ padding: '16px 0', textAlign: 'center' }}>尚无时间线事件，在聊天过程中会自动生成，或手动添加</p>}

      <div className="stack-list" style={{ maxHeight: 400, overflowY: 'auto' }}>
        {entries.map((entry, i) => (
          <div key={i} className="mini-card" style={{ position: 'relative' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <span className="pill pill-sm">{entry.entry_type || '事件'}</span>
              {entry.era && <span className="channel-badge">{entry.era}</span>}
            </div>
            <strong>{entry.title}</strong>
            {entry.description && <p style={{ fontSize: '0.82rem', color: 'var(--text-2)', marginTop: 4 }}>{entry.description}</p>}
            <button type="button" className="btn btn-ghost btn-sm" style={{ position: 'absolute', top: 8, right: 8 }} onClick={() => void deleteEntry(i)} disabled={deletingIndex !== null} title="删除">✕</button>
          </div>
        ))}
      </div>
    </div>
  );
}
