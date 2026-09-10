import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';

import { api } from '../api/client';
import InlineQueryError from './InlineQueryError';
import { useUndoDelete } from './UndoToast';
import { useToast } from '../hooks/useToast';
import UiIcon from './UiIcon';

const OPENING_OPTIONS = [
  { key: 'narrator_enabled', configKey: 'default_narrator_enabled', label: '旁白', description: '加入场景叙述与剧情推进', fallback: false },
  { key: 'choice_generation_enabled', configKey: 'default_choice_generation_enabled', label: '剧情选项', description: '在回复后提供可选行动', fallback: true },
  { key: 'anti_cheat_enabled', configKey: 'default_anti_cheat_enabled', label: '规则约束', description: '应用世界设定中的行为约束', fallback: true },
] as const;

function defaultSessionTitle() {
  return '新对话';
}

export default function SessionSidebar() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const { triggerDelete, UndoToast } = useUndoDelete();
  const [search, setSearch] = useState('');
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [title, setTitle] = useState(defaultSessionTitle);
  const [templateId, setTemplateId] = useState('custom');
  const [encyclopediaId, setEncyclopediaId] = useState<number | null>(null);
  const [openingOverrides, setOpeningOverrides] = useState<Partial<Record<typeof OPENING_OPTIONS[number]['key'], boolean>>>({});
  const [selectedCharacterIds, setSelectedCharacterIds] = useState<Set<number>>(new Set());
  const importArchiveInputRef = useRef<HTMLInputElement>(null);
  const createToggleButtonRef = useRef<HTMLButtonElement>(null);
  const createHeadingRef = useRef<HTMLHeadingElement>(null);
  const characterDefaultsInitializedRef = useRef(false);
  const templateDefaultInitializedRef = useRef(false);
  const characterSelectionTouchedRef = useRef(false);
  const templateSelectionTouchedRef = useRef(false);
  const normalizedSearch = search.trim();
  const deferredSearch = useDeferredValue(normalizedSearch);

  const sessionsQuery = useQuery({
    queryKey: deferredSearch ? ['sessions', 'search', deferredSearch] : ['sessions'],
    queryFn: () => api.listSessions(deferredSearch),
  });
  const charactersQuery = useQuery({ queryKey: ['characters'], queryFn: api.listCharacters, enabled: showCreateForm });
  const templatesQuery = useQuery({ queryKey: ['world-templates'], queryFn: () => api.listWorldTemplates(), enabled: showCreateForm });
  const encyclopediasQuery = useQuery({ queryKey: ['encyclopedias'], queryFn: api.listEncyclopedias, enabled: showCreateForm });
  const localConfigQuery = useQuery({
    queryKey: ['local-config'],
    queryFn: api.getLocalConfig,
    staleTime: 20_000,
    enabled: showCreateForm,
  });

  const createOptionsLoading = charactersQuery.isFetching
    || templatesQuery.isFetching
    || encyclopediasQuery.isFetching
    || localConfigQuery.isFetching;
  const createOptionsError = charactersQuery.error
    || templatesQuery.error
    || encyclopediasQuery.error
    || localConfigQuery.error;
  const searchPending = normalizedSearch !== deferredSearch || sessionsQuery.isFetching;

  const template = useMemo(
    () => templatesQuery.data?.find((item) => item.template_id === templateId),
    [templateId, templatesQuery.data],
  );
  const encyclopedia = useMemo(
    () => encyclopediasQuery.data?.find((item) => item.id === encyclopediaId),
    [encyclopediaId, encyclopediasQuery.data],
  );

  useEffect(() => {
    window.addEventListener('create-session', openCreateForm);
    return () => window.removeEventListener('create-session', openCreateForm);
  }, []);

  useEffect(() => {
    if (!showCreateForm) return undefined;
    const focusFrame = window.requestAnimationFrame(() => createHeadingRef.current?.focus());
    return () => window.cancelAnimationFrame(focusFrame);
  }, [showCreateForm]);

  useEffect(() => {
    if (!showCreateForm || characterDefaultsInitializedRef.current || charactersQuery.data === undefined) return;
    const characters = charactersQuery.data ?? [];
    if (!characterSelectionTouchedRef.current) {
      setSelectedCharacterIds(characters.length === 1 ? new Set([characters[0].id]) : new Set());
    }
    characterDefaultsInitializedRef.current = true;
  }, [showCreateForm, charactersQuery.data]);

  useEffect(() => {
    if (
      !showCreateForm ||
      templateDefaultInitializedRef.current ||
      templatesQuery.data === undefined ||
      localConfigQuery.data === undefined
    ) return;
    const configuredTemplateId = localConfigQuery.data.default_world_template_id || 'custom';
    const templateAvailable = configuredTemplateId === 'custom'
      || templatesQuery.data.some((item) => item.template_id === configuredTemplateId);
    if (!templateSelectionTouchedRef.current) {
      setTemplateId(templateAvailable ? configuredTemplateId : 'custom');
    }
    templateDefaultInitializedRef.current = true;
  }, [showCreateForm, templatesQuery.data, localConfigQuery.data]);

  const createSession = useMutation({
    mutationFn: () => api.createSessionWithConfig({
      title: title.trim() || defaultSessionTitle(),
      template_id: templateId,
      encyclopedia_id: encyclopediaId,
      gameplay_mode: template?.gameplay_mode ?? '自由剧情',
      narrator_enabled: openingOverrides.narrator_enabled ?? localConfigQuery.data?.default_narrator_enabled ?? false,
      choice_generation_enabled: openingOverrides.choice_generation_enabled ?? localConfigQuery.data?.default_choice_generation_enabled ?? true,
      anti_cheat_enabled: openingOverrides.anti_cheat_enabled ?? localConfigQuery.data?.default_anti_cheat_enabled ?? true,
      initial_character_ids: [...selectedCharacterIds],
    }),
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: ['sessions'] });
      setShowCreateForm(false);
      setTitle(defaultSessionTitle());
      setTemplateId('custom');
      setEncyclopediaId(null);
      setOpeningOverrides({});
      setSelectedCharacterIds(new Set());
      characterDefaultsInitializedRef.current = false;
      templateDefaultInitializedRef.current = false;
      characterSelectionTouchedRef.current = false;
      templateSelectionTouchedRef.current = false;
      showToast('会话已创建', 'success');
      navigate(`/chat/${session.id}`);
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '创建会话失败', 'error'),
  });

  const importSessionArchive = useMutation({
    mutationFn: (file: File) => api.importSessionArchive(file),
    onSuccess: async (result) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['characters'] }),
      ]);
      showToast(
        `已导入“${result.title}”：${result.message_count} 条消息、${result.character_count} 位角色`,
        'success',
      );
      navigate(`/chat/${result.session_id}`);
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '导入会话包失败', 'error'),
  });

  function toggleCharacter(characterId: number) {
    characterSelectionTouchedRef.current = true;
    setSelectedCharacterIds((previous) => {
      const next = new Set(previous);
      if (next.has(characterId)) next.delete(characterId);
      else next.add(characterId);
      return next;
    });
  }

  function openCreateForm() {
    setShowCreateForm(true);
  }

  function closeCreateForm() {
    setShowCreateForm(false);
    window.requestAnimationFrame(() => createToggleButtonRef.current?.focus());
  }

  return (
    <aside className="session-panel" aria-label="会话列表">
      <div className="session-panel-header">
        <div className="session-panel-heading">
          <button
            type="button"
            className="btn-icon mobile-only"
            data-mobile-session-close
            onClick={() => window.dispatchEvent(new CustomEvent('close-mobile-sessions'))}
            title="关闭会话列表"
            aria-label="关闭会话列表"
          >
            <UiIcon name="back" />
          </button>
          <h3>对话</h3>
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          <button
            type="button"
            className="btn-icon"
            disabled={importSessionArchive.isPending}
            onClick={() => importArchiveInputRef.current?.click()}
            title="导入墨境会话包"
            aria-label="导入墨境会话包"
          >
            <UiIcon name={importSessionArchive.isPending ? 'loading' : 'import'} className={importSessionArchive.isPending ? 'ui-icon-loading' : undefined} />
          </button>
          <button
            ref={createToggleButtonRef}
            type="button"
            className="btn-icon"
            onClick={() => { if (showCreateForm) closeCreateForm(); else openCreateForm(); }}
            title={showCreateForm ? '收起新建对话' : '新建对话'}
            aria-label={showCreateForm ? '收起新建对话' : '新建对话'}
            aria-expanded={showCreateForm}
          >
            <UiIcon name={showCreateForm ? 'close' : 'plus'} />
          </button>
        </div>
      </div>

      <input
        ref={importArchiveInputRef}
        type="file"
        accept=".zip,application/zip"
        hidden
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) importSessionArchive.mutate(file);
          event.target.value = '';
        }}
      />

      <div className="session-search" aria-busy={searchPending}>
        <UiIcon name={searchPending ? 'loading' : 'search'} className={searchPending ? 'ui-icon-loading' : undefined} />
        <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="按标题搜索对话…" aria-label="按标题搜索对话" />
        {search && (
          <button type="button" className="session-search-clear" onClick={() => setSearch('')} aria-label="清空会话搜索">
            <UiIcon name="close" />
          </button>
        )}
      </div>

      {showCreateForm && (
        <section className="world-box session-create-box" aria-labelledby="new-session-heading">
          <div className="session-create-header">
            <div>
              <h2 id="new-session-heading" ref={createHeadingRef} tabIndex={-1}>新建对话</h2>
              <span>创建后直接进入故事，可稍后调整角色与世界。</span>
            </div>
            <button type="button" className="btn-icon" onClick={closeCreateForm} aria-label="收起新建对话">
              <UiIcon name="close" />
            </button>
          </div>
          {createOptionsError && (
            <InlineQueryError
              message="新建会话选项加载失败，可重试或创建空会话"
              error={createOptionsError}
              retrying={createOptionsLoading}
              onRetry={() => {
                void Promise.all([
                  charactersQuery.refetch(),
                  templatesQuery.refetch(),
                  encyclopediasQuery.refetch(),
                  localConfigQuery.refetch(),
                ]);
              }}
            />
          )}
          <div className="form-group">
            <label htmlFor="new-session-title">标题</label>
            <input id="new-session-title" value={title} onChange={(event) => setTitle(event.target.value)} />
          </div>
          <div className="form-group">
            <label>参与角色（可稍后添加）</label>
            <div className="session-character-picker">
              {charactersQuery.isLoading && <span className="hint">正在读取角色…</span>}
              {(charactersQuery.data ?? []).map((character) => (
                <button
                  key={character.id}
                  type="button"
                  className={`btn btn-sm ${selectedCharacterIds.has(character.id) ? 'btn-primary' : 'btn-ghost'}`}
                  onClick={() => toggleCharacter(character.id)}
                  aria-pressed={selectedCharacterIds.has(character.id)}
                >
                  {character.name || '未命名'}
                </button>
              ))}
              {!charactersQuery.isLoading && !charactersQuery.isError && (charactersQuery.data?.length ?? 0) === 0 && (
                <span className="hint">暂无角色，可创建空会话后再添加。</span>
              )}
            </div>
          </div>
          <details className="session-create-options">
            <summary>
              <span>世界与百科</span>
              <small>
                {template ? template.label : '不使用世界模板'}
                {encyclopedia ? ` · ${encyclopedia.name}` : ''}
              </small>
            </summary>
            <div className="form-group">
              <label htmlFor="new-session-template">世界模板</label>
              <small className="guide-inline">提供故事开局、玩法与固定规则。</small>
              <select
                id="new-session-template"
                value={templateId}
                disabled={templatesQuery.isLoading || templatesQuery.isError}
                onChange={(event) => {
                  templateSelectionTouchedRef.current = true;
                  setTemplateId(event.target.value);
                }}
              >
                <option value="custom">不使用模板</option>
                {templatesQuery.data?.map((item) => <option key={item.id} value={item.template_id}>{item.label}</option>)}
              </select>
            </div>
            <div className="form-group">
              <label htmlFor="new-session-encyclopedia">世界百科（可选）</label>
              <small className="guide-inline">补充人物、地点与历史知识，可与世界模板组合。</small>
              <select
                id="new-session-encyclopedia"
                value={encyclopediaId ?? ''}
                disabled={encyclopediasQuery.isLoading || encyclopediasQuery.isError}
                onChange={(event) => setEncyclopediaId(event.target.value ? Number(event.target.value) : null)}
              >
                <option value="">不绑定百科</option>
                {encyclopediasQuery.data?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select>
            </div>
          </details>
          <details className="session-create-options">
            <summary><span>对话设置</span><small>仅用于这次新对话</small></summary>
            {OPENING_OPTIONS.map(option => (
              <label key={option.key} className="session-create-toggle">
                <span><strong>{option.label}</strong><small>{option.description}</small></span>
                <input type="checkbox" aria-label={option.label}
                  checked={openingOverrides[option.key] ?? localConfigQuery.data?.[option.configKey] ?? option.fallback}
                  disabled={createOptionsLoading || createSession.isPending}
                  onChange={event => setOpeningOverrides(current => ({ ...current, [option.key]: event.target.checked }))} />
              </label>
            ))}
          </details>
          {createSession.isError && <p className="session-create-error" role="alert">
            {createSession.error instanceof Error ? createSession.error.message : '创建会话失败，请重试'}
          </p>}
          <button type="button" className="btn btn-primary" onClick={() => createSession.mutate()} disabled={createSession.isPending || createOptionsLoading}>
            {createSession.isPending ? '创建中...' : createOptionsLoading ? '正在准备选项…' : '开始新对话'}
          </button>
        </section>
      )}

      <div className="session-list">
        {sessionsQuery.isLoading && (
          <div className="session-empty session-list-state" role="status">
            <UiIcon name="loading" className="ui-icon-loading" />
            <strong>正在读取对话</strong>
          </div>
        )}
        {sessionsQuery.isError && (
          <InlineQueryError
            message="会话列表加载失败，请确认本机服务已启动后重试"
            retrying={sessionsQuery.isFetching}
            onRetry={() => { void sessionsQuery.refetch(); }}
          />
        )}
        {!sessionsQuery.isLoading && !sessionsQuery.isError && (sessionsQuery.data?.length ?? 0) === 0 && (
          normalizedSearch ? (
            <div className="session-empty session-list-state">
              <UiIcon name="search" />
              <strong>没有匹配的对话</strong>
              <span>换个标题关键词，或清空搜索查看全部记录。</span>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSearch('')}>清空搜索</button>
            </div>
          ) : (
            <div className="session-empty session-list-state">
              <UiIcon name="chat" />
              <strong>还没有对话</strong>
              <span>创建一个故事，或导入已有的墨境会话包。</span>
              <button type="button" className="btn btn-primary btn-sm" onClick={openCreateForm}>新建对话</button>
            </div>
          )
        )}
        {sessionsQuery.data?.map((session) => (
          <div key={session.id} className="session-item-wrapper">
            <button
              type="button"
              className={`session-item ${String(session.id) === sessionId ? 'active' : ''}`}
              onClick={() => navigate(`/chat/${session.id}`)}
            >
              <span className="session-item-avatar">{session.title?.trim()?.[0] || '未'}</span>
              <span className="session-item-info">
                <span className="session-item-title">{session.title?.trim() || '未命名对话'}</span>
                {session.last_message_preview && <span className="session-item-preview">{session.last_message_preview}</span>}
                <span className="session-item-meta">{session.message_count} 条消息 · {session.participant_count} 位角色</span>
              </span>
            </button>
            <button
              type="button"
              className="session-delete-button"
              title="删除会话"
              aria-label={`删除对话 ${session.title?.trim() || '未命名对话'}`}
              onClick={() => triggerDelete(session.title || '会话', async () => {
                try {
                  await api.deleteSession(session.id);
                  await queryClient.invalidateQueries({ queryKey: ['sessions'] });
                  if (String(session.id) === sessionId) navigate('/chat');
                  showToast('会话已删除', 'success');
                } catch (error) {
                  showToast(error instanceof Error ? error.message : '删除会话失败', 'error');
                }
              })}
            >
              <UiIcon name="delete" />
            </button>
          </div>
        ))}
      </div>
      {UndoToast}
    </aside>
  );
}
