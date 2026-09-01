import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
import InlineQueryError from '../components/InlineQueryError';
import UiIcon from '../components/UiIcon';

export default function ChatLandingPage() {
  const sessionsQuery = useQuery({ queryKey: ['sessions'], queryFn: () => api.listSessions() });
  const charactersQuery = useQuery({ queryKey: ['characters'], queryFn: api.listCharacters });
  const hasSessions = (sessionsQuery.data?.length ?? 0) > 0;

  function openSessionList() {
    window.dispatchEvent(new CustomEvent('open-mobile-sessions'));
  }

  function openCreateSession() {
    window.dispatchEvent(new CustomEvent('open-mobile-sessions'));
    window.dispatchEvent(new CustomEvent('create-session'));
  }

  return (
    <main className="main-content">
      <div className="page-scroll chat-landing">
        <div className="welcome-card" aria-busy={sessionsQuery.isPending}>
          {sessionsQuery.isPending ? (
            <>
              <div className="welcome-icon" aria-hidden="true"><UiIcon name="loading" className="ui-icon-loading" /></div>
              <h1 className="welcome-title">正在读取你的对话</h1>
              <p className="welcome-desc">正在连接本机服务，请稍候。</p>
            </>
          ) : sessionsQuery.isError && sessionsQuery.data === undefined ? (
            <>
              <div className="welcome-icon welcome-icon-error" aria-hidden="true"><UiIcon name="close" /></div>
              <h1 className="welcome-title">暂时无法读取对话</h1>
              <p className="welcome-desc">本机服务可能尚未启动，已有记录不会因此被当成空白。</p>
              <InlineQueryError
                message="会话列表加载失败，请确认本机服务已启动后重试"
                retrying={sessionsQuery.isFetching}
                onRetry={() => { void sessionsQuery.refetch(); }}
              />
              <div className="welcome-actions">
                <Link className="btn btn-ghost btn-lg" to="/settings?tab=api">配置模型服务</Link>
              </div>
            </>
          ) : (
            <>
              <div className="welcome-icon" aria-hidden="true"><UiIcon name="chat" /></div>
              <h1 className="welcome-title">{hasSessions ? '继续你的故事' : '开始你的第一个故事'}</h1>
              <p className="welcome-desc">
                {hasSessions ? '打开已有对话继续剧情，或从这里开始一段新故事。' : '准备角色后开始对话，或打开小说创作直接续写故事。'}
              </p>
              <div className="welcome-actions">
                {hasSessions && (
                  <button type="button" className="btn btn-primary btn-lg mobile-only" onClick={openSessionList}>
                    查看已有对话
                  </button>
                )}
                <button
                  type="button"
                  className={`btn btn-lg ${hasSessions ? 'btn-ghost' : 'btn-primary'}`}
                  onClick={openCreateSession}
                >
                  <UiIcon name="plus" />
                  新建对话
                </button>
                <Link className="btn btn-ghost btn-lg" to="/story-simulation">小说创作</Link>
                {!hasSessions && <Link className="btn btn-ghost btn-lg" to="/characters">管理角色</Link>}
              </div>
              {!charactersQuery.isLoading && !charactersQuery.isError && (
                <p className="welcome-chars">本机已有 {charactersQuery.data?.length ?? 0} 个角色</p>
              )}
            </>
          )}
          {sessionsQuery.isError && sessionsQuery.data !== undefined && (
            <InlineQueryError
              message="刷新会话列表失败，当前仍显示上一次读取结果"
              retrying={sessionsQuery.isFetching}
              onRetry={() => { void sessionsQuery.refetch(); }}
            />
          )}
        </div>
      </div>
    </main>
  );
}
