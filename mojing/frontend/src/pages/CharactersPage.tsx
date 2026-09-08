import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useBeforeUnload, useBlocker, useNavigate, useSearchParams } from 'react-router-dom';

import { api } from '../api/client';
import { ApiProbePanel } from '../components/ApiProbePanel';
import AiCompleteButton from '../components/AiCompleteButton';
import { confirmModal } from '../components/ConfirmModal';
import CharacterImportDialog from '../components/CharacterImportDialog';
import CreationHomeLink from '../components/CreationHomeLink';
import InlineQueryError from '../components/InlineQueryError';
import MacroSelector from '../components/MacroSelector';
import UiIcon from '../components/UiIcon';
import { useUndoDelete } from '../components/UndoToast';
import { useToast } from '../hooks/useToast';
import { COMPACT_LAYOUT_QUERY, useMediaQuery } from '../hooks/useMediaQuery';
import type { Character } from '../types';
import { readMoJingStorage, writeMoJingStorage } from '../utils/mojingStorage';

const emptyCharacter: Partial<Character> = {
  name: '',
  persona_prompt: '',
  api_key: '',
  api_base_url: '',
  model_name: '',
  temperature: 0.9,
  max_tokens: 1200,
  avatar_color: '#F97316',
  avatar_image_path: '',
  voice_profile_id: null,
  voice_provider: '',
  voice_api_base_url: '',
  voice_api_key: '',
  voice_model: '',
  image_gen_enabled: false,
  image_gen_api_key: '',
  image_gen_base_url: '',
  image_gen_model: 'dall-e-3',
  think_max_enabled: false,
  think_max_model_name: '',
  card_image_path: '',
};

type DetailTab = 'basic' | 'api' | 'import' | 'voice' | 'image' | 'advanced';

const CHAR_SIDEBAR_LAYOUT_KEY = 'mojing_characters_sidebar_layout';

function characterDraftSnapshot(character: Partial<Character> | null): string {
  return character ? JSON.stringify(character) : '';
}

function mergeCharacterDraftSnapshot(snapshot: string, savedFields: Partial<Character>): string {
  if (!snapshot) return characterDraftSnapshot(savedFields);
  try {
    return characterDraftSnapshot({ ...(JSON.parse(snapshot) as Partial<Character>), ...savedFields });
  } catch {
    return snapshot;
  }
}

export default function CharactersPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const { triggerDelete, UndoToast } = useUndoDelete();
  const isCompactLayout = useMediaQuery(COMPACT_LAYOUT_QUERY);
  const [editing, setEditing] = useState<Partial<Character> | null>(null);
  const [editingBaseline, setEditingBaseline] = useState('');
  const [activeTab, setActiveTab] = useState<DetailTab>('basic');
  const [searchText, setSearchText] = useState('');
  const [voiceName, setVoiceName] = useState('');
  const [voiceDescription, setVoiceDescription] = useState('');
  const [voiceFile, setVoiceFile] = useState<File | null>(null);
  const [isRecording, setIsRecording] = useState(false);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const [sourceFilename, setSourceFilename] = useState('persona.txt');
  const [sourceText, setSourceText] = useState('');
  const [importOpen, setImportOpen] = useState(false);
  const cardExportMutation = useMutation({
    mutationFn: (characterId: number) => api.exportCharacterCard(characterId),
    onSuccess: () => showToast('角色卡已下载', 'success'),
    onError: (error) => showToast(error instanceof Error ? error.message : '角色卡导出失败，请重试', 'error'),
  });
  const [revealId, setRevealId] = useState<number | null>(null);
  const libraryRef = useRef<HTMLDivElement>(null);
  const [assetSearch, setAssetSearch] = useState('');
  const [voiceMode, setVoiceMode] = useState<'builtin' | 'clone' | 'external'>('builtin');
  const [sidebarLayout, setSidebarLayout] = useState<'list' | 'grid'>(() => {
    try {
      return readMoJingStorage(CHAR_SIDEBAR_LAYOUT_KEY) === 'grid' ? 'grid' : 'list';
    } catch {
      return 'list';
    }
  });
  const [cardGenHint, setCardGenHint] = useState('');
  const loadedCharacterRouteRef = useRef<string | null>(null);
  const savingCharacterRouteRef = useRef<string | null>(null);
  const allowCharacterNavigationRef = useRef(false);

  const characterRouteKey = searchParams.get('characterId')?.trim() ?? '';
  const setCharacterRoute = useCallback((value: number | 'new' | null, replace = false) => {
    const routeValue = value == null ? '' : String(value);
    if ((searchParams.get('characterId')?.trim() ?? '') === routeValue) return;
    const next = new URLSearchParams(searchParams);
    if (value == null) next.delete('characterId');
    else next.set('characterId', String(value));
    setSearchParams(next, { replace });
  }, [searchParams, setSearchParams]);

  useEffect(() => {
    try {
      writeMoJingStorage(CHAR_SIDEBAR_LAYOUT_KEY, sidebarLayout);
    } catch {
      /* ignore */
    }
  }, [sidebarLayout]);

  const charactersQuery = useQuery({ queryKey: ['characters'], queryFn: api.listCharacters });
  const voicesQuery = useQuery({ queryKey: ['voices'], queryFn: api.listVoices });
  const providersQuery = useQuery({ queryKey: ['provider-catalog'], queryFn: api.listProviderCatalog });
  const localConfigQuery = useQuery({ queryKey: ['local-config'], queryFn: api.getLocalConfig, staleTime: 20_000 });
  const editingSnapshot = useMemo(() => characterDraftSnapshot(editing), [editing]);
  const isCharacterDirty = Boolean(editing) && Boolean(editingBaseline) && editingSnapshot !== editingBaseline;

  const characterNavigationBlocker = useBlocker(({ currentLocation, nextLocation }) => {
    if (allowCharacterNavigationRef.current) {
      allowCharacterNavigationRef.current = false;
      return false;
    }
    return isCharacterDirty && (
      currentLocation.pathname !== nextLocation.pathname ||
      currentLocation.search !== nextLocation.search
    );
  });

  useEffect(() => {
    if (characterNavigationBlocker.state !== 'blocked') return;
    let active = true;
    void confirmModal(
      '角色修改尚未保存',
      '离开后会丢失当前修改。确认放弃修改并离开吗？',
    ).then((leave) => {
      if (!active || characterNavigationBlocker.state !== 'blocked') return;
      if (leave) characterNavigationBlocker.proceed();
      else characterNavigationBlocker.reset();
    });
    return () => { active = false; };
  }, [characterNavigationBlocker]);

  useBeforeUnload(useCallback((event) => {
    if (!isCharacterDirty) return;
    event.preventDefault();
    event.returnValue = '';
  }, [isCharacterDirty]));

  useEffect(() => {
    if (loadedCharacterRouteRef.current === characterRouteKey) {
      const loadedId = Number(characterRouteKey);
      const loadedStillExists = charactersQuery.data?.some((character) => character.id === loadedId);
      if (
        Number.isSafeInteger(loadedId) &&
        loadedId > 0 &&
        !charactersQuery.isFetching &&
        !charactersQuery.isError &&
        charactersQuery.data !== undefined &&
        !loadedStillExists
      ) {
        loadedCharacterRouteRef.current = '';
        setEditing(null);
        setEditingBaseline('');
        setCharacterRoute(null, true);
      }
      return;
    }
    if (!characterRouteKey) {
      loadedCharacterRouteRef.current = '';
      setEditing(null);
      setEditingBaseline('');
      return;
    }
    if (characterRouteKey === 'new') {
      loadedCharacterRouteRef.current = characterRouteKey;
      const draft = { ...emptyCharacter, name: '' };
      setEditing(draft);
      setEditingBaseline(characterDraftSnapshot(draft));
      setActiveTab('basic');
      return;
    }
    const requestedId = Number(characterRouteKey);
    if (!Number.isSafeInteger(requestedId) || requestedId <= 0) {
      loadedCharacterRouteRef.current = '';
      setEditing(null);
      setEditingBaseline('');
      setCharacterRoute(null, true);
      return;
    }
    const selected = charactersQuery.data?.find((character) => character.id === requestedId);
    if (selected) {
      loadedCharacterRouteRef.current = characterRouteKey;
      setEditing(selected);
      setEditingBaseline(characterDraftSnapshot(selected));
      setActiveTab('basic');
      return;
    }
    if (charactersQuery.isPending || charactersQuery.isFetching || charactersQuery.isError || charactersQuery.data === undefined) return;
    loadedCharacterRouteRef.current = '';
    setEditing(null);
    setEditingBaseline('');
    setCharacterRoute(null, true);
  }, [
    characterRouteKey,
    charactersQuery.data,
    charactersQuery.isError,
    charactersQuery.isFetching,
    charactersQuery.isPending,
    setCharacterRoute,
  ]);

  const hasPublicTextKey = useMemo(
    () => Boolean(String(localConfigQuery.data?.public_text_api_key ?? '').trim()),
    [localConfigQuery.data],
  );

  /** 角色页「文字」连通测试：角色字段 + 公共 Key 回退 */
  const charTextProbePayload = useMemo(() => {
    const lc = localConfigQuery.data;
    if (!editing) return null;
    const characterBase = (editing.api_base_url ?? '').trim();
    const hasCharacterKey = Boolean((editing.api_key ?? '').trim());
    const isPlaceholderBase = !characterBase || /^https:\/\/api\.deepseek\.com(?:\/v1)?\/?$/i.test(characterBase);
    const useCharacterRoute = hasCharacterKey || !isPlaceholderBase;
    const base = useCharacterRoute
      ? characterBase
      : (lc?.public_text_base_url ?? '').trim() || characterBase;
    const key = (editing.api_key ?? '').trim() || (lc?.public_text_api_key ?? '').trim();
    const model = useCharacterRoute
      ? (editing.model_name ?? '').trim()
      : (lc?.public_text_model ?? '').trim() || (editing.model_name ?? '').trim();
    return { base, key, model };
  }, [editing, localConfigQuery.data]);

  const charImageProbePayload = useMemo(() => {
    const lc = localConfigQuery.data;
    if (!editing || !editing.image_gen_enabled) return null;
    const base = (editing.image_gen_base_url ?? '').trim() || (lc?.public_image_base_url ?? '').trim() || (lc?.public_text_base_url ?? '').trim();
    const key =
      (editing.image_gen_api_key ?? '').trim() ||
      (lc?.public_image_api_key ?? '').trim() ||
      (lc?.public_text_api_key ?? '').trim();
    const model = (editing.image_gen_model ?? 'dall-e-3').trim();
    return { base, key, model };
  }, [editing, localConfigQuery.data]);

  /** 麦克风转写：仅用设置「语音转写」与配图/对话公网根；不混入角色对话 Key（与 ApiKey 审计一致）。 */
  const charVoiceSttProbePayload = useMemo(() => {
    const lc = localConfigQuery.data;
    if (!editing) return null;
    const base = ((lc?.public_voice_base_url ?? '').trim() || (lc?.public_image_base_url ?? '').trim() || (lc?.public_text_base_url ?? '').trim());
    const key = (lc?.public_voice_api_key ?? '').trim() || (lc?.public_image_api_key ?? '').trim() || (lc?.public_text_api_key ?? '').trim();
    const model = (lc?.public_voice_model ?? '').trim() || 'whisper-1';
    return { base, key, model };
  }, [editing, localConfigQuery.data]);

  const BUILTIN_PROVIDERS = useMemo(() => [
    { label: '继承设置中的公共 API', base_url: '', models: [] as string[] },
    ...(providersQuery.data ?? []).filter((p) => p.base_url).map((p) => ({
      label: p.label, base_url: p.base_url, models: p.models.map((m) => m.id),
    })),
  ], [providersQuery.data]);

  const avatarAssetsQuery = useQuery({
    queryKey: ['assets', 'avatar', assetSearch],
    queryFn: () => api.listAssets('avatar', assetSearch),
    enabled: editing?.id != null,
  });

  const filteredCharacters = useMemo(() => {
    const data = [...(charactersQuery.data ?? [])].sort((a, b) =>
      Number(b.favorite) - Number(a.favorite) || Date.parse(b.created_at) - Date.parse(a.created_at) || b.id - a.id);
    if (!searchText.trim()) return data;
    return data.filter((c) => c.name.includes(searchText) || c.model_name?.includes(searchText));
  }, [charactersQuery.data, searchText]);

  useEffect(() => {
    if (!revealId || (isCompactLayout && editing)) return;
    const target = libraryRef.current?.querySelector(`[data-character-id="${revealId}"]`);
    if (target) { target.scrollIntoView({ block: 'nearest' }); setRevealId(null); }
  }, [revealId, filteredCharacters, isCompactLayout, editing]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!editing) throw new Error('请先选择或新建角色');
      if (!editing.name?.trim()) {
        throw new Error('请填写角色名字（必填）');
      }
      if (editing.id) {
        const saved = charactersQuery.data?.find((character) => character.id === editing.id);
        return api.updateCharacter(editing.id, {
          ...editing,
          clear_api_key: Boolean(saved?.api_key && !editing.api_key),
          clear_voice_api_key: Boolean(saved?.voice_api_key && !editing.voice_api_key),
          clear_image_gen_api_key: Boolean(saved?.image_gen_api_key && !editing.image_gen_api_key),
        });
      }
      return api.createCharacter(editing);
    },
    onSuccess: async (saved) => {
      const id = saved?.id ?? editing?.id;
      if (savingCharacterRouteRef.current === loadedCharacterRouteRef.current) {
        if (savingCharacterRouteRef.current === 'new' && id) { setSearchText(''); setRevealId(id); }
        setEditing(saved);
        setEditingBaseline(characterDraftSnapshot(saved));
        if (id) {
          loadedCharacterRouteRef.current = String(id);
          if (characterRouteKey !== String(id)) {
            allowCharacterNavigationRef.current = true;
          }
          setCharacterRoute(id, true);
        }
      }
      await queryClient.invalidateQueries({ queryKey: ['characters'] });
      showToast('角色已保存', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
    onSettled: () => { savingCharacterRouteRef.current = null; },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteCharacter(id),
    onSuccess: (_result, deletedId) => {
      if (loadedCharacterRouteRef.current === String(deletedId)) {
        loadedCharacterRouteRef.current = '';
        setEditing(null);
        setEditingBaseline('');
        allowCharacterNavigationRef.current = true;
        setCharacterRoute(null, true);
      }
      queryClient.invalidateQueries({ queryKey: ['characters'] });
      showToast('角色已删除', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const startChatMutation = useMutation({
    mutationFn: (character: Character) => api.createSessionWithConfig({
      title: `与 ${character.name} 的新故事`,
      initial_character_ids: [character.id],
    }),
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: ['sessions'] });
      showToast('新故事已创建', 'success');
      navigate(`/chat/${session.id}`);
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '创建会话失败', 'error'),
  });

  const favoriteMutation = useMutation({
    mutationFn: (id: number) => api.toggleFavorite(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['characters'] }),
  });

  const uploadVoiceMutation = useMutation({
    mutationFn: async () => {
      if (!voiceFile || !voiceName.trim()) throw new Error('请填写声色名称并选择参考音频');
      const formData = new FormData();
      formData.append('name', voiceName);
      formData.append('description', voiceDescription);
      formData.append('language', 'zh-cn');
      formData.append('file', voiceFile);
      return api.uploadVoice(formData);
    },
    onSuccess: async () => {
      setVoiceName(''); setVoiceDescription(''); setVoiceFile(null);
      await queryClient.invalidateQueries({ queryKey: ['voices'] });
      showToast('声线上传成功', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const importProfileMutation = useMutation({
    mutationFn: async () => {
      if (!editing?.id) throw new Error('请先保存角色');
      if (isCharacterDirty) throw new Error('请先保存当前角色修改，再抽取人物设定');
      if (!sourceText.trim()) throw new Error('请先粘贴设定文本或选择 TXT / DOCX 文件');
      const request = { characterId: editing.id, personaPrompt: editing.persona_prompt ?? '' };
      const profile = await api.importCharacterProfile(request.characterId, {
        source_text: sourceText,
        source_filename: sourceFilename,
        merge_into_persona_prompt: true,
      });
      const saved = (await api.listCharacters()).find((character) => character.id === request.characterId);
      if (!saved) throw new Error('人物设定已抽取，但角色资料刷新失败，请重新打开角色');
      return { profile, saved, request };
    },
    onSuccess: async ({ saved, request }) => {
      if (loadedCharacterRouteRef.current === String(request.characterId)) {
        setEditing((current) => {
          if (!current || current.id !== request.characterId || current.persona_prompt !== request.personaPrompt) return current;
          return { ...current, persona_prompt: saved.persona_prompt };
        });
        setEditingBaseline((baseline) => mergeCharacterDraftSnapshot(baseline, { persona_prompt: saved.persona_prompt }));
      }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['character-profile', request.characterId] }),
        queryClient.invalidateQueries({ queryKey: ['characters'] }),
      ]);
      showToast('人物设定抽取完成', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const generateCardImageMutation = useMutation({
    mutationFn: async () => {
      if (!editing?.id) throw new Error('请先保存角色');
      return api.generateCharacterCardImage(editing.id, { prompt_hint: cardGenHint, size: '1024x1792' });
    },
    onSuccess: async (c) => {
      const savedFields = { card_image_path: c.card_image_path };
      setEditing((prev) => (prev ? { ...prev, ...savedFields } : c));
      setEditingBaseline((baseline) => mergeCharacterDraftSnapshot(baseline, savedFields));
      await queryClient.invalidateQueries({ queryKey: ['characters'] });
      showToast('形象图已生成并保存', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const profileQuery = useQuery({
    queryKey: ['character-profile', editing?.id],
    queryFn: () => api.getCharacterProfile(Number(editing!.id)),
    enabled: Boolean(editing?.id),
  });

  async function confirmCharacterReplacement(message: string): Promise<boolean> {
    if (!isCharacterDirty) return true;
    return confirmModal('角色修改尚未保存', message);
  }
  async function handleNew() {
    if (!editing?.id && loadedCharacterRouteRef.current === 'new') return;
    if (!await confirmCharacterReplacement('新建角色会丢失当前修改。确认继续吗？')) return;
    const draft = { ...emptyCharacter, name: '' };
    loadedCharacterRouteRef.current = 'new';
    setEditing(draft);
    setEditingBaseline(characterDraftSnapshot(draft));
    setActiveTab('basic');
    allowCharacterNavigationRef.current = true;
    setCharacterRoute('new');
  }
  async function handleSelect(char: Character) {
    if (editing?.id === char.id) return;
    if (!await confirmCharacterReplacement('打开其他角色会丢失当前修改。确认继续吗？')) return;
    loadedCharacterRouteRef.current = String(char.id);
    setEditing(char);
    setEditingBaseline(characterDraftSnapshot(char));
    setActiveTab('basic');
    allowCharacterNavigationRef.current = true;
    setCharacterRoute(char.id);
  }
  async function closeEditor() {
    if (!await confirmCharacterReplacement('返回角色列表会丢失当前修改。确认继续吗？')) return;
    loadedCharacterRouteRef.current = '';
    setEditing(null);
    setEditingBaseline('');
    allowCharacterNavigationRef.current = true;
    setCharacterRoute(null, true);
  }
  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!editing?.name?.trim()) {
      showToast('请填写角色名字（必填）', 'warn');
      return;
    }
    savingCharacterRouteRef.current = loadedCharacterRouteRef.current;
    saveMutation.mutate();
  }

  const currentProvider = useMemo(() => {
    if (!editing?.api_base_url) return null;
    const base = editing.api_base_url.toLowerCase();
    return BUILTIN_PROVIDERS.find((p) => p.base_url && base.includes(p.base_url.toLowerCase())) ?? null;
  }, [editing?.api_base_url, BUILTIN_PROVIDERS]);

  const DETAIL_TABS: { id: DetailTab; label: string }[] = [
    { id: 'basic', label: '角色设定' },
    { id: 'api', label: '模型线路' },
    { id: 'import', label: '资料工具' },
    { id: 'voice', label: '语音' },
    { id: 'image', label: '图片' },
    { id: 'advanced', label: '更多设置' },
  ];

  return (
    <div className={`page-layout with-secondary-nav characters-layout ${!editing ? 'is-empty-main' : ''}`} data-mobile-level={editing ? 2 : 1}>
      {importOpen && <CharacterImportDialog onClose={() => setImportOpen(false)} onOpen={(character) => {
        setImportOpen(false);
        queryClient.setQueryData<Character[]>(['characters'], (current) => [...(current || []).filter((item) => item.id !== character.id), character]);
        setSearchText('');
        setRevealId(character.id);
        void handleSelect(character);
      }} />}
      {/* ========== 左侧角色列表 ========== */}
      <aside className="secondary-sidebar">
        <div className="secondary-sidebar-header">
          <div className="creation-workspace-title">
            <CreationHomeLink compact />
            <h3>角色</h3>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <button
              type="button"
              className="btn-icon"
              title={sidebarLayout === 'grid' ? '切换为列表' : '切换为网格'}
              aria-label={sidebarLayout === 'grid' ? '切换为列表' : '切换为网格'}
              onClick={() => setSidebarLayout((value) => (value === 'grid' ? 'list' : 'grid'))}
            >
              <UiIcon name={sidebarLayout === 'grid' ? 'menu' : 'grid'} />
            </button>
            <button type="button" className="btn-icon" onClick={handleNew} title="新建角色" aria-label="新建角色"><UiIcon name="plus" /></button>
          </div>
        </div>
        <div className="secondary-sidebar-search">
          <input value={searchText} onChange={(e) => setSearchText(e.target.value)} placeholder="按名字搜索角色…" aria-label="按名字搜索角色" />
        </div>
        <div className="character-library-actions"><button type="button" className="btn btn-ghost btn-sm" onClick={() => setImportOpen(true)}>导入角色</button></div>
        <p className="character-library-order">收藏优先 · 最新创建在前</p>
        <div ref={libraryRef} className={`secondary-sidebar-list ${sidebarLayout === 'grid' ? 'character-sidebar-grid' : ''}`}>
          {charactersQuery.isError && (
            <InlineQueryError
              message="角色列表加载失败"
              error={charactersQuery.error}
              retrying={charactersQuery.isFetching}
              onRetry={() => { void charactersQuery.refetch(); }}
            />
          )}
          {charactersQuery.isLoading && (
            <div className="secondary-sidebar-empty"><p>正在加载角色…</p></div>
          )}
          {filteredCharacters.map((char) => {
            const cover = (char.card_image_path || char.avatar_image_path || '').trim();
            if (sidebarLayout === 'grid') {
              return (
                <button
                  key={char.id}
                  data-character-id={char.id}
                  type="button"
                  className={`character-grid-card ${editing?.id === char.id ? 'active' : ''}`}
                  onClick={() => handleSelect(char)}
                >
                  <div
                    className="character-grid-cover"
                    style={{
                      backgroundColor: char.avatar_color,
                      backgroundImage: cover ? `url(${api.mediaRefUrl(cover)})` : undefined,
                    }}
                  >
                    {!cover && <span className="character-grid-initial">{char.name[0]}</span>}
                  </div>
                  <div className="character-grid-caption">
                    <span className="character-grid-name">{char.name}</span>
                    <span
                      className={`character-grid-fav ${char.favorite ? 'active' : ''}`}
                      role="presentation"
                      title={char.favorite ? '取消收藏' : '收藏'}
                      onClick={(e) => {
                        e.stopPropagation();
                        favoriteMutation.mutate(char.id);
                      }}
                    >
                      <UiIcon name="bookmark" />
                    </span>
                  </div>
                </button>
              );
            }
            return (
              <button key={char.id} data-character-id={char.id} type="button" className={`secondary-nav-item ${editing?.id === char.id ? 'active' : ''}`} onClick={() => handleSelect(char)}>
                <span
                  className="secondary-nav-avatar"
                  style={{
                    backgroundColor: char.avatar_color,
                    backgroundImage: cover ? `url(${api.mediaRefUrl(cover)})` : undefined,
                  }}
                >
                  {cover ? null : <span>{char.name[0]}</span>}
                </span>
                <span className="secondary-nav-text">
                  <span className="secondary-nav-name">{char.name}</span>
                  <span className="secondary-nav-sub">{char.persona_prompt?.trim() ? '人设已填写' : '待补充人设'}</span>
                </span>
                <span
                  className={`secondary-nav-fav ${char.favorite ? 'active' : ''}`}
                  role="presentation"
                  title={char.favorite ? '取消收藏' : '收藏'}
                  onClick={(e) => { e.stopPropagation(); favoriteMutation.mutate(char.id); }}
                >
                  <UiIcon name="bookmark" />
                </span>
              </button>
            );
          })}
          {!charactersQuery.isLoading && !charactersQuery.isError && filteredCharacters.length === 0 && (
            <div className="secondary-sidebar-empty">
              <UiIcon name={searchText.trim() ? 'search' : 'person'} />
              <p>{searchText.trim() ? '没有匹配的角色' : '还没有角色'}</p>
              {searchText.trim() ? (
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSearchText('')}>清空搜索</button>
              ) : (
                <button type="button" className="btn btn-primary btn-sm" onClick={handleNew}><UiIcon name="plus" />创建第一个角色</button>
              )}
            </div>
          )}
        </div>
      </aside>

      {/* ========== 右侧详情区 ========== */}
      <main className="secondary-main">
        {!editing ? (
          <div className="secondary-empty">
            <div className="welcome-icon" aria-hidden="true"><UiIcon name="person" /></div>
            <h2>选择一个角色</h2>
            <p style={{ color: 'var(--text-2)', margin: '8px 0 16px' }}>
              {isCompactLayout
                ? '从列表选择角色查看资料，或直接创建一个新角色。'
                : '从左侧选择一个角色查看或编辑，也可导入形象与设定'}
            </p>
            <button type="button" className="btn btn-primary" onClick={handleNew}><UiIcon name="plus" />新建角色</button>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="secondary-detail-header">
              <div className="secondary-detail-title">
                <button className="btn-icon mobile-only" type="button" onClick={closeEditor} style={{ marginRight: 8, flexShrink: 0 }} aria-label="返回角色列表">←</button>
                <span className="secondary-nav-avatar" style={{ backgroundColor: editing.avatar_color ?? '#F97316', width: 40, height: 40, fontSize: '1rem' }}>
                  {editing.name?.[0] || '?'}
                </span>
                <div>
                  <h2>{editing.name || '未命名角色'}</h2>
                  <p className="secondary-detail-meta">{isCharacterDirty ? '有未保存修改' : editing.persona_prompt?.trim() ? '人设已填写' : '待补充人设'}</p>
                </div>
              </div>
              <div className="button-row">
                <button className="btn btn-primary btn-sm" type="submit" disabled={saveMutation.isPending} title={!editing.name?.trim() ? '请填写角色名字' : saveMutation.isPending ? '保存中…' : ''}>
                  {saveMutation.isPending ? '保存中...' : editing.id ? '保存修改' : '创建角色'}
                </button>
                {editing.id && (
                  <button
                    className="btn btn-primary btn-sm"
                    type="button"
                    disabled={startChatMutation.isPending || isCharacterDirty}
                    title={isCharacterDirty ? '请先保存角色修改' : ''}
                    onClick={() => startChatMutation.mutate(editing as Character)}
                  >
                    {startChatMutation.isPending ? <><UiIcon name="loading" className="ui-icon-loading" />正在创建…</> : <><UiIcon name="chat" />开始对话</>}
                  </button>
                )}
                {editing.id && (
                  <button className="btn btn-ghost btn-sm btn-danger" type="button" onClick={() => { triggerDelete(editing.name || '角色', () => deleteMutation.mutate(editing.id!), () => {}); }}>
                    删除
                  </button>
                )}
              </div>
            </div>

            <div className="secondary-tabs" role="tablist" aria-label="角色资料分页">
              {DETAIL_TABS.map((tab) => (
                <button
                  type="button"
                  role="tab"
                  aria-selected={activeTab === tab.id}
                  aria-controls={`character-panel-${tab.id}`}
                  key={tab.id}
                  className={`secondary-tab ${activeTab === tab.id ? 'active' : ''}`}
                  onClick={() => setActiveTab(tab.id)}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            <div className="secondary-detail-body" role="tabpanel" id={`character-panel-${activeTab}`}>
              {/* ===== 基础信息 ===== */}
              {activeTab === 'basic' && (
                <details className="form-section" open>
                  <summary>基础信息</summary>
                  <div className="form-grid">
                  <div className="form-group full-row">
                    <label className="required">名字</label>
                    <input value={editing.name ?? ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder="给角色起个名字" maxLength={50} required autoFocus />
                    {editing.name && editing.name.length > 40 && <div className="field-error">名字太长，建议不超过 40 个字</div>}
                  </div>
                  <div className="form-group full-row">
                    <label>角色人设</label>
                    <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
                      <MacroSelector
                        onInsert={(macro) => setEditing({ ...editing, persona_prompt: (editing.persona_prompt ?? '') + macro })}
                        onCopied={() => showToast('已复制到剪贴板，可粘贴到人设任意位置', 'success')}
                      />
                      <AiCompleteButton
                        targetType="character"
                        targetData={{ name: editing.name, persona_prompt: editing.persona_prompt }}
                        fieldsToComplete={['persona_prompt']}
                        onCompleted={(result) => setEditing({ ...editing, persona_prompt: (result.persona_prompt as string) || editing.persona_prompt })}
                      />
                    </div>
                    <textarea rows={12} value={editing.persona_prompt ?? ''} onChange={(e) => setEditing({ ...editing, persona_prompt: e.target.value })} placeholder={
`描述角色是谁、性格如何、怎样说话，以及重要的背景与关系。`
                    } maxLength={10000} />
                    <div className="hint">
                      <span>写清角色身份、性格、说话方式和关键经历，之后仍可继续修改。</span>
                      <span style={{ float: 'right' }}>{(editing.persona_prompt ?? '').length}/10000</span>
                    </div>
                  </div>
                  <div className="form-group full-row">
                    <label>头像颜色</label>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <input type="color" value={editing.avatar_color ?? '#F97316'} onChange={(e) => setEditing({ ...editing, avatar_color: e.target.value })} style={{ width: 48, padding: 2, height: 40 }} />
                      <span style={{ fontSize: '0.78rem', color: 'var(--muted)' }}>没有头像时显示这个颜色</span>
                    </div>
                  </div>
                  <div className="form-group full-row">
                    <label>头像 / 立绘</label>
                    <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                      <div className="avatar-preview large" style={{ backgroundColor: editing.avatar_color ?? '#F97316', backgroundImage: editing.avatar_image_path ? `url(${api.mediaRefUrl(editing.avatar_image_path)})` : undefined }}>
                        {!editing.avatar_image_path && <span>{editing.name?.slice(0, 1) || '?'}</span>}
                      </div>
                      <div>
                        <input type="file" accept="image/*" onChange={(e) => {
                          const f = e.target.files?.[0];
                          if (f && editing.id) {
                            void api.uploadCharacterAvatar(editing.id, f)
                              .then((c) => {
                                const savedFields = { avatar_image_path: c.avatar_image_path };
                                setEditing((prev) => (prev ? { ...prev, ...savedFields } : c));
                                setEditingBaseline((baseline) => mergeCharacterDraftSnapshot(baseline, savedFields));
                                queryClient.invalidateQueries({ queryKey: ['characters'] });
                                showToast('\u5934\u50cf\u5df2\u4e0a\u4f20', 'success');
                              })
                              .catch((error) => showToast(error instanceof Error ? error.message : '\u5934\u50cf\u4e0a\u4f20\u5931\u8d25', 'error'));
                          }
                          e.target.value = '';
                        }} />
                        <div className="hint">保存角色后可以上传头像</div>
                      </div>
                    </div>
                  </div>
                  {editing.id && (
                    <div className="form-group full-row">
                      <label>选一个内置头像</label>
                      <p className="hint" style={{ marginBottom: 8 }}>
                        从内置素材中选择头像；保存后仍可随时更换。
                      </p>
                      <input value={assetSearch} onChange={(e) => setAssetSearch(e.target.value)} placeholder="搜内置头像…" />
                      <div className="stack-list" style={{ maxHeight: 200, overflowY: 'auto', marginTop: 8 }}>
                        {avatarAssetsQuery.data?.map((asset) => {
                          const picked = ((asset.external_url ?? '').trim() || (asset.storage_path ?? '').trim());
                          const thumb = picked;
                          return (
                          <div key={asset.id} className="mini-card" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 12px', cursor: 'pointer' }} onClick={() => { if (picked) setEditing({ ...editing, avatar_image_path: picked }); }}>
                            {thumb ? <div className="avatar-preview" style={{ backgroundImage: `url(${api.mediaRefUrl(thumb)})`, width: 36, height: 36 }} /> : null}
                            <div><strong>{asset.label}</strong><div className="hint">{asset.author || ''}</div></div>
                          </div>
                          );
                        })}
                      </div>
                    </div>
                  )}
                </div>
                </details>
              )}

              {/* ===== API 配置 ===== */}
              {activeTab === 'api' && (
                <details className="form-section" open>
                  <summary>联网配置</summary>
                  <div className="form-grid">
                  {editing.api_base_url === '' && (
                    <div className="notice notice-info full-row" style={{ marginBottom: 12 }}>
                      当前继承「设置 → 公共 API」。角色可以先创建；真正对话前只需在公共设置或本角色任一处配置可用线路。
                    </div>
                  )}
                  <div className="form-group full-row">
                    <label>AI 供应商</label>
                    <select value={BUILTIN_PROVIDERS.some((p) => p.base_url === (editing.api_base_url ?? '')) ? editing.api_base_url ?? '' : '__custom__'} onChange={(e) => {
                      if (e.target.value === '__custom__') { setEditing({ ...editing, api_base_url: 'https://', api_key: '', model_name: '' }); return; }
                      const p = BUILTIN_PROVIDERS.find((item) => item.base_url === e.target.value);
                      if (!p) return;
                      setEditing({
                        ...editing,
                        api_base_url: p.base_url,
                        model_name: p.base_url === editing.api_base_url ? editing.model_name : '',
                        api_key: p.base_url === editing.api_base_url ? editing.api_key : '',
                      });
                    }}>
                      {BUILTIN_PROVIDERS.map((p) => (<option value={p.base_url} key={p.base_url}>{p.label}</option>))}
                      <option value="__custom__">自定义</option>
                    </select>
                    <div className="hint">选择服务商填入地址，再填写该平台的 Key 与模型；自定义接口可直接修改下面字段。</div>
                  </div>
                  <div className="form-group full-row">
                    <label>角色独立接口地址（可选）</label>
                    <input value={editing.api_base_url ?? ''} onChange={(e) => setEditing({ ...editing, api_base_url: e.target.value })} placeholder="留空则继承公共接口" maxLength={255} />
                    <div className="hint">只有这个角色需要不同线路时才填写。</div>
                  </div>
                  <div className="form-group full-row">
                    <label>角色独立模型（可选）</label>
                    <input value={editing.model_name ?? ''} onChange={(e) => setEditing({ ...editing, model_name: e.target.value })} placeholder="留空则继承公共模型" maxLength={120} />
                    {Boolean(currentProvider?.models.length) && <div className="hint">模型示例：{currentProvider?.models.join('、')}</div>}
                    {!currentProvider && editing.api_base_url && <div className="hint">输入你想使用的模型名称（如 deepseek-chat、gpt-4o）</div>}
                  </div>
                  <div className="form-group full-row">
                    <label>角色独立接口密钥（可选）</label>
                    <div style={{ position: 'relative' }}>
                      <input
                        value={editing.api_key ?? ''}
                        onChange={(e) => setEditing({ ...editing, api_key: e.target.value })}
                        placeholder="留空则继承公共 Key"
                        maxLength={255}
                        type={editing.api_key?.startsWith('sk-') ? 'password' : 'text'}
                      />
                    </div>
                    <div className="hint">
                      留空使用「设置 → 公共 API」；填写后仅覆盖这个角色。密钥保存在本机。
                    </div>
                    {editing.api_key && editing.api_key.length > 0 && editing.api_key.length < 10 && (
                      <div className="field-error">密钥长度过短，请检查是否填写正确</div>
                    )}
                  </div>
                  {charTextProbePayload && (
                    <div className="form-group full-row">
                      <ApiProbePanel
                        channel="text"
                        baseUrl={charTextProbePayload.base}
                        apiKey={charTextProbePayload.key}
                        model={charTextProbePayload.model}
                        characterId={editing.id}
                        disabled={!charTextProbePayload.base}
                        disabledReason="请先在公共设置或本角色中填写接口地址"
                        buttonLabel="测试本角色文字 API（含公共 Key 回退）"
                      />
                    </div>
                  )}
                  <div className="form-group full-row">
                    <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={Boolean(editing.think_max_enabled)}
                        onChange={(e) => setEditing({ ...editing, think_max_enabled: e.target.checked })}
                        style={{ marginTop: 4 }}
                      />
                      <span>
                        本角色始终使用思考 / Max 线路
                        <div className="hint" style={{ marginTop: 6 }}>
                          开启后，该角色回复<strong>直接</strong>走思考模型映射，无需在「设置」或「对话页」再开一次。适合固定用 DeepSeek Reasoner 等。
                        </div>
                      </span>
                    </label>
                  </div>
                  <div className="form-group full-row">
                    <label>思考 / Max 模型覆盖（可选）</label>
                    <input
                      value={editing.think_max_model_name ?? ''}
                      onChange={(e) => setEditing({ ...editing, think_max_model_name: e.target.value })}
                      placeholder="留空则用设置里的「思考模型」或内置映射"
                    />
                  </div>
                </div>
                </details>
              )}

              {/* ===== 导入/导出 ===== */}
              {activeTab === 'import' && (
                <div className="form-grid">
                  <div className="form-group full-row">
                    <div className="form-section-title">导入新角色</div>
                    <p className="hint">角色卡和便携包会创建独立角色，当前资料保持不变。</p>
                    <button type="button" className="btn btn-ghost" onClick={() => setImportOpen(true)}>导入角色</button>
                  </div>
                  {editing.id && (
                    <div className="form-group full-row">
                      <div className="form-section-title">导出</div>
                      <div className="button-row" style={{ flexWrap: 'wrap', gap: 8 }}>
                        <button className="btn btn-ghost btn-sm" type="button"
                          disabled={cardExportMutation.isPending || isCharacterDirty}
                          title={isCharacterDirty ? '请先保存角色修改' : '导出完整角色设定与图片'}
                          onClick={() => cardExportMutation.mutate(editing.id!)}>
                          {cardExportMutation.isPending ? '正在导出…' : '导出 PNG 角色卡'}
                        </button>
                        {isCharacterDirty && <span className="hint">保存修改后可导出 PNG 角色卡</span>}
                        <button className="btn btn-ghost btn-sm" type="button" onClick={() => api.downloadCharacterPortable(editing.id!, 'json', 'raw').catch((e) => showToast(String(e), 'error'))}>便携 JSON</button>
                        <button className="btn btn-ghost btn-sm" type="button" onClick={() => api.downloadCharacterPortable(editing.id!, 'txt', 'raw').catch((e) => showToast(String(e), 'error'))}>便携 TXT</button>
                        <button className="btn btn-ghost btn-sm" type="button" onClick={() => api.downloadCharacterPortable(editing.id!, 'docx', 'raw').catch((e) => showToast(String(e), 'error'))}>便携 DOCX</button>
                      </div>
                      <div className="button-row" style={{ flexWrap: 'wrap', gap: 8, marginTop: 8 }}>
                        <span className="hint" style={{ marginRight: 8 }}>智能摘要后导出（需已配置全局文字访问密钥）：</span>
                        <button
                          className="btn btn-primary btn-sm"
                          type="button"
                          disabled={!hasPublicTextKey}
                          title={hasPublicTextKey ? '' : '请先在设置页填写全局文字访问密钥'}
                          onClick={() => api.downloadCharacterPortable(editing.id!, 'json', 'summary').catch((e) => showToast(String(e), 'error'))}
                        >摘要 JSON</button>
                        <button
                          className="btn btn-primary btn-sm"
                          type="button"
                          disabled={!hasPublicTextKey}
                          onClick={() => api.downloadCharacterPortable(editing.id!, 'txt', 'summary').catch((e) => showToast(String(e), 'error'))}
                        >摘要 TXT</button>
                        <button
                          className="btn btn-primary btn-sm"
                          type="button"
                          disabled={!hasPublicTextKey}
                          onClick={() => api.downloadCharacterPortable(editing.id!, 'docx', 'summary').catch((e) => showToast(String(e), 'error'))}
                        >摘要 DOCX</button>
                      </div>
                    </div>
                  )}
                  <div className="form-group full-row">
                    <div className="separator" />
                    <div className="form-section-title">从 TXT / DOCX 抽取人物设定</div>
                    <div className="form-row">
                      <div className="form-group">
                        <label>源文件名</label>
                        <input value={sourceFilename} onChange={(e) => setSourceFilename(e.target.value)} />
                      </div>
                    </div>
                    <textarea rows={8} value={sourceText} onChange={(e) => setSourceText(e.target.value)} placeholder="粘贴角色的原始设定文本，或先选择 .txt / .md / .docx 文件……" />
                    <input
                      type="file"
                      accept=".txt,.md,.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                      onChange={async (e) => {
                        const f = e.target.files?.[0];
                        if (!f) return;
                        setSourceFilename(f.name);
                        const lower = f.name.toLowerCase();
                        if (lower.endsWith('.docx')) {
                          try {
                            const { text } = await api.extractDocxPlainText(f);
                            setSourceText(text);
                            showToast('已从 DOCX 抽取正文，可继续点「抽取人物卡」', 'success');
                          } catch (err) {
                            showToast(String(err), 'error');
                          }
                        } else {
                          setSourceText(await f.text());
                        }
                        e.target.value = '';
                      }}
                    />
                    <button
                      className="btn btn-primary btn-sm"
                      type="button"
                      onClick={() => importProfileMutation.mutate()}
                      disabled={importProfileMutation.isPending || isCharacterDirty || !sourceText.trim()}
                      title={isCharacterDirty ? '请先保存当前角色修改' : !sourceText.trim() ? '请先粘贴设定文本或选择文件' : ''}
                      style={{ marginTop: 8 }}
                    >
                      {importProfileMutation.isPending ? '抽取中...' : '抽取人物卡（合并到当前角色设定）'}
                    </button>
                    {profileQuery.data && (
                      <div className="mini-card" style={{ marginTop: 8 }}>
                        <strong>已抽取人物卡</strong>
                        <small>{new Date(profileQuery.data.extracted_at).toLocaleString('zh-CN')}</small>
                        <pre className="json-preview">{JSON.stringify(profileQuery.data.character_card_json, null, 2)}</pre>
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* ===== 语音绑定 + TTS 配置 + 录音 ===== */}
              {activeTab === 'voice' && (
                <div className="form-grid character-edit-form">
                  <div className="form-group full-row">
                    <div className="form-section-title"><UiIcon name="volume" />声色配置模式</div>
                    <div className="button-row" style={{ gap: 8, marginTop: 8 }}>
                      <button type="button" className={`btn ${voiceMode === 'builtin' ? 'btn-primary' : 'btn-ghost'} btn-sm`} onClick={() => setVoiceMode('builtin')}>免费内置 (Edge TTS)</button>
                      <button type="button" className={`btn ${voiceMode === 'clone' ? 'btn-primary' : 'btn-ghost'} btn-sm`} onClick={() => setVoiceMode('clone')}>克隆声色 (需上传音频)</button>
                      <button type="button" className={`btn ${voiceMode === 'external' ? 'btn-primary' : 'btn-ghost'} btn-sm`} onClick={() => setVoiceMode('external')}>外部 API (需配置密钥)</button>
                    </div>
                  </div>
                  {/* ── 声线绑定 ── */}
                  <div className="form-group full-row">
                    <label>绑定声线</label>
                    <select value={editing.voice_profile_id ?? ''} onChange={(e) => setEditing({ ...editing, voice_profile_id: e.target.value ? Number(e.target.value) : null })}>
                      <option value="">不绑定（使用默认语音）</option>
                      {voicesQuery.data?.map((v) => (<option value={v.id} key={v.id}>{v.name}</option>))}
                    </select>
                  </div>

                  {/* ── TTS 服务配置（每个角色独立） ── */}
                  <div className="form-group full-row">
                    <div className="separator" />
                    <div className="form-section-title"><UiIcon name="volume" />TTS 语音服务配置</div>
                    <div className="hint" style={{ marginBottom: 8 }}>每个角色可以配置独立的语音 AI 服务。未配置时使用全局默认。</div>
                  </div>
                  <div className="form-group full-row">
                    <label>常用 TTS 线路（快速填写）</label>
                    <div className="hint" style={{ marginBottom: 8 }}>
                      路由由下方「语音 API 地址 / 密钥」决定；不再使用单独的「供应商」枚举字段（与 Android 审计一致）。
                    </div>
                    <div className="button-row" style={{ flexWrap: 'wrap', gap: 6 }}>
                      {(
                        [
                          ['edge_tts', 'Edge（免费）', '', 'zh-CN-XiaoxiaoNeural'],
                          ['xtts_v2', '本地 XTTS', 'http://127.0.0.1:8011', 'xtts_v2'],
                          ['volcengine', '火山 TTS', 'https://openspeech.bytedance.com/api/v1/tts', 'BV700_streaming'],
                          ['fish_audio', 'Fish Audio', 'https://api.fish.audio/v1', 's2-pro'],
                          ['azure_tts', 'Azure TTS', 'https://eastus.tts.speech.microsoft.com/cognitiveservices/v1', 'zh-CN-XiaoxiaoNeural'],
                        ] as const
                      ).map(([id, label, url, model]) => (
                        <button
                          key={id}
                          type="button"
                          className="btn btn-ghost btn-sm"
                          onClick={() =>
                            setEditing({
                              ...editing,
                              voice_provider: '',
                              voice_api_base_url: url,
                              voice_model: model,
                            })
                          }
                        >
                          {label}
                        </button>
                      ))}
                    </div>
                  </div>
                  {(voiceMode === 'external' || voiceMode === 'clone') && (
                    <>
                      <div className="form-group full-row">
                        <label>语音 API 地址</label>
                        <input value={editing.voice_api_base_url || ''} onChange={(e) => setEditing({ ...editing, voice_api_base_url: e.target.value })} placeholder="如 https://openspeech.bytedance.com/api/v1/tts" />
                      </div>
                      <div className="form-group full-row">
                        <label>语音 API Key</label>
                        <input type="password" value={editing.voice_api_key || ''} onChange={(e) => setEditing({ ...editing, voice_api_key: e.target.value })} placeholder="填写语音服务的 API Key" />
                        <div className="hint">密钥只保存在本机，不会上传</div>
                      </div>
                    </>
                  )}
                  <div className="form-group full-row">
                    <label>语音模型 / 声线 ID</label>
                    <input value={editing.voice_model || ''} onChange={(e) => setEditing({ ...editing, voice_model: e.target.value })} placeholder="如 BV700_streaming 或 zh-CN-XiaoxiaoNeural" />
                    <div className="hint">不同供应商的模型名不同，选择供应商后会自动填入推荐值</div>
                  </div>

                  {charVoiceSttProbePayload && (
                    <div className="form-group full-row">
                      <div className="separator" />
                      <div className="form-section-title"><UiIcon name="microphone" />语音输入转文字（麦克风）</div>
                      <div className="hint" style={{ marginBottom: 8 }}>
                        使用「设置 → 公共 API → 语音转写」线路；根地址可回退到配图/公共对话根。<strong>不使用</strong>本角色「对话 API Key」。与上方「语音模型」（朗读 TTS）不是同一套接口。
                      </div>
                      <ApiProbePanel
                        channel="voice"
                        baseUrl={charVoiceSttProbePayload.base}
                        apiKey={charVoiceSttProbePayload.key}
                        model={charVoiceSttProbePayload.model}
                        characterId={editing.id}
                        disabled={!charVoiceSttProbePayload.base}
                        disabledReason="请先在设置中填写语音转写根地址，或配置公共配图/对话根地址"
                        buttonLabel="测试语音转写（Whisper 兼容）"
                      />
                    </div>
                  )}

                  {/* ── 参考音频上传 + 录音 ── */}
                  <div className="form-group full-row">
                    <div className="separator" />
                    <div className="form-section-title"><UiIcon name="attachment" />参考音频</div>
                    <div className="hint" style={{ marginBottom: 8 }}>上传或录制一段参考音频，用于声音克隆定制角色专属声线</div>
                  </div>
                  <div className="form-row">
                    <div className="form-group">
                      <label>声色名称</label>
                      <input value={voiceName} onChange={(e) => setVoiceName(e.target.value)} placeholder="例如：温柔女声" />
                    </div>
                  </div>
                  <div className="form-group">
                    <label>声色描述</label>
                    <textarea rows={3} value={voiceDescription} onChange={(e) => setVoiceDescription(e.target.value)} placeholder="描述这个声线的特点…" />
                  </div>
                  <div className="form-group">
                    <label>上传音频文件</label>
                    <input type="file" accept=".wav,.mp3,.m4a,.flac" onChange={(e) => setVoiceFile(e.target.files?.[0] ?? null)} />
                  </div>
                  <div className="form-group">
                    <label>或者在线录音</label>
                    <div className="button-row" style={{ gap: 8 }}>
                      {!isRecording ? (
                        <button className="btn btn-primary btn-sm" type="button" onClick={async () => {
                          try {
                            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                            const recorder = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus' });
                            audioChunksRef.current = [];
                            recorder.ondataavailable = (e) => { if (e.data.size > 0) audioChunksRef.current.push(e.data); };
                            recorder.onstop = () => {
                              stream.getTracks().forEach(t => t.stop());
                              const blob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
                              const file = new File([blob], `recording_${Date.now()}.webm`, { type: 'audio/webm' });
                              setVoiceFile(file);
                              showToast('录音完成，已将音频设为参考音频', 'success');
                            };
                            mediaRecorderRef.current = recorder;
                            recorder.start();
                            setIsRecording(true);
                            showToast('开始录音…', 'info');
                          } catch (err) {
                            showToast(`录音失败：${err}`, 'error');
                          }
                        }}><UiIcon name="microphone" /><span>开始录音</span></button>
                      ) : (
                        <button className="btn btn-ghost btn-sm btn-danger" type="button" onClick={() => {
                          mediaRecorderRef.current?.stop();
                          setIsRecording(false);
                        }}><UiIcon name="stop" /><span>停止录音</span></button>
                      )}
                      {voiceFile && <span style={{ fontSize: '0.78rem', color: 'var(--text-2)' }}>已选择：{voiceFile.name}</span>}
                    </div>
                  </div>
                  <button className="btn btn-primary btn-sm" type="button" onClick={() => uploadVoiceMutation.mutate()} disabled={uploadVoiceMutation.isPending}>
                    {uploadVoiceMutation.isPending ? '上传中...' : '上传并生成声色档案'}
                  </button>

                  {/* ── 可用声线列表 + 试听 ── */}
                  <div className="form-group full-row">
                    <div className="separator" />
                    <div className="form-section-title">可用声线</div>
                    <div className="stack-list">
                      {voicesQuery.data?.map((v) => (
                        <div key={v.id} className="mini-card" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <div style={{ flex: 1 }}>
                            <strong>{v.name}</strong>
                            <small>{v.provider}</small>
                            <p>{v.description || ''}</p>
                          </div>
                          <button className="btn btn-ghost btn-sm" type="button" onClick={async () => {
                            try {
                              const blob = await api.fetchVoicePreview(v.id);
                              const url = URL.createObjectURL(blob);
                              const audio = new Audio(url);
                              audio.onended = () => URL.revokeObjectURL(url);
                              audio.onerror = () => {
                                URL.revokeObjectURL(url);
                                showToast('\u64ad\u653e\u5931\u8d25', 'error');
                              };
                              await audio.play();
                            } catch { showToast('试听请求失败', 'error'); }
                          }}><UiIcon name="volume" /><span>试听</span></button>
                          <button className="btn btn-primary btn-sm" type="button" onClick={() => {
                            setEditing({ ...editing, voice_profile_id: v.id });
                            showToast(`已绑定「${v.name}」`, 'success');
                          }}>绑定</button>
                        </div>
                      ))}
                      {voicesQuery.data?.length === 0 && <div style={{ color: 'var(--text-2)', fontSize: '0.82rem', padding: 8 }}>暂无可用声线</div>}
                    </div>
                  </div>
                </div>
              )}

              {/* ===== 图片生成 ===== */}
              {activeTab === 'image' && (
                <div className="form-grid character-edit-form">
                  <div className="form-group full-row">
                    <div className="form-section-title">列表与网格封面图</div>
                    <p className="hint">侧栏与网格优先展示竖版封面图，没有则用头像。配图线路与对话线路分离：未单独填写生图密钥时，依次使用设置中的配图 Key、公共对话 Key（不会使用本页「对话 API Key」）。</p>
                    {editing.id ? (
                      <>
                        <label>可选补充画面说明</label>
                        <input
                          value={cardGenHint}
                          onChange={(e) => setCardGenHint(e.target.value)}
                          placeholder="例如：冬季校服、柔和顶光、半身"
                          style={{ width: '100%', maxWidth: 480 }}
                        />
                        <div style={{ marginTop: 10, display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
                          <button
                            type="button"
                            className="btn btn-primary btn-sm"
                            disabled={generateCardImageMutation.isPending}
                            onClick={() => generateCardImageMutation.mutate()}
                          >
                            {generateCardImageMutation.isPending ? '生成中…' : '生成列表封面图'}
                          </button>
                          {(editing.card_image_path || '').trim() ? (
                            <div
                              className="character-card-thumb-preview"
                              style={{
                                width: 96,
                                aspectRatio: '2 / 3',
                                borderRadius: 8,
                                backgroundColor: editing.avatar_color ?? '#334155',
                                backgroundImage: `url(${api.mediaRefUrl(editing.card_image_path)})`,
                                backgroundSize: 'cover',
                                backgroundPosition: 'center',
                                border: '1px solid var(--line)',
                              }}
                              title="当前卡图"
                            />
                          ) : null}
                        </div>
                      </>
                    ) : (
                      <p className="hint" style={{ marginTop: 8 }}>请先保存角色后再生成封面图。</p>
                    )}
                  </div>
                  <div className="form-group full-row">
                    <label>
                      <input type="checkbox" checked={editing.image_gen_enabled ?? false} onChange={(e) => setEditing({ ...editing, image_gen_enabled: e.target.checked })} />
                      {' '}启用对话内图片生成
                    </label>
                    <div className="hint">开启后，AI 可在回复中插入 [生成图片:描述] 标签自动生成图片</div>
                  </div>
                  {editing.image_gen_enabled && (
                    <>
                      <div className="form-group full-row">
                        <label>生图模型</label>
                        <select value={editing.image_gen_model ?? 'dall-e-3'} onChange={(e) => setEditing({ ...editing, image_gen_model: e.target.value })}>
                          <option value="dall-e-3">DALL-E 3</option>
                          <option value="dall-e-2">DALL-E 2</option>
                          <option value="stable-diffusion">Stable Diffusion</option>
                        </select>
                      </div>
                      <div className="form-group full-row">
                        <label>生图 API 地址（为空则复用主 API）</label>
                        <input value={editing.image_gen_base_url ?? ''} onChange={(e) => setEditing({ ...editing, image_gen_base_url: e.target.value })} placeholder="留空则用设置中的配图根地址，再空则用公共对话根地址" />
                      </div>
                      <div className="form-group full-row">
                        <label>生图 API Key（为空则复用主 Key）</label>
                        <input type="password" value={editing.image_gen_api_key ?? ''} onChange={(e) => setEditing({ ...editing, image_gen_api_key: e.target.value })} placeholder="留空则用设置中的配图 Key，再空则用公共对话 Key" />
                        <div className="hint">密钥只保存在本机</div>
                      </div>
                      {charImageProbePayload && (
                        <div className="form-group full-row">
                          <ApiProbePanel
                            channel="image"
                            baseUrl={charImageProbePayload.base}
                            apiKey={charImageProbePayload.key}
                            model={charImageProbePayload.model}
                            characterId={editing.id}
                            disabled={!charImageProbePayload.base}
                            disabledReason="请先填写主对话接口地址或生图专用地址"
                            buttonLabel="测试本角色生图 API（含主 Key / 公共 Key 回退）"
                          />
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}

              {/* ===== 高级参数 ===== */}
              {activeTab === 'advanced' && (
                <div className="form-grid">
                  <div className="form-group">
                    <label>创意程度（0~2）</label>
                    <input type="number" step="0.1" min="0" max="2" value={editing.temperature ?? 0.9} onChange={(e) => {
                      const value = Number(e.target.value);
                      if (value < 0 || value > 2) return;
                      setEditing({ ...editing, temperature: value });
                    }} />
                    <div className="hint">越低越稳定，越高越有创意。推荐 0.7~1.2</div>
                  </div>
                  <div className="form-group">
                    <label>一次最多写多少字</label>
                    <input type="number" min={64} max={32768} value={editing.max_tokens ?? 1200} onChange={(e) => setEditing({ ...editing, max_tokens: Number(e.target.value) })} />
                    <div className="hint">每次回复的字数上限。推荐 512~2048</div>
                    {editing.max_tokens !== undefined && editing.max_tokens < 64 && <div className="field-error">最少 64 个字</div>}
                  </div>
                  <div className="form-group"><label>核心采样 (Top-P) — {editing.top_p ?? 1.0}</label><input type="range" step="0.05" min="0" max="1.0" value={editing.top_p ?? 1.0} onChange={(e) => setEditing({ ...editing, top_p: Number(e.target.value) })} /><div className="hint">0.9-1.0 适合创意写作</div></div>
                  <div className="form-group"><label>候选词数 (Top-K) — {editing.top_k ?? 0}</label><input type="range" step="1" min="0" max="100" value={editing.top_k ?? 0} onChange={(e) => setEditing({ ...editing, top_k: Number(e.target.value) })} /><div className="hint">0 表示不限制</div></div>
                  <div className="form-group"><label>重复词惩罚 — {editing.frequency_penalty ?? 0.0}</label><input type="range" step="0.1" min="-2.0" max="2.0" value={editing.frequency_penalty ?? 0.0} onChange={(e) => setEditing({ ...editing, frequency_penalty: Number(e.target.value) })} /><div className="hint">越高越避免重复用词</div></div>
                  <div className="form-group"><label>话题重复惩罚 — {editing.presence_penalty ?? 0.0}</label><input type="range" step="0.1" min="-2.0" max="2.0" value={editing.presence_penalty ?? 0.0} onChange={(e) => setEditing({ ...editing, presence_penalty: Number(e.target.value) })} /><div className="hint">越高越倾向聊新话题</div></div>
                  <div className="form-group"><label>复读惩罚 — {editing.repetition_penalty ?? 1.0}</label><input type="range" step="0.05" min="0.1" max="2.0" value={editing.repetition_penalty ?? 1.0} onChange={(e) => setEditing({ ...editing, repetition_penalty: Number(e.target.value) })} /><div className="hint">1.0 表示无额外惩罚</div></div>
                </div>
              )}
            </div>

            <div className="button-row full-row" style={{ marginTop: 16, padding: '12px 0', borderTop: '1px solid var(--line)' }}>
              <button className="btn btn-primary" type="submit" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? '保存中...' : editing.id ? '保存修改' : '创建角色'}
              </button>
              <button className="btn btn-ghost" type="button" onClick={() => { void closeEditor(); }}>取消</button>
            </div>
          </form>
        )}
      </main>
      {UndoToast}
    </div>
  );
}
