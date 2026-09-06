import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Message } from '../types';
import { friendlyFetchError } from '../utils/userFacingError';
import './DeleteMessageDialog.css';

export default function MessageContextDialog({ sessionId, branchId, message, onClose, onSaved }: {
  sessionId: number; branchId: string; message: Message; onClose: () => void; onSaved: () => Promise<void>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const working = useRef(false);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');
  const queryClient = useQueryClient();
  const include = message.include_in_context === false;
  const action = include ? '恢复到上下文' : '排除上下文';
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.showModal();
    return () => previous?.focus();
  }, []);
  async function submit() {
    if (working.current) return;
    working.current = true;
    setBusy(true);
    setError('');
    let committed = saved;
    try {
      if (!committed) {
        await api.setMessageContext(sessionId, message.id, branchId, include, !include);
        committed = true;
        setSaved(true);
        for (const key of ['memory-segments', 'session-event-tree', 'session', 'token-usage']) {
          void queryClient.invalidateQueries({ queryKey: [key, sessionId] });
        }
        void queryClient.invalidateQueries({ queryKey: ['sessions'] });
      }
      await onSaved();
      onClose();
    } catch (failure) {
      setError(`${committed ? '设置已保存，消息显示未能刷新' : '设置未完成'}：${friendlyFetchError(failure)}`);
    } finally {
      working.current = false;
      setBusy(false);
    }
  }
  return <dialog ref={dialog} className="delete-message-dialog" aria-labelledby="message-context-title"
    onCancel={(event) => { event.preventDefault(); if (!working.current) onClose(); }}>
    <h2 id="message-context-title">{action}？</h2>
    <blockquote>{message.content.slice(0, 240)}{message.content.length > 240 ? '…' : ''}</blockquote>
    <p>{include ? '从下一轮开始，这条原文可再次参与模型上下文和自动摘要。是否实际发送仍取决于当前故事线及上下文预算。' : '从下一轮开始，这条原文不参与模型上下文和自动摘要；仍可阅读、搜索和导出。'}</p>
    <div className="delete-message-references"><strong>原文保留，相关自动记忆重新整理</strong>
      <p>共享这条原文的故事线都会采用此设置。受影响的自动摘要与事件会失效；用户锁定纠正及角色设定保留。若锁定纠正中引用了相同内容，请在记忆面板按需调整。</p>
    </div>
    {error && <p role="alert">{error}</p>}
    <div className="delete-message-actions">
      <button type="button" className="btn btn-ghost" autoFocus disabled={busy} onClick={onClose}>{saved ? '返回对话' : '取消'}</button>
      <button type="button" className="btn" disabled={busy} onClick={() => void submit()}>{busy ? saved ? '正在刷新…' : '正在保存…' : saved ? '重试刷新' : action}</button>
    </div>
  </dialog>;
}
