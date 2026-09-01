import { FormEvent, useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import EmojiPicker from './EmojiPicker';
import VoiceInputButton from './VoiceInputButton';
import UiIcon from './UiIcon';
import { streamErrorDetail } from '../utils/userFacingError';
import { insertTextAtSelection } from '../utils/textInsertion';
import { mergeSelectedFiles, splitFilesBySize } from '../utils/fileSelection';
import { useToast } from '../hooks/useToast';

interface ChatInputBarProps {
  input: string;
  setInput: (value: string) => void;
  files: File[];
  setFiles: Dispatch<SetStateAction<File[]>>;
  fileUrls: string[];
  submittingFiles?: File[];
  isGenerating: boolean;
  isPending: boolean;
  isError: boolean;
  errorMessage?: string;
  retryReplyAvailable?: boolean;
  onRetryReply?: () => void;
  onSend: (event: FormEvent) => void;
  onStop: () => void;
  onQuickAction: (actionName: string, returnFocusTarget?: HTMLElement | null) => void;
  quickActionPendingLabel?: string | null;
  canCreateEntryFromMessage: boolean;
  settingConflictHint?: string;
  quotingPreview?: string | null;
  onClearQuote?: () => void;
  onRequestNarrator?: () => void;
  narratorEnabled?: boolean;
  inputPlaceholder?: string;
  narratorActionLabel?: string;
  maxUploadMb?: number;
  focusRequestKey?: number;
}

export default function ChatInputBar({
  input, setInput, files, setFiles, fileUrls, submittingFiles = [],
  isGenerating, isPending, isError, errorMessage,
  retryReplyAvailable, onRetryReply,
  onSend, onStop, onQuickAction, quickActionPendingLabel, canCreateEntryFromMessage,
  settingConflictHint,
  quotingPreview, onClearQuote, onRequestNarrator, narratorEnabled, inputPlaceholder, narratorActionLabel,
  maxUploadMb,
  focusRequestKey = 0,
}: ChatInputBarProps) {
  const { showToast } = useToast();
  const [showEmojiPicker, setShowEmojiPicker] = useState(false);
  const [showMoreTools, setShowMoreTools] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const moreToolsRef = useRef<HTMLDivElement>(null);
  const moreToolsMenuRef = useRef<HTMLDivElement>(null);
  const moreToolsTriggerRef = useRef<HTMLButtonElement>(null);
  const emojiTriggerRef = useRef<HTMLButtonElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const inputSelectionRef = useRef({ text: input, start: input.length, end: input.length });
  const observedFocusRequestRef = useRef(focusRequestKey);
  const previousQuickActionPendingRef = useRef(Boolean(quickActionPendingLabel));
  const normalizedMaxUploadMb = typeof maxUploadMb === 'number' && Number.isFinite(maxUploadMb)
    ? Math.max(1, Math.floor(maxUploadMb))
    : undefined;
  const submittingFileSet = new Set(submittingFiles);
  const submittingFileCount = files.reduce(
    (count, file) => count + (submittingFileSet.has(file) ? 1 : 0),
    0,
  );

  const closeMoreTools = useCallback((restoreFocus = true) => {
    setShowMoreTools(false);
    if (!restoreFocus) return;
    window.requestAnimationFrame(() => moreToolsTriggerRef.current?.focus());
  }, []);

  const closeEmojiPicker = useCallback((restoreFocus = true) => {
    setShowEmojiPicker(false);
    if (!restoreFocus) return;
    window.requestAnimationFrame(() => emojiTriggerRef.current?.focus());
  }, []);

  useEffect(() => {
    if (!showMoreTools) return;
    const frame = window.requestAnimationFrame(() => {
      moreToolsMenuRef.current?.querySelector<HTMLElement>('[role="menuitem"]:not(:disabled)')?.focus();
    });
    const closeOnOutsidePress = (event: PointerEvent) => {
      if (event.target instanceof Node && !moreToolsRef.current?.contains(event.target)) {
        closeMoreTools(false);
      }
    };
    const handleMenuKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        closeMoreTools();
        return;
      }
      const menu = moreToolsMenuRef.current;
      if (!menu) return;
      const items = Array.from(menu.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)'))
        .filter((element) => element.getClientRects().length > 0);
      if (items.length === 0) return;
      if (event.key === 'Tab') {
        window.requestAnimationFrame(() => {
          if (!moreToolsRef.current?.contains(document.activeElement)) closeMoreTools(false);
        });
        return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const currentIndex = items.indexOf(document.activeElement as HTMLElement);
      const targetIndex = event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? items.length - 1
          : event.key === 'ArrowUp'
            ? (currentIndex <= 0 ? items.length - 1 : currentIndex - 1)
            : (currentIndex < 0 || currentIndex === items.length - 1 ? 0 : currentIndex + 1);
      items[targetIndex]?.focus();
    };
    document.addEventListener('pointerdown', closeOnOutsidePress, true);
    document.addEventListener('keydown', handleMenuKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('pointerdown', closeOnOutsidePress, true);
      document.removeEventListener('keydown', handleMenuKeyDown);
    };
  }, [closeMoreTools, showMoreTools]);

  useEffect(() => {
    const isPending = Boolean(quickActionPendingLabel);
    const wasPending = previousQuickActionPendingRef.current;
    previousQuickActionPendingRef.current = isPending;
    if (wasPending && !isPending && showMoreTools) closeMoreTools(false);
  }, [closeMoreTools, quickActionPendingLabel, showMoreTools]);

  useEffect(() => {
    if (focusRequestKey === observedFocusRequestRef.current) return undefined;
    observedFocusRequestRef.current = focusRequestKey;
    const frame = window.requestAnimationFrame(() => {
      const textarea = textareaRef.current;
      if (!textarea) return;
      const cursor = textarea.value.length;
      textarea.focus();
      textarea.setSelectionRange(cursor, cursor);
      inputSelectionRef.current = { text: textarea.value, start: cursor, end: cursor };
      textarea.style.height = 'auto';
      textarea.style.height = `${Math.min(textarea.scrollHeight, 120)}px`;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [focusRequestKey]);

  const rememberInputSelection = (textarea: HTMLTextAreaElement) => {
    inputSelectionRef.current = {
      text: textarea.value,
      start: textarea.selectionStart,
      end: textarea.selectionEnd,
    };
  };

  const insertIntoInput = (text: string) => {
    const rememberedSelection = inputSelectionRef.current;
    const selection = rememberedSelection.text === input
      ? rememberedSelection
      : { start: input.length, end: input.length };
    const result = insertTextAtSelection(
      input,
      selection.start,
      selection.end,
      text,
    );
    inputSelectionRef.current = { text: result.text, start: result.cursor, end: result.cursor };
    setInput(result.text);
    requestAnimationFrame(() => {
      if (!textareaRef.current) return;
      textareaRef.current.focus();
      textareaRef.current.setSelectionRange(result.cursor, result.cursor);
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 120)}px`;
    });
  };

  const handleVoiceText = (text: string) => insertIntoInput(text);

  return (
    <div className="chat-inputbar">
      {quotingPreview ? (
        <div className="quote-reply-bar">
          <span className="quote-reply-text">引用：{quotingPreview}</span>
          {onClearQuote ? (
            <button type="button" className="quote-reply-clear" onClick={onClearQuote}>
              取消
            </button>
          ) : null}
        </div>
      ) : null}
      <div className="chat-inputbar-tools compact-wrap">
        <VoiceInputButton onTranscribed={handleVoiceText} />
        {narratorEnabled && onRequestNarrator ? (
          <button className="chat-inputbar-btn" type="button" onClick={onRequestNarrator} disabled={isGenerating} title={narratorActionLabel ?? '来一段旁白'} aria-label={narratorActionLabel ?? '来一段旁白'}>
            <UiIcon name="narrator" />
          </button>
        ) : null}
        <button
          ref={emojiTriggerRef}
          className="chat-inputbar-btn"
          type="button"
          onClick={() => {
            if (showEmojiPicker) closeEmojiPicker();
            else {
              setShowMoreTools(false);
              setShowEmojiPicker(true);
            }
          }}
          title="选择表情"
          aria-label="选择表情"
          aria-expanded={showEmojiPicker}
          aria-controls="emoji-picker"
        >
          <UiIcon name="smile" />
        </button>
        <button
          className="chat-inputbar-btn"
          type="button"
          onClick={() => fileInputRef.current?.click()}
          title={`${files.length > 0 ? '继续添加图片' : '添加图片'}${normalizedMaxUploadMb ? `（单张不超过 ${normalizedMaxUploadMb} MB）` : ''}`}
          aria-label={files.length > 0 ? '继续添加图片' : '添加图片'}
        >
          <UiIcon name="attachment" />
        </button>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          accept="image/*"
          onChange={(event) => {
            const selected = Array.from(event.target.files ?? []);
            const { accepted, rejected } = normalizedMaxUploadMb
              ? splitFilesBySize(selected, normalizedMaxUploadMb * 1024 * 1024)
              : { accepted: selected, rejected: [] };
            if (accepted.length > 0) {
              setFiles((current) => mergeSelectedFiles(current, accepted));
            }
            if (rejected.length > 0) {
              showToast(
                `${rejected.length} 张图片超过单张 ${normalizedMaxUploadMb} MB 上限，未添加`,
                'warn',
              );
            }
            event.target.value = '';
          }}
          hidden
        />
        {files.length > 0 && (
          <div
            className="chat-attachment-previews"
            aria-label={`已添加 ${files.length} 张图片${submittingFileCount > 0 ? `，其中 ${submittingFileCount} 张正在发送` : ''}`}
          >
            <span className="pill chat-attachment-count">
              {files.length} 张{submittingFileCount > 0 ? ` · ${submittingFileCount} 张发送中` : ''}
            </span>
            {files.map((f, i) => {
              const isSubmitting = submittingFileSet.has(f);
              return (
                <div
                  key={`${f.name}-${f.lastModified}-${i}`}
                  className={`chat-attachment-preview${isSubmitting ? ' is-submitting' : ''}`}
                >
                  <img src={fileUrls[i]} alt={`图片附件 ${i + 1}`} />
                  <button
                    type="button"
                    className="chat-attachment-remove"
                    aria-label={isSubmitting ? `图片 ${f.name} 正在发送` : `移除图片 ${f.name}`}
                    title={isSubmitting ? '正在发送，停止后可移除' : '移除图片'}
                    disabled={isSubmitting}
                    onClick={() => setFiles((prev) => prev.filter((_, j) => j !== i))}
                  >
                    <UiIcon name={isSubmitting ? 'loading' : 'close'} className={isSubmitting ? 'ui-icon-loading' : undefined} />
                  </button>
                </div>
              );
            })}
          </div>
        )}
        <div className="chat-inputbar-more-wrap" ref={moreToolsRef}>
          <button
            ref={moreToolsTriggerRef}
            type="button"
            className="chat-inputbar-btn chat-inputbar-more"
            onClick={() => {
              if (showMoreTools) closeMoreTools();
              else {
                setShowEmojiPicker(false);
                setShowMoreTools(true);
              }
            }}
            title={quickActionPendingLabel || '更多工具'}
            aria-label={quickActionPendingLabel || '更多工具'}
            aria-busy={quickActionPendingLabel ? true : undefined}
            aria-haspopup="menu"
            aria-expanded={showMoreTools}
            aria-controls="more-tools-menu"
          >
            <UiIcon name={quickActionPendingLabel ? 'loading' : 'plus'} className={quickActionPendingLabel ? 'ui-icon-loading' : undefined} />
          </button>
          {showMoreTools && (
            <div ref={moreToolsMenuRef} id="more-tools-menu" className="more-tools-menu" role="menu" aria-label="更多工具">
              <button type="button" className="more-tools-item" role="menuitem" disabled={Boolean(quickActionPendingLabel)} onClick={() => { closeMoreTools(); onQuickAction('summarize_session_events', moreToolsTriggerRef.current); }}>
                <UiIcon name="summary" />
                <span>总结本次事件</span>
              </button>
              <button
                type="button"
                className="more-tools-item"
                role="menuitem"
                disabled={Boolean(quickActionPendingLabel)}
                title={settingConflictHint}
                onClick={() => { closeMoreTools(); onQuickAction('check_setting_conflicts', moreToolsTriggerRef.current); }}
              >
                <UiIcon name="search" />
                <span>{settingConflictHint || '检查设定冲突'}</span>
              </button>
              <button
                type="button"
                className="more-tools-item"
                role="menuitem"
                disabled={!canCreateEntryFromMessage || Boolean(quickActionPendingLabel)}
                title={canCreateEntryFromMessage ? undefined : '生成完成并保存消息后可用'}
                onClick={() => { closeMoreTools(); onQuickAction('create_entry_from_message', moreToolsTriggerRef.current); }}
              >
                <UiIcon name="book" />
                <span>{canCreateEntryFromMessage ? '沉淀为百科条目' : '消息保存后可沉淀百科'}</span>
              </button>
            </div>
          )}
        </div>
      </div>
      {quickActionPendingLabel ? (
        <div className="chat-quick-action-status" role="status" aria-live="polite">
          <UiIcon name="loading" className="ui-icon-loading" />
          <span>{quickActionPendingLabel}</span>
        </div>
      ) : null}
      {showEmojiPicker && (
        <EmojiPicker
          onSelect={insertIntoInput}
          onClose={closeEmojiPicker}
        />
      )}
      <form className="chat-inputbar-form" onSubmit={onSend}>
        <textarea
          ref={textareaRef}
          className="chat-inputbar-input"
          placeholder={inputPlaceholder ?? '输入消息…'}
          title="Enter 发送，Shift+Enter 换行"
          value={input}
          onChange={(event) => {
            rememberInputSelection(event.currentTarget);
            setInput(event.currentTarget.value);
          }}
          onSelect={(event) => rememberInputSelection(event.currentTarget)}
          onBlur={(event) => rememberInputSelection(event.currentTarget)}
          maxLength={10000}
          rows={1}
          aria-label="消息内容"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              e.preventDefault();
              onSend(e as unknown as FormEvent);
            }
          }}
          onInput={(e) => {
            const ta = e.currentTarget;
            ta.style.height = 'auto';
            ta.style.height = Math.min(ta.scrollHeight, 120) + 'px';
          }}
        />
        <div className="chat-inputbar-extra">
          <span className="chat-inputbar-count">{input.length}/10000</span>
        </div>
        <div className="chat-inputbar-send-group">
          {isGenerating && (
            <button className="chat-inputbar-stop" type="button" onClick={onStop} title="停止生成">
              <UiIcon name="stop" />
              <span>停止</span>
            </button>
          )}
          <button className="chat-inputbar-send" type="submit" disabled={isPending || isGenerating || (!input.trim() && files.length === 0)} title={isPending || isGenerating ? '生成中…' : '发送消息'}>
            {isPending || isGenerating ? <><UiIcon name="loading" className="ui-icon-loading" /><span>生成中</span></> : '发送'}
          </button>
        </div>
        {isError && (
          <div className="chat-inputbar-error" role="alert">
            <UiIcon name="warning" />
            <div className="chat-inputbar-error-copy">
              <strong>消息发送失败</strong>
              <span>{streamErrorDetail(errorMessage)}</span>
            </div>
          </div>
        )}
        {retryReplyAvailable && onRetryReply && (
          <div className="chat-inputbar-error chat-inputbar-retry">
            <span>回复未完成，用户消息已经保存。</span>
            <button type="button" className="btn btn-sm" onClick={onRetryReply} disabled={isGenerating || isPending}>
              重新生成回复
            </button>
          </div>
        )}
      </form>
    </div>
  );
}
