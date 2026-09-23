import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import CreationHomeLink from '../components/CreationHomeLink';
import InlineQueryError from '../components/InlineQueryError';
import { confirmModal } from '../components/ConfirmModal';
import type { WorldLibrary } from '../types';
import './WorldLibraryPage.css';

type LegacyWorld = WorldLibrary['legacy_templates'][number];

export default function WorldLibraryPage() {
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState('');
  const queryClient = useQueryClient();
  const library = useQuery({ queryKey: ['world-library'], queryFn: api.getWorldLibrary });
  const promote = useMutation({
    mutationFn: (world: LegacyWorld) => api.promoteWorld(world.template_id, world.updated_at, world.source_hash),
    onMutate: () => setNotice(''),
    onSuccess: async (world) => {
      setNotice(`“${world.name}”已归入世界，背景与条目可在世界资料中编辑。`);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['world-library'] }),
        queryClient.invalidateQueries({ queryKey: ['encyclopedias'] }),
        queryClient.invalidateQueries({ queryKey: ['world-templates'] }),
      ]);
    },
  });
  async function moveWorld(world: LegacyWorld) {
    if (promote.isPending) return;
    const accepted = await confirmModal(
      `整理“${world.name}”`,
      '将背景、玩法与全部条目归入世界资料。内容完全一致时合并为同一世界；内容不同的同名资料分别保留。旧工坊原文及已有会话保留。',
      'default', { confirmLabel: '归入世界' },
    );
    if (accepted) promote.mutate(world);
  }
  async function retryPromotion() {
    const submitted = promote.variables;
    if (!submitted || promote.isPending) return;
    const refreshed = await library.refetch();
    if (refreshed.isError) return;
    const latest = refreshed.data?.legacy_templates.find((world) => world.template_id === submitted.template_id);
    if (latest && (latest.updated_at !== submitted.updated_at || latest.source_hash !== submitted.source_hash)) {
      await moveWorld(latest);
    } else {
      // Reuse the original operation when its response may have been lost.
      promote.mutate(submitted);
    }
  }
  const worlds = (library.data?.worlds ?? []).filter((world) =>
    `${world.name}\n${world.description}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  return (
    <main className="page world-library-page">
      <header className="world-library-header">
        <div><h1>世界</h1><p className="hint">每个世界，都有自己的百科。背景、人物、地点与事件在此整理。</p></div>
        <CreationHomeLink />
      </header>
      <nav className="world-library-tools" aria-label="世界工具">
        <Link className="btn btn-primary" to="/encyclopedia">管理世界</Link>
        <Link className="btn btn-ghost" to="/workbench?tab=create">生成世界</Link>
        <Link className="btn btn-ghost" to="/workbench?tab=import">导入资料</Link>
        <Link className="btn btn-ghost" to="/workbench?tab=history">生成记录</Link>
      </nav>
      <div className="world-library-filter">
        <label className="world-library-search"><span>搜索世界</span><input type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索名称或简介" /></label>
        {library.isSuccess && <span className="world-library-count" role="status">{search.trim() ? `${worlds.length} 个匹配` : `${worlds.length} 个世界`}</span>}
      </div>
      {library.isPending && <p role="status">正在读取世界…</p>}
      {library.isError && <InlineQueryError message="世界读取失败" error={library.error} onRetry={() => void library.refetch()} />}
      {notice && <p role="status" className="hint">{notice} {promote.data && <Link to={`/encyclopedia?encId=${promote.data.encyclopedia_id}`}>打开世界资料</Link>}</p>}
      {promote.isError && <InlineQueryError message="整理未完成，原资料已保留" error={promote.error} retrying={library.isFetching} onRetry={() => void retryPromotion()} />}
      <section className="world-library-grid" aria-label="我的世界">
        {worlds.map((world) => (
          <Link to={`/encyclopedia?encId=${world.id}`} className="world-library-card" key={world.id}>
            <div className={`world-library-cover${world.cover_image_path ? '' : ' world-library-cover-empty'}`} aria-hidden="true">
              <span>{Array.from(world.name.trim())[0] || '境'}</span>
              {world.cover_image_path && <img src={api.mediaRefUrl(world.cover_image_path)} alt="" loading="lazy" onError={(event) => { event.currentTarget.style.display = 'none'; }} />}
            </div>
            <div className="world-library-card-body">
              <span className="world-library-mode">{world.gameplay_mode || '自由剧情'}</span>
              <h2>{world.name}</h2><p>{world.description || '尚未填写简介，进入百科完善世界设定。'}</p>
              <span className="world-library-open">打开世界百科 <span aria-hidden="true">↗</span></span>
            </div>
          </Link>
        ))}
      </section>
      {library.isSuccess && worlds.length === 0 && <p className="hint">{search.trim() ? '没有匹配的世界。' : '还没有世界，可以手动建立或从旧工坊资料整理。'}</p>}
      {!!library.data?.legacy_templates.length && (
        <details className="page-card world-library-legacy">
          <summary>旧工坊资料 · {library.data.legacy_templates.length}</summary>
          <p className="hint">将旧资料归入世界后，在同一处维护背景与百科条目。</p>
          {library.data.legacy_templates.map((world) => (
            <div className="world-library-legacy-row" key={world.template_id}>
              <div><strong>{world.name}</strong><p>{world.description || '未填写简介'}</p></div>
              <button className="btn btn-ghost" type="button" disabled={promote.isPending} onClick={() => void moveWorld(world)}>
                {promote.isPending && promote.variables?.template_id === world.template_id ? '正在整理…' : '归入世界'}
              </button>
            </div>
          ))}
        </details>
      )}
    </main>
  );
}
