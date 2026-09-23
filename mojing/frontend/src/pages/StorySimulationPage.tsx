import type { FormEvent } from 'react';
import './StorySimulationPage.css';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { confirmModal } from '../components/ConfirmModal';
import CreationHomeLink from '../components/CreationHomeLink';
import InlineQueryError from '../components/InlineQueryError';
import WorldResultText from '../components/WorldResultText';
import StorySimulationForm, { type StorySimulationFormValues } from '../components/StorySimulationForm';
import type { StoryWritingPayload } from '../types';
import { useStoryGeneration } from '../contexts/StoryGenerationContext';
import { newStoryRequestId, readStoryDraft, saveStoryDraft, type StoryDraftStatus } from '../utils/storyDraftStorage';

export default function StorySimulationPage() {
  const navigate = useNavigate();
  const { generation, start, dismiss, discardUnsaved, retryUnsaved, consumeCompleted } = useStoryGeneration();
  const [loadedDraft] = useState(readStoryDraft);
  const [values, setValues] = useState(loadedDraft.values);
  const [draftStatus, setDraftStatus] = useState<StoryDraftStatus | 'saving'>(loadedDraft.status);
  const [preparing, setPreparing] = useState(false);
  const valuesRef = useRef(values);
  valuesRef.current = values;
  const ownedRawRef = useRef(loadedDraft.raw);
  const draftStatusRef = useRef<StoryDraftStatus>(loadedDraft.status);
  const saveQueueRef = useRef<Promise<unknown>>(Promise.resolve());
  const lastQueuedValuesRef = useRef<string | null>(
    (generation.phase === 'running' || generation.phase === 'success' || Boolean(generation.unsavedText))
      && generation.requestId === loadedDraft.values.request_id
      && loadedDraft.status === 'saved'
      ? JSON.stringify(loadedDraft.values) : null,
  );
  const saveSequenceRef = useRef(0);
  const mountedRef = useRef(true);
  const completedRef = useRef(generation.phase === 'success' && generation.requestId === loadedDraft.values.request_id);
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
  const generating = generation.phase === 'running';
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
    return () => { mountedRef.current = false; };
  }, []);

  useEffect(() => {
    if (!generating) submissionLockedRef.current = false;
  }, [generating]);

  const requestStateQuery = useQuery({
    queryKey: ['story-request', values.request_id],
    queryFn: ({ signal }) => api.getStoryRequestState(values.request_id!, signal),
    enabled: Boolean(values.request_id) && !preparing && !generating && generation.phase !== 'success',
    retry: false,
  });
  const recovered = requestStateQuery.data;
  const discardMutation = useMutation({
    mutationFn: api.discardStoryDraft,
    onSuccess: (_, requestId) => {
      setValues((current) => current.request_id === requestId ? { ...current, request_id: undefined } : current);
      setError('');
      setNotice('已放弃生成正文，原输入仍然保留。');
    },
    onError: (reason) => setError(reason instanceof Error ? reason.message : '放弃正文失败，请重试'),
  });
  const discardRecoveredDraft = async () => {
    const requestId = valuesRef.current.request_id;
    if (!requestId || discardMutation.isPending || generating) return;
    if (await confirmModal('放弃生成正文', '这次生成的正文将被删除，原输入会保留。是否继续？', 'warning')) {
      if (mountedRef.current && valuesRef.current.request_id === requestId) discardMutation.mutate(requestId);
    }
  };
  const discardUnsavedResult = async () => {
    if (await confirmModal('清除待保存正文', '请先确认已复制完整正文。清除后将无法从墨境恢复这次生成结果，是否继续？', 'warning')) {
      discardUnsaved();
      setNotice('待保存正文已清除，可以重新创作。');
    }
  };

  useEffect(() => {
    if (generation.phase !== 'success' || generation.requestId !== valuesRef.current.request_id || !generation.sessionId) return;
    let active = true;
    completedRef.current = true;
    void saveQueueRef.current.then(async () => {
      if (!active || !generation.requestId) return;
      const sessionId = await consumeCompleted(generation.requestId);
      if (active && sessionId) navigate(`/chat/${sessionId}`);
    });
    return () => { active = false; };
  }, [consumeCompleted, generation.phase, generation.requestId, generation.sessionId, navigate]);

  const handleSubmit = (event?: FormEvent<HTMLFormElement>) => {
    event?.preventDefault();
    if (submissionLockedRef.current || generating || discardMutation.isPending) return;
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
      setError('');
      setNotice('');
      if (!start(payload(submittedValues), submittedRaw)) {
        submissionLockedRef.current = false;
        setNotice('已有一项创作仍在进行，请先查看其状态。');
      }
    }).finally(() => { if (mountedRef.current) setPreparing(false); });
  };

  const replaceDraft = async () => {
    const observed = readStoryDraft();
    if (!await confirmModal('保存当前输入', '将用本页内容替换已保存的创作草稿，是否继续？', 'warning')) return;
    if (mountedRef.current) await queueDraftSave(valuesRef.current, { expectedRaw: observed.raw });
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
      {values.request_id && !generating && requestStateQuery.isFetching && <p role="status">正在读取本次创作…</p>}
      {values.request_id && !generating && requestStateQuery.isError && <InlineQueryError
        message="本次创作结果读取失败" error={requestStateQuery.error} retrying={requestStateQuery.isFetching}
        onRetry={() => { void requestStateQuery.refetch(); }} />}
      {!generation.unsavedText && recovered?.status === 'draft' && <section className="page-card" aria-label="待保存的小说正文">
        <div className="card-header"><h2>{recovered.title || '小说正文已就绪'}</h2><span>{recovered.chapter_count} 章</span></div>
        <p>正文已保留，继续保存会直接创建会话。</p>
        <WorldResultText key={recovered.request_id} text={recovered.text || ''} label="小说正文" />
        <div className="world-result-text-actions">
          <button type="button" className="btn btn-primary" disabled={preparing || generating || discardMutation.isPending} onClick={() => { handleSubmit(); }}>继续保存并打开</button>
          <button type="button" className="btn btn-ghost" disabled={preparing || generating || discardMutation.isPending} onClick={() => { void discardRecoveredDraft(); }}>放弃正文</button>
        </div>
      </section>}
      {!generation.unsavedText && recovered?.status === 'saved' && recovered.session_id && <section className="page-card" aria-label="已保存的创作会话">
        <h2>{recovered.title || '本次创作已保存'}</h2>
        <button type="button" className="btn btn-primary" disabled={preparing || generating} onClick={() => { handleSubmit(); }}>打开已保存会话</button>
      </section>}
      {generation.unsavedText && <section className="page-card" aria-label="未保存的小说正文">
        <div className="card-header"><h2>正文已生成，但尚未写入会话</h2></div>
        <p>{generation.unsavedLocal
          ? generation.unsavedRecovery
            ? '正文已暂存在此浏览器。数据库恢复后可直接继续保存，不会再次生成；也可以先复制全文。'
            : '正文已暂存在此浏览器。请先复制全文。'
          : '浏览器暂存也未成功，正文目前只在此页面。请立即复制全文，关闭页面会丢失。'}</p>
        <WorldResultText text={generation.unsavedText} label="未保存的小说正文" />
        <div className="world-result-text-actions">
          {generation.unsavedRecovery && <button type="button" className="btn btn-primary"
            disabled={generation.savingUnsaved}
            onClick={() => { void retryUnsaved().then((sessionId) => { if (sessionId) navigate(`/chat/${sessionId}`); }); }}>
            {generation.savingUnsaved ? '正在保存…' : '继续保存这篇正文'}
          </button>}
          <button type="button" className="btn btn-ghost" disabled={generation.savingUnsaved}
            onClick={() => { void discardUnsavedResult(); }}>已复制，清除这份正文</button>
        </div>
      </section>}
      {['unavailable', 'conflict', 'unreadable'].includes(draftStatus) && (
        <section className="page-card" role="status" aria-label="草稿保存状态">
          <p>{draftStatus === 'unavailable' ? '草稿尚未保存，本页输入仍然保留。' : draftStatus === 'conflict' ? '其他页面已更新草稿，本页输入尚未覆盖已保存内容。' : '已保存的草稿暂时无法读取，本页输入仍然保留。'}</p>
          <div className="form-actions">
            {draftStatus === 'unavailable'
              ? <button type="button" className="btn btn-secondary" disabled={preparing || generating} onClick={() => { void queueDraftSave(valuesRef.current); }}>重试保存</button>
              : <button type="button" className="btn btn-secondary" disabled={preparing || generating} onClick={() => { void replaceDraft(); }}>保存当前输入</button>}
          </div>
        </section>
      )}
      {!generation.unsavedText && (!values.request_id || recovered?.status === 'missing' || recovered?.status === 'saved') && <StorySimulationForm
        values={values}
        onChange={(patch) => { setValues((current) => ({ ...current, ...patch, request_id: undefined })); setError(''); setNotice(''); if (generation.phase === 'error' || generation.phase === 'stopped') dismiss(); }}
        onSubmit={handleSubmit}
        loading={generating || preparing}
        draftStatusText={draftStatus === 'saved' ? '草稿已保存' : draftStatus === 'saving' ? '正在保存草稿…' : '草稿尚未保存'}
        charactersQuery={charactersQuery}
        templatesQuery={templatesQuery}
        encyclopediasQuery={encyclopediasQuery}
      />}
      {generating && generation.requestId === values.request_id && (
        <section className="story-simulation-working" aria-label="当前小说写作">
          <span className="story-simulation-working-kicker">小说创作 · {generation.chapterCount} 章</span>
          <h2>故事正在展开</h2>
          <p>正在按本次提交的设定写作。你可以浏览其他页面，完成后从顶部状态栏打开新会话。</p>
          {values.premise.trim() && <blockquote>{values.premise.trim()}</blockquote>}
        </section>
      )}
      {error && <div className="story-simulation-error" role="alert">{error}</div>}
      {notice && <div className="story-simulation-notice" role="status">{notice}</div>}
    </main>
  );
}
