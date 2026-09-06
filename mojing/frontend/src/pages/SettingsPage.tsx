import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useBeforeUnload, useBlocker, useSearchParams } from 'react-router-dom';

import { api } from '../api/client';
import type { LocalConfig, VoiceServiceConfig } from '../types';
import { ModelPlatformsPanel } from '../components/ModelPlatforms';
import { ApiProbePanel } from '../components/ApiProbePanel';
import OutputRulesPage from '../components/OutputRulesPage';
import { CostPanel } from '../components/CostPanel';
import { confirmModal } from '../components/ConfirmModal';
import InlineQueryError from '../components/InlineQueryError';
import UiIcon, { type UiIconName } from '../components/UiIcon';
import { useToast } from '../hooks/useToast';
import { applyChatDensity, applyTheme, CHAT_DENSITY_IDS, CHAT_DENSITY_LABELS, currentChatDensityId, currentThemeId, THEME_IDS, THEME_LABELS } from '../theme';

function maskConfiguredSecret(value: string | undefined): string {
  const v = (value ?? '').trim();
  if (!v) return '（未填写）';
  if (v.length <= 8) return `已填入 ${v.length} 个字符`;
  return `${v.slice(0, 4)}…${v.slice(-2)} · 共 ${v.length} 字符`;
}

type SettingsTab = 'persona' | 'appearance' | 'defaults' | 'api' | 'data' | 'rules' | 'costs';

const SETTINGS_TABS: Array<{ id: SettingsTab; label: string; icon: UiIconName }> = [
  { id: 'persona', label: '个人资料', icon: 'person' },
  { id: 'appearance', label: '外观', icon: 'sparkles' },
  { id: 'defaults', label: '默认配置', icon: 'settings' },
  { id: 'api', label: '模型服务', icon: 'world' },
  { id: 'data', label: '记录转移', icon: 'archive' },
  { id: 'rules', label: '输出规则', icon: 'document' },
  { id: 'costs', label: '成本统计', icon: 'summary' },
];

function isSettingsTab(value: string | null): value is SettingsTab {
  return SETTINGS_TABS.some((item) => item.id === value);
}

function SettingsLoadState({
  label,
  error,
  onRetry,
}: {
  label: string;
  error?: unknown;
  onRetry?: () => void;
}) {
  const hasError = error !== undefined && error !== null;
  return (
    <div className="page-card settings-load-state" role={hasError ? 'alert' : 'status'}>
      <strong>{hasError ? `${label}加载失败` : `正在加载${label}…`}</strong>
      {hasError && <p>{error instanceof Error ? error.message : '本地服务暂不可用，请稍后重试。'}</p>}
      {hasError && onRetry && <button type="button" className="btn btn-primary btn-sm" onClick={onRetry}>重新加载</button>}
    </div>
  );
}

export default function SettingsPage() {
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const [appearanceRev, bumpAppearance] = useReducer((n: number) => n + 1, 0);
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTab = searchParams.get('tab');
  const tab: SettingsTab = isSettingsTab(requestedTab) ? requestedTab : 'persona';
  const tabsRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const tabs = tabsRef.current;
    if (!tabs) return;
    const revealActiveTab = () => {
      const active = tabs.querySelector<HTMLElement>('[aria-selected="true"]');
      if (!active) return;
      const bounds = tabs.getBoundingClientRect();
      const item = active.getBoundingClientRect();
      if (item.right > bounds.right) tabs.scrollLeft += item.right - bounds.right;
      else if (item.left < bounds.left) tabs.scrollLeft += item.left - bounds.left;
    };
    revealActiveTab();
    const observer = new ResizeObserver(revealActiveTab);
    observer.observe(tabs);
    return () => observer.disconnect();
  }, [tab]);
  const localConfigQuery = useQuery({ queryKey: ['local-config'], queryFn: api.getLocalConfig, placeholderData: (prev) => prev });
  const voiceServiceQuery = useQuery({ queryKey: ['voice-service-config'], queryFn: api.getVoiceServiceConfig });
  const worldTemplatesQuery = useQuery({ queryKey: ['world-templates'], queryFn: () => api.listWorldTemplates() });
  const activePersonaQuery = useQuery({ queryKey: ['persona-active'], queryFn: api.getActivePersona });

  const [modelPlatformDirty, setModelPlatformDirty] = useState(false);
  const [personaName, setPersonaName] = useState('');
  const [personaDesc, setPersonaDesc] = useState('');
  const [personaColor, setPersonaColor] = useState('#53c7a8');
  const [localConfigDraft, setLocalConfigDraft] = useState<LocalConfig | null>(null);
  const [voiceServiceDraft, setVoiceServiceDraft] = useState<VoiceServiceConfig | null>(null);

  const selectTab = useCallback((nextTab: SettingsTab) => {
    const nextParams = new URLSearchParams(searchParams);
    if (nextTab === 'persona') nextParams.delete('tab');
    else nextParams.set('tab', nextTab);
    setSearchParams(nextParams);
  }, [searchParams, setSearchParams]);
  useEffect(() => {
    const onDensity = () => bumpAppearance();
    const onTheme = () => bumpAppearance();
    window.addEventListener('mojing-chat-density-changed', onDensity);
    window.addEventListener('mojing-theme-changed', onTheme);
    return () => {
      window.removeEventListener('mojing-chat-density-changed', onDensity);
      window.removeEventListener('mojing-theme-changed', onTheme);
    };
  }, []);

  useEffect(() => {
    if (activePersonaQuery.data) {
      setPersonaName(activePersonaQuery.data.name);
      setPersonaDesc(activePersonaQuery.data.description);
      setPersonaColor(activePersonaQuery.data.avatar_color || '#53c7a8');
    }
  }, [activePersonaQuery.data]);

  useEffect(() => {
    if (localConfigQuery.data) {
      setLocalConfigDraft((current) => current ?? localConfigQuery.data ?? null);
    }
  }, [localConfigQuery.data]);

  useEffect(() => {
    if (voiceServiceQuery.data) {
      setVoiceServiceDraft((current) => current ?? voiceServiceQuery.data ?? null);
    }
  }, [voiceServiceQuery.data]);

  const patchLocalConfig = useCallback(
    (patch: Partial<LocalConfig>) => {
      setLocalConfigDraft((current) => current ? { ...current, ...patch } : current);
    },
    [],
  );

  const patchVoiceService = useCallback(
    (patch: Partial<VoiceServiceConfig>) => {
      setVoiceServiceDraft((current) => current ? { ...current, ...patch } : current);
    },
    [],
  );

  const saveLocalConfigMutation = useMutation({
    mutationFn: async () => {
      if (!localConfigDraft) throw new Error('配置尚未加载完成，请稍后再试');
      const saved = localConfigQuery.data;
      return api.updateLocalConfig({
        ...localConfigDraft,
        clear_public_text_api_key: Boolean(saved?.public_text_api_key && !localConfigDraft.public_text_api_key),
        clear_public_image_api_key: Boolean(saved?.public_image_api_key && !localConfigDraft.public_image_api_key),
        clear_public_voice_api_key: Boolean(saved?.public_voice_api_key && !localConfigDraft.public_voice_api_key),
      });
    },
    onSuccess: (saved) => {
      setLocalConfigDraft(saved);
      queryClient.setQueryData(['local-config'], saved);
      showToast('本机配置已保存', 'success');
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '保存配置失败', 'error'),
  });

  const saveVoiceServiceMutation = useMutation({
    mutationFn: async () => {
      if (!voiceServiceDraft) throw new Error('语音服务配置尚未加载完成');
      return api.updateVoiceServiceConfig({
        ...voiceServiceDraft,
        clear_external_api_key: Boolean(
          voiceServiceQuery.data?.external_api_key && !voiceServiceDraft.external_api_key,
        ),
      });
    },
    onSuccess: (saved) => {
      setVoiceServiceDraft(saved);
      queryClient.setQueryData(['voice-service-config'], saved);
      showToast('外部语音服务配置已保存', 'success');
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '保存语音服务失败', 'error'),
  });

  const savePersonaMutation = useMutation({
    mutationFn: async () => {
      if (!personaName.trim()) {
        throw new Error('请填写「我的名字」（必填）');
      }
      const payload = {
        id: activePersonaQuery.data?.id || undefined,
        name: personaName.trim(),
        description: personaDesc,
        avatar_color: personaColor,
        avatar_image_path: activePersonaQuery.data?.avatar_image_path ?? '',
        is_active: true,
      };
      const saved = await api.savePersona(payload);
      return { ...payload, id: saved.id };
    },
    onSuccess: (saved) => {
      setPersonaName(saved.name);
      setPersonaDesc(saved.description);
      setPersonaColor(saved.avatar_color || '#53c7a8');
      queryClient.setQueryData(['persona-active'], saved);
      queryClient.invalidateQueries({ queryKey: ['personas'] });
      showToast('个人资料已保存', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const lc = localConfigDraft;
  const vs = voiceServiceDraft;
  const localConfigDirty = Boolean(lc && localConfigQuery.data && JSON.stringify(lc) !== JSON.stringify(localConfigQuery.data));
  const voiceServiceDirty = Boolean(vs && voiceServiceQuery.data && JSON.stringify(vs) !== JSON.stringify(voiceServiceQuery.data));
  const personaDirty = Boolean(activePersonaQuery.data && (
    personaName !== activePersonaQuery.data.name ||
    personaDesc !== activePersonaQuery.data.description ||
    personaColor !== (activePersonaQuery.data.avatar_color || '#53c7a8')
  ));
  const isSettingsDirty = localConfigDirty || voiceServiceDirty || personaDirty || modelPlatformDirty;
  const sortedTemplates = [...(worldTemplatesQuery.data ?? [])].sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'));

  const settingsNavigationBlocker = useBlocker(({ currentLocation, nextLocation }) => (
    (isSettingsDirty && currentLocation.pathname !== nextLocation.pathname)
    || (modelPlatformDirty && currentLocation.search !== nextLocation.search)
  ));

  useEffect(() => {
    if (settingsNavigationBlocker.state !== 'blocked') return;
    let active = true;
    void confirmModal(
      '设置修改尚未保存',
      '离开设置页会丢失当前修改。确认放弃修改并离开吗？',
    ).then((leave) => {
      if (!active || settingsNavigationBlocker.state !== 'blocked') return;
      if (leave) settingsNavigationBlocker.proceed();
      else settingsNavigationBlocker.reset();
    });
    return () => { active = false; };
  }, [settingsNavigationBlocker]);

  useBeforeUnload(useCallback((event) => {
    if (!isSettingsDirty) return;
    event.preventDefault();
    event.returnValue = '';
  }, [isSettingsDirty]));

  return (
    <div className="settings-page">
      <div className="settings-header">
        <h2>设置</h2>
        <div ref={tabsRef} className="settings-tabs" style={{ marginTop: 12 }} role="tablist" aria-label="设置分类">
          {SETTINGS_TABS.map((item) => (
            <button
              key={item.id}
              id={`settings-tab-${item.id}`}
              type="button"
              role="tab"
              aria-selected={tab === item.id}
              aria-controls={`settings-panel-${item.id}`}
              className={`settings-tab ${tab === item.id ? 'active' : ''}`}
              onClick={() => selectTab(item.id)}
            >
              <UiIcon name={item.icon} className="settings-tab-icon" />
              <span>{item.label}</span>
            </button>
          ))}
        </div>
      </div>

      <div
        id={`settings-panel-${tab}`}
        role="tabpanel"
        aria-labelledby={`settings-tab-${tab}`}
      >
      {tab === 'persona' && activePersonaQuery.isPending && (
        <SettingsLoadState label="个人资料" />
      )}
      {tab === 'persona' && activePersonaQuery.isError && (
        <SettingsLoadState label="个人资料" error={activePersonaQuery.error} onRetry={() => { void activePersonaQuery.refetch(); }} />
      )}
      {tab === 'persona' && !activePersonaQuery.isPending && !activePersonaQuery.isError && (
        <div className="page-card">
          <div className="card-header">
            <div><p className="eyebrow">我的个人资料</p><h2>用户人设</h2></div>
            <div className="chat-msg-avatar chat-msg-avatar-self" style={{ background: personaColor, width: 48, height: 48, fontSize: '1.2rem' }}>
              <span>{personaName ? personaName[0] : '我'}</span>
            </div>
          </div>
          <p className="guide-text">
            对话中的称呼与简介；人设会写入提示词。在角色系统提示里可用 <code>{'{{user}}'}</code>、<code>{'{{user_description}}'}</code>。
          </p>
          <div className="form-row">
            <div className="form-group">
              <label className="required">我的名字</label>
              <input value={personaName} onChange={(e) => setPersonaName(e.target.value)} placeholder="玩家" maxLength={50} />
              <div className="hint">其他角色对你的称呼</div>
            </div>
            <div className="form-group">
              <label>头像颜色</label>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <input type="color" value={personaColor} onChange={(e) => setPersonaColor(e.target.value)} className="color-picker" />
                <span style={{ fontSize: '0.78rem', color: 'var(--muted)' }}>{personaColor}</span>
              </div>
            </div>
          </div>
          <div className="form-group" style={{ marginTop: 8 }}>
            <label>我的描述（可选）</label>
            <textarea rows={3} value={personaDesc} onChange={(e) => setPersonaDesc(e.target.value)} placeholder="性格、背景、说话风格等，供模型参考。" />
          </div>
          <div className="button-row" style={{ marginTop: 10 }}>
            <button type="button" className="btn btn-primary btn-sm" onClick={() => savePersonaMutation.mutate()} disabled={savePersonaMutation.isPending}>
              {savePersonaMutation.isPending ? '保存中...' : '保存我的资料'}
            </button>
            {activePersonaQuery.data && (
              <span className="pill pill-green" style={{ fontSize: '0.72rem', marginLeft: 8 }}>已保存</span>
            )}
          </div>
        </div>
      )}

      {tab === 'appearance' && (
        <div className="page-card">
          <div className="card-header">
            <div><p className="eyebrow">外观</p><h2>主题与聊天密度</h2></div>
          </div>
          <p className="guide-text" style={{ marginBottom: 12 }}>
            主题适用于整个应用；聊天密度影响会话页气泡与行距。
          </p>
          <div className="settings-appearance-row">
            <div className="form-group">
              <label>配色主题</label>
              <select
                value={currentThemeId()}
                onChange={(e) => {
                  applyTheme(e.target.value);
                  bumpAppearance();
                }}
              >
                {THEME_IDS.map((id) => (
                  <option key={id} value={id}>{THEME_LABELS[id]}</option>
                ))}
              </select>
            </div>
            <div className="form-group">
              <label>聊天密度</label>
              <div className="settings-segmented" key={appearanceRev}>
                {CHAT_DENSITY_IDS.map((id) => (
                  <button
                    key={id}
                    type="button"
                    className={currentChatDensityId() === id ? 'active' : ''}
                    onClick={() => {
                      applyChatDensity(id);
                      bumpAppearance();
                    }}
                  >
                    {CHAT_DENSITY_LABELS[id]}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {tab === 'defaults' && !lc && (
        <SettingsLoadState
          label="默认配置"
          error={localConfigQuery.error}
          onRetry={localConfigQuery.isError ? () => { void localConfigQuery.refetch(); } : undefined}
        />
      )}
      {tab === 'defaults' && lc && (
        <div className="page-card">
          <div className="card-header">
            <div><p className="eyebrow">默认配置</p><h2>新建会话的默认行为</h2></div>
          </div>
          <p className="guide-text" style={{ marginBottom: 12 }}>
            这些选项会带入下一次新建故事，已经开始的会话不会改变。
          </p>
          <div className="form-grid" style={{ marginTop: 8 }}>
              <div className="form-group full-row">
                <label htmlFor="settings-default-world">默认世界</label>
                <select
                  id="settings-default-world"
                  value={lc.default_world_template_id || 'custom'}
                  onChange={(e) => patchLocalConfig({ default_world_template_id: e.target.value })}
                  disabled={worldTemplatesQuery.isLoading || worldTemplatesQuery.isError}
                >
                  {!sortedTemplates.some((t) => t.template_id === 'custom') && (
                    <option value="custom">自定义（无预设模板）</option>
                  )}
                  {sortedTemplates.map((t) => (
                    <option key={t.template_id} value={t.template_id}>{t.is_builtin ? `【内置】${t.label}` : t.label}</option>
                  ))}
                  {lc.default_world_template_id &&
                    !sortedTemplates.some((t) => t.template_id === lc.default_world_template_id) &&
                    lc.default_world_template_id !== 'custom' && (
                    <option value={lc.default_world_template_id} disabled>原默认世界已不可用</option>
                  )}
                </select>
                {worldTemplatesQuery.isError && (
                  <InlineQueryError
                    message="世界模板列表加载失败，当前值仍会保留"
                    error={worldTemplatesQuery.error}
                    retrying={worldTemplatesQuery.isFetching}
                    onRetry={() => { void worldTemplatesQuery.refetch(); }}
                  />
                )}
              </div>
              <div className="form-group">
                <label htmlFor="settings-default-narrator">旁白</label>
                <select id="settings-default-narrator" value={String(lc.default_narrator_enabled)} onChange={(e) => {
                  patchLocalConfig({ default_narrator_enabled: e.target.value === 'true' });
                }}>
                  <option value="false">默认关闭</option><option value="true">默认开启</option>
                </select>
              </div>
              <div className="form-group">
                <label htmlFor="settings-default-choices">本回合选项</label>
                <select id="settings-default-choices" value={String(lc.default_choice_generation_enabled)} onChange={(e) => {
                  patchLocalConfig({ default_choice_generation_enabled: e.target.value === 'true' });
                }}>
                  <option value="true">默认开启</option><option value="false">默认关闭</option>
                </select>
              </div>
              <div className="form-group">
                <label htmlFor="settings-default-rules">保持角色与世界规则</label>
                <select id="settings-default-rules" value={String(lc.default_anti_cheat_enabled)} onChange={(e) => {
                  patchLocalConfig({ default_anti_cheat_enabled: e.target.value === 'true' });
                }}>
                  <option value="true">默认开启</option><option value="false">默认关闭</option>
                </select>
                <div className="hint">减少角色或世界设定被临时要求带偏的情况。</div>
              </div>
              <div className="form-group">
                <label htmlFor="settings-default-speakers">每轮自动发言角色上限</label>
                <input id="settings-default-speakers" type="number" min={1} value={lc.max_auto_speakers} onChange={(e) => {
                  patchLocalConfig({ max_auto_speakers: Number(e.target.value) || 2 });
                }} />
              </div>
              <details className="settings-defaults-advanced full-row">
                <summary>更多本机限制</summary>
                <div className="form-grid settings-defaults-advanced-body">
                  <div className="form-group">
                    <label htmlFor="settings-upload-limit">单个文件上传上限（MB）</label>
                    <input id="settings-upload-limit" type="number" min={1} value={lc.max_upload_mb} onChange={(e) => {
                      patchLocalConfig({ max_upload_mb: Number(e.target.value) || 20 });
                    }} />
                  </div>
                  <div className="form-group">
                    <label htmlFor="settings-memory-interval">自动整理记忆的消息间隔</label>
                    <input id="settings-memory-interval" type="number" min={1} value={lc.memory_compact_threshold} onChange={(e) => {
                      patchLocalConfig({ memory_compact_threshold: Number(e.target.value) || 120 });
                    }} />
                  </div>
                </div>
              </details>
              <div className="button-row full-row settings-save-row">
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={!localConfigDirty || saveLocalConfigMutation.isPending}
                  onClick={() => saveLocalConfigMutation.mutate()}
                >
                  {saveLocalConfigMutation.isPending ? '保存中...' : localConfigDirty ? '保存默认配置' : '默认配置已保存'}
                </button>
              </div>
          </div>
        </div>
      )}

      {tab === 'api' && !lc && (
        <SettingsLoadState
          label="公共 API 配置"
          error={localConfigQuery.error}
          onRetry={localConfigQuery.isError ? () => { void localConfigQuery.refetch(); } : undefined}
        />
      )}
      {tab === 'api' && lc && (
        <div className="page-card">
          <div className="card-header">
            <div><p className="eyebrow">本机线路</p><h2>模型服务</h2></div>
          </div>
          <p className="settings-api-intro">
            开始对话只需先配置文字服务。图片、语音和思考模型都是可选能力，可以稍后再设置。
          </p>

          <div className="settings-api-layout">
            <ModelPlatformsPanel onDirtyChange={setModelPlatformDirty} />

            <details className="settings-api-docs">
              <summary>填写与测试说明</summary>
              <div className="settings-api-docs-body">
                <p>接口需兼容常见 OpenAI 格式。API 地址填写根路径，通常以 <code>/v1</code> 结尾，不要填写到 <code>…/chat/completions</code> 等具体请求路径。</p>
                <p><strong>测试连接</strong>会请求真实上游：文字约 1 token；生图会消耗额度；语音使用极短静音测试转写。</p>
              </div>
            </details>

            <details className="settings-api-docs">
              <summary>查看当前已保存的生效线路</summary>
              <div className="settings-api-docs-body">
                <p>
                  聊天页手动选择优先；未选择时使用角色独立线路，角色未填写时继承默认平台。
                </p>
                <ul style={{ margin: '8px 0 0', paddingLeft: 20, fontSize: '0.88rem', lineHeight: 1.65 }}>
                  <li>
                    <strong>文字</strong>：<code>{(localConfigQuery.data?.public_text_base_url || '').trim() || '—'}</code> · Key {maskConfiguredSecret(localConfigQuery.data?.public_text_api_key)} · 模型 <code>{(localConfigQuery.data?.public_text_model || '').trim() || '—'}</code>
                  </li>
                  <li>
                    <strong>生图</strong>：<code>{(lc.public_image_base_url || '').trim() || '—'}</code> · Key {maskConfiguredSecret(lc.public_image_api_key)} · 模型 <code>{(lc.public_image_model || '').trim() || '—'}</code>
                  </li>
                  <li>
                    <strong>语音转写</strong>：<code>{(lc.public_voice_base_url || '').trim() || '—'}</code> · Key {maskConfiguredSecret(lc.public_voice_api_key)} · 模型 <code>{(lc.public_voice_model || '').trim() || '—'}</code>
                  </li>
                  <li>
                    <strong>思考 / Max</strong>：<code>{lc.allow_session_think_max ? '允许会话内切换' : '关闭'}</code> · 模型 <code>{(lc.think_max_model || '').trim() || '—'}</code>
                  </li>
                </ul>
              </div>
            </details>

            <details className="settings-api-advanced">
              <summary>
                <span><strong>图片、语音与思考设置</strong><small>按需配置，不影响普通文字对话</small></span>
                <span className="pill">可选</span>
              </summary>
              <div className="settings-api-advanced-body">

            <section className="settings-api-card">
              <h3 className="settings-api-card-title">图片生成</h3>
              <div className="settings-api-fields">
                <div className="form-group">
                  <label>API 地址</label>
                  <input value={lc.public_image_base_url || ''} onChange={(e) => {
                    patchLocalConfig({ public_image_base_url: e.target.value });
                  }} placeholder="https://…/v1" />
                </div>
                <div className="form-group">
                  <label>API 密钥</label>
                  <input type="password" value={lc.public_image_api_key || ''} onChange={(e) => {
                    patchLocalConfig({ public_image_api_key: e.target.value });
                  }} placeholder="sk-…" />
                </div>
                <div className="form-group">
                  <label>模型</label>
                  <input value={lc.public_image_model || 'dall-e-3'} onChange={(e) => {
                    patchLocalConfig({ public_image_model: e.target.value });
                  }} placeholder="生图模型 id" />
                </div>
                <div className="form-group full-row settings-api-probe">
                  <ApiProbePanel channel="image" baseUrl={lc.public_image_base_url || ''} apiKey={lc.public_image_api_key || ''} model={lc.public_image_model || 'dall-e-3'} buttonLabel="测试生图连接" />
                </div>
              </div>
            </section>

            <section className="settings-api-card">
              <h3 className="settings-api-card-title">语音转文字</h3>
              <div className="settings-api-fields">
                <div className="form-group">
                  <label>API 地址</label>
                  <input value={lc.public_voice_base_url || ''} onChange={(e) => {
                    patchLocalConfig({ public_voice_base_url: e.target.value });
                  }} placeholder="可与文字相同根地址" />
                </div>
                <div className="form-group">
                  <label>API 密钥</label>
                  <input type="password" value={lc.public_voice_api_key || ''} onChange={(e) => {
                    patchLocalConfig({ public_voice_api_key: e.target.value });
                  }} placeholder="sk-…" />
                </div>
                <div className="form-group">
                  <label>转写模型</label>
                  <input value={lc.public_voice_model || ''} onChange={(e) => {
                    patchLocalConfig({ public_voice_model: e.target.value });
                  }} placeholder="whisper-1 或网关模型 id" />
                </div>
                <div className="hint full-row" style={{ marginTop: 0 }}>
                  用于语音输入转写；朗读仍走内置 Edge / XTTS 等。
                </div>
                <div className="form-group full-row settings-api-probe">
                  <ApiProbePanel channel="voice" baseUrl={lc.public_voice_base_url || ''} apiKey={lc.public_voice_api_key || ''} model={lc.public_voice_model || ''} buttonLabel="测试语音转写" />
                </div>
              </div>
            </section>

            <section className="settings-api-card">
              <h3 className="settings-api-card-title">思考 / Max（对话）</h3>
              <div className="settings-api-fields">
                <div className="form-group full-row">
                  <label style={{ display: 'flex', alignItems: 'flex-start', gap: 10, cursor: 'pointer' }}>
                    <input
                      type="checkbox"
                      checked={Boolean(lc.allow_session_think_max)}
                      onChange={(e) => patchLocalConfig({ allow_session_think_max: e.target.checked })}
                      style={{ marginTop: 4 }}
                    />
                    <span>
                      允许在<strong>对话页</strong>为当前会话开启「思考 / Max」
                      <div className="hint" style={{ marginTop: 6 }}>
                        关闭时对话内开关不可用。角色页可单独为某角色常开，与此项无关。
                      </div>
                    </span>
                  </label>
                </div>
                <div className="form-group full-row">
                  <label>思考 / Max 模型 id</label>
                  <input
                    value={lc.think_max_model ?? 'deepseek-reasoner'}
                    onChange={(e) => patchLocalConfig({ think_max_model: e.target.value })}
                    placeholder="如 deepseek-reasoner"
                  />
                  <div className="hint">开启时优先用此处；角色若填「思考模型覆盖」则以角色为准。</div>
                </div>
              </div>
            </section>

            {vs && (
              <section className="settings-api-card">
                <h3 className="settings-api-card-title">外部语音克隆服务（可选）</h3>
                <p className="settings-api-intro" style={{ marginBottom: 10 }}>
                  与本地 XTTS 并行：可按模式优先走外部 HTTP 克隆，失败再回退。
                </p>
                <div className="settings-api-fields">
                  <div className="form-group full-row">
                    <label style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }}>
                      <input
                        type="checkbox"
                        checked={Boolean(vs.enabled)}
                        onChange={(e) => patchVoiceService({ enabled: e.target.checked })}
                      />
                      <span>启用外部服务</span>
                    </label>
                  </div>
                  <div className="form-group full-row">
                    <label>策略</label>
                    <select
                      value={vs.mode || 'builtin_only'}
                      onChange={(e) => patchVoiceService({ mode: e.target.value })}
                    >
                      <option value="builtin_only">仅内置 / 本地</option>
                      <option value="external_clone_preferred">优先外部克隆，失败再本地</option>
                      <option value="external_only">仅外部（不可用则报错）</option>
                    </select>
                  </div>
                  <div className="form-group">
                    <label>服务 Base URL</label>
                    <input
                      value={vs.external_base_url || ''}
                      onChange={(e) => patchVoiceService({ external_base_url: e.target.value })}
                      placeholder="https://…"
                    />
                  </div>
                  <div className="form-group">
                    <label>API 密钥</label>
                    <input
                      type="password"
                      value={vs.external_api_key || ''}
                      onChange={(e) => patchVoiceService({ external_api_key: e.target.value })}
                      placeholder="可选"
                    />
                  </div>
                  <div className="form-group">
                    <label>克隆接口路径</label>
                    <input
                      value={vs.clone_endpoint || '/clone'}
                      onChange={(e) => patchVoiceService({ clone_endpoint: e.target.value })}
                      placeholder="/clone"
                    />
                  </div>
                  <div className="form-group full-row">
                    <label>请求超时（秒）</label>
                    <input
                      type="number"
                      value={vs.timeout_seconds ?? 120}
                      onChange={(e) => patchVoiceService({ timeout_seconds: Number(e.target.value) || 120 })}
                    />
                  </div>
                </div>
              </section>
            )}
            {!vs && voiceServiceQuery.isPending && <p className="hint">正在加载外部语音服务配置…</p>}
            {!vs && voiceServiceQuery.isError && (
              <InlineQueryError
                message="外部语音服务配置加载失败，不影响上方公共 API 配置"
                error={voiceServiceQuery.error}
                retrying={voiceServiceQuery.isFetching}
                onRetry={() => { void voiceServiceQuery.refetch(); }}
              />
            )}
              </div>
            </details>

            <p className="hint" style={{ margin: 0, fontSize: '0.76rem' }}>文字、图片与语音转写可以分别使用不同线路。</p>
            {(localConfigDirty || voiceServiceDirty) && <div className="settings-save-bar">
              <span className="hint">这里保存图片、语音与思考设置；文字平台请使用编辑区的“保存平台”。</span>
              <div className="button-row">
                {vs && voiceServiceDirty && (
                  <button
                    type="button"
                    className="btn btn-ghost"
                    disabled={saveVoiceServiceMutation.isPending}
                    onClick={() => saveVoiceServiceMutation.mutate()}
                  >
                    {saveVoiceServiceMutation.isPending ? '保存中...' : '保存语音服务'}
                  </button>
                )}
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={!localConfigDirty || saveLocalConfigMutation.isPending}
                  onClick={() => saveLocalConfigMutation.mutate()}
                >
                  {saveLocalConfigMutation.isPending ? '保存中...' : localConfigDirty ? '保存其他设置' : '其他设置已保存'}
                </button>
              </div>
            </div>}
          </div>
        </div>
      )}

      {tab === 'data' && (
        <div className="page-card">
          <div className="card-header">
            <div><p className="eyebrow">本地记录</p><h2>转移一段故事</h2></div>
          </div>
          <p className="guide-text">
            会话包是普通 ZIP，不设密码，也不绑定账号。可以发给其他墨境用户，导入后会创建一份新会话，不覆盖原记录。
          </p>
          <ol className="compact-list">
            <li>导出：打开要转移的会话，在聊天“更多”中选择“导出完整会话包”。</li>
            <li>导入：回到会话列表，点击顶部的导入按钮并选择 ZIP。</li>
          </ol>
          <p className="hint" style={{ marginTop: 12 }}>
            会话包包含角色文字设定、世界配置、消息、故事线和记忆纠正；不包含 API Key、本机路径或图片、语音等媒体文件。
          </p>
          <div className="settings-save-bar" style={{ marginTop: 16 }}>
            <span className="hint">需要转移多个故事时，分别导出对应会话即可。</span>
            <Link className="btn btn-primary" to="/chat">前往会话列表</Link>
          </div>
        </div>
      )}

      {tab === 'rules' && <OutputRulesPage />}
      {tab === 'costs' && <CostPanel />}
      </div>
    </div>
  );
}
