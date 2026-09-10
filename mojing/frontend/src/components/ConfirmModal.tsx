import { useEffect, useId, useRef, useState } from 'react';
import UiIcon from './UiIcon';

interface ConfirmModalProps {
  open: boolean;
  requestId?: number;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'danger' | 'warning' | 'default';
  onConfirm: () => void;
  onCancel: () => void;
}

type ConfirmModalOptions = {
  confirmLabel?: string;
  cancelLabel?: string;
  signal?: AbortSignal;
};

let confirmRequestId = 0;

export function confirmModal(
  title: string,
  message: string,
  variant: 'danger' | 'warning' | 'default' = 'default',
  options: ConfirmModalOptions = {},
): Promise<boolean> {
  if (options.signal?.aborted) return Promise.resolve(false);

  return new Promise((resolve) => {
    const requestId = ++confirmRequestId;
    let settled = false;
    const settle = (value: boolean) => {
      if (settled) return;
      settled = true;
      options.signal?.removeEventListener('abort', handleAbort);
      resolve(value);
    };
    const handleAbort = () => {
      window.dispatchEvent(new CustomEvent('dismiss-confirm', { detail: { requestId } }));
      settle(false);
    };
    options.signal?.addEventListener('abort', handleAbort, { once: true });
    const event = new CustomEvent('show-confirm', {
      detail: {
        requestId,
        title,
        message,
        variant,
        confirmLabel: options.confirmLabel,
        cancelLabel: options.cancelLabel,
        resolve: settle,
        handled: false,
      },
    });
    window.dispatchEvent(event);
    if (!event.detail.handled) settle(false);
  });
}

export function ConfirmModalProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<ConfirmModalProps>({
    open: false, title: '', message: '', onConfirm: () => {}, onCancel: () => {},
  });
  const dialogRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const cancelButtonRef = useRef<HTMLButtonElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const titleId = useId();
  const descriptionId = useId();
  const [inputValue, setInputValue] = useState('');
  const activeRequestRef = useRef<{ requestId: number; resolve: (value: boolean) => void } | null>(null);

  useEffect(() => {
    function finish(requestId: number, value: boolean) {
      const active = activeRequestRef.current;
      if (!active || active.requestId !== requestId) return;
      activeRequestRef.current = null;
      active.resolve(value);
      setState((previous) => previous.requestId === requestId ? { ...previous, open: false } : previous);
    }
    function handler(e: Event) {
      const detail = (e as CustomEvent).detail;
      detail.handled = true;
      activeRequestRef.current?.resolve(false);
      activeRequestRef.current = { requestId: detail.requestId, resolve: detail.resolve };
      setState({
        open: true,
        requestId: detail.requestId,
        title: detail.title,
        message: detail.message,
        variant: detail.variant || 'default',
        confirmLabel: detail.confirmLabel,
        cancelLabel: detail.cancelLabel,
        onConfirm: () => finish(detail.requestId, true),
        onCancel: () => finish(detail.requestId, false),
      });
      setInputValue('');
    }
    function dismissHandler(e: Event) {
      const requestId = (e as CustomEvent).detail?.requestId;
      finish(requestId, false);
    }
    window.addEventListener('show-confirm', handler);
    window.addEventListener('dismiss-confirm', dismissHandler);
    return () => {
      window.removeEventListener('show-confirm', handler);
      window.removeEventListener('dismiss-confirm', dismissHandler);
      activeRequestRef.current?.resolve(false);
      activeRequestRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!state.open) return undefined;

    previousFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;

    const focusTimer = window.setTimeout(() => {
      (inputRef.current ?? cancelButtonRef.current ?? dialogRef.current)?.focus();
    }, 0);

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        state.onCancel();
        return;
      }

      if (event.key !== 'Tab') return;

      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
      )).filter((element) => !element.hasAttribute('hidden'));
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !dialog.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !dialog.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', handleKeyDown, true);
    return () => {
      window.clearTimeout(focusTimer);
      document.removeEventListener('keydown', handleKeyDown, true);
      const previousFocus = previousFocusRef.current;
      if (previousFocus && document.contains(previousFocus)) {
        window.requestAnimationFrame(() => previousFocus.focus());
      }
    };
  }, [state.open, state.onCancel]);

  if (!state.open) return <>{children}</>;

  return (
    <>
      {children}
      <div
        className="confirm-overlay"
        onClick={(e) => { if (e.target === e.currentTarget) state.onCancel(); }}
      >
        <div
          ref={dialogRef}
          className="confirm-dialog confirm-prompt-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          aria-describedby={descriptionId}
          tabIndex={-1}
        >
          <h3 id={titleId}>
            {state.variant !== 'default' && <UiIcon name="warning" />}
            <span>{state.title}</span>
          </h3>
          <p id={descriptionId} tabIndex={0} aria-label="确认说明">{state.message}</p>
          {state.variant === 'danger' && (
            <input
              ref={inputRef}
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder="输入 DELETE 确认"
              aria-label="输入 DELETE 确认删除"
              className="confirm-input"
            />
          )}
          <div className="confirm-buttons">
            <button ref={cancelButtonRef} type="button" className="btn btn-ghost" onClick={state.onCancel} style={{ fontSize: '0.85rem' }}>
              {state.cancelLabel ?? '取消'}
            </button>
            <button type="button"
              className={`btn ${state.variant === 'danger' ? 'btn-danger' : 'btn-primary'}`}
              onClick={state.onConfirm}
              disabled={state.variant === 'danger' && inputValue !== 'DELETE'}
              style={{ fontSize: '0.85rem' }}
            >
              {state.confirmLabel ?? (state.variant === 'danger' ? '确认删除' : '确认')}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
