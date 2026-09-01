import React, { useState, useRef, useCallback, useEffect } from 'react';
import UiIcon from './UiIcon';

interface UndoToastState {
  label: string;
  visible: boolean;
  onConfirm: () => void;
  onUndo: () => void;
}

/** 删除前等待时长：过短难撤销，过长体验差 */
const UNDO_DELAY_MS = 5000;

export function useUndoDelete() {
  const [toast, setToast] = useState<UndoToastState | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const triggerDelete = useCallback(
    (label: string, onConfirm: () => void, onUndo?: () => void) => {
      if (timerRef.current) clearTimeout(timerRef.current);

      setToast({
        label,
        visible: true,
        onConfirm,
        onUndo: onUndo || (() => {}),
      });

      timerRef.current = setTimeout(() => {
        timerRef.current = null;
        onConfirm();
        setToast(null);
      }, UNDO_DELAY_MS);
    },
    [],
  );

  const handleUndo = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = null;
    if (toast) {
      toast.onUndo();
    }
    setToast(null);
  }, [toast]);

  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current);
  }, []);

  /** 仅用于展示剩余秒数（与 UNDO_DELAY_MS 对齐） */
  const [secondsLeft, setSecondsLeft] = useState(1);
  useEffect(() => {
    if (!toast?.visible) return;
    setSecondsLeft(Math.max(1, Math.ceil(UNDO_DELAY_MS / 1000)));
    const interval = setInterval(() => {
      setSecondsLeft((s) => Math.max(0, s - 1));
    }, 1000);
    return () => clearInterval(interval);
  }, [toast?.visible]);

  const UndoToast = toast?.visible ? (
    <div className="undo-toast">
      <aside className="undo-toast-inner" aria-label="删除操作待确认">
        <UiIcon name="delete" className="undo-toast-icon" />
        <div className="undo-toast-body">
          <span className="undo-toast-title">将删除</span>
          <span className="undo-toast-label">{toast.label}</span>
        </div>
        <span className="undo-toast-timer">{secondsLeft > 0 ? `${secondsLeft}s` : '…'}</span>
        <button type="button" className="undo-toast-btn" onClick={handleUndo}>撤销</button>
        <button type="button" className="undo-toast-close" onClick={handleUndo} aria-label="取消删除" title="取消删除">×</button>
      </aside>
    </div>
  ) : null;

  return { triggerDelete, UndoToast };
}
