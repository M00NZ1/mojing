import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Message } from '../types';
import InlineQueryError from './InlineQueryError';
import { friendlyFetchError } from '../utils/userFacingError';
import './DeleteMessageDialog.css';

export default function DeleteMessageDialog({ sessionId, branchId, message, onClose, onDeleted }: {
  sessionId: number; branchId: string; message: Message; onClose: () => void; onDeleted: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const busy = useRef(false);
  const client = useQueryClient();
  const impact = useQuery({
    queryKey: ['message-deletion-impact', sessionId, branchId, message.id],
    queryFn: ({ signal }) => api.messageDeletionImpact(sessionId, message.id, branchId, signal),
    retry: false, gcTime: 0,
  });
  const remove = useMutation({
    mutationFn: () => api.deleteMessage(sessionId, message.id, branchId),
    onSuccess: () => {
      for (const key of ['session-message-search', 'session-branches', 'bookmarks', 'memory-segments', 'session', 'session-event-tree', 'prompt-trace']) {
        void client.invalidateQueries({ queryKey: [key, sessionId] });
      }
      void client.invalidateQueries({ queryKey: ['sessions'] });
      onDeleted();
    },
    onError: () => { void impact.refetch(); },
    onSettled: () => { busy.current = false; },
  });
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.showModal();
    return () => { previous?.focus(); };
  }, []);
  function submit() {
    if (busy.current || !impact.data?.can_delete || impact.isFetching || impact.isError) return;
    busy.current = true;
    remove.mutate();
  }
  return <dialog ref={dialog} className="delete-message-dialog" aria-labelledby="delete-message-title"
    onCancel={(event) => { event.preventDefault(); if (!busy.current) onClose(); }}>
    <h2 id="delete-message-title">删除这条消息？</h2>
    <blockquote>{message.content.slice(0, 240)}{message.content.length > 240 ? '…' : ''}</blockquote>
    {impact.isPending && <p role="status">正在检查故事线引用…</p>}
    {impact.isError && <InlineQueryError message="暂时无法确认删除影响" error={impact.error} retrying={impact.isFetching} onRetry={() => void impact.refetch()} />}
    {impact.data && !impact.data.can_delete && <div className="delete-message-references" role="status">
      <strong>需要保留这条消息</strong><p>{impact.data.reason}</p>
      <ul>{impact.data.branches.map((branch) => <li key={branch.branch_id}>{branch.is_checkpoint ? '检查点' : '故事线'} · {branch.label}</li>)}</ul>
      {impact.data.reference_count > impact.data.branches.length && <p>另有 {impact.data.reference_count - impact.data.branches.length} 个引用。</p>}
    </div>}
    {impact.data?.can_delete && <p>删除后无法撤销。此消息会从所有共享其原文的故事线中移除，相关收藏也会移除；其他消息和媒体文件保留。</p>}
    {impact.data?.can_delete && ((impact.data.memory_segments_removed ?? 0) > 0 || (impact.data.memory_events_removed ?? 0) > 0 || impact.data.summary_reset) && <div className="delete-message-references" role="note">
      <strong>相关自动记忆也会更新</strong>
      <p>将移除受影响及其后的 {impact.data.memory_segments_removed ?? 0} 段自动摘要、{impact.data.memory_events_removed ?? 0} 项事件{impact.data.summary_reset ? '，并清空主线自动概览' : ''}，避免继续引用已删除的剧情。后续对话会按现有批次重新整理。</p>
      <p>已锁定的记忆纠正与角色设定保留。</p>
    </div>}
    {remove.isError && <p role="alert">删除未完成：{friendlyFetchError(remove.error)}。可在检查结果更新后重试。</p>}
    <div className="delete-message-actions">
      <button type="button" className="btn btn-ghost" autoFocus disabled={remove.isPending} onClick={onClose}>{impact.data && !impact.data.can_delete ? '保留并返回' : '取消'}</button>
      <button type="button" className="btn btn-danger" disabled={!impact.data?.can_delete || impact.isFetching || impact.isError || remove.isPending} onClick={submit}>{remove.isPending ? '正在删除…' : remove.isError ? '重试删除' : '确认删除'}</button>
    </div>
  </dialog>;
}
