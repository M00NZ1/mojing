import { useEffect, useRef } from 'react';

import UiIcon from './UiIcon';
import type { SettingConflictEntry, SettingConflictResult } from '../utils/settingConflictPresentation';

type Props = {
  result: SettingConflictResult;
  onClose: () => void;
  onOpenEncyclopedia: () => void;
  onOpenEntry: (entry: SettingConflictEntry) => void;
};

const FOCUSABLE_SELECTOR = [
  'button:not(:disabled)',
  '[href]',
  'input:not(:disabled)',
  'select:not(:disabled)',
  'textarea:not(:disabled)',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

export default function SettingConflictDialog({ result, onClose, onOpenEncyclopedia, onOpenEntry }: Props) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const hasConflicts = result.conflicts.length > 0;

  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const frame = window.requestAnimationFrame(() => closeButtonRef.current?.focus());
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== 'Tab') return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
        .filter((element) => element.getClientRects().length > 0 && window.getComputedStyle(element).visibility !== 'hidden');
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!dialog.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  return (
    <div
      className="confirm-overlay setting-conflict-overlay"
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={dialogRef}
        className="confirm-dialog setting-conflict-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="setting-conflict-title"
        aria-describedby="setting-conflict-summary"
        tabIndex={-1}
      >
        <div className="setting-conflict-header">
          <div>
            <h3 id="setting-conflict-title"><UiIcon name={hasConflicts ? 'warning' : 'book'} />设定冲突检查</h3>
            <p id="setting-conflict-summary">
              {hasConflicts
                ? `发现 ${result.conflicts.length} 项整理提示。检查结果不会自动修改设定。`
                : result.message || '未发现重复标题或使用范围过宽的标签。'}
            </p>
          </div>
          <button ref={closeButtonRef} type="button" className="setting-conflict-close" onClick={onClose} aria-label="关闭设定冲突检查">
            <UiIcon name="close" />
          </button>
        </div>

        {hasConflicts ? (
          <ol className="setting-conflict-list">
            {result.conflicts.map((conflict, index) => (
              <li key={`${conflict.message}-${index}`}>
                <strong>{conflict.message}</strong>
                {conflict.entries.length > 0 ? (
                  <div className="setting-conflict-entries" aria-label="相关百科条目">
                    {conflict.entries.map((entry) => (
                      <button
                        key={entry.id}
                        type="button"
                        className="setting-conflict-entry"
                        aria-label={`打开百科条目“${entry.title}”${entry.summary ? `，${entry.summary}` : ''}`}
                        onClick={() => onOpenEntry(entry)}
                      >
                        <UiIcon name="book" />
                        <span className="setting-conflict-entry-copy">
                          <span>{entry.title}</span>
                          {entry.summary ? <small>{entry.summary}</small> : null}
                        </span>
                      </button>
                    ))}
                  </div>
                ) : (
                  <small>可打开当前百科库查看并整理相关条目。</small>
                )}
              </li>
            ))}
          </ol>
        ) : null}

        <div className="confirm-buttons setting-conflict-actions">
          <button type="button" className="btn btn-ghost" onClick={onOpenEncyclopedia}>打开百科库</button>
          <button type="button" className="btn btn-primary" onClick={onClose}>完成</button>
        </div>
      </div>
    </div>
  );
}
