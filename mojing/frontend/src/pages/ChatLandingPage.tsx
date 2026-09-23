import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
import InlineQueryError from '../components/InlineQueryError';
import UiIcon from '../components/UiIcon';
import type { SessionItem } from '../types';
import './ChatLandingPage.css';

function sessionDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: 'long', day: 'numeric',
  }).format(date);
}

function SessionMeta({ session }: { session: SessionItem }) {
  const date = sessionDate(session.updated_at);
  return (
    <span className="story-landing-meta">
      {date && <time dateTime={session.updated_at}>更新于 {date}</time>}
      <span>{session.message_count} 条消息</span>
      {session.participant_count > 0 && <span>{session.participant_count} 位角色</span>}
    </span>
  );
}

export default function ChatLandingPage() {
  const sessionsQuery = useQuery({ queryKey: ['sessions'], queryFn: () => api.listSessions() });
  const recent = sessionsQuery.data?.slice(0, 3) ?? [];
  const latest = recent[0];
  const latestPreview = latest?.last_message_preview?.trim() || latest?.summary?.trim();

  function openSessionList() {
    window.dispatchEvent(new CustomEvent('open-mobile-sessions'));
  }

  function openCreateSession() {
    window.dispatchEvent(new CustomEvent('open-mobile-sessions'));
    window.dispatchEvent(new CustomEvent('create-session'));
  }

  return (
    <main className="main-content">
      <div className="page-scroll story-landing">
        <div className="story-landing-content">
          <header className="story-landing-header">
            <div className="story-landing-mark" aria-hidden="true">墨</div>
            <div>
              <h1>{latest ? '故事，接着写。' : '从这里，进入故事。'}</h1>
              <p>{latest ? '回到最近的对话，或为下一段旅程挑选新的角色与世界。' : '创建对话，选择角色和世界；已有资料会留在这台设备上。'}</p>
            </div>
          </header>

          {sessionsQuery.isPending && (
            <section className="story-landing-status" role="status" aria-busy="true">
              <UiIcon name="loading" className="ui-icon-loading" />
              <span>正在读取本机对话…</span>
            </section>
          )}

          {sessionsQuery.isError && sessionsQuery.data === undefined && (
            <section className="story-landing-status story-landing-error">
              <h2>暂时无法读取对话</h2>
              <p>请确认本机服务已启动。已有记录不会因此变成空白。</p>
              <InlineQueryError
                message="会话列表加载失败"
                retrying={sessionsQuery.isFetching}
                onRetry={() => { void sessionsQuery.refetch(); }}
              />
              <Link className="btn btn-ghost" to="/settings?tab=api">配置模型服务</Link>
            </section>
          )}

          {!sessionsQuery.isPending && sessionsQuery.data !== undefined && (
            latest ? (
              <div className="story-landing-grid">
                <section className="story-landing-recent" aria-labelledby="story-landing-recent-title">
                  <div className="story-landing-section-heading">
                    <h2 id="story-landing-recent-title">最近的故事</h2>
                    <button type="button" className="story-landing-all" onClick={openSessionList}>查看全部对话</button>
                  </div>
                  <article className="story-landing-feature">
                    <span className="story-landing-feature-label">最近更新</span>
                    <h3>{latest.title?.trim() || '未命名对话'}</h3>
                    {latestPreview && <p className="story-landing-summary">{latestPreview}</p>}
                    <SessionMeta session={latest} />
                    <Link className="btn btn-primary story-landing-continue" to={`/chat/${latest.id}`}>继续对话</Link>
                  </article>
                  {recent.length > 1 && (
                    <div className="story-landing-more" aria-label="其他最近对话">
                      {recent.slice(1).map((session) => (
                        <Link key={session.id} to={`/chat/${session.id}`} className="story-landing-row">
                          <span className="story-landing-row-title">{session.title?.trim() || '未命名对话'}</span>
                          <SessionMeta session={session} />
                          <span className="story-landing-row-open" aria-hidden="true">打开</span>
                        </Link>
                      ))}
                    </div>
                  )}
                </section>
                <aside className="story-landing-new" aria-labelledby="story-landing-new-title">
                  <h2 id="story-landing-new-title">开启新篇</h2>
                  <p>新建一段对话，或从小说创作开始。</p>
                  <button type="button" className="btn btn-ghost" onClick={openCreateSession}><UiIcon name="plus" />新建对话</button>
                  <Link className="story-landing-text-link" to="/story-simulation">前往小说创作</Link>
                </aside>
              </div>
            ) : (
              <section className="story-landing-empty" aria-labelledby="story-landing-empty-title">
                <h2 id="story-landing-empty-title">写下第一句。</h2>
                <p>准备角色后开始对话；也可以直接进入小说创作。</p>
                <div className="story-landing-empty-actions">
                  <button type="button" className="btn btn-primary" onClick={openCreateSession}><UiIcon name="plus" />新建对话</button>
                  <Link className="btn btn-ghost" to="/characters">管理角色</Link>
                  <Link className="story-landing-text-link" to="/story-simulation">小说创作</Link>
                </div>
              </section>
            )
          )}

          {sessionsQuery.isError && sessionsQuery.data !== undefined && (
            <InlineQueryError
              message="刷新对话失败，当前显示上一次读取的记录"
              retrying={sessionsQuery.isFetching}
              onRetry={() => { void sessionsQuery.refetch(); }}
            />
          )}
        </div>
      </div>
    </main>
  );
}
