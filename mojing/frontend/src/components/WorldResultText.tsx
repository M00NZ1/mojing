import { useId, useRef, useState } from 'react';
import { copyText } from '../utils/clipboard';
import './WorldResultText.css';

/** A bounded reader for generated source text; copying always uses the full value. */
export default function WorldResultText({ text, label }: { text: string; label: string }) {
  const id = useId();
  const [expanded, setExpanded] = useState(false);
  const [copyState, setCopyState] = useState<'idle' | 'copying' | 'copied' | 'failed'>('idle');
  const copying = useRef(false);
  const long = text.length > 320 || text.split('\n', 7).length > 6;
  return <section className="world-result-reader" aria-label={label}>
    <div key={expanded ? 'full' : 'preview'} id={id} className={`world-history-body world-result-text ${long ? expanded ? 'is-expanded' : 'is-collapsed' : ''}`}
      tabIndex={long && expanded ? 0 : undefined} role={long && expanded ? 'region' : undefined}
      aria-label={long && expanded ? `${label}全文` : undefined}>{text || '暂无正文'}</div>
    <div className="world-result-text-actions">
      {long && <button type="button" className="btn btn-ghost btn-sm" aria-expanded={expanded} aria-controls={id}
        onClick={() => setExpanded((value) => !value)}>{expanded ? '收起阅读' : '展开阅读'}</button>}
      <button type="button" className="btn btn-ghost btn-sm" disabled={!text || copyState === 'copying'} onClick={async () => {
        if (copying.current) return;
        copying.current = true; setCopyState('copying');
        try { await copyText(text); setCopyState('copied'); }
        catch { setCopyState('failed'); }
        finally { copying.current = false; }
      }}>{copyState === 'copying' ? '正在复制…' : '复制全文'}</button>
      {copyState === 'copied' && <span role="status">已复制全文</span>}
      {copyState === 'failed' && <span role="alert">复制失败，请重试或选择正文复制。</span>}
    </div>
  </section>;
}
