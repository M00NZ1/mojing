import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useBeforeUnload, useLocation, useNavigate } from 'react-router-dom';
import { api, GeneratedStoryNotSavedError } from '../api/client';
import type { StoryWritingPayload } from '../types';
import { clearSubmittedStoryDraft } from '../utils/storyDraftStorage';
import { clearUnsavedGeneratedStory, readUnsavedGeneratedStory, saveUnsavedGeneratedStory } from '../utils/generatedStoryStorage';
import { isAbortError } from '../utils/userFacingError';
import './StoryGenerationContext.css';

type GenerationPhase = 'idle' | 'running' | 'success' | 'error' | 'stopped';

type StoryGenerationState = {
  phase: GenerationPhase;
  requestId: string | null;
  chapterCount: number;
  sessionId: number | null;
  submittedRaw?: string;
  error: string;
  stopping: boolean;
  unsavedText?: string;
  unsavedLocal?: boolean;
};

const idleState: StoryGenerationState = {
  phase: 'idle', requestId: null, chapterCount: 0, sessionId: null, error: '', stopping: false,
};

type StoryGenerationContextValue = {
  generation: StoryGenerationState;
  start: (payload: StoryWritingPayload, submittedRaw: string) => boolean;
  stop: () => void;
  dismiss: () => void;
  discardUnsaved: () => void;
  consumeCompleted: (requestId: string) => Promise<number | null>;
};

const StoryGenerationContext = createContext<StoryGenerationContextValue | null>(null);

export function StoryGenerationProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [generation, setGeneration] = useState<StoryGenerationState>(() => {
    const recovered = readUnsavedGeneratedStory();
    return recovered ? {
      ...idleState, phase: 'error', requestId: recovered.requestId,
      error: '正文已生成，但数据库未保存。请先复制全文。',
      unsavedText: recovered.text, unsavedLocal: true,
    } : idleState;
  });
  const generationRef = useRef(generation);
  const controllerRef = useRef<AbortController | null>(null);
  const consumingRef = useRef(false);

  const updateGeneration = useCallback((next: StoryGenerationState) => {
    generationRef.current = next;
    setGeneration(next);
  }, []);

  const start = useCallback((payload: StoryWritingPayload, submittedRaw: string) => {
    if (controllerRef.current || !payload.request_id || consumingRef.current || generationRef.current.unsavedText) return false;
    const controller = new AbortController();
    controllerRef.current = controller;
    const running: StoryGenerationState = {
      phase: 'running', requestId: payload.request_id, chapterCount: payload.chapter_count,
      sessionId: null, submittedRaw, error: '', stopping: false,
    };
    updateGeneration(running);
    void (async () => {
      try {
        const result = await api.createStorySession(payload, controller.signal);
        if (controllerRef.current !== controller) return;
        updateGeneration({ ...running, phase: 'success', sessionId: result.session_id });
        void queryClient.invalidateQueries({ queryKey: ['sessions'] });
      } catch (reason) {
        if (controllerRef.current !== controller) return;
        if (reason instanceof GeneratedStoryNotSavedError && reason.requestId === payload.request_id) {
          const unsavedLocal = saveUnsavedGeneratedStory({ requestId: reason.requestId, text: reason.text });
          updateGeneration({ ...running, phase: 'error', error: reason.message,
            unsavedText: reason.text, unsavedLocal });
          return;
        }
        updateGeneration(isAbortError(reason)
          ? { ...running, phase: 'stopped', stopping: false }
          : { ...running, phase: 'error', error: reason instanceof Error ? reason.message : '小说生成失败，请重试' });
      } finally {
        if (controllerRef.current === controller) controllerRef.current = null;
      }
    })();
    return true;
  }, [queryClient, updateGeneration]);

  const stop = useCallback(() => {
    if (!controllerRef.current || generationRef.current.stopping) return;
    updateGeneration({ ...generationRef.current, stopping: true });
    controllerRef.current.abort();
  }, [updateGeneration]);

  const dismiss = useCallback(() => {
    if (controllerRef.current || generationRef.current.phase === 'running' || generationRef.current.unsavedText) return;
    updateGeneration(idleState);
  }, [updateGeneration]);

  const discardUnsaved = useCallback(() => {
    const current = generationRef.current;
    if (controllerRef.current || !current.unsavedText || !current.requestId) return;
    clearUnsavedGeneratedStory(current.requestId);
    updateGeneration(idleState);
  }, [updateGeneration]);

  const consumeCompleted = useCallback(async (requestId: string) => {
    const current = generationRef.current;
    if (consumingRef.current || current.phase !== 'success' || current.requestId !== requestId || !current.sessionId) return null;
    consumingRef.current = true;
    try {
      await clearSubmittedStoryDraft(current.submittedRaw);
      if (generationRef.current === current) updateGeneration(idleState);
      return current.sessionId;
    } finally {
      consumingRef.current = false;
    }
  }, [updateGeneration]);

  useBeforeUnload(useCallback((event) => {
    if (generationRef.current.phase !== 'running' &&
      !(generationRef.current.unsavedText && !generationRef.current.unsavedLocal)) return;
    event.preventDefault();
    event.returnValue = '';
  }, []));

  useEffect(() => () => { controllerRef.current?.abort(); }, []);

  return <StoryGenerationContext.Provider value={{ generation, start, stop, dismiss, discardUnsaved, consumeCompleted }}>{children}</StoryGenerationContext.Provider>;
}

export function useStoryGeneration(): StoryGenerationContextValue {
  const context = useContext(StoryGenerationContext);
  if (!context) throw new Error('StoryGenerationProvider is missing');
  return context;
}

export function StoryGenerationStatus() {
  const { generation, stop, dismiss, consumeCompleted } = useStoryGeneration();
  const location = useLocation();
  const navigate = useNavigate();
  if (generation.phase === 'idle') return null;
  const openCompleted = async () => {
    if (!generation.requestId) return;
    const sessionId = await consumeCompleted(generation.requestId);
    if (sessionId) navigate(`/chat/${sessionId}`);
  };
  const title = generation.phase === 'running'
    ? (generation.stopping ? '正在停止写作' : `正在写作 · ${generation.chapterCount} 章`)
    : generation.phase === 'success' ? '小说已生成'
      : generation.phase === 'stopped' ? '本次生成已停止' : generation.unsavedText ? '正文尚未保存' : '小说生成失败';
  return (
    <aside className={`story-generation-status is-${generation.phase}`} role={generation.phase === 'error' ? 'alert' : 'status'} aria-live={generation.phase === 'error' ? 'assertive' : 'polite'} aria-label="小说创作状态">
      <span className="story-generation-status-mark" aria-hidden="true" />
      <div className="story-generation-status-copy">
        <strong>{title}</strong>
        <span>{generation.phase === 'error' ? generation.error : generation.phase === 'success' ? '正文与会话已保存' : generation.phase === 'running' ? '切换页面后会继续写作' : '草稿仍然保留'}</span>
      </div>
      <div className="story-generation-status-actions">
        {generation.phase === 'success'
          ? <button type="button" className="btn btn-primary" onClick={() => { void openCompleted(); }}>打开会话</button>
          : location.pathname !== '/story-simulation' && <Link className="btn btn-secondary" to="/story-simulation">返回创作</Link>}
        {generation.phase === 'running'
          ? <button type="button" className="btn btn-ghost" disabled={generation.stopping} onClick={stop}>{generation.stopping ? '正在停止…' : '停止生成'}</button>
          : !generation.unsavedText && <button type="button" className="story-generation-status-dismiss" aria-label="收起创作状态" onClick={dismiss}>×</button>}
      </div>
    </aside>
  );
}
