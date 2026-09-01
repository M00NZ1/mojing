import type { FormEvent } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useBeforeUnload, useBlocker, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { confirmModal } from '../components/ConfirmModal';
import CreationHomeLink from '../components/CreationHomeLink';
import StorySimulationForm, { type StorySimulationFormValues } from '../components/StorySimulationForm';
import type { StoryWritingPayload } from '../types';
import { shouldBlockStoryGenerationNavigation } from '../utils/storyGenerationNavigation';
import { isAbortError } from '../utils/userFacingError';

const initialValues: StorySimulationFormValues = {
  premise: '',
  direction: '',
  tone: '有画面感、人物动机清楚、适合连续长篇创作',
  chapter_count: 2,
  template_id: '',
  encyclopedia_id: '',
  character_ids: [],
};
const STORY_DRAFT_STORAGE_KEY = 'mojing:story-simulation-draft:v1';

function loadStoryDraft(): StorySimulationFormValues {
  if (typeof window === 'undefined') return initialValues;
  try {
    const saved = JSON.parse(window.localStorage.getItem(STORY_DRAFT_STORAGE_KEY) ?? '{}') as Partial<StorySimulationFormValues>;
    const chapterCount = Number(saved.chapter_count);
    return {
      premise: typeof saved.premise === 'string' ? saved.premise : initialValues.premise,
      direction: typeof saved.direction === 'string' ? saved.direction : initialValues.direction,
      tone: typeof saved.tone === 'string' ? saved.tone : initialValues.tone,
      chapter_count: [1, 2, 3].includes(chapterCount) ? chapterCount : initialValues.chapter_count,
      template_id: typeof saved.template_id === 'string' ? saved.template_id : initialValues.template_id,
      encyclopedia_id: typeof saved.encyclopedia_id === 'string' ? saved.encyclopedia_id : initialValues.encyclopedia_id,
      character_ids: Array.isArray(saved.character_ids)
        ? saved.character_ids.filter((id): id is number => Number.isInteger(id) && id > 0)
        : initialValues.character_ids,
    };
  } catch {
    return initialValues;
  }
}

export default function StorySimulationPage() {
  const navigate = useNavigate();
  const [values, setValues] = useState(loadStoryDraft);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [stopping, setStopping] = useState(false);
  const [submittedChapterCount, setSubmittedChapterCount] = useState(initialValues.chapter_count);
  const requestControllerRef = useRef<AbortController | null>(null);
  const navigationPromptControllerRef = useRef<AbortController | null>(null);
  const allowNavigationRef = useRef(false);
  const [completedSessionId, setCompletedSessionId] = useState<number | null>(null);
  const charactersQuery = useQuery({ queryKey: ['characters'], queryFn: api.listCharacters });
  const templatesQuery = useQuery({ queryKey: ['world-templates'], queryFn: () => api.listWorldTemplates() });
  const encyclopediasQuery = useQuery({ queryKey: ['encyclopedias'], queryFn: api.listEncyclopedias });

  useEffect(() => {
    try {
      window.localStorage.setItem(STORY_DRAFT_STORAGE_KEY, JSON.stringify(values));
    } catch {
      // 浏览器禁用本机存储时仍允许继续创作，本轮只失去刷新恢复能力。
    }
  }, [values]);

  useEffect(() => {
    if (!charactersQuery.data) return;
    const availableIds = new Set(charactersQuery.data.map((item) => item.id));
    setValues((current) => {
      const characterIds = current.character_ids.filter((id) => availableIds.has(id));
      return characterIds.length === current.character_ids.length ? current : { ...current, character_ids: characterIds };
    });
  }, [charactersQuery.data]);

  useEffect(() => {
    if (!templatesQuery.data || !values.template_id) return;
    if (!templatesQuery.data.some((item) => item.template_id === values.template_id)) {
      setValues((current) => ({ ...current, template_id: '' }));
    }
  }, [templatesQuery.data, values.template_id]);

  useEffect(() => {
    if (!encyclopediasQuery.data || !values.encyclopedia_id) return;
    if (!encyclopediasQuery.data.some((item) => String(item.id) === values.encyclopedia_id)) {
      setValues((current) => ({ ...current, encyclopedia_id: '' }));
    }
  }, [encyclopediasQuery.data, values.encyclopedia_id]);

  const payload = (): StoryWritingPayload => ({
    premise: values.premise.trim(),
    direction: values.direction.trim(),
    tone: values.tone.trim(),
    chapter_count: values.chapter_count,
    ...(values.template_id ? { template_id: values.template_id } : {}),
    ...(values.encyclopedia_id ? { encyclopedia_id: Number(values.encyclopedia_id) } : {}),
    character_ids: values.character_ids,
  });

  useEffect(() => () => requestControllerRef.current?.abort(), []);

  const createMutation = useMutation({
    mutationFn: (requestPayload: StoryWritingPayload) => {
      const controller = new AbortController();
      requestControllerRef.current = controller;
      return api.createStorySession(requestPayload, controller.signal);
    },
    onMutate: (requestPayload) => {
      allowNavigationRef.current = false;
      setCompletedSessionId(null);
      setError('');
      setNotice('');
      setStopping(false);
      setSubmittedChapterCount(requestPayload.chapter_count);
    },
    onError: (reason) => {
      if (isAbortError(reason)) {
        setNotice('已停止本次生成，草稿仍然保留，可以修改后重新开始。');
        return;
      }
      setError(reason instanceof Error ? reason.message : '小说开篇生成失败，请重试');
    },
    onSuccess: (result) => {
      try {
        window.localStorage.removeItem(STORY_DRAFT_STORAGE_KEY);
      } catch {
        // 存储不可用不影响已创建会话。
      }
      setCompletedSessionId(result.session_id);
    },
    onSettled: () => {
      requestControllerRef.current = null;
      setStopping(false);
    },
  });

  const generationNavigationBlocker = useBlocker(({ currentLocation, nextLocation }) => (
    shouldBlockStoryGenerationNavigation(
      createMutation.isPending,
      allowNavigationRef.current,
      currentLocation,
      nextLocation,
    )
  ));

  useEffect(() => {
    if (generationNavigationBlocker.state !== 'blocked' || !createMutation.isPending) return;
    const promptController = new AbortController();
    navigationPromptControllerRef.current?.abort();
    navigationPromptControllerRef.current = promptController;
    let active = true;
    void confirmModal(
      '小说仍在生成',
      '离开页面会停止本次生成。草稿会继续保留；要停止并离开吗？',
      'warning',
      {
        signal: promptController.signal,
        confirmLabel: '停止并离开',
        cancelLabel: '继续生成',
      },
    ).then((leave) => {
      if (!active || promptController.signal.aborted || generationNavigationBlocker.state !== 'blocked') return;
      navigationPromptControllerRef.current = null;
      if (leave) {
        allowNavigationRef.current = true;
        requestControllerRef.current?.abort();
        generationNavigationBlocker.proceed();
      } else {
        generationNavigationBlocker.reset();
      }
    });
    return () => { active = false; };
  }, [createMutation.isPending, generationNavigationBlocker]);

  useEffect(() => {
    if (createMutation.isPending || generationNavigationBlocker.state !== 'blocked') return;
    navigationPromptControllerRef.current?.abort();
    navigationPromptControllerRef.current = null;
    generationNavigationBlocker.reset();
  }, [createMutation.isPending, generationNavigationBlocker]);

  useEffect(() => {
    if (completedSessionId === null) return;
    navigationPromptControllerRef.current?.abort();
    navigationPromptControllerRef.current = null;
    if (generationNavigationBlocker.state === 'blocked') {
      generationNavigationBlocker.reset();
      return;
    }
    allowNavigationRef.current = true;
    navigate(`/chat/${completedSessionId}`);
  }, [completedSessionId, generationNavigationBlocker, navigate]);

  useBeforeUnload(useCallback((event) => {
    if (!createMutation.isPending || allowNavigationRef.current) return;
    event.preventDefault();
    event.returnValue = '';
  }, [createMutation.isPending]));

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!createMutation.isPending) createMutation.mutate(payload());
  };

  const stopGeneration = () => {
    if (!requestControllerRef.current || stopping) return;
    setStopping(true);
    requestControllerRef.current.abort();
  };

  return (
    <main className="page story-simulation-page">
      <header className="story-simulation-header">
        <div>
          <h1>小说创作</h1>
          <p>输入大致背景和走向，直接生成连续故事；添加已有角色后，正文会沿用人物设定。</p>
        </div>
        <CreationHomeLink />
      </header>
      <StorySimulationForm
        values={values}
        onChange={(patch) => { setValues((current) => ({ ...current, ...patch })); setError(''); setNotice(''); }}
        onSubmit={handleSubmit}
        loading={createMutation.isPending}
        charactersQuery={charactersQuery}
        templatesQuery={templatesQuery}
        encyclopediasQuery={encyclopediasQuery}
      />
      {error && <div className="story-simulation-error" role="alert">{error}</div>}
      {notice && <div className="story-simulation-notice" role="status">{notice}</div>}
      {createMutation.isPending && (
        <section className="page-card story-simulation-results" aria-live="polite">
          <div className="card-header story-simulation-progress-header">
            <h2>{stopping ? '正在停止' : '正在写作'}</h2>
            <button type="button" className="btn btn-ghost" onClick={stopGeneration} disabled={stopping}>{stopping ? '正在停止…' : '停止生成'}</button>
          </div>
          <p className="story-simulation-muted">正在整理人物设定并连续生成 {submittedChapterCount} 章，完成后会自动进入创作会话。</p>
          <p className="story-simulation-muted">本次请求采用提交时的内容；写作完成前表单会保持锁定。</p>
        </section>
      )}
    </main>
  );
}
