import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { canSaveEditedMessage, hasUnsavedEditMessage } from '../utils/editMessageDraft';
import UiIcon from './UiIcon';

interface EditMessageModalProps {
  messageId: number | null;
  content: string;
  originalContent: string;
  regenerateAfterSave: boolean;
  isSaving: boolean;
  saveError?: string | null;
  onContentChange: (content: string) => void;
  onSave: (messageId: number, content: string) => void | Promise<void>;
  onClose: () => void;
}

const FOCUSABLE_SELECTOR = [
  'button:not(:disabled)',
  'textarea:not(:disabled)',
  '[href]',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

export default function EditMessageModal({
  messageId,
  content,
  originalContent,
  regenerateAfterSave,
  isSaving,
  saveError,
  onContentChange,
  onSave,
  onClose,
}: EditMessageModalProps) {
  const dialogRef = useRef<HTMLFormElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const continueEditingRef = useRef<HTMLButtonElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const fallbackFocusRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);
  const dirtyRef = useRef(false);
  const isSavingRef = useRef(false);
  const discardPendingRef = useRef(false);
  const [discardPending, setDiscardPending] = useState(false);
  const titleId = useId();
  const descriptionId = useId();
  const fieldId = useId();
  const discardTitleId = useId();
  const hasChanges = hasUnsavedEditMessage(content, originalContent);
  const canSave = canSaveEditedMessage(content, originalContent, isSaving);

  onCloseRef.current = onClose;
  dirtyRef.current = hasChanges;
  isSavingRef.current = isSaving;
  discardPendingRef.current = discardPending;

  useEffect(() => {
    if (messageId === null) return undefined;

    setDiscardPending(false);
    discardPendingRef.current = false;
    previousFocusRef.current = document.activeElement instanceof HTMLElement && document.activeElement !== document.body
      ? document.activeElement
      : null;
    fallbackFocusRef.current = document.querySelector<HTMLElement>(
      `[data-message-edit-trigger="${messageId}"], [data-message-actions-trigger="${messageId}"]`,
    );
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const frame = window.requestAnimationFrame(() => textareaRef.current?.focus());

    const focusTextarea = () => {
      window.requestAnimationFrame(() => textareaRef.current?.focus());
    };

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        if (isSavingRef.current) return;
        if (discardPendingRef.current) {
          discardPendingRef.current = false;
          setDiscardPending(false);
          focusTextarea();
        } else if (dirtyRef.current) {
          discardPendingRef.current = true;
          setDiscardPending(true);
          window.requestAnimationFrame(() => continueEditingRef.current?.focus());
        } else {
          onCloseRef.current();
        }
        return;
      }

      if (event.key !== 'Tab') return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusRoot = discardPendingRef.current
        ? dialog.querySelector<HTMLElement>('[data-edit-discard-confirm]')
        : dialog;
      if (!focusRoot) return;
      const focusable = Array.from(focusRoot.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
        .filter((element) => element.getClientRects().length > 0 && window.getComputedStyle(element).visibility !== 'hidden');
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!focusRoot.contains(document.activeElement)) {
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
      document.body.style.overflow = previousOverflow;
      const returnTarget = previousFocusRef.current?.isConnected
        ? previousFocusRef.current
        : fallbackFocusRef.current?.isConnected
          ? fallbackFocusRef.current
          : null;
      if (returnTarget) {
        window.requestAnimationFrame(() => {
          if (returnTarget.isConnected) returnTarget.focus();
        });
      }
    };
  }, [messageId]);

  if (messageId === null) return null;

  const requestClose = () => {
    if (isSaving) return;
    if (hasChanges) {
      discardPendingRef.current = true;
      setDiscardPending(true);
      window.requestAnimationFrame(() => continueEditingRef.current?.focus());
      return;
    }
    onClose();
  };

  const continueEditing = () => {
    discardPendingRef.current = false;
    setDiscardPending(false);
    window.requestAnimationFrame(() => textareaRef.current?.focus());
  };

  return createPortal(
    <div
      className="edit-message-overlay"
      onClick={(event) => {
        if (event.target === event.currentTarget) requestClose();
      }}
    >
      <form
        ref={dialogRef}
        className="chat-edit-modal edit-message-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        aria-busy={isSaving}
        tabIndex={-1}
        onSubmit={(event) => {
          event.preventDefault();
          if (canSave) void onSave(messageId, content);
        }}
      >
        <div className="edit-message-header">
          <div>
            <h3 id={titleId}>编辑消息</h3>
            <p id={descriptionId} className="hint">
              保存后会从这里创建新的故事线，当前故事线和后续内容保持不变。
              {regenerateAfterSave ? ' 角色会根据修改后的消息重新回复。' : ' 之后的对话将从修改后的内容继续。'}
            </p>
          </div>
          <button
            type="button"
            className="edit-message-close"
            aria-label="关闭消息编辑"
            title="关闭"
            disabled={isSaving}
            onClick={requestClose}
          >
            <UiIcon name="close" />
          </button>
        </div>

        <label className="edit-message-field" htmlFor={fieldId}>
          <span>修改后的消息</span>
          <textarea
            ref={textareaRef}
            id={fieldId}
            value={content}
            onChange={(event) => {
              if (discardPendingRef.current) {
                discardPendingRef.current = false;
                setDiscardPending(false);
              }
              onContentChange(event.target.value);
            }}
            rows={6}
            disabled={isSaving}
          />
        </label>

        {saveError && <p className="edit-message-error" role="alert">{saveError}</p>}

        {discardPending ? (
          <div className="edit-message-discard" role="alert" aria-labelledby={discardTitleId} data-edit-discard-confirm>
            <div>
              <strong id={discardTitleId}>放弃这次修改？</strong>
              <p>尚未保存的改写会丢失，原消息不会改变。</p>
            </div>
            <div className="edit-message-discard-actions">
              <button ref={continueEditingRef} type="button" className="btn btn-ghost" onClick={continueEditing}>继续编辑</button>
              <button type="button" className="btn btn-danger" onClick={onClose}>放弃修改</button>
            </div>
          </div>
        ) : (
          <div className="edit-message-actions">
            <span className="edit-message-status" role="status" aria-live="polite">
              {isSaving ? '正在创建编辑故事线…' : ''}
            </span>
            <button type="button" className="btn btn-ghost" onClick={requestClose} disabled={isSaving}>取消</button>
            <button type="submit" className="btn btn-primary" disabled={!canSave}>
              {isSaving ? '正在创建…' : '创建编辑故事线'}
            </button>
          </div>
        )}
      </form>
    </div>,
    document.body,
  );
}
