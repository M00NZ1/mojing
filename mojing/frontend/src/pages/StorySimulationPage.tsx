import type { FormEvent } from 'react';
import './StorySimulationPage.css';
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
import { clearSubmittedStoryDraft, initialStoryValues as initialValues, newStoryRequestId, readStoryDraft, saveStoryDraft, type StoryDraftStatus } from '../utils/storyDraftStorage';

export default function StorySimulationPage() {
  const navigate = useNavigate();
  const [loadedDraft] = useState(readStoryDraft);
  const [values, setValues] = useState(loadedDraft.values);
  const [draftStatus, setDraftStatus] = useState<StoryDraftStatus | 'saving'>(loadedDraft.status);
  const [preparing, setPreparing] = useState(false);
  const valuesRef = useRef(values);
  valuesRef.current = values;
  const ownedRawRef = useRef(loadedDraft.raw);
  const draftStatusRef = useRef<StoryDraftStatus>(loadedDraft.status);
  const saveQueueRef = useRef<Promise<unknown>>(Promise.resolve());
  const lastQueuedValuesRef = useRef<string | null>(null);
  const saveSequenceRef = useRef(0);
  const mountedRef = useRef(true);
  const completedRef = useRef(false);
  const submissionLockedRef = useRef(false);

  const queueDraftSave = useCallback((next: StorySimulationFormValues, replacement?: { expectedRaw: string | null | undefined }) => {
    const sequence = ++saveSequenceRef.current;
    const task = saveQueueRef.current.then(async () => {
      if (completedRef.current) return undefined;
      if (!replacement && ['conflict', 'unreadable'].includes(draftStatusRef.current)) {
        if (mountedRef.current && sequence === saveSequenceRef.current) setDraftStatus(draftStatusRef.current);
        return undefined;
      }
      if (mountedRef.current) setDraftStatus('saving');
      const result = await saveStoryDraft(next, replacement ? replacement.expectedRaw : ownedRawRef.current);
      draftStatusRef.current = result.status;
      if (result.status === 'saved') ownedRawRef.current = result.raw;
      if (mountedRef.current && sequence === saveSequenceRef.current) setDraftStatus(result.status);
      return result.status === 'saved' && typeof result.raw === 'string' ? result.raw : undefined;
    });
    saveQueueRef.current = task;
    return task;
  }, []);
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
    const serialized = JSON.stringify(values);
    if (lastQueuedValuesRef.current === serialized || completedRef.current) return;
    lastQueuedValuesRef.current = serialized;
    void queueDraftSave(values);
  }, [values, queueDraftSave]);

  useEffect(() => {
    if (!charactersQuery.data || values.request_id) return;
    const availableIds = new Set(charactersQuery.data.map((item) => item.id));
    setValues((current) => {
      const characterIds = current.character_ids.filter((id) => availableIds.has(id));
      return characterIds.length === current.character_ids.length ? current : { ...current, character_ids: characterIds, request_id: undefined };
    });
  }, [charactersQuery.data, values.request_id]);

  useEffect(() => {
    if (!templatesQuery.data || !values.template_id || values.request_id) return;
    if (!templatesQuery.data.some((item) => item.template_id === values.template_id)) {
      setValues((current) => ({ ...current, template_id: '', request_id: undefined }));
    }
  }, [templatesQuery.data, values.template_id, values.request_id]);

  useEffect(() => {
    if (!encyclopediasQuery.data || !values.encyclopedia_id || values.request_id) return;
    if (!encyclopediasQuery.data.some((item) => String(item.id) === values.encyclopedia_id)) {
      setValues((current) => ({ ...current, encyclopedia_id: '', request_id: undefined }));
    }
  }, [encyclopediasQuery.data, values.encyclopedia_id, values.request_id]);

  const payload = (values: StorySimulationFormValues): StoryWritingPayload => ({
    request_id: values.request_id,
    premise: values.premise.trim(),
    direction: values.direction.trim(),
    tone: values.tone.trim(),
    chapter_count: values.chapter_count,
    ...(values.template_id ? { template_id: values.template_id } : {}),
    ...(values.encyclopedia_id ? { encyclopedia_id: Number(values.encyclopedia_id) } : {}),
    character_ids: values.character_ids,
  });

  useEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; requestControllerRef.current?.abort(); };
  }, []);

  const createMutation = useMutation({
    mutationFn: ({ requestPayload }: { requestPayload: StoryWritingPayload; submittedRaw?: string }) => {
      const controller = new AbortController();
      requestControllerRef.current = controller;
      return api.createStorySession(requestPayload, controller.signal);
    },
    onMutate: ({ requestPayload }) => {
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
    onSuccess: async (result, request) => {
      completedRef.current = true;
      await saveQueueRef.current;
      await clearSubmittedStoryDraft(request.submittedRaw);
      if (mountedRef.current) setCompletedSessionId(result.session_id);
    },
    onSettled: () => {
      submissionLockedRef.current = false;
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
    if (submissionLockedRef.current || createMutation.isPending) return;
    submissionLockedRef.current = true;
    setPreparing(true);
    const submittedValues = { ...valuesRef.current, request_id: valuesRef.current.request_id ?? newStoryRequestId() };
    valuesRef.current = submittedValues;
    lastQueuedValuesRef.current = JSON.stringify(submittedValues);
    setValues(submittedValues);
    void queueDraftSave(submittedValues).then((submittedRaw) => {
      if (!mountedRef.current) { submissionLockedRef.current = false; return; }
      if (submittedRaw === undefined) {
        submissionLockedRef.current = false;
        setNotice('草稿未能保存，请先重试保存或处理草稿冲突，再继续创作。');
        return;
      }
      createMutation.mutate({ requestPayload: payload(submittedValues), submittedRaw });
    }).finally(() => { if (mountedRef.current) setPreparing(false); });
  };

  const replaceDraft = async () => {
    const observed = readStoryDraft();
    if (!await confirmModal('保存当前输入', '将用本页内容替换已保存的创作草稿，是否继续？', 'warning')) return;
    if (mountedRef.current) await queueDraftSave(valuesRef.current, { expectedRaw: observed.raw });
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
      {['unavailable', 'conflict', 'unreadable'].includes(draftStatus) && (
        <section className="page-card" role="status" aria-label="草稿保存状态">
          <p>{draftStatus === 'unavailable' ? '草稿尚未保存，本页输入仍然保留。' : draftStatus === 'conflict' ? '其他页面已更新草稿，本页输入尚未覆盖已保存内容。' : '已保存的草稿暂时无法读取，本页输入仍然保留。'}</p>
          <div className="form-actions">
            {draftStatus === 'unavailable'
              ? <button type="button" className="btn btn-secondary" disabled={preparing || createMutation.isPending} onClick={() => { void queueDraftSave(valuesRef.current); }}>重试保存</button>
              : <button type="button" className="btn btn-secondary" disabled={preparing || createMutation.isPending} onClick={() => { void replaceDraft(); }}>保存当前输入</button>}
          </div>
        </section>
      )}
      <StorySimulationForm
        values={values}
        onChange={(patch) => { setValues((current) => ({ ...current, ...patch, request_id: undefined })); setError(''); setNotice(''); }}
        onSubmit={handleSubmit}
        loading={createMutation.isPending || preparing}
        draftStatusText={draftStatus === 'saved' ? '草稿已保存' : draftStatus === 'saving' ? '正在保存草稿…' : '草稿尚未保存'}
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
