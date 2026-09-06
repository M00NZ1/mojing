import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { NavLink, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { confirmModal } from './ConfirmModal';
import InlineQueryError from './InlineQueryError';
import './StarterWorldCard.css';

export default function StarterWorldCard() {
  const catalog = useQuery({ queryKey: ['starter-catalog'], queryFn: api.getStarterCatalog });
  const client = useQueryClient();
  const navigate = useNavigate();
  const start = useMutation({ mutationFn: () => {
    const data = catalog.data;
    if (!data?.available) throw new Error('示例资料已发生变化，请刷新后重试。');
    return api.createSessionWithConfig({ title: `${data.title} · 新故事`, template_id: data.template_id,
      encyclopedia_id: data.encyclopedia_id, initial_character_ids: data.characters?.map((item) => item.id), narrator_enabled: true });
  }, onSuccess: (session) => {
    void client.invalidateQueries({ queryKey: ['sessions'] });
    navigate(`/chat/${session.id}`);
  } });
  const restore = useMutation({ mutationFn: api.restoreStarterCatalog, onSuccess: async () => {
    await Promise.all(['starter-catalog', 'characters', 'encyclopedias', 'world-templates'].map((key) => client.invalidateQueries({ queryKey: [key] })));
  } });
  if (catalog.isLoading) return <p className="hint" role="status">正在读取开局资料…</p>;
  if (catalog.isError) return <InlineQueryError message="开局资料读取失败" error={catalog.error} retrying={catalog.isFetching} onRetry={() => void catalog.refetch()} />;
  const data = catalog.data;
  const retired = Object.values(data?.retired || {}).flat();
  return <>
    {data?.available && <section className="starter-world" aria-label="雾港开局">
      <div className="starter-world-copy"><p className="eyebrow">从一封旧信开始</p><h2>{data.title}</h2>
        <p>{data.summary}</p><div className="starter-world-meta"><span>一个百科 · 一个世界</span><span>{data.characters?.map((item) => item.name).join(' / ')}</span></div>
        <p className="hint">角色与设定已经就绪，你的身份、来意与下一步由你决定。</p>
        <div className="starter-world-actions"><button type="button" className="btn btn-primary" disabled={start.isPending} onClick={() => start.mutate()}>{start.isPending ? '正在打开故事…' : '从雾港开始'}</button><NavLink to="/encyclopedia" className="btn btn-ghost">先看看设定</NavLink></div>
        {start.isError && <InlineQueryError message="未能打开故事" error={start.error} retrying={start.isPending} onRetry={() => start.mutate()} />}
      </div>
    </section>}
    {retired.length > 0 && <details className="starter-retired"><summary>旧示例已收起 · {retired.length} 项</summary>
      <p className="hint">仅收起原样、无引用的旧示例；内容仍保留在本机，已修改或使用过的资料不受影响。</p>
      <p className="starter-retired-names">{retired.join('、')}</p>
      <button type="button" className="btn btn-ghost btn-sm" disabled={restore.isPending} onClick={async () => { if (await confirmModal('恢复旧示例', '这些旧示例将重新出现在角色、百科和世界列表中，现有内容不会被覆盖。')) restore.mutate(); }}>{restore.isPending ? '正在恢复…' : '恢复旧示例'}</button>
      {restore.isError && <InlineQueryError message="恢复失败，旧资料仍然保留" error={restore.error} onRetry={() => restore.mutate()} />}
    </details>}
  </>;
}
