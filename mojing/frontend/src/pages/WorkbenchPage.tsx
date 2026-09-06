import { FormEvent, useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useBeforeUnload, useBlocker, useNavigate, useSearchParams } from 'react-router-dom';

import { api } from '../api/client';
import { confirmModal } from '../components/ConfirmModal';
import WorldJobHistory from '../components/WorldJobHistory';
import CreationHomeLink from '../components/CreationHomeLink';
import InlineQueryError from '../components/InlineQueryError';
import UiIcon from '../components/UiIcon';
import { useUndoDelete } from '../components/UndoToast';
import { useWorldDraft } from '../hooks/useWorldDraft';
import { isAbortError } from '../utils/userFacingError';
import { useToast } from '../hooks/useToast';
import type {
  WorldGenerationResult,
  WorldImportResult,
  WorldLoreEntry,
  WorldQualityReport,
  WorldTemplate,
  WorldTemplateBundlePreview,
  WorldTemplatePackage,
} from '../types';

const WORLD_TYPE_OPTIONS = ['修仙', '玄幻', '剑与魔法', 'DND', '战锤', '都市', '校园', '科幻', '赛博朋克', '克苏鲁', '其他'];

/* ===================== 辅助组件 ===================== */

function QualityReportCard({ report }: { report: WorldQualityReport }) {
  return (
    <div className="quality-card">
      <div className="quality-score">
        <strong>质量评分 {report.score} / 100</strong>
        <span>{report.verdict}</span>
      </div>
      {report.strengths.length > 0 && (
        <div className="guide-inline"><strong>优点</strong><ul className="compact-list">{report.strengths.map((item) => <li key={item}>{item}</li>)}</ul></div>
      )}
      {report.risks.length > 0 && (
        <div className="guide-inline"><strong>风险</strong><ul className="compact-list">{report.risks.map((item) => <li key={item}>{item}</li>)}</ul></div>
      )}
      {report.issues.length > 0 && (
        <div className="guide-inline"><strong>问题与修复建议</strong><ul className="compact-list">{report.issues.map((item) => <li key={`${item.code}-${item.message}`}>{item.message}{item.suggestion ? ` 建议：${item.suggestion}` : ''}</li>)}</ul></div>
      )}
    </div>
  );
}

function WorldResultCard({
  result,
  label,
  saving,
  startingChat,
  onSave,
  onManage,
  onStartChat,
}: {
  result: WorldGenerationResult | WorldImportResult;
  label: string;
  saving: boolean;
  startingChat: boolean;
  onSave: () => void;
  onManage: () => void;
  onStartChat: () => void;
}) {
  const saved = Boolean(result.saved_template);
  return (
    <div className="page-card" style={{ borderColor: 'var(--accent)' }}>
      <div className="card-header">
        <h2 className="workbench-result-title"><UiIcon name="world" />{label}</h2>
        <span className={`pill ${saved ? 'pill-green' : ''}`}>{saved ? '已保存' : '尚未保存'}</span>
      </div>
      <p style={{ fontSize: '0.9rem', fontWeight: 600, marginBottom: 8 }}>{result.template.label}</p>
      <p style={{ fontSize: '0.82rem', color: 'var(--text-2)', marginBottom: 12 }}>{result.template.summary}</p>
      <QualityReportCard report={result.quality_report} />
      <div className="button-row" style={{ marginTop: 12, gap: 8 }}>
        {saved ? (
          <>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onManage} disabled={startingChat}>管理此世界</button>
            <button type="button" className="btn btn-primary btn-sm" onClick={onStartChat} disabled={startingChat}>
              {startingChat ? '正在创建对话…' : '用此世界开始对话'}
            </button>
          </>
        ) : (
          <button type="button" className="btn btn-primary btn-sm" onClick={onSave} disabled={saving}>
            {saving ? '正在保存…' : '保存到模板库'}
          </button>
        )}
      </div>
      {!saved && <p className="hint" style={{ marginTop: 8 }}>{result.job_id ? '完整结果已保留在生成记录中，刷新后仍可找回。' : ''}保存后可在新对话中直接使用这个世界和 Lore 条目。</p>}
      <details className="debug-card" style={{ marginTop: 12 }}>
        <summary>查看生成的命名和条目详情</summary>
        <div className="guide-inline" style={{ marginTop: 8 }}>
          <strong>人名</strong><div>{result.names.person_names.join('、') || '无'}</div>
        </div>
        <div className="guide-inline"><strong>地名</strong><div>{result.names.place_names.join('、') || '无'}</div></div>
        <div className="guide-inline"><strong>物品名</strong><div>{result.names.item_names.join('、') || '无'}</div></div>
        <div className="guide-inline"><strong>世界条目（前 8 条）</strong><ul className="compact-list">{result.lore_entries.slice(0, 8).map((item) => <li key={`${item.entry_type}-${item.title}`}>{item.entry_type} / {item.title}</li>)}</ul></div>
      </details>
    </div>
  );
}

/* ===================== 主页面 ===================== */

export default function WorkbenchPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const { triggerDelete, UndoToast } = useUndoDelete();
  const { draft, setField, ready: draftReady, saving: draftSaving, error: draftError } = useWorldDraft();
  const requestController = useRef<AbortController | null>(null);
  const navigationPrompt = useRef<AbortController | null>(null);
  const [generationActive, setGenerationActive] = useState(false);
  const [activeJobId, setActiveJobId] = useState<number | null>(null);
  const [generationNotice, setGenerationNotice] = useState('');
  useEffect(() => () => { requestController.current?.abort(); navigationPrompt.current?.abort(); }, []);
  const characterId = draft.characterId;
  const setCharacterId = (value: number | null) => setField('characterId', value);
  const generateWorldType = draft.generateWorldType;
  const setGenerateWorldType = (value: string) => setField('generateWorldType', value);
  const generateTheme = draft.generateTheme;
  const setGenerateTheme = (value: string) => setField('generateTheme', value);
  const generateTone = draft.generateTone;
  const setGenerateTone = (value: string) => setField('generateTone', value);
  const generateExtra = draft.generateExtra;
  const setGenerateExtra = (value: string) => setField('generateExtra', value);
  const generateLabel = draft.generateLabel;
  const setGenerateLabel = (value: string) => setField('generateLabel', value);
  const autoSaveGeneratedWorld = draft.autoSaveGeneratedWorld;
  const setAutoSaveGeneratedWorld = (value: boolean) => setField('autoSaveGeneratedWorld', value);
  const [generatedWorld, setGeneratedWorld] = useState<WorldGenerationResult | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get('tab') || 'create';
  const setActiveTab = (tab: string) => setSearchParams({ tab });
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [showManageAdvanced, setShowManageAdvanced] = useState(false);

  // import
  const importSourceFilename = draft.importSourceFilename;
  const setImportSourceFilename = (value: string) => setField('importSourceFilename', value);
  const importSourceText = draft.importSourceText;
  const setImportSourceText = (value: string) => setField('importSourceText', value);
  const importCategoryHint = draft.importCategoryHint;
  const setImportCategoryHint = (value: string) => setField('importCategoryHint', value);
  const autoSaveImportedWorld = draft.autoSaveImportedWorld;
  const setAutoSaveImportedWorld = (value: boolean) => setField('autoSaveImportedWorld', value);
  const [importedWorld, setImportedWorld] = useState<WorldImportResult | null>(null);

  // manage state
  const [templateEditing, setTemplateEditing] = useState<WorldTemplate | null>(null);
  const [templateId, setTemplateId] = useState('');
  const [templateLabel, setTemplateLabel] = useState('');
  const [templateCategory, setTemplateCategory] = useState('通用');
  const [templateSummary, setTemplateSummary] = useState('');
  const [templateGameplayMode, setTemplateGameplayMode] = useState('自由剧情');
  const [templatePrompt, setTemplatePrompt] = useState('');
  const [templateCoverImagePath, setTemplateCoverImagePath] = useState('');
  const [templateChoices, setTemplateChoices] = useState('');
  const [templateAntiCheat, setTemplateAntiCheat] = useState('');
  const [worldTemplateSearch, setWorldTemplateSearch] = useState('');
  const deferredWorldTemplateSearch = useDeferredValue(worldTemplateSearch.trim());
  const [coverAssetSearch, setCoverAssetSearch] = useState('');
  const [exportingTemplateId, setExportingTemplateId] = useState<string | null>(null);
  const [exportingBundle, setExportingBundle] = useState(false);
  const [lastTemplateResult, setLastTemplateResult] = useState<{ search: string; items: WorldTemplate[] } | null>(null);
  const [templateBaseline, setTemplateBaseline] = useState('');

  const charactersQuery = useQuery({ queryKey: ['characters'], queryFn: api.listCharacters });
  const worldTemplatesQuery = useQuery({
    queryKey: ['world-templates', deferredWorldTemplateSearch],
    queryFn: () => api.listWorldTemplates(deferredWorldTemplateSearch),
    placeholderData: keepPreviousData,
  });
  const coverAssetsQuery = useQuery({
    queryKey: ['assets', 'world_cover', coverAssetSearch],
    queryFn: () => api.listAssets('world_cover', coverAssetSearch),
  });
  useEffect(() => {
    if (!worldTemplatesQuery.isSuccess || worldTemplatesQuery.isPlaceholderData || !worldTemplatesQuery.data) return;
    setLastTemplateResult({ search: deferredWorldTemplateSearch, items: worldTemplatesQuery.data });
  }, [deferredWorldTemplateSearch, worldTemplatesQuery.data, worldTemplatesQuery.isPlaceholderData, worldTemplatesQuery.isSuccess]);
  const visibleWorldTemplates = worldTemplatesQuery.data ?? lastTemplateResult?.items ?? [];
  const templateDraftSnapshot = useMemo(() => JSON.stringify({
    templateId,
    templateLabel,
    templateCategory,
    templateSummary,
    templateGameplayMode,
    templatePrompt,
    templateCoverImagePath,
    templateChoices,
    templateAntiCheat,
  }), [templateAntiCheat, templateCategory, templateChoices, templateCoverImagePath, templateGameplayMode, templateId, templateLabel, templatePrompt, templateSummary]);
  const isTemplateDirty = Boolean(templateId) && Boolean(templateBaseline) && templateDraftSnapshot !== templateBaseline;

  const templateNavigationBlocker = useBlocker(({ currentLocation, nextLocation }) =>
    (generationActive && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search)) ||
    ((isTemplateDirty || Boolean(draftError) || draftSaving) && currentLocation.pathname !== nextLocation.pathname),
  );

  useEffect(() => {
    if (templateNavigationBlocker.state !== 'blocked') return;
    if (!generationActive && !isTemplateDirty && !draftError && !draftSaving) { templateNavigationBlocker.reset(); return; }
    const controller = new AbortController();
    navigationPrompt.current?.abort();
    navigationPrompt.current = controller;
    let active = true;
    void confirmModal(
      generationActive ? '世界仍在生成' : '内容尚未保存',
      generationActive ? '停止并离开会取消本次请求；已保存的草稿和完整结果会保留。' : '当前修改可能尚未保存。确认离开吗？',
      'warning', { signal: controller.signal, confirmLabel: generationActive ? '停止并离开' : '确认离开', cancelLabel: generationActive ? '继续生成' : '继续编辑' },
    ).then((leave) => {
      if (!active || controller.signal.aborted || templateNavigationBlocker.state !== 'blocked') return;
      if (leave) { requestController.current?.abort(); templateNavigationBlocker.proceed(); }
      else templateNavigationBlocker.reset();
    });
    return () => { active = false; controller.abort(); };
  }, [templateNavigationBlocker, generationActive, isTemplateDirty, draftError, draftSaving]);

  useBeforeUnload(useCallback((event) => {
    if (!isTemplateDirty && !generationActive && !draftSaving && !draftError) return;
    event.preventDefault();
    event.returnValue = '';
  }, [isTemplateDirty, generationActive, draftSaving, draftError]));

  async function runGeneration<T>(action: (signal: AbortSignal) => Promise<T>): Promise<T> {
    if (requestController.current) throw new Error('请先停止或等待当前生成完成');
    const controller = new AbortController();
    requestController.current = controller;
    setGenerationActive(true);
    setGenerationNotice('');
    try { return await action(controller.signal); }
    finally {
      requestController.current = null;
      setGenerationActive(false);
      navigationPrompt.current?.abort();
      if (templateNavigationBlocker.state === 'blocked') templateNavigationBlocker.reset();
      void queryClient.invalidateQueries({ queryKey: ['jobs', 'world'] });
    }
  }

  const generationError = (error: unknown) => {
    if (isAbortError(error)) setGenerationNotice('已停止本次请求。草稿仍然保留，可以修改后重新生成；已完成的结果可在生成记录中查看。');
    else showToast(error instanceof Error ? error.message : '生成失败，请重试', 'error');
  };

  async function handleExportTemplate(templateId: string) {
    if (exportingTemplateId) return;
    setExportingTemplateId(templateId);
    try {
      await api.exportWorldTemplate(templateId);
      showToast('\u4e16\u754c\u6a21\u677f\u5df2\u5f00\u59cb\u4e0b\u8f7d', 'success');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '\u4e16\u754c\u6a21\u677f\u5bfc\u51fa\u5931\u8d25', 'error');
    } finally {
      setExportingTemplateId(null);
    }
  }

  async function handleExportBundle() {
    if (exportingBundle) return;
    setExportingBundle(true);
    try {
      await api.exportWorldTemplateBundle(false);
      showToast('\u6a21\u677f\u5305\u5df2\u5f00\u59cb\u4e0b\u8f7d', 'success');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '\u6a21\u677f\u5305\u5bfc\u51fa\u5931\u8d25', 'error');
    } finally {
      setExportingBundle(false);
    }
  }

  const progress = useQuery({ queryKey: ['world-progress', activeJobId], queryFn: () => api.worldJobProgress(activeJobId!), enabled: generationActive && activeJobId !== null, refetchInterval: generationActive ? 1000 : false });
  const pause = useMutation({ mutationFn: () => api.pauseWorldJob(activeJobId!), onSuccess: () => { void progress.refetch(); }, onError: (error) => showToast(error instanceof Error ? error.message : '暂停请求失败，请重试', 'error') });
  async function createAndRun(operation: 'generate' | 'import', payload: object, signal: AbortSignal) {
    setActiveJobId(null);
    const job = await api.createWorldJob(operation, payload, signal);
    setActiveJobId(job.id);
    const outcome = await api.runWorldJob(job.id, signal);
    if (outcome.status === 'paused') setGenerationNotice('已暂停，完成的步骤已保存。可在生成记录中继续，不会重做已保存步骤。');
    return outcome.result;
  }
  const resume = useMutation({ mutationFn: (id: number) => runGeneration(async (signal) => {
    setActiveJobId(id);
    const outcome = await api.runWorldJob(id, signal);
    if (outcome.status === 'paused') setGenerationNotice('已暂停，完成的步骤已保存。');
    return outcome.result;
  }), onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['jobs', 'world'] }); void queryClient.invalidateQueries({ queryKey: ['world-templates'] }); }, onError: generationError });

  const generateWorldMutation = useMutation({
    mutationFn: () => {
      if (!generateTheme.trim()) {
        throw new Error('请填写「核心主题」（一句话描述，必填）');
      }
      return runGeneration((signal) => createAndRun('generate', {
        character_id: characterId,
        world_type: generateWorldType,
        core_theme: generateTheme,
        tone: generateTone,
        extra_requirements: generateExtra,
        label: generateLabel,
        auto_save: autoSaveGeneratedWorld,
      }, signal));
    },
    onSuccess: async (payload) => {
      if (!payload) return;
      setGeneratedWorld(payload);
      await queryClient.invalidateQueries({ queryKey: ['jobs', 'world'] });
      if (payload.saved_template) await queryClient.invalidateQueries({ queryKey: ['world-templates'] });
      showToast(payload.saved_template ? '世界设定已生成并已保存到模板库' : '世界设定已生成，可在下方查看结果', 'success');
    },
    onError: generationError,
  });

  const importWorldMutation = useMutation({
    mutationFn: () => {
      if (!importSourceFilename.trim()) {
        throw new Error('请填写「源文件名」（必填）');
      }
      if (!importSourceText.trim()) {
        throw new Error('请粘贴「世界设定原文」（必填）');
      }
      return runGeneration((signal) => createAndRun('import', {
        character_id: characterId,
        source_text: importSourceText,
        source_filename: importSourceFilename,
        category_hint: importCategoryHint,
        auto_save: autoSaveImportedWorld,
      }, signal));
    },
    onSuccess: async (payload) => {
      if (!payload) return;
      setImportedWorld(payload);
      await queryClient.invalidateQueries({ queryKey: ['jobs', 'world'] });
      if (payload.saved_template) await queryClient.invalidateQueries({ queryKey: ['world-templates'] });
      showToast(payload.saved_template ? '导入完成并已保存为模板' : '导入完成，可在下方查看结果', 'success');
    },
    onError: generationError,
  });

  const saveWorldResultMutation = useMutation({
    mutationFn: async ({ result }: { kind: 'generated' | 'imported'; result: WorldGenerationResult | WorldImportResult }) => {
      if (result.job_id) return (await api.saveWorldJobResult(result.job_id)).saved_template!;
      return api.importWorldTemplatePackage({
        package_json: {
          format_version: 1,
          exported_at: new Date().toISOString(),
          template: result.template,
          lore_entries: result.lore_entries,
        },
      });
    },
    onSuccess: async (savedTemplate, variables) => {
      const savedResult = { ...variables.result, saved_template: savedTemplate };
      if (variables.kind === 'generated') setGeneratedWorld(savedResult);
      else setImportedWorld(savedResult);
      await queryClient.invalidateQueries({ queryKey: ['world-templates'] });
      showToast('世界设定和 Lore 已保存到模板库', 'success');
    },
    onError: (e) => showToast(e instanceof Error ? e.message : '保存世界设定失败', 'error'),
  });

  const startWorldChatMutation = useMutation({
    mutationFn: (result: WorldGenerationResult | WorldImportResult) => {
      const template = result.saved_template;
      if (!template) throw new Error('请先保存这个世界');
      return api.createSessionWithConfig({
        title: `${template.label} · 新故事`,
        template_id: template.template_id,
        gameplay_mode: template.gameplay_mode,
        initial_character_ids: characterId ? [characterId] : undefined,
      });
    },
    onSuccess: async (session) => {
      await queryClient.invalidateQueries({ queryKey: ['sessions'] });
      showToast('已创建使用该世界的新对话', 'success');
      navigate(`/chat/${session.id}`);
    },
    onError: (e) => showToast(e instanceof Error ? e.message : '创建对话失败', 'error'),
  });

  const saveTemplateMutation = useMutation({
    mutationFn: async () => {
      if (!templateLabel.trim()) {
        throw new Error('请填写「模板名称」（必填）');
      }
      if (!templateEditing && !templateId.trim()) {
        throw new Error('新建时请填写「模板 ID」（英文唯一标识，必填）');
      }
      const payload = { label: templateLabel, category: templateCategory, summary: templateSummary, gameplay_mode: templateGameplayMode, world_prompt: templatePrompt, cover_image_path: templateCoverImagePath, suggested_choices: templateChoices.split('\n').map((item) => item.trim()).filter(Boolean), anti_cheat_prompt: templateAntiCheat };
      return templateEditing ? api.updateWorldTemplate(templateEditing.template_id, payload) : api.createWorldTemplate({ template_id: templateId.trim(), ...payload });
    },
    onSuccess: async (payload) => {
      fillTemplateForm(payload);
      await queryClient.invalidateQueries({ queryKey: ['world-templates'] });
      showToast('世界模板已保存', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const deleteTemplateMutation = useMutation({
    mutationFn: (id: string) => api.deleteWorldTemplate(id),
    onSuccess: async () => {
      clearTemplateForm();
      setManageQualityReport(null);
      await queryClient.invalidateQueries({ queryKey: ['world-templates'] });
      showToast('已删除该世界模板', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const [manageQualityReport, setManageQualityReport] = useState<WorldQualityReport | null>(null);
  const reviewManageQualityMutation = useMutation({
    mutationFn: async () => {
      let lore_entries: {
        title: string;
        entry_type: string;
        keywords_json: string[];
        content: string;
        sort_order: number;
        is_core: boolean;
      }[] = [];
      const tid = templateEditing?.template_id || templateId.trim();
      if (tid) {
        const lore = await api.listWorldLoreEntries(tid);
        lore_entries = lore.map((r) => ({
          title: r.title,
          entry_type: r.entry_type,
          keywords_json: r.keywords_json ?? [],
          content: r.content,
          sort_order: r.sort_order,
          is_core: r.is_core,
        }));
      }
      return api.reviewWorldQuality({
        template_id: tid || 'preview',
        label: templateLabel,
        category: templateCategory,
        summary: templateSummary,
        gameplay_mode: templateGameplayMode,
        world_prompt: templatePrompt,
        cover_image_path: templateCoverImagePath,
        suggested_choices: templateChoices.split('\n').map((item) => item.trim()).filter(Boolean),
        anti_cheat_prompt: templateAntiCheat,
        lore_entries,
      });
    },
    onSuccess: (report) => {
      setManageQualityReport(report);
      showToast('已生成完整质量报告（含 Lore）', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  function fillWithRandom() {
    const themes = [
      '一个被遗忘的上古文明苏醒，改变了整个世界的力量格局',
      '各大势力为争夺一件远古神器而展开明争暗斗',
      '天灾降临，幸存者们必须在废墟中重建文明',
      '一个普通少年/少女偶然获得了改变命运的力量',
      '来自异界的入侵者打破了长久的和平，各方被迫联手',
      '古老预言中的末日即将到来，主角必须在有限时间内找到救赎',
      '隐藏在幕后的组织操控着世界的历史走向',
      '人类与异族的矛盾日益激化，战争一触即发',
    ];
    setGenerateWorldType(WORLD_TYPE_OPTIONS[Math.floor(Math.random() * WORLD_TYPE_OPTIONS.length)]);
    setGenerateTheme(themes[Math.floor(Math.random() * themes.length)]);
    setGenerateTone('史诗冒险');
    setGenerateLabel('');
    setGenerateExtra('请根据主题生成一个完整且充满细节的世界设定，包含地理、势力、文化等核心要素');
  }

  function fillWithAIDetail() {
    const expandMap: Record<string, string> = {
      '修仙': '构建一个宏大的修仙世界，包含：修炼境界体系（炼气→筑基→金丹→元婴→化神→大乘→渡劫）、五大修仙宗门（剑宗、符宗、丹宗、器宗、阵宗）、正道与魔道的千年恩怨、散修与宗门弟子的生存差异、灵药与妖兽的分布地图、修仙界的货币与资源体系。',
      '玄幻': '构建一个瑰丽奇诡的玄幻世界，包含：血脉觉醒体系、远古神族遗脉、万族林立（人族、妖族、灵族、魔族）、天材地宝与秘境探险、宗门与帝国的权力博弈、上古战场与失落遗迹。',
      '剑与魔法': '构建一个经典的剑与魔法世界，包含：魔法体系（元素、神圣、暗影、自然、时空）、战士与法师的职业划分、龙族与巨人等远古种族、王国与公国的政治格局、冒险者公会的运作规则、地下城与宝藏的分布。',
      'DND': '构建一个适合DND跑团的战役世界，包含：费伦风格的地理设定、主要城市与地下城、神明与信仰体系、种族分布（人类、精灵、矮人、半身人、龙裔等）、适合冒险的危机事件与隐藏阴谋。',
      '战锤': '构建一个 grimdark 的战锤风格世界，包含：人类帝国与混沌势力的对抗、星际战士战团与修会、异形种族（绿皮、灵族、泰伦）、机械神教与技术异端、亚空间与恶魔的威胁。',
      '科幻': '构建一个硬科幻风格的世界，包含：星际航行与超光速引擎、外星文明与第一类接触、人工智能与赛博格技术、地球联邦与星际殖民地的政治格局、科技伦理与人类存亡的抉择。',
      '赛博朋克': '构建一个赛博朋克风格的近未来世界，包含：巨型企业与政府的权力博弈、街头文化与黑客地下社会、义体改造与意识上传技术、贫富分化的城市生态、数字空间与虚拟现实的设定。',
    };
    const expanded = expandMap[generateWorldType] || `请详细展开一个${generateWorldType}风格的世界设定，包含地理、势力、文化、种族、历史等核心要素。`;
    setGenerateExtra(expanded);
  }

  function fillTemplateForm(template: WorldTemplate) {
    setManageQualityReport(null);
    setTemplateEditing(template); setTemplateId(template.template_id); setTemplateLabel(template.label);
    setTemplateCategory(template.category); setTemplateSummary(template.summary); setTemplateGameplayMode(template.gameplay_mode);
    setTemplatePrompt(template.world_prompt); setTemplateCoverImagePath(template.cover_image_path ?? '');
    setTemplateChoices((template.suggested_choices ?? []).join('\n')); setTemplateAntiCheat(template.anti_cheat_prompt);
    setTemplateBaseline(JSON.stringify({
      templateId: template.template_id,
      templateLabel: template.label,
      templateCategory: template.category,
      templateSummary: template.summary,
      templateGameplayMode: template.gameplay_mode,
      templatePrompt: template.world_prompt,
      templateCoverImagePath: template.cover_image_path ?? '',
      templateChoices: (template.suggested_choices ?? []).join('\n'),
      templateAntiCheat: template.anti_cheat_prompt,
    }));
  }

  function clearTemplateForm() {
    setTemplateEditing(null); setTemplateId(''); setTemplateLabel(''); setTemplateCategory('通用');
    setTemplateSummary(''); setTemplateGameplayMode('自由剧情'); setTemplatePrompt(''); setTemplateCoverImagePath('');
    setTemplateChoices(''); setTemplateAntiCheat('');
    setManageQualityReport(null);
    setTemplateBaseline('');
  }

  async function confirmTemplateReplacement(): Promise<boolean> {
    if (!isTemplateDirty) return true;
    return confirmModal('世界设定尚未保存', '打开其他世界会丢失当前修改。确认继续吗？');
  }

  async function openTemplateForm(template: WorldTemplate) {
    if (templateEditing?.template_id === template.template_id) return;
    if (!await confirmTemplateReplacement()) return;
    fillTemplateForm(template);
  }

  async function openNewTemplateForm() {
    if (!await confirmTemplateReplacement()) return;
    clearTemplateForm();
    const generatedId = `world_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
    setTemplateId(generatedId);
    setTemplateBaseline(JSON.stringify({
      templateId: generatedId,
      templateLabel: '',
      templateCategory: '通用',
      templateSummary: '',
      templateGameplayMode: '自由剧情',
      templatePrompt: '',
      templateCoverImagePath: '',
      templateChoices: '',
      templateAntiCheat: '',
    }));
  }

  async function closeTemplateForm() {
    if (!await confirmTemplateReplacement()) return;
    clearTemplateForm();
  }

  async function manageSavedWorld(result: WorldGenerationResult | WorldImportResult) {
    const template = result.saved_template;
    if (!template) return;
    if (!await confirmTemplateReplacement()) return;
    fillTemplateForm(template);
    setWorldTemplateSearch('');
    setActiveTab('manage');
  }

  if (!draftReady) return <div className="page-card" role="status">正在恢复创作草稿…</div>;

  return (<>
    <div className="workbench-layout" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* ==================== 顶部标签切换 ==================== */}
      <div className="page-card" style={{ padding: '12px 16px' }}>
        <div className="creation-workspace-toolbar">
          <CreationHomeLink />
          <div className="creation-workspace-tabs">
            <button type="button" className={`btn ${activeTab === 'create' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setActiveTab('create')}>
              <UiIcon name="sparkles" />生成世界
            </button>
            <button type="button" className={`btn ${activeTab === 'import' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setActiveTab('import')}>
              <UiIcon name="import" />从文本整理
            </button>
            <button type="button" className={`btn ${activeTab === 'manage' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setActiveTab('manage')}>
              <UiIcon name="world" />我的世界
            </button>
            <button type="button" className={`btn ${activeTab === 'history' ? 'btn-primary' : 'btn-ghost'}`} onClick={() => setActiveTab('history')}>生成记录</button>
          </div>
        </div>
      </div>

      {draftError && <p className="inline-query-error" role="alert">{draftError}</p>}
      {generationNotice && <div className="hint" role="status">{generationNotice} <button type="button" className="btn btn-ghost btn-sm" onClick={() => setActiveTab('history')}>查看生成记录</button></div>}
      {generationActive && <div className="page-card world-generation-progress" role="status"><div><strong>{progress.data?.status === 'pause_requested' ? '正在暂停…' : '正在构建世界设定'}</strong><p className="hint">已保存 {progress.data?.completed_steps || 0} 个步骤{progress.data?.stage_label ? ` · ${progress.data.stage_label}` : ''}。暂停会在当前步骤保存后生效。</p></div><div className="button-row">
        <button type="button" className="btn btn-primary" disabled={!activeJobId || pause.isPending || progress.data?.status !== 'running'} onClick={() => pause.mutate()}>暂停生成</button>
        <button type="button" className="btn btn-ghost" onClick={() => requestController.current?.abort()}>停止生成</button></div></div>}
      {generationActive && progress.isError && <InlineQueryError message="进度读取失败，生成请求仍在进行" error={progress.error} onRetry={() => void progress.refetch()} />}
      {activeTab === 'history' && <WorldJobHistory onManage={(result) => { void manageSavedWorld(result); }} onResume={(id) => resume.mutate(id)} generationActive={generationActive} />}


      {/* ==================== 快速创建世界 ==================== */}
      {activeTab === 'create' && (
        <div className="page-card">
          <div className="card-header">
            <div>
              <p className="eyebrow">世界生成器</p>
              <h2>快速创建世界设定</h2>
            </div>
            <div className="card-actions">
              <button type="button" className="btn btn-ghost" onClick={fillWithRandom} title="随机生成所有字段">
                <UiIcon name="regenerate" />换个主题
              </button>
              <button type="button" className="btn btn-ghost" onClick={fillWithAIDetail} title="用 AI 填充详细要求">
                <UiIcon name="sparkles" />补充详细要求
              </button>
            </div>
          </div>

          <p style={{ fontSize: '0.85rem', color: 'var(--text-2)', marginBottom: 16 }}>
            选择世界类型和主题，点击下方按钮一键生成完整世界设定。所有字段都会同时填充，不会冲突。
          </p>

          <form onSubmit={(event) => { event.preventDefault(); generateWorldMutation.mutate(); }}>
            <div className="form-row">
              <div className="form-group">
                <label className="required">世界类型</label>
                <select value={generateWorldType} onChange={(event) => setGenerateWorldType(event.target.value)}>
                  {WORLD_TYPE_OPTIONS.map((item) => (<option value={item} key={item}>{item}</option>))}
                </select>
              </div>
              <div className="form-group">
                <label>世界名称（可选）</label>
                <input value={generateLabel} onChange={(event) => setGenerateLabel(event.target.value)} placeholder="例如：苍玄大陆" maxLength={50} />
              </div>
            </div>

            <div className="button-row workbench-mobile-cta">
              <button className="btn btn-primary btn-lg" type="submit" disabled={generationActive}>
                {generateWorldMutation.isPending && <UiIcon name="loading" className="ui-icon-loading" />}{generateWorldMutation.isPending ? '生成中…' : '生成世界设定'}
              </button>
            </div>

            <div className="form-group" style={{ marginTop: 10 }}>
              <label className="required">核心主题（一句话描述世界核心冲突）</label>
              <textarea rows={2} value={generateTheme} onChange={(event) => setGenerateTheme(event.target.value)} placeholder="例如：一个被遗忘的上古文明苏醒，改变了整个世界的力量格局" />
            </div>

            <details className="debug-card" style={{ marginTop: 10 }} open={Boolean(generateExtra)}>
              <summary>详细设定要求（可选）</summary>
              <div className="form-group" style={{ marginTop: 8 }}>
                <textarea rows={4} value={generateExtra} onChange={(event) => setGenerateExtra(event.target.value)} placeholder="告诉 AI 你想要的详细设定，例如修炼体系、宗门、势力分布等。留空则 AI 会自动补全基础内容。" />
              </div>
            </details>

            <div className="form-row" style={{ marginTop: 10, alignItems: 'end' }}>
              <div className="form-group">
                <label>使用模型</label>
                <select value={characterId ?? ''} onChange={(event) => setCharacterId(event.target.value ? Number(event.target.value) : null)}>
                  <option value="">仅本地生成（免费，基础内容）</option>
                  {charactersQuery.data?.map((character) => (<option value={character.id} key={character.id}>{character.name}</option>))}
                </select>
              </div>
              <div className="form-group">
                <label>基调</label>
                <input value={generateTone} onChange={(event) => setGenerateTone(event.target.value)} placeholder="史诗冒险" />
              </div>
              <div className="form-group">
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.82rem', color: 'var(--text-2)', paddingTop: 8 }}>
                  <input type="checkbox" style={{ width: '16px', height: '16px', flexShrink: 0 }} checked={autoSaveGeneratedWorld} onChange={(event) => setAutoSaveGeneratedWorld(event.target.checked)} />
                  生成后自动保存
                </label>
              </div>
            </div>

            <div className="button-row workbench-desktop-actions" style={{ marginTop: 16 }}>
              <button className="btn btn-primary btn-lg" type="submit" disabled={generationActive}>
                {generateWorldMutation.isPending && <UiIcon name="loading" className="ui-icon-loading" />}{generateWorldMutation.isPending ? '生成中…' : '生成世界设定'}
              </button>
            </div>
          </form>

          {generatedWorld && (
            <div style={{ marginTop: 16 }}>
              <WorldResultCard
                result={generatedWorld}
                label="世界设定已生成"
                saving={saveWorldResultMutation.isPending && saveWorldResultMutation.variables?.kind === 'generated'}
                startingChat={startWorldChatMutation.isPending}
                onSave={() => saveWorldResultMutation.mutate({ kind: 'generated', result: generatedWorld })}
                onManage={() => { void manageSavedWorld(generatedWorld); }}
                onStartChat={() => startWorldChatMutation.mutate(generatedWorld)}
              />
            </div>
          )}

          <button type="button" className="btn btn-ghost" onClick={() => setActiveTab('history')}>查看生成记录与已保留结果</button>
        </div>
      )}

      {/* ==================== 从文本导入 ==================== */}
      {activeTab === 'import' && (
        <div className="page-card">
          <div className="card-header">
            <div>
              <p className="eyebrow">从文本导入</p>
              <h2>长文本转世界模板</h2>
            </div>
          </div>
          <p style={{ fontSize: '0.85rem', color: 'var(--text-2)', marginBottom: 16 }}>
            如果你有现成的世界设定文档（小说设定集、跑团战役手册），粘贴到下方让 AI 自动抽取结构化信息。
          </p>
          <form onSubmit={(event) => { event.preventDefault(); importWorldMutation.mutate(); }}>
            <div className="form-row">
              <div className="form-group">
                <label className="required">源文件名</label>
                <input value={importSourceFilename} onChange={(event) => setImportSourceFilename(event.target.value)} placeholder="例如：我的世界设定.txt" maxLength={100} required />
              </div>
              <div className="form-group">
                <label className="required">类型提示</label>
                <select value={importCategoryHint} onChange={(event) => setImportCategoryHint(event.target.value)}>
                  {WORLD_TYPE_OPTIONS.map((item) => (<option value={item} key={item}>{item}</option>))}
                </select>
              </div>
            </div>
            <div className="button-row workbench-mobile-cta">
              <button className="btn btn-primary btn-lg" type="submit" disabled={generationActive}>
                {importWorldMutation.isPending && <UiIcon name="loading" className="ui-icon-loading" />}{importWorldMutation.isPending ? '整理中…' : '整理世界设定'}
              </button>
            </div>
            <div className="form-group" style={{ marginTop: 10 }}>
              <label className="required">世界设定原文</label>
              <textarea rows={12} value={importSourceText} onChange={(event) => setImportSourceText(event.target.value)} placeholder="粘贴你的世界设定文本，AI 会自动抽取关键信息..." required />
            </div>
            <div className="workbench-import-actions workbench-import-desktop-actions" style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 10 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.82rem', color: 'var(--text-2)' }}>
                <input type="checkbox" style={{ width: '16px', height: '16px', flexShrink: 0 }} checked={autoSaveImportedWorld} onChange={(event) => setAutoSaveImportedWorld(event.target.checked)} />
                抽取后自动保存
              </label>
              <button className="btn btn-primary" type="submit" disabled={generationActive} style={{ marginLeft: 'auto' }}>
                {importWorldMutation.isPending && <UiIcon name="loading" className="ui-icon-loading" />}{importWorldMutation.isPending ? '整理中…' : '整理世界设定'}
              </button>
            </div>
          </form>

          {importedWorld && (
            <div style={{ marginTop: 16 }}>
              <WorldResultCard
                result={importedWorld}
                label="世界设定已抽取"
                saving={saveWorldResultMutation.isPending && saveWorldResultMutation.variables?.kind === 'imported'}
                startingChat={startWorldChatMutation.isPending}
                onSave={() => saveWorldResultMutation.mutate({ kind: 'imported', result: importedWorld })}
                onManage={() => { void manageSavedWorld(importedWorld); }}
                onStartChat={() => startWorldChatMutation.mutate(importedWorld)}
              />
            </div>
          )}
        </div>
      )}

      {/* ==================== 管理已有世界（折叠） ==================== */}
      {activeTab === 'manage' && (
        <>
          <div className="page-card">
            <div className="card-header">
              <div><p className="eyebrow">世界模板</p><h2>所有已保存的世界</h2></div>
              <div className="card-actions">
                <button type="button" className="btn btn-primary btn-sm" onClick={() => { void openNewTemplateForm(); }}><UiIcon name="plus" />新建世界</button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => setShowManageAdvanced((v) => !v)}><UiIcon name="more" />{showManageAdvanced ? '收起工具' : '更多工具'}</button>
              </div>
            </div>
            <div className="form-group" style={{ marginBottom: 12 }}>
              <input value={worldTemplateSearch} onChange={(event) => setWorldTemplateSearch(event.target.value)} placeholder="按名称搜索世界…" aria-label="按名称搜索世界" />
            </div>
            {worldTemplatesQuery.isLoading && <div className="secondary-sidebar-empty"><UiIcon name="loading" className="ui-icon-loading" /><p>正在读取世界资料…</p></div>}
            {worldTemplatesQuery.isFetching && !worldTemplatesQuery.isLoading && !worldTemplatesQuery.isError && (
              <p className="hint">正在更新模板列表…</p>
            )}
            {worldTemplatesQuery.isError && (
              <InlineQueryError
                message={lastTemplateResult ? '模板列表更新失败，仍显示上次结果' : '世界模板加载失败'}
                error={worldTemplatesQuery.error}
                retrying={worldTemplatesQuery.isFetching}
                onRetry={() => { void worldTemplatesQuery.refetch(); }}
              />
            )}
            {worldTemplatesQuery.isSuccess && !worldTemplatesQuery.isPlaceholderData && visibleWorldTemplates.length === 0 && (
              <div className="secondary-sidebar-empty">
                <UiIcon name={worldTemplateSearch.trim() ? 'search' : 'world'} />
                <p>{worldTemplateSearch.trim() ? '没有匹配的世界' : '还没有保存的世界'}</p>
                {worldTemplateSearch.trim() ? (
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => setWorldTemplateSearch('')}>清空搜索</button>
                ) : (
                  <button type="button" className="btn btn-primary btn-sm" onClick={() => { void openNewTemplateForm(); }}><UiIcon name="plus" />新建第一个世界</button>
                )}
              </div>
            )}
            <div className="stack-list workbench-world-list">
              {visibleWorldTemplates.map((template) => (
                <div key={template.id} className="mini-card workbench-world-row">
                  <span className="workbench-world-icon"><UiIcon name="world" /></span>
                  <div className="workbench-world-copy">
                    <strong>{template.label}</strong>
                    <span>{template.category} · {template.gameplay_mode}</span>
                    <p>{template.summary || '尚未填写简介'}</p>
                  </div>
                  <div className="card-actions">
                    <button type="button" className="btn btn-primary btn-sm" onClick={() => { void openTemplateForm(template); }}><UiIcon name="edit" />编辑</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => void handleExportTemplate(template.template_id)} disabled={exportingTemplateId === template.template_id}><UiIcon name="archive" />导出</button>
                    {!template.is_builtin && (
                      <button type="button" className="btn btn-ghost btn-sm btn-danger" onClick={() => { triggerDelete(template.label, () => deleteTemplateMutation.mutate(template.template_id), () => {}); }}><UiIcon name="delete" />删除</button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* 编辑/新建模板表单 */}
          {(templateId || templateEditing) && (
            <div className="page-card" style={{ borderColor: 'var(--accent)' }}>
              <div className="card-header">
                <div><p className="eyebrow">{templateEditing ? '编辑世界' : '新世界'}</p><h2>{templateEditing ? templateEditing.label : '建立一个空白世界'}</h2></div>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => { void closeTemplateForm(); }}><UiIcon name="close" />关闭</button>
              </div>
              <form className="form-grid" onSubmit={(event) => { event.preventDefault(); saveTemplateMutation.mutate(); }}>
                <div className="form-group"><label className="required" htmlFor="world-template-label">世界名称</label><input id="world-template-label" value={templateLabel} onChange={(event) => setTemplateLabel(event.target.value)} required placeholder="例如：雾海诸国" /></div>
                <div className="form-group"><label htmlFor="world-template-category">分类</label><input id="world-template-category" value={templateCategory} onChange={(event) => setTemplateCategory(event.target.value)} /></div>
                <div className="form-group"><label htmlFor="world-template-mode">玩法模式</label><input id="world-template-mode" value={templateGameplayMode} onChange={(event) => setTemplateGameplayMode(event.target.value)} /></div>
                <div className="full-row form-group"><label htmlFor="world-template-summary">摘要</label><textarea id="world-template-summary" rows={3} value={templateSummary} onChange={(event) => setTemplateSummary(event.target.value)} /></div>
                <div className="full-row form-group">
                  <label htmlFor="world-template-prompt">世界背景设定</label>
                  <textarea id="world-template-prompt" rows={8} value={templatePrompt} onChange={(event) => setTemplatePrompt(event.target.value)} placeholder="描述世界背景、地理、势力、文化与历史…" />
                </div>
                <details className="debug-card full-row">
                  <summary>更多设置</summary>
                  <div className="form-group" style={{ marginTop: 8 }}><label htmlFor="world-template-choices">建议开场选择（每行一条）</label><textarea id="world-template-choices" rows={3} value={templateChoices} onChange={(event) => setTemplateChoices(event.target.value)} /></div>
                  <div className="form-group"><label htmlFor="world-template-rules">固定规则</label><textarea id="world-template-rules" rows={3} value={templateAntiCheat} onChange={(event) => setTemplateAntiCheat(event.target.value)} placeholder="约束角色能力、世界边界或不可违背的设定" /></div>
                  {templateCoverImagePath && <div className="guide-inline"><span>已选择封面</span><button type="button" className="btn btn-ghost btn-sm" onClick={() => setTemplateCoverImagePath('')}>移除封面</button></div>}
                  <div className="form-group"><label htmlFor="world-template-cover-search">搜索内置封面</label><input id="world-template-cover-search" value={coverAssetSearch} onChange={(event) => setCoverAssetSearch(event.target.value)} placeholder="按标签搜索" /></div>
                  <div className="stack-list">{coverAssetsQuery.data?.map((asset) => (<article className="mini-card" key={asset.id}><header><strong>{asset.label}</strong><small>{asset.license_name}</small></header><button className="ghost-button" type="button" onClick={() => setTemplateCoverImagePath(asset.storage_path)}>设为封面</button></article>))}</div>
                </details>
                <div className="button-row full-row" style={{ marginTop: 16 }}>
                  <button
                    type="button"
                    className="btn btn-ghost"
                    disabled={reviewManageQualityMutation.isPending}
                    onClick={() => reviewManageQualityMutation.mutate()}
                  >
                    {reviewManageQualityMutation.isPending && <UiIcon name="loading" className="ui-icon-loading" />}{reviewManageQualityMutation.isPending ? '分析中…' : '检查世界完整度'}
                  </button>
                  <button className="btn btn-primary" type="submit" disabled={saveTemplateMutation.isPending}>{saveTemplateMutation.isPending ? '保存中…' : '保存世界设定'}</button>
                </div>
                {manageQualityReport && (
                  <div className="full-row" style={{ marginTop: 12 }}>
                    <QualityReportCard report={manageQualityReport} />
                  </div>
                )}
              </form>
            </div>
          )}

          {/* 高级工具 */}
          {showManageAdvanced && (
            <div className="page-card" style={{ borderColor: 'var(--line-soft)' }}>
              <div className="card-header"><h2>转移世界资料</h2></div>
              <p style={{ fontSize: '0.82rem', color: 'var(--muted)', marginBottom: 12 }}>导出本机世界模板与 Lore，不经过模型服务。</p>
              <div className="form-group">
                <label>导出</label>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => void handleExportBundle()} disabled={exportingBundle}>
                  导出所有自定义模板
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
    {UndoToast}
  </>);
}
