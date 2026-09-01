import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import {
  BATCH_GENERATE_MAX_COUNT,
  BATCH_GENERATE_MIN_COUNT,
  hasBatchGenerateAnchor,
  parseBatchGenerateCount,
} from '../utils/batchGeneration';
import UiIcon from './UiIcon';

type Props = {
  open: boolean;
  category: string;
  count: string;
  contextHint: string;
  anchorWeak: boolean;
  allowWeakAnchor: boolean;
  onCountChange: (value: string) => void;
  onContextHintChange: (value: string) => void;
  onAllowWeakAnchorChange: (value: boolean) => void;
  onCancel: () => void;
  onSubmit: (count: number, contextHint: string) => void;
};

const FOCUSABLE_SELECTOR = [
  'button:not(:disabled)',
  'input:not(:disabled)',
  'select:not(:disabled)',
  'textarea:not(:disabled)',
  '[href]',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

export default function BatchGenerateDialog({
  open,
  category,
  count,
  contextHint,
  anchorWeak,
  allowWeakAnchor,
  onCountChange,
  onContextHintChange,
  onAllowWeakAnchorChange,
  onCancel,
  onSubmit,
}: Props) {
  const dialogRef = useRef<HTMLFormElement>(null);
  const countInputRef = useRef<HTMLInputElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const onCancelRef = useRef(onCancel);
  onCancelRef.current = onCancel;
  const titleId = useId();
  const descriptionId = useId();
  const countHelpId = useId();
  const anchorHelpId = useId();
  const parsedCount = parseBatchGenerateCount(count);
  const anchorReady = hasBatchGenerateAnchor(anchorWeak, contextHint, allowWeakAnchor);
  const canSubmit = parsedCount !== null && anchorReady;

  useEffect(() => {
    if (!open) return undefined;
    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    const frame = window.requestAnimationFrame(() => countInputRef.current?.focus());

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        onCancelRef.current();
        return;
      }
      if (event.key !== 'Tab') return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
        .filter((element) => (
          element.getAttribute('aria-hidden') !== 'true'
          && element.getClientRects().length > 0
          && window.getComputedStyle(element).visibility !== 'hidden'
        ));
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

    document.addEventListener('keydown', handleKeyDown, true);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('keydown', handleKeyDown, true);
      const previousFocus = previousFocusRef.current;
      if (previousFocus && document.contains(previousFocus)) {
        window.requestAnimationFrame(() => previousFocus.focus());
      }
    };
  }, [open]);

  if (!open) return null;

  return createPortal(
    <div
      className="confirm-overlay encyclopedia-batch-overlay"
      onClick={(event) => {
        if (event.target === event.currentTarget) onCancel();
      }}
    >
      <form
        ref={dialogRef}
        className="confirm-dialog encyclopedia-batch-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        tabIndex={-1}
        onSubmit={(event) => {
          event.preventDefault();
          if (parsedCount !== null && anchorReady) {
            onSubmit(parsedCount, contextHint.trim());
          }
        }}
      >
        <div className="encyclopedia-batch-header">
          <div>
            <h3 id={titleId}><UiIcon name="sparkles" /><span>AI 批量生成{category}</span></h3>
            <p id={descriptionId}>根据当前百科库和已有条目，逐条补充同类资料。</p>
          </div>
          <button type="button" className="encyclopedia-batch-close" onClick={onCancel} aria-label="关闭批量生成" title="关闭">
            <UiIcon name="close" />
          </button>
        </div>

        <div className="encyclopedia-batch-fields">
          <label className="encyclopedia-batch-field" htmlFor="encyclopedia-batch-count">
            <span>生成数量</span>
            <input
              ref={countInputRef}
              id="encyclopedia-batch-count"
              type="number"
              inputMode="numeric"
              min={BATCH_GENERATE_MIN_COUNT}
              max={BATCH_GENERATE_MAX_COUNT}
              step={1}
              value={count}
              onChange={(event) => onCountChange(event.target.value)}
              aria-invalid={parsedCount === null}
              aria-describedby={countHelpId}
            />
            <small id={countHelpId} className={parsedCount === null ? 'form-error' : undefined}>
              {parsedCount === null ? '请输入 5–20 之间的整数。' : '每条会单独生成，数量越多等待越久。'}
            </small>
          </label>

          <label className="encyclopedia-batch-field" htmlFor="encyclopedia-batch-hint">
            <span>额外提示 <small>可选</small></span>
            <textarea
              id="encyclopedia-batch-hint"
              rows={5}
              value={contextHint}
              onChange={(event) => onContextHintChange(event.target.value)}
              placeholder="例如：围绕港口黑市、旧贵族和蒸汽机械，避免出现现代科技。"
              aria-describedby={anchorWeak ? anchorHelpId : undefined}
            />
          </label>
        </div>

        {anchorWeak && (
          <div id={anchorHelpId} className={`encyclopedia-batch-warning ${anchorReady ? '' : 'is-required'}`}>
            <UiIcon name="warning" />
            <div>
              <strong>当前百科库的世界锚点较少</strong>
              <p>建议先填写额外提示，避免生成内容偏离预期。</p>
              <label>
                <input
                  type="checkbox"
                  checked={allowWeakAnchor}
                  onChange={(event) => onAllowWeakAnchorChange(event.target.checked)}
                />
                <span>不填写提示，仍按现有资料生成</span>
              </label>
            </div>
          </div>
        )}

        <div className="encyclopedia-batch-actions">
          <button type="button" className="btn btn-ghost" onClick={onCancel}>取消</button>
          <button type="submit" className="btn btn-primary" disabled={!canSubmit}>
            <UiIcon name="sparkles" />开始生成
          </button>
        </div>
      </form>
    </div>,
    document.body,
  );
}
