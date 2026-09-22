import { lazy, Suspense, useCallback, useEffect, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom';

import { api } from './api/client';
import SessionSidebar from './components/SessionSidebar';
import { ConfirmModalProvider } from './components/ConfirmModal';
import GlobalLoadingBar from './components/LoadingBar';
import ChatLandingPage from './pages/ChatLandingPage';
import UiIcon from './components/UiIcon';
import { COMPACT_LAYOUT_QUERY, useMediaQuery } from './hooks/useMediaQuery';
import { StoryGenerationProvider, StoryGenerationStatus, useStoryGeneration } from './contexts/StoryGenerationContext';

const ChatPage = lazy(() => import('./pages/ChatPage'));
const CharactersPage = lazy(() => import('./pages/CharactersPage'));
const CreationHubPage = lazy(() => import('./pages/CreationHubPage'));
const EncyclopediaPage = lazy(() => import('./pages/EncyclopediaPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const StorySimulationPage = lazy(() => import('./pages/StorySimulationPage'));
const UsagePage = lazy(() => import('./pages/UsagePage'));
const WorkbenchPage = lazy(() => import('./pages/WorkbenchPage'));
const WorldLibraryPage = lazy(() => import('./pages/WorldLibraryPage'));

const primaryNavigation = [
  { to: '/chat', icon: 'chat', label: '对话', paths: ['/chat'] },
  { to: '/create', icon: 'sparkles', label: '创作', paths: ['/create', '/characters', '/worlds', '/encyclopedia', '/workbench', '/story-simulation'] },
  { to: '/settings', icon: 'settings', label: '设置', paths: ['/settings', '/usage'] },
] as const;

function PageLoadingState() {
  return (
    <main className="main-content" aria-busy="true">
      <div className="page-scroll chat-landing">
        <div className="welcome-card" role="status">
          <div className="welcome-icon"><UiIcon name="loading" className="ui-icon-loading" /></div>
          <h1 className="welcome-title">正在打开页面</h1>
          <p className="welcome-desc">正在加载本机界面…</p>
        </div>
      </div>
    </main>
  );
}

function ValidChatRoute() {
  const sessionId = Number(useParams().sessionId);
  const isValidSessionId = Number.isInteger(sessionId) && sessionId > 0;
  const sessionQuery = useQuery({
    queryKey: ['session', sessionId],
    queryFn: () => api.getSession(sessionId),
    enabled: isValidSessionId,
    retry: false,
  });

  if (!isValidSessionId) return <Navigate to="/chat" replace />;
  if (sessionQuery.isPending) {
    return (
      <main className="main-content" aria-busy="true">
        <div className="page-scroll chat-landing">
          <div className="welcome-card" role="status">
            <h1 className="welcome-title">正在打开对话</h1>
            <p className="welcome-desc">正在读取本机会话…</p>
          </div>
        </div>
      </main>
    );
  }
  if (sessionQuery.isError) {
    return (
      <main className="main-content">
        <div className="page-scroll chat-landing">
          <div className="welcome-card" role="alert">
            <h1 className="welcome-title">无法打开这个对话</h1>
            <p className="welcome-desc">这个对话可能已被删除，或本机服务暂时无法读取它。</p>
            <div className="welcome-actions">
              <Link className="btn btn-primary" to="/chat" replace>返回对话列表</Link>
              <button
                type="button"
                className="btn btn-ghost"
                disabled={sessionQuery.isFetching}
                onClick={() => { void sessionQuery.refetch(); }}
              >
                {sessionQuery.isFetching ? '重新加载中…' : '重新加载'}
              </button>
            </div>
          </div>
        </div>
      </main>
    );
  }
  return <ChatPage />;
}

function AppLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { generation } = useStoryGeneration();
  const isChat = location.pathname.startsWith('/chat');
  const showStoryGenerationStatus = generation.phase !== 'idle';
  const isChatLanding = location.pathname === '/chat' || location.pathname === '/';
  const isCompactLayout = useMediaQuery(COMPACT_LAYOUT_QUERY);
  const showMobileSessions = isCompactLayout
    && isChat
    && new URLSearchParams(location.search).get('sessions') === 'open';
  const isMobileSessionHistoryEntry = Boolean(
    (location.state as { mojingMobileSessions?: boolean } | null)?.mojingMobileSessions,
  );
  const mobileSessionOverlayRef = useRef<HTMLDivElement>(null);
  const mobileSessionReturnFocusRef = useRef<HTMLElement | null>(null);
  const mobileSessionClosingRef = useRef(false);

  const sessionOverlaySearch = useCallback((open: boolean) => {
    const search = new URLSearchParams(location.search);
    if (open) search.set('sessions', 'open');
    else search.delete('sessions');
    const value = search.toString();
    return value ? `?${value}` : '';
  }, [location.search]);

  const openMobileSessions = useCallback(() => {
    if (!isCompactLayout || !isChat || showMobileSessions) return;
    if (document.activeElement instanceof HTMLElement) {
      mobileSessionReturnFocusRef.current = document.activeElement;
    }
    const currentState = location.state && typeof location.state === 'object'
      ? location.state as Record<string, unknown>
      : {};
    navigate(
      { pathname: location.pathname, search: sessionOverlaySearch(true), hash: location.hash },
      { state: { ...currentState, mojingMobileSessions: true } },
    );
  }, [isChat, isCompactLayout, location.hash, location.pathname, location.state, navigate, sessionOverlaySearch, showMobileSessions]);

  const closeMobileSessions = useCallback(() => {
    if (!showMobileSessions || mobileSessionClosingRef.current) return;
    mobileSessionClosingRef.current = true;
    if (isMobileSessionHistoryEntry) {
      navigate(-1);
      return;
    }
    const nextState = location.state && typeof location.state === 'object'
      ? { ...location.state as Record<string, unknown> }
      : {};
    delete nextState.mojingMobileSessions;
    navigate(
      { pathname: location.pathname, search: sessionOverlaySearch(false), hash: location.hash },
      { replace: true, state: nextState },
    );
  }, [isMobileSessionHistoryEntry, location.hash, location.pathname, location.state, navigate, sessionOverlaySearch, showMobileSessions]);

  useEffect(() => {
    const toggle = () => { if (showMobileSessions) closeMobileSessions(); else openMobileSessions(); };
    window.addEventListener('toggle-mobile-sessions', toggle);
    window.addEventListener('open-mobile-sessions', openMobileSessions);
    window.addEventListener('close-mobile-sessions', closeMobileSessions);
    return () => {
      window.removeEventListener('toggle-mobile-sessions', toggle);
      window.removeEventListener('open-mobile-sessions', openMobileSessions);
      window.removeEventListener('close-mobile-sessions', closeMobileSessions);
    };
  }, [closeMobileSessions, openMobileSessions, showMobileSessions]);

  useEffect(() => {
    if (!showMobileSessions) mobileSessionClosingRef.current = false;
  }, [showMobileSessions]);

  useEffect(() => {
    if (!isCompactLayout || !isChat || !showMobileSessions) return undefined;

    const overlay = mobileSessionOverlayRef.current;
    if (!overlay) return undefined;

    const focusFrame = window.requestAnimationFrame(() => {
      if (!overlay.contains(document.activeElement)) {
        overlay.querySelector<HTMLElement>('[data-mobile-session-close]')?.focus();
      }
    });

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        closeMobileSessions();
        return;
      }
      if (event.key !== 'Tab') return;

      const focusable = Array.from(overlay.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), summary, [href], [tabindex]:not([tabindex="-1"])',
      )).filter((element) => !element.hasAttribute('hidden') && element.getClientRects().length > 0);
      if (focusable.length === 0) {
        event.preventDefault();
        overlay.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !overlay.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !overlay.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', handleKeyDown, true);
    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.removeEventListener('keydown', handleKeyDown, true);
      const returnTarget = mobileSessionReturnFocusRef.current;
      mobileSessionReturnFocusRef.current = null;
      if (returnTarget && document.contains(returnTarget)) {
        window.requestAnimationFrame(() => returnTarget.focus());
      }
    };
  }, [closeMobileSessions, isChat, isCompactLayout, showMobileSessions]);

  return (
    <div className={`app-shell ${isChat && !isChatLanding ? 'in-chat' : ''} ${showStoryGenerationStatus ? 'has-story-generation-status' : ''}`}>
      <GlobalLoadingBar />
      <nav className="sidebar" aria-label="主导航">
        <div className="sidebar-logo" aria-hidden="true">墨</div>
        {primaryNavigation.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={() => `nav-btn ${item.paths.some((path) => location.pathname.startsWith(path)) ? 'active' : ''}`}
            title={item.label}
          >
            <UiIcon name={item.icon} /><span className="nav-label">{item.label}</span>
          </NavLink>
        ))}
      </nav>

      <div className="content-area">
        <StoryGenerationStatus />
        <div className="content-view">
          <div
            ref={mobileSessionOverlayRef}
            className={`session-overlay ${isChat && showMobileSessions ? 'visible' : ''}`}
            role={isCompactLayout && showMobileSessions ? 'dialog' : undefined}
            aria-modal={isCompactLayout && showMobileSessions ? true : undefined}
            aria-label={isCompactLayout && showMobileSessions ? '会话列表' : undefined}
            tabIndex={isCompactLayout && showMobileSessions ? -1 : undefined}
          >
            {isChat && <SessionSidebar />}
          </div>
          <Suspense fallback={<PageLoadingState />}>
            <Routes>
              <Route path="/" element={<Navigate to="/chat" replace />} />
              <Route path="/chat" element={<ChatLandingPage />} />
              <Route path="/chat/:sessionId" element={<ValidChatRoute />} />
              <Route path="/create" element={<CreationHubPage />} />
              <Route path="/characters/*" element={<CharactersPage />} />
              <Route path="/worlds" element={<WorldLibraryPage />} />
              <Route path="/encyclopedia/*" element={<EncyclopediaPage />} />
              <Route path="/workbench/*" element={<WorkbenchPage />} />
              <Route path="/story-simulation" element={<StorySimulationPage />} />
              <Route path="/usage/platform" element={<UsagePage level="platform" />} />
              <Route path="/usage/model" element={<UsagePage level="model" />} />
              <Route path="/settings/*" element={<SettingsPage />} />
              <Route path="*" element={<Navigate to="/chat" replace />} />
            </Routes>
          </Suspense>
        </div>
      </div>
    </div>
  );
}

export default function App() {
  return <ConfirmModalProvider><StoryGenerationProvider><AppLayout /></StoryGenerationProvider></ConfirmModalProvider>;
}
