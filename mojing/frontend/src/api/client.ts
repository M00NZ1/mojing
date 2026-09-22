import type {
  ModelPlatform,
  ModelCatalog,
  ModelSelection,
  ModelChoice,
  AssetItem,
  CostData,
  UsageModelsResponse,
  UsageProvidersResponse,
  UsageRecordsResponse,
  ApiChannel,
  Character,
  CharacterProfile,
  CharacterTemplate,
  EncyclopediaEntry,
  EncyclopediaEntryDetail,
  JobRun,
  WorldJobSummary,
  MacroItem,
  MemoryCorrection,
  MemorySegment,
  Message,
  MessageSearchHit,
  MessagePage,
  MessageWindowPage,
  LocalConfig,
  Participant,
  ProviderPreset,
  ProviderOption,
  PublicApiProbeChannel,
  PublicApiProbeResult,
  PurposeOption,
  PromptTemplate,
  PromptTemplateRevision,
  SessionCharacterState,
  SessionEventNode,
  SessionBranch,
  SessionItem,
  SessionWorld,
  SpeakerPlan,
  SystemStatus,
  TokenUsageStats,
  VoiceServiceConfig,
  VoiceClip,
  VoiceProfile,
  WorldEncyclopedia,
  WorldLibrary,
  WorldEncyclopediaSavePayload,
  WorldImportResult,
  WorldTemplateBundle,
  WorldTemplateBundlePreview,
  WorldTemplatePackage,
  Expression,
  WorldGenerationResult,
  WorldLoreEntry,
  WorldQualityReport,
  WorldTemplate,
  StoryWritingPayload,
  StoryWritingResult,
  StoryRequestState,
} from '../types';

import { friendlyFetchError } from '../utils/userFacingError';
const API_BASE = (typeof import.meta !== 'undefined' && (import.meta as any).env?.VITE_API_BASE) || 'http://127.0.0.1:8000/api';
const VITE_STORAGE = typeof import.meta !== 'undefined' ? (import.meta as any).env?.VITE_STORAGE_BASE : undefined;
const STORAGE_PREFIX = VITE_STORAGE !== undefined ? VITE_STORAGE : 'http://127.0.0.1:8000';

/** 解析 FastAPI / 通用 JSON 错误体，便于 Toast 展示 */
function formatApiErrorPayload(payload: unknown, status: number): string {
  if (payload && typeof payload === 'object' && 'detail' in payload) {
    const detail = (payload as { detail: unknown }).detail;
    if (typeof detail === 'string' && detail.trim()) return detail.trim();
    if (Array.isArray(detail)) {
      const parts = detail
        .map((item) => {
          if (item && typeof item === 'object' && 'msg' in item) {
            const loc = (item as { loc?: unknown[] }).loc;
            const locStr = Array.isArray(loc) ? loc.filter((x) => x !== 'body').join('.') : '';
            const msg = String((item as { msg?: string }).msg ?? '').trim();
            if (locStr && msg) return `${locStr}：${msg}`;
            return msg;
          }
          return String(item);
        })
        .filter(Boolean);
      if (parts.length) return parts.join('；');
    }
  }
  return `请求失败（HTTP ${status}）`;
}

function mapStreamDoneToProbeResult(ev: Record<string, unknown>): PublicApiProbeResult {
  const attempts = Array.isArray(ev.attempts)
    ? (ev.attempts as Record<string, unknown>[]).map((a) => ({
        base_url: String(a.base_url ?? ''),
        ok: Boolean(a.ok),
        error: (a.error as string | null) ?? null,
        detail: (a.detail as string | null) ?? null,
      }))
    : [];
  return {
    ok: Boolean(ev.ok),
    channel: String(ev.channel ?? ''),
    error: (ev.error as string | null) ?? null,
    detail: (ev.detail as string | null) ?? null,
    warnings: (ev.warnings as string[]) ?? [],
    meta: (ev.meta as Record<string, unknown> | null) ?? null,
    used_base_url: (ev.used_base_url as string | null | undefined) ?? null,
    attempts,
  };
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const { headers: optionHeaders, ...requestOptions } = options ?? {};
  const headers = new Headers(optionHeaders);
  const isFormDataBody = typeof FormData !== 'undefined' && requestOptions.body instanceof FormData;
  const hasBody = requestOptions.body !== undefined && requestOptions.body !== null;
  if (hasBody && !isFormDataBody && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...requestOptions,
      headers,
    });
  } catch (e) {
    if (e instanceof Error && e.name === 'AbortError') throw e;
    throw new Error(friendlyFetchError(e));
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(formatApiErrorPayload(payload, response.status));
  }
  return response.json() as Promise<T>;
}


async function downloadFile(path: string, fallbackFilename: string, options?: RequestInit): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, options);
  } catch (e) {
    throw new Error(friendlyFetchError(e));
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(formatApiErrorPayload(payload, response.status));
  }

  const blob = await response.blob();
  const disposition = response.headers.get('content-disposition') ?? '';
  const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plainName = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  const filename = encodedName
    ? decodeURIComponent(encodedName)
    : (plainName || fallbackFilename);
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export const api = {
  getWorldLibrary() { return request<WorldLibrary>('/worlds/library'); },
  promoteWorld(templateId: string, updatedAt: string, sourceHash: string) {
    return request<{ encyclopedia_id: number; name: string }>(`/worlds/templates/${encodeURIComponent(templateId)}/promote`, {
      method: 'POST', body: JSON.stringify({ updated_at: updatedAt, source_hash: sourceHash }),
    });
  },
  getStarterCatalog() {
    return request<{ available: boolean; title?: string; summary?: string; template_id?: string; encyclopedia_id?: number; characters?: { id: number; name: string }[]; retired: Record<string, string[]> }>('/system/starter-catalog');
  },
  restoreStarterCatalog() { return request('/system/starter-catalog/restore', { method: 'POST' }); },
  getModelPlatforms() { return request<ModelCatalog>('/system/model-platforms'); },
  saveModelPlatform(platform: ModelPlatform) {
    return request<ModelCatalog>(`/system/model-platforms/${encodeURIComponent(platform.id)}`, { method: 'PUT', body: JSON.stringify(platform) });
  },
  setDefaultModelPlatform(id: string) {
    return request<ModelCatalog>(`/system/model-platforms/${encodeURIComponent(id)}/default`, { method: 'POST' });
  },
  discoverModels(platform: ModelPlatform, signal?: AbortSignal) {
    return request<{ models: string[] }>('/system/model-platforms/discover', { method: 'POST', body: JSON.stringify({ ...platform, platform_id: platform.id }), signal });
  },
  getModelChoice(sessionId: number) { return request<ModelChoice>(`/sessions/${sessionId}/model-choice`); },
  setModelChoice(sessionId: number, selection: ModelSelection | null) {
    return request<ModelChoice>(`/sessions/${sessionId}/model-choice`, { method: 'PUT', body: JSON.stringify({ selection }) });
  },
  storageUrl(path: string) { return `${STORAGE_PREFIX}${path}`; },
  /** 头像/卡图等：支持 `https?://` 外链或 storage 相对路径（自动加 /storage/） */
  mediaRefUrl(ref: string | null | undefined): string {
    const s = (ref ?? '').trim();
    if (!s) return '';
    if (/^https?:\/\//i.test(s)) return s;
    if (s.startsWith('/storage/')) return `${STORAGE_PREFIX}${s}`;
    return `${STORAGE_PREFIX}/storage/${s}`;
  },
  listAssets(category?: string, query?: string) {
    const search = new URLSearchParams();
    if (category?.trim()) search.set('category', category.trim());
    if (query?.trim()) search.set('q', query.trim());
    const suffix = search.toString() ? `?${search.toString()}` : '';
    return request<AssetItem[]>(`/assets${suffix}`);
  },
  listSessions(query?: string) {
    const search = new URLSearchParams();
    if (query?.trim()) search.set('q', query.trim());
    const suffix = search.toString() ? `?${search.toString()}` : '';
    return request<SessionItem[]>(`/sessions${suffix}`);
  },
  getSystemStatus() {
    return request<SystemStatus>('/system/status');
  },
  listMacros() {
    return request<MacroItem[]>('/system/macros');
  },
  getLocalConfig() {
    return request<LocalConfig>('/system/local-config');
  },
  updateLocalConfig(payload: LocalConfig & {
    clear_public_text_api_key?: boolean;
    clear_public_image_api_key?: boolean;
    clear_public_voice_api_key?: boolean;
  }) {
    return request<LocalConfig>('/system/local-config', {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },

  probePublicApi(payload: {
    channel: PublicApiProbeChannel;
    base_url?: string | null;
    api_key?: string | null;
    model?: string | null;
    character_id?: number | null;
  }) {
    return request<PublicApiProbeResult>('/system/probe-public-api', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  async probePublicApiStream(
    payload: {
      channel: PublicApiProbeChannel;
      base_url?: string | null;
      api_key?: string | null;
      model?: string | null;
      character_id?: number | null;
    },
    onEvent: (ev: Record<string, unknown>) => void,
  ): Promise<PublicApiProbeResult> {
    let response: Response;
    try {
      response = await fetch(`${API_BASE}/system/probe-public-api/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
      });
    } catch (e) {
      throw new Error(friendlyFetchError(e));
    }
    if (!response.ok) {
      const payloadErr = await response.json().catch(() => ({}));
      throw new Error(formatApiErrorPayload(payloadErr, response.status));
    }
    if (!response.body) {
      throw new Error('服务器未返回流式正文');
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let lastDone: PublicApiProbeResult | null = null;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        const t = line.trim();
        if (!t) continue;
        let ev: Record<string, unknown>;
        try {
          ev = JSON.parse(t) as Record<string, unknown>;
        } catch {
          continue;
        }
        onEvent(ev);
        if (ev.type === 'done') {
          lastDone = mapStreamDoneToProbeResult(ev);
        }
      }
    }
    const tail = buffer.trim();
    if (tail) {
      try {
        const ev = JSON.parse(tail) as Record<string, unknown>;
        onEvent(ev);
        if (ev.type === 'done') {
          lastDone = mapStreamDoneToProbeResult(ev);
        }
      } catch {
        /* ignore trailing garbage */
      }
    }
    if (!lastDone) {
      throw new Error('探测未完成（未收到结束事件）');
    }
    return lastDone;
  },

  getVoiceServiceConfig() {
    return request<VoiceServiceConfig>('/system/voice-service-config');
  },
  updateVoiceServiceConfig(payload: VoiceServiceConfig & { clear_external_api_key?: boolean }) {
    return request<VoiceServiceConfig>('/system/voice-service-config', {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  createSession(title: string) {
    return request<SessionItem>('/sessions', {
      method: 'POST',
      body: JSON.stringify({ title }),
    });
  },
  createSessionWithConfig(payload: {
    title: string;
    template_id?: string;
    encyclopedia_id?: number | null;
    gameplay_mode?: string;
    narrator_enabled?: boolean;
    narrator_name?: string;
    choice_generation_enabled?: boolean;
    max_choice_count?: number;
    anti_cheat_enabled?: boolean;
    /** 创建后写入参与者；至少一项时与 Android 新建对话行为一致 */
    initial_character_ids?: number[];
  }) {
    return request<SessionItem>('/sessions', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  createStorySession(payload: StoryWritingPayload, signal?: AbortSignal) {
    return request<StoryWritingResult>('/story-simulations', {
      method: 'POST',
      body: JSON.stringify(payload),
      signal,
    });
  },
  getStoryRequestState(requestId: string, signal?: AbortSignal) {
    return request<StoryRequestState>(`/story-simulations/requests/${encodeURIComponent(requestId)}`, { signal });
  },
  discardStoryDraft(requestId: string) {
    return request<{ deleted: boolean }>(`/story-simulations/requests/${encodeURIComponent(requestId)}/draft`, { method: 'DELETE' });
  },
  getSession(sessionId: number) {
    return request<SessionItem>(`/sessions/${sessionId}`);
  },
  addUserMessage(sessionId: number, content: string, branchId = 'main') {
    return request<Message>(`/sessions/${sessionId}/user-message`, {
      method: 'POST',
      body: JSON.stringify({ content, branch_id: branchId }),
    });
  },
  sessionTimeline(sessionId: number) {
    return request<{ entries: { title: string; description: string; entry_type: string; timestamp: string; era: string }[]; gameplay_mode: string }>(`/sessions/${sessionId}/timeline`);
  },
  addSessionTimelineEntry(sessionId: number, payload: { title: string; description: string; entry_type: string; era: string }) {
    return request<{ entries: { title: string; description: string; entry_type: string; timestamp: string; era: string }[]; gameplay_mode: string }>(`/sessions/${sessionId}/timeline`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  deleteSessionTimelineEntry(sessionId: number, entryIndex: number) {
    return request<{ ok: boolean }>(`/sessions/${sessionId}/timeline/${entryIndex}`, {
      method: 'DELETE',
    });
  },

  voiceTranscribe(formData: FormData) {
    return request('/voices/transcribe', { method: 'POST', body: formData });
  },

  costs(params?: { days?: number }) {
    const search = new URLSearchParams();
    if (params?.days) search.set('days', String(params.days));
    const suffix = search.toString() ? `?${search.toString()}` : '';
    return request<CostData>(`/costs${suffix}`);
  },
  costProviders(params: { days: number; status: 'all' | 'success' | 'failed' }) {
    const search = new URLSearchParams({ days: String(params.days), status: params.status });
    return request<UsageProvidersResponse>(`/costs/providers?${search}`);
  },
  costModels(provider: string, params: { days: number; status: 'all' | 'success' | 'failed' }) {
    const search = new URLSearchParams({ days: String(params.days), status: params.status });
    return request<UsageModelsResponse>(`/costs/providers/${encodeURIComponent(provider)}/models?${search}`);
  },
  costModelRecords(provider: string, model: string, params: { days: number; status: 'all' | 'success' | 'failed'; beforeId?: number }) {
    const search = new URLSearchParams({ days: String(params.days), status: params.status, model, limit: '50' });
    if (params.beforeId) search.set('before_id', String(params.beforeId));
    return request<UsageRecordsResponse>(`/costs/providers/${encodeURIComponent(provider)}/records?${search}`);
  },
  updateSession(sessionId: number, payload: { title?: string; think_max_enabled?: boolean }) {
    return request<SessionItem>(`/sessions/${sessionId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  deleteSession(sessionId: number) {
    return request<{ ok: boolean }>(`/sessions/${sessionId}`, {
      method: 'DELETE',
    });
  },
  getMessages(sessionId: number, cursor?: number, branchId = 'main') {
    const search = new URLSearchParams({ limit: '40' });
    if (cursor) search.set('cursor', String(cursor));
    if (branchId) search.set('branch_id', branchId);
    return request<MessagePage>(`/sessions/${sessionId}/messages?${search.toString()}`);
  },
  getMessageWindow(sessionId: number, anchorId: number, branchId = 'main', radius = 20) {
    const search = new URLSearchParams({ anchor_id: String(anchorId), radius: String(radius) });
    if (branchId) search.set('branch_id', branchId);
    return request<MessageWindowPage>(`/sessions/${sessionId}/messages/window?${search.toString()}`);
  },
  searchMessagePage(sessionId: number, query: string, branchId = 'main', before?: number, signal?: AbortSignal, advanceIndex = true) {
    const search = new URLSearchParams({ q: query, branch_id: branchId, limit: '25', advance_index: String(advanceIndex) });
    if (before !== undefined) search.set('before', String(before));
    return request<{ items: MessageSearchHit[]; next_cursor: number | null; total_count: number | null; index: { ready: boolean; indexed_count: number } }>(`/sessions/${sessionId}/messages/search-page?${search}`, { signal });
  },
  rebuildMessageSearchIndex(sessionId: number) { return request(`/sessions/${sessionId}/messages/search-index/rebuild`, { method: 'POST' }); },
  searchMessages(sessionId: number, query: string, limit = 40, branchId = 'main') {
    const search = new URLSearchParams({ q: query, limit: String(limit) });
    if (branchId) search.set('branch_id', branchId);
    return request<MessageSearchHit[]>(`/sessions/${sessionId}/messages/search?${search.toString()}`);
  },
  updateMessage(sessionId: number, messageId: number, content: string, branchId = 'main') {
    return request<Message>(`/sessions/${sessionId}/messages/${messageId}`, {
      method: 'PUT',
      body: JSON.stringify({ content, branch_id: branchId }),
    });
  },
  messageDeletionImpact(sessionId: number, messageId: number, branchId: string, signal?: AbortSignal) {
    return request<{ can_delete: boolean; reason: string; reference_count: number; memory_segments_removed?: number; memory_events_removed?: number; summary_reset?: boolean; branches: { branch_id: string; label: string; is_checkpoint: boolean }[] }>(`/sessions/${sessionId}/messages/${messageId}/deletion-impact?branch_id=${encodeURIComponent(branchId)}`, { signal });
  },
  setMessageContext(sessionId: number, messageId: number, branchId: string, include: boolean, expected: boolean) {
    return request<{ id: number; include_in_context: boolean; changed: boolean }>(`/sessions/${sessionId}/messages/${messageId}/context`, {
      method: 'PUT', body: JSON.stringify({ include_in_context: include, expected_include_in_context: expected, branch_id: branchId }),
    });
  },
  deleteMessage(sessionId: number, messageId: number, branchId?: string) {
    const suffix = branchId === undefined ? '' : `?branch_id=${encodeURIComponent(branchId)}`;
    return request<{ ok: boolean }>(`/sessions/${sessionId}/messages/${messageId}${suffix}`, {
      method: 'DELETE',
    });
  },
  listBookmarks(sessionId: number) {
    return request<{ id: number; session_id: number; message_id: number; note: string; created_at: string }[]>(`/sessions/${sessionId}/bookmarks`);
  },
  toggleBookmark(sessionId: number, messageId: number) {
    return request<{ ok: boolean; bookmarked: boolean }>(`/sessions/${sessionId}/bookmarks`, {
      method: 'POST',
      body: JSON.stringify({ message_id: messageId }),
    });
  },
  listEncyclopedias() {
    return request<WorldEncyclopedia[]>('/encyclopedia');
  },
  saveEncyclopedia(payload: WorldEncyclopediaSavePayload) {
    return request<WorldEncyclopedia>('/encyclopedia', { method: 'POST', body: JSON.stringify(payload) });
  },
  deleteEncyclopedia(id: number) {
    return request<{ ok: boolean }>(`/encyclopedia/${id}`, { method: 'DELETE' });
  },
  bootstrapEncyclopediaTemplate(id: number) {
    return request<{ encyclopedia_id: number; created_count: number; updated_count: number; skipped_count: number }>(`/encyclopedia/${id}/bootstrap-template`, { method: 'POST' });
  },
  importEncyclopediaSources(id: number, payload: {
    sources: Array<{
      title?: string;
      url?: string;
      api_url?: string;
      source_text?: string;
      source_license?: string;
      entry_type?: string;
      tags?: string[];
      trust_level?: string;
      canon_scope?: string;
      canon_conflicts?: string[];
      unknown_fields?: string[];
      is_featured?: boolean;
      verified?: boolean;
      sort_order?: number;
    }>;
    dry_run?: boolean;
    overwrite_existing?: boolean;
    max_extract_chars?: number;
  }) {
    return request<{ encyclopedia_id: number; dry_run: boolean; imported_count: number; skipped_count: number; failed_count: number; items: Array<{ title: string; entry_type: string; status: string; entry_id?: number | null; source_url: string; warnings: string[] }> }>(`/encyclopedia/${id}/import-sources`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  listEncyclopediaEntries(params: { encyclopedia_id?: number; entry_type?: string; q?: string }) {
    const search = new URLSearchParams();
    if (params.encyclopedia_id) search.set('encyclopedia_id', String(params.encyclopedia_id));
    if (params.entry_type) search.set('entry_type', params.entry_type);
    if (params.q) search.set('q', params.q);
    return request<EncyclopediaEntry[]>(`/encyclopedia/entries?${search.toString()}`);
  },
  saveEncyclopediaEntry(payload: {
    id?: number;
    encyclopedia_id: number;
    title: string;
    entry_type: string;
    summary: string;
    content: string;
    cover_image_path?: string;
    tags: string;
    related_entries: string;
    sort_order?: number;
    is_featured?: boolean;
    meta_json?: Record<string, unknown>;
    change_note?: string;
  }) {
    return request<{ id: number }>('/encyclopedia/entries', { method: 'POST', body: JSON.stringify(payload) });
  },
  generateEncyclopediaEntryCoverImage(entryId: number, payload: { prompt_hint?: string; size?: string } = {}) {
    return request<EncyclopediaEntry>(`/encyclopedia/entries/${entryId}/generate-cover-image`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  previewEncyclopediaEntryCoverImage(payload: {
    title?: string;
    entry_type?: string;
    summary?: string;
    prompt_hint?: string;
    size?: string;
  }) {
    return request<{ urls: string[]; revised_prompt: string; error?: string | null }>('/encyclopedia/entries/preview-cover-image', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  persistEncyclopediaEntryCoverFromUrl(payload: { image_url: string }) {
    return request<{ cover_image_path: string }>('/encyclopedia/entries/persist-cover-from-url', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  deleteEncyclopediaEntry(id: number) {
    return request<{ ok: boolean }>(`/encyclopedia/entries/${id}`, { method: 'DELETE' });
  },
  getTokenUsage(sessionId: number, branchId = 'main') {
    const search = new URLSearchParams({ branch_id: branchId });
    return request<TokenUsageStats>(`/sessions/${sessionId}/token-usage?${search.toString()}`);
  },
  listSessionBranches(sessionId: number) {
    return request<SessionBranch[]>(`/sessions/${sessionId}/branches`);
  },
  createSessionBranch(
    sessionId: number,
    payload: { source_message_id: number; branch_id?: string; label?: string; parent_branch_id?: string },
  ) {
    return request<SessionBranch>(`/sessions/${sessionId}/branches`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  openSessionExport(sessionId: number) {
    return downloadFile(`/sessions/${sessionId}/export`, `session_${String(sessionId).padStart(4, '0')}.zip`);
  },
  async importSessionArchive(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    return request<{
      session_id: number;
      title: string;
      message_count: number;
      character_count: number;
      branch_count: number;
    }>('/sessions/import-archive', {
      method: 'POST',
      body: formData,
    });
  },
  async downloadSessionChatHtml(sessionId: number, branchId: string = 'main') {
    const search = new URLSearchParams({ branch_id: branchId });
    const response = await fetch(`${API_BASE}/sessions/${sessionId}/export-chat-html?${search.toString()}`);
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(formatApiErrorPayload(payload, response.status));
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    const safe = branchId.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 80) || 'main';
    a.download = `session_${String(sessionId).padStart(4, '0')}_${safe}.html`;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
  async importTavernChat(sessionId: number, characterId: number, file: File, branchId = 'main') {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('character_id', String(characterId));
    formData.append('branch_id', branchId);
    const response = await fetch(`${API_BASE}/sessions/${sessionId}/import-tavern-chat`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(formatApiErrorPayload(payload, response.status));
    }
    return response.json() as Promise<{ imported: number; branch_id: string }>;
  },
  listParticipants(sessionId: number) {
    return request<Participant[]>(`/sessions/${sessionId}/participants`);
  },
  listCharacterStates(sessionId: number) {
    return request<SessionCharacterState[]>(`/sessions/${sessionId}/character-states`);
  },
  listMemorySegments(sessionId: number, branchId: string) {
    const search = new URLSearchParams({ branch_id: branchId });
    return request<MemorySegment[]>(`/sessions/${sessionId}/memory-segments?${search.toString()}`);
  },
  listMemoryCorrections(sessionId: number, branchId: string) {
    const search = new URLSearchParams({ branch_id: branchId });
    return request<MemoryCorrection[]>(`/sessions/${sessionId}/memory-corrections?${search.toString()}`);
  },
  createMemoryCorrection(sessionId: number, body: { content: string; branch_id: string | null; source_message_id: number | null }) {
    return request<MemoryCorrection>(`/sessions/${sessionId}/memory-corrections`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },
  updateMemoryCorrection(sessionId: number, id: number, body: { content: string; branch_id: string | null; source_message_id: number | null }) {
    return request<MemoryCorrection>(`/sessions/${sessionId}/memory-corrections/${id}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    });
  },
  deleteMemoryCorrection(sessionId: number, id: number) {
    return request<{ ok: boolean }>(`/sessions/${sessionId}/memory-corrections/${id}`, { method: 'DELETE' });
  },
  getSpeakerPlan(sessionId: number, payload: { user_message?: string; max_auto_speakers?: number; branch_id?: string }) {
    return request<SpeakerPlan>(`/sessions/${sessionId}/speaker-plan`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  addParticipant(sessionId: number, characterId: number) {
    return request<Participant[]>(`/sessions/${sessionId}/participants`, {
      method: 'POST',
      body: JSON.stringify({ character_id: characterId }),
    });
  },
  removeParticipant(sessionId: number, characterId: number) {
    return request<Participant[]>(`/sessions/${sessionId}/participants/${characterId}`, {
      method: 'DELETE',
    });
  },
  updateParticipantTalkativeness(sessionId: number, characterId: number, talkativeness: number) {
    return request<Participant[]>(`/sessions/${sessionId}/participants/${characterId}`, {
      method: 'PATCH',
      body: JSON.stringify({ talkativeness }),
    });
  },
  listSessionEventTree(sessionId: number, branchId = 'main') {
    const search = new URLSearchParams({ branch_id: branchId });
    return request<SessionEventNode[]>(`/sessions/${sessionId}/event-tree?${search.toString()}`);
  },
  listCharacters() {
    return request<Character[]>('/characters');
  },
  async uploadCharacterAvatar(characterId: number, file: File) {
    const formData = new FormData();
    formData.append('file', file);
    const response = await fetch(`${API_BASE}/characters/${characterId}/avatar`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.detail ?? '头像上传失败');
    }
    return response.json() as Promise<Character>;
  },
  listExpressions(characterId: number) {
    return request<Expression[]>(`/characters/${characterId}/expressions`);
  },
  async saveExpression(characterId: number, payload: { id?: number; expression: string; label: string; image_path: string; sort_order?: number }) {
    return request<Expression>(`/characters/${characterId}/expressions`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  async deleteExpression(characterId: number, expressionId: number) {
    return request<{ ok: boolean }>(`/characters/${characterId}/expressions/${expressionId}`, { method: 'DELETE' });
  },
  async uploadExpressionImage(characterId: number, expression: string, file: File) {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('expression', expression);
    const response = await fetch(`${API_BASE}/characters/${characterId}/expressions/upload`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.detail ?? '表情图片上传失败');
    }
    return response.json() as Promise<Expression>;
  },
  async importCharacterCard(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    const response = await fetch(`${API_BASE}/characters/import-card`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.detail ?? '形象卡导入失败');
    }
    return response.json() as Promise<Character>;
  },
  async importCharacterCardJson(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    const response = await fetch(`${API_BASE}/characters/import-card-json`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.detail ?? '扩展设定 JSON 导入失败');
    }
    return response.json() as Promise<Character>;
  },
  async extractDocxPlainText(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    const response = await fetch(`${API_BASE}/characters/extract-docx-text`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.detail ?? 'DOCX 解析失败');
    }
    return response.json() as Promise<{ text: string; filename: string }>;
  },
  exportCharacterCard(characterId: number) {
    return downloadFile(`/characters/${characterId}/export-card`, `character_${characterId}.png`);
  },
  previewCharacterCardImage(payload: { name?: string; persona_prompt?: string; prompt_hint?: string; size?: string }) {
    return request<{ urls: string[]; revised_prompt: string; error?: string | null }>('/characters/preview-card-image', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  generateCharacterCardImage(characterId: number, payload: { prompt_hint?: string; size?: string } = {}) {
    return request<Character>(`/characters/${characterId}/generate-card-image`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  async downloadCharacterPortable(characterId: number, format: 'json' | 'txt' | 'docx', mode: 'raw' | 'summary') {
    const path =
      mode === 'raw'
        ? `/characters/${characterId}/export-portable?format=${format}`
        : `/characters/${characterId}/export-portable-summary?format=${format}`;
    const res = await fetch(`${API_BASE}${path}`, {
      method: mode === 'raw' ? 'GET' : 'POST',
    });
    if (!res.ok) {
      const payload = await res.json().catch(() => ({}));
      throw new Error((payload as { detail?: string }).detail ?? '导出失败');
    }
    const blob = await res.blob();
    const cd = res.headers.get('Content-Disposition');
    let filename = '';
    if (cd) {
      const m = cd.match(/filename="?([^";]+)"?/i);
      if (m) filename = m[1];
    }
    if (!filename) {
      filename = mode === 'raw' ? `character_portable.${format}` : `character_summary.${format}`;
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
  async importCharacterPortable(file: File) {
    const formData = new FormData();
    formData.append('file', file);
    const res = await fetch(`${API_BASE}/characters/import-portable`, {
      method: 'POST',
      body: formData,
    });
    if (!res.ok) {
      const payload = await res.json().catch(() => ({}));
      throw new Error((payload as { detail?: string }).detail ?? '便携包导入失败');
    }
    return res.json() as Promise<Character>;
  },
  listCharacterTemplates() {
    return request<CharacterTemplate[]>('/characters/templates/defaults');
  },
  createCharacter(payload: Partial<Character>) {
    return request<Character>('/characters', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateCharacter(characterId: number, payload: Partial<Character> & {
    clear_api_key?: boolean;
    clear_voice_api_key?: boolean;
    clear_image_gen_api_key?: boolean;
  }) {
    return request<Character>(`/characters/${characterId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  deleteCharacter(characterId: number) {
    return request<{ ok: boolean }>(`/characters/${characterId}`, {
      method: 'DELETE',
    });
  },
  toggleFavorite(characterId: number) {
    return request<{ ok: boolean; favorite: boolean }>(`/characters/${characterId}/favorite`, {
      method: 'PATCH',
    });
  },
  importCharacterFromUrl(url: string) {
    return request<Character>('/characters/import-url', {
      method: 'POST',
      body: JSON.stringify({ url }),
    });
  },
  listVoices() {
    return request<VoiceProfile[]>('/voices');
  },
  getCharacterProfile(characterId: number) {
    return request<CharacterProfile | null>(`/characters/${characterId}/profile`);
  },
  importCharacterProfile(
    characterId: number,
    payload: { source_text: string; source_filename: string; merge_into_persona_prompt: boolean },
  ) {
    return request<CharacterProfile>(`/characters/${characterId}/profile/import`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  listProviderCatalog() {
    return request<ProviderPreset[]>('/providers/catalog');
  },
  listChannels() {
    return request<ApiChannel[]>('/channels');
  },
  saveChannel(payload: Partial<ApiChannel>) {
    return request<ApiChannel>('/channels', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  deleteChannel(channelId: string) {
    return request<{ ok: boolean }>(`/channels/${channelId}`, {
      method: 'DELETE',
    });
  },
  listChannelProviders() {
    return request<ProviderOption[]>('/channels/providers');
  },
  listChannelPurposes() {
    return request<PurposeOption[]>('/channels/purposes');
  },
  listPersonas() {
    return request<{ id: number; name: string; description: string; avatar_color: string; avatar_image_path: string; is_active: boolean }[]>('/personas');
  },
  getActivePersona() {
    return request<{ id: number; name: string; description: string; avatar_color: string; avatar_image_path: string }>('/personas/active');
  },
  savePersona(payload: { id?: number; name: string; description: string; avatar_color: string; avatar_image_path: string; is_active?: boolean }) {
    return request<{ id: number }>('/personas', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  getSessionWorld(sessionId: number) {
    return request<SessionWorld>(`/sessions/${sessionId}/world`);
  },
  listWorldTemplates(query?: string) {
    const search = new URLSearchParams();
    if (query?.trim()) search.set('q', query.trim());
    const suffix = search.toString() ? `?${search.toString()}` : '';
    return request<WorldTemplate[]>(`/worlds/templates${suffix}`);
  },
  exportWorldTemplate(templateId: string) {
    const safeId = templateId.replace(/[^a-zA-Z0-9_-]/g, '_');
    return downloadFile(`/worlds/templates/${encodeURIComponent(templateId)}/export-file`, `${safeId || 'world_template'}.json`);
  },
  exportWorldTemplateBundle(includeBuiltin = true) {
    const search = new URLSearchParams({ include_builtin: includeBuiltin ? 'true' : 'false' });
    return downloadFile(`/worlds/templates/export-bundle-file?${search.toString()}`, 'world_templates_bundle.json');
  },
  importWorldTemplatePackage(payload: {
    package_json: Record<string, unknown>;
    override_existing?: boolean;
    new_template_id?: string;
    new_label?: string;
  }) {
    return request<WorldTemplate>('/worlds/templates/import-package', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  getWorldTemplatePackage(templateId: string) {
    return request<WorldTemplatePackage>(`/worlds/templates/${templateId}/export`);
  },
  getWorldTemplateBundle(includeBuiltin = true) {
    const search = new URLSearchParams({ include_builtin: includeBuiltin ? 'true' : 'false' });
    return request<WorldTemplateBundle>(`/worlds/templates/export-bundle?${search.toString()}`);
  },
  importWorldTemplateBundle(payload: {
    bundle_json: Record<string, unknown>;
    override_existing?: boolean;
    replace_all_custom_templates?: boolean;
  }) {
    return request<WorldTemplate[]>('/worlds/templates/import-bundle', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  previewWorldTemplateBundleImport(payload: {
    bundle_json: Record<string, unknown>;
    override_existing?: boolean;
    replace_all_custom_templates?: boolean;
  }) {
    return request<WorldTemplateBundlePreview>('/worlds/templates/preview-bundle-import', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  createWorldTemplate(payload: {
    template_id: string;
    label: string;
    category: string;
    summary: string;
    gameplay_mode: string;
    world_prompt: string;
    cover_image_path: string;
    suggested_choices: string[];
    anti_cheat_prompt: string;
  }) {
    return request<WorldTemplate>('/worlds/templates', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateWorldTemplate(
    templateId: string,
      payload: {
        label: string;
        category: string;
        summary: string;
        gameplay_mode: string;
        world_prompt: string;
        cover_image_path: string;
        suggested_choices: string[];
        anti_cheat_prompt: string;
      },
  ) {
    return request<WorldTemplate>(`/worlds/templates/${templateId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  deleteWorldTemplate(templateId: string) {
    return request<{ ok: boolean }>(`/worlds/templates/${templateId}`, {
      method: 'DELETE',
    });
  },
  reviewWorldQuality(payload: {
    template_id?: string;
    label: string;
    category: string;
    summary: string;
    gameplay_mode: string;
    world_prompt: string;
    cover_image_path?: string;
    suggested_choices: string[];
    anti_cheat_prompt: string;
    lore_entries: {
      title: string;
      entry_type?: string;
      keywords_json?: string[];
      content: string;
      sort_order?: number;
      is_core?: boolean;
    }[];
  }) {
    return request<WorldQualityReport>('/worlds/review-quality', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  importEncyclopediaWorldInfo(encyclopediaId: number, jsonText: string) {
    return request<{ created: number }>(`/encyclopedia/${encyclopediaId}/import-worldinfo`, {
      method: 'POST',
      body: JSON.stringify({ json_text: jsonText }),
    });
  },
  listWorldLoreEntries(templateId: string) {
    return request<WorldLoreEntry[]>(`/worlds/templates/${templateId}/lore`);
  },
  createWorldLoreEntry(
    templateId: string,
    payload: {
      title: string;
      entry_type: string;
      keywords_json: string[];
      content: string;
      sort_order: number;
      is_core: boolean;
    },
  ) {
    return request<WorldLoreEntry>(`/worlds/templates/${templateId}/lore`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  updateWorldLoreEntry(
    entryId: number,
    payload: {
      title: string;
      entry_type: string;
      keywords_json: string[];
      content: string;
      sort_order: number;
      is_core: boolean;
    },
  ) {
    return request<WorldLoreEntry>(`/worlds/lore/${entryId}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  deleteWorldLoreEntry(entryId: number) {
    return request<{ ok: boolean }>(`/worlds/lore/${entryId}`, {
      method: 'DELETE',
    });
  },
  generateWorld(payload: {
    character_id?: number | null;
    world_type: string;
    core_theme?: string;
    tone?: string;
    extra_requirements?: string;
    person_name_count?: number;
    place_name_count?: number;
    item_name_count?: number;
    lore_entry_count?: number;
    auto_save?: boolean;
    template_id?: string;
    label?: string;
  }, signal?: AbortSignal) {
    return request<WorldGenerationResult>('/worlds/generate', {
      method: 'POST',
      signal,
      body: JSON.stringify(payload),
    });
  },
  importWorld(payload: {
    character_id?: number | null;
    source_text: string;
    source_filename?: string;
    category_hint?: string;
    template_id?: string;
    label?: string;
    auto_save?: boolean;
  }, signal?: AbortSignal) {
    return request<WorldImportResult>('/worlds/import', {
      method: 'POST',
      signal,
      body: JSON.stringify(payload),
    });
  },
  listPromptTemplates() {
    return request<PromptTemplate[]>('/prompts/templates');
  },
  listPromptTemplateRevisions(templateId: string) {
    return request<PromptTemplateRevision[]>(`/prompts/templates/${templateId}/revisions`);
  },
  updateSessionWorld(
    sessionId: number,
    payload: {
      encyclopedia_id?: number | null;
      world_prompt: string;
      template_id: string;
      gameplay_mode: string;
      narrator_enabled: boolean;
      narrator_name: string;
      choice_generation_enabled: boolean;
      max_choice_count: number;
      suggested_choices_json: string[];
      anti_cheat_enabled: boolean;
      anti_cheat_prompt: string;
    },
  ) {
    return request<SessionWorld>(`/sessions/${sessionId}/world`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },
  async uploadVoice(formData: FormData) {
    const response = await fetch(`${API_BASE}/voices/upload`, {
      method: 'POST',
      body: formData,
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.detail ?? '上传失败');
    }
    return response.json() as Promise<VoiceProfile>;
  },
  synthesizeMessage(messageId: number) {
    return request<{ message_id: number; clips: VoiceClip[] }>(`/voices/synthesize/message/${messageId}`, {
      method: 'POST',
    });
  },
  async fetchVoicePreview(voiceId: number) {
    let response: Response;
    try {
      response = await fetch(`${API_BASE}/voices/${voiceId}/preview`, {
        method: 'POST',
      });
    } catch (e) {
      throw new Error(friendlyFetchError(e));
    }
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(formatApiErrorPayload(payload, response.status));
    }
    return response.blob();
  },
  rewritePrompt(payload: { character_id: number; source_text: string; instruction: string; chunk_size?: number }) {
    return request<{ result: string }>('/workbench/rewrite', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  listJobs(scope?: string, targetId?: number, limit = 30) {
    const search = new URLSearchParams({ limit: String(limit) });
    if (scope?.trim()) search.set('scope', scope.trim());
    if (typeof targetId === 'number') search.set('target_id', String(targetId));
    return request<JobRun[]>(`/jobs?${search.toString()}`);
  },
  createWorldJob(operation: 'generate' | 'import', payload: object, signal?: AbortSignal) {
    return request<{ id: number; status: string }>('/jobs/world-request', { method: 'POST', body: JSON.stringify({ operation, request: payload }), signal });
  },
  runWorldJob(id: number, signal?: AbortSignal) { return request<{ status: 'succeeded' | 'paused'; result: WorldGenerationResult | null }>(`/jobs/${id}/run-world`, { method: 'POST', signal }); },
  pauseWorldJob(id: number) { return request(`/jobs/${id}/pause-world`, { method: 'POST' }); },
  worldJobProgress(id: number) { return request<{ status: string; completed_steps: number | null; stage_label: string | null; can_resume: boolean }>(`/jobs/${id}/world-progress`); },
  worldJobHistory(beforeId?: number, signal?: AbortSignal) {
    return request<{ items: WorldJobSummary[]; next_cursor: number | null }>(`/jobs/world-history${beforeId ? `?before_id=${beforeId}` : ''}`, { signal });
  },
  worldJobResult(jobId: number, signal?: AbortSignal) { return request<WorldGenerationResult>(`/jobs/${jobId}/world-result`, { signal }); },
  saveWorldJobResult(jobId: number) { return request<WorldGenerationResult>(`/jobs/${jobId}/save-world`, { method: 'POST' }); },
  async createUserMessageWithFiles(sessionId: number, payload: { content: string; files: File[]; branch_id?: string }, signal?: AbortSignal) {
    const formData = new FormData();
    formData.append('content', payload.content);
    formData.append('branch_id', payload.branch_id || 'main');
    for (const file of payload.files) {
      formData.append('files', file);
    }
    const response = await fetch(`${API_BASE}/sessions/${sessionId}/user-message/upload`, {
      method: 'POST',
      body: formData,
      signal,
    });
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(data.detail ?? '上传消息失败');
    }
    return response.json() as Promise<Message>;
  },
  async streamGenerate(
    sessionId: number,
    payload: {
      user_message?: string;
      character_ids?: number[];
      include_narrator?: boolean;
      auto_select_speakers?: boolean;
      max_auto_speakers?: number;
      branch_id?: string;
      narrator_only?: boolean;
    },
    onEvent: (event: Record<string, unknown>) => void,
    signal?: AbortSignal,
  ) {
    const response = await fetch(`${API_BASE}/sessions/${sessionId}/generate/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
      signal,
    });
    if (!response.ok || !response.body) {
      // 尝试解析后端返回的具体错误信息，便于前端展示给用户
      let errorDetail = '流式请求失败';
      try {
        const errPayload = await response.json();
        errorDetail = errPayload.detail ?? errorDetail;
      } catch { /* 解析失败时使用默认提示 */ }
      throw new Error(errorDetail);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
      const blocks = buffer.split('\n\n');
      buffer = blocks.pop() ?? '';

      for (const block of blocks) {
        const line = block.trim();
        if (!line.startsWith('data:')) continue;
        const json = line.slice(5).trim();
        if (!json) continue;
        onEvent(JSON.parse(json));
      }

      if (done) {
        break;
      }
    }
  },
  getEncyclopediaEntry(entryId: number) {
    return request<EncyclopediaEntryDetail>(`/encyclopedia/entries/${entryId}`);
  },
  listEncyclopediaTimeline(encyclopediaId: number, branch = 'main') {
    const s = new URLSearchParams();
    s.set('encyclopedia_id', String(encyclopediaId));
    s.set('branch', branch);
    return request<
      Array<{
        id: number;
        encyclopedia_id: number;
        title: string;
        time_label: string;
        time_order: number;
        entry_type: string;
        summary: string;
        content: string;
        tags: string;
        timeline_branch: string;
      }>
    >(`/encyclopedia/timeline?${s.toString()}`);
  },
  getEncyclopediaRelationGraph(encyclopediaId: number) {
    return request<{
      encyclopedia_id: number;
      nodes: { id: number; title: string; entry_type: string }[];
      edges: { id: number; source: number; target: number; relation_type: string; label: string }[];
    }>(`/encyclopedia/${encyclopediaId}/relation-graph`);
  },
  /** 整库沉淀条目（推断置信或带 source_session），对齐 Android 沉积列表 */
  listEncyclopediaSedimentEntries(encyclopediaId: number) {
    return request<EncyclopediaEntry[]>(`/encyclopedia/${encyclopediaId}/sediment-entries`);
  },
  listEncyclopediaSedimentPage(encyclopediaId: number, status: string, beforeId: number | null) {
    const params = new URLSearchParams({ status });
    if (beforeId !== null) params.set('before_id', String(beforeId));
    return request<{items: Pick<EncyclopediaEntry, 'id' | 'title' | 'entry_type' | 'summary' | 'confidence' | 'source_session_id'>[]; next_cursor: number | null}>(`/encyclopedia/${encyclopediaId}/sediment-page?${params}`);
  },
  confirmEncyclopediaSedimentEntries(encyclopediaId: number, entryIds: number[]) {
    return request<{ confirmed: number }>(`/encyclopedia/${encyclopediaId}/sediment-entries/confirm`, {
      method: 'POST', body: JSON.stringify({ entry_ids: entryIds }),
    });
  },
  getEncyclopediaEntryGraph(entryId: number, depth = 2) {
    return request<{
      nodes: { id: number; title: string; entry_type: string }[];
      edges: { source: number; target: number; relation_type: string; label: string }[];
    }>(`/encyclopedia/entries/${entryId}/graph?depth=${depth}`);
  },
  get(path: string) {
    return request<any>(path);
  },
  post(path: string, body?: any, signal?: AbortSignal) {
    return request<any>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined, signal });
  },
  delete(path: string) {
    return request<any>(path, { method: 'DELETE' });
  },
};
