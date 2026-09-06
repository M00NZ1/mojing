export type CostData = {
  date: string;
  model: string;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  cost: number;
};

export type SessionItem = {
  id: number;
  title: string;
  summary: string;
  created_at: string;
  updated_at: string;
  message_count: number;
  participant_count: number;
  think_max_enabled?: boolean;
  /** 与 Android SessionCard：会话内最新一条消息摘要 */
  last_message_preview?: string | null;
  world?: SessionWorld | null;
};

export type StoryWritingPayload = {
  premise: string;
  direction: string;
  tone: string;
  chapter_count: number;
  template_id?: string;
  encyclopedia_id?: number;
  character_ids: number[];
};

export type StoryWritingResult = {
  session_id: number;
  title: string;
  chapter_count: number;
  mode: 'story_writing';
  status: 'created';
};

export type SessionBranch = {
  branch_id: string;
  label: string;
  source_message_id?: number | null;
  parent_branch_id?: string | null;
  source_message_preview?: string | null;
  source_created_at?: string | null;
  latest_created_at?: string | null;
  depth: number;
  message_count: number;
  latest_message_id: number | null;
};

export type VoiceProfile = {
  id: number;
  name: string;
  provider: string;
  reference_audio_path: string;
  language: string;
  description: string;
  created_at: string;
};

export type Character = {
  id: number;
  name: string;
  persona_prompt: string;
  api_key: string;
  api_base_url: string;
  model_name: string;
  temperature: number;
  max_tokens: number;
  top_p: number;
  top_k: number;
  frequency_penalty: number;
  presence_penalty: number;
  repetition_penalty: number;
  avatar_color: string;
  avatar_image_path: string;
  /** 竖版封面图路径；列表/网格主图优先，空则回退头像 */
  card_image_path: string;
  voice_profile_id: number | null;
  voice_provider: string;
  voice_api_base_url: string;
  voice_api_key: string;
  voice_model: string;
  image_gen_enabled: boolean;
  image_gen_api_key: string;
  image_gen_base_url: string;
  image_gen_model: string;
  think_max_enabled?: boolean;
  think_max_model_name?: string;
  voice_profile?: VoiceProfile | null;
  favorite: boolean;
  created_at: string;
  updated_at: string;
};

export type AssetItem = {
  id: string;
  label: string;
  category: string;
  kind: string;
  preview_path: string;
  storage_path: string;
  external_url: string;
  source_label: string;
  author: string;
  license_name: string;
  attribution_required: boolean;
  is_builtin: boolean;
  tags: string[];
};

export type Participant = {
  id: number;
  sort_order: number;
  talkativeness?: number;
  character: Character;
};

export type SessionEventNode = {
  id: number;
  session_id: number;
  character_id: number | null;
  branch_id: string;
  parent_event_id: number | null;
  event_type: string;
  title: string;
  description: string;
  importance: number;
  message_id: number | null;
  resolved: boolean;
  created_at: string;
};

export type Message = {
  id: number;
  session_id: number;
  speaker_type: 'user' | 'character' | 'system' | 'narrator';
  character_id: number | null;
  branch_id: string;
  parent_message_id?: number | null;
  regenerated_from_message_id?: number | null;
  swipe_group_id?: string | null;
  character_name?: string | null;
  character_avatar_path?: string | null;
  content: string;
  structured_content: Record<string, unknown>;
  created_at: string;
  attachments?: Attachment[];
  /** 仅用于将 SSE 临时消息与对应的 message_start/delta/message_end 关联。 */
  stream_key?: string;
};

export type MessagePage = {
  items: Message[];
  next_cursor: number | null;
};

export type MessageWindowPage = {
  items: Message[];
  older_cursor: number | null;
  newer_cursor: number | null;
};

export type MessageSearchHit = {
  id: number;
  session_id: number;
  speaker_type: string;
  character_id: number | null;
  character_name?: string | null;
  branch_id: string;
  snippet: string;
  created_at: string;
};

export type VoiceClip = {
  kind: string;
  text: string;
  url: string;
};

export type Attachment = {
  id: number;
  asset_type: string;
  file_name: string;
  mime_type: string;
  storage_path: string;
  generation_prompt: string;
  generation_model: string;
  created_at: string;
};

export type SessionWorld = {
  id: number;
  session_id: number;
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
  auto_sediment_enabled: boolean;
  sediment_interval: number;
  updated_at: string;
};

export type CharacterTemplate = {
  id: string;
  label: string;
  gender: string;
  age_group: string;
  summary: string;
  persona_prompt: string;
  recommended_voice_names: string[];
};

export type ProviderModelPreset = {
  id: string;
  label: string;
  supports_vision: boolean;
  supports_audio: boolean;
  recommended: boolean;
  context_note: string;
};

export type ProviderPreset = {
  provider_id: string;
  label: string;
  base_url: string;
  model_param_name: string;
  notes: string;
  models: ProviderModelPreset[];
};

export type CharacterProfile = {
  id: number;
  character_id: number;
  source_filename: string;
  raw_persona_text: string;
  character_card_json: Record<string, unknown>;
  character_card_markdown: string;
  extracted_at: string;
};

export type SessionCharacterState = {
  id: number;
  session_id: number;
  character_id: number;
  dynamic_state_json: Record<string, unknown>;
  relations_json: Record<string, unknown>;
  private_facts_json: unknown[];
  event_log_json: unknown[];
  last_compacted_message_id: number | null;
  goals_json: unknown[];
  emotional_state: string;
  last_significant_event_id: number | null;
  updated_at: string;
};

export type SpeakerPlan = {
  character_ids: number[];
  reason: string;
};

export type WorldTemplate = {
  id: number;
  label: string;
  template_id: string;
  category: string;
  summary: string;
  gameplay_mode: string;
  world_prompt: string;
  cover_image_path: string;
  suggested_choices: string[];
  anti_cheat_prompt: string;
  is_builtin: boolean;
};

export type WorldEncyclopedia = {
  id: number;
  name: string;
  description: string;
  /** 与后端 `WorldEncyclopediaModel.genre_tags` 对齐；列表接口可能随 `__dict__` 一并返回 */
  genre_tags?: string;
  /** 与后端 `world_prompt` 对齐 */
  world_prompt?: string;
  gameplay_mode?: string;
  anti_cheat_prompt?: string;
  narrator_config_json?: Record<string, unknown>;
  cover_image_path: string;
  is_official: boolean;
  entry_count: number;
  created_at: string;
  updated_at: string;
};

export type WorldEncyclopediaSavePayload = {
  id?: number;
  name: string;
  description: string;
  genre_tags?: string;
  world_prompt?: string;
  gameplay_mode?: string;
  anti_cheat_prompt?: string;
  cover_image_path?: string;
  is_official?: boolean;
};

export type WorldLoreEntry = {
  id: number;
  world_template_id: number;
  title: string;
  entry_type: string;
  keywords_json: string[];
  content: string;
  sort_order: number;
  is_core: boolean;
  created_at: string;
  updated_at: string;
};

export type EncyclopediaMetaJson = Record<string, unknown>;

/** 与 `EncyclopediaPage.tsx` 中 `ENTRY_SCHEMAS` 顶层键一致；API 或跨端仍可能出现其它 `entry_type` 字符串。 */
export type EncyclopediaSchemaEntryKey =
  | 'world'
  | 'character'
  | 'location'
  | 'faction'
  | 'event'
  | 'item'
  | 'skill'
  | 'profession'
  | 'concept'
  | 'timeline';

const ENC_SCHEMA_KEYS: readonly EncyclopediaSchemaEntryKey[] = [
  'world',
  'character',
  'location',
  'faction',
  'event',
  'item',
  'skill',
  'profession',
  'concept',
  'timeline',
] as const;

export function isEncyclopediaSchemaEntryKey(v: string): v is EncyclopediaSchemaEntryKey {
  return (ENC_SCHEMA_KEYS as readonly string[]).includes(v);
}

export type EncyclopediaEntry = {
  id: number;
  encyclopedia_id: number;
  title: string;
  entry_type: string;
  summary: string;
  content: string;
  cover_image_path: string;
  tags: string;
  meta_json: EncyclopediaMetaJson;
  related_entries: string;
  sort_order: number;
  is_featured: boolean;
  source_session_id: number | null;
  source_message_id: number | null;
  confidence: string;
  last_referenced_at: string | null;
  created_at: string;
  updated_at: string;
};

/** 百科编辑表单草稿（新建 / 编辑弹层） */
export type EncyclopediaEntryDraft = Partial<
  Pick<
    EncyclopediaEntry,
    | 'id'
    | 'encyclopedia_id'
    | 'title'
    | 'entry_type'
    | 'summary'
    | 'content'
    | 'cover_image_path'
    | 'tags'
    | 'related_entries'
    | 'sort_order'
    | 'is_featured'
  >
> & {
  meta_json?: EncyclopediaMetaJson;
  change_note?: string;
};

export type EncyclopediaEntryDetail = {
  entry: EncyclopediaEntry;
  relations: Array<Record<string, unknown>>;
};

export type PromptTemplate = {
  id: number;
  template_id: string;
  label: string;
  category: string;
  scope: string;
  description: string;
  system_prompt: string;
  user_prompt: string;
  variables_json: string[];
  output_format: string;
  version: number;
  is_builtin: boolean;
  created_at: string;
  updated_at: string;
};

export type PromptTemplateRevision = {
  id: number;
  prompt_template_id: number;
  version: number;
  system_prompt: string;
  user_prompt: string;
  variables_json: string[];
  output_format: string;
  change_note: string;
  created_at: string;
};

export type GeneratedNamePack = {
  person_names: string[];
  place_names: string[];
  item_names: string[];
};

export type WorldQualityIssue = {
  level: string;
  code: string;
  message: string;
  suggestion: string;
};

export type WorldQualityReport = {
  score: number;
  verdict: string;
  strengths: string[];
  risks: string[];
  issues: WorldQualityIssue[];
};

export type WorldImportChunkDebug = {
  chunk_index: number;
  char_length: number;
  summary: string;
  extracted_titles: string[];
  extracted_types: string[];
  extracted_names: GeneratedNamePack;
  strategy: string;
};

export type WorldBuildDebug = {
  strategy: string;
  detected_category: string;
  chunk_count: number;
  merge_notes: string[];
  chunk_debug: WorldImportChunkDebug[];
};

export type WorldGenerationResult = {
  job_id?: number | null;
  template: {
    template_id: string;
    label: string;
    category: string;
    summary: string;
    gameplay_mode: string;
    world_prompt: string;
    cover_image_path: string;
    suggested_choices: string[];
    anti_cheat_prompt: string;
  };
  lore_entries: Array<{
    title: string;
    entry_type: string;
    keywords_json: string[];
    content: string;
    sort_order: number;
    is_core: boolean;
  }>;
  names: GeneratedNamePack;
  quality_report: WorldQualityReport;
  debug?: WorldBuildDebug | null;
  saved_template?: WorldTemplate | null;
};

export type WorldImportResult = WorldGenerationResult;

export type WorldTemplatePackage = {
  format_version: number;
  exported_at: string;
  template: {
    template_id: string;
    label: string;
    category: string;
    summary: string;
    gameplay_mode: string;
    world_prompt: string;
    cover_image_path: string;
    suggested_choices: string[];
    anti_cheat_prompt: string;
  };
  lore_entries: Array<{
    title: string;
    entry_type: string;
    keywords_json: string[];
    content: string;
    sort_order: number;
    is_core: boolean;
  }>;
};

export type WorldTemplateBundle = {
  format_version: number;
  exported_at: string;
  package_count: number;
  packages: WorldTemplatePackage[];
};

export type WorldTemplateBundlePreviewItem = {
  template_id: string;
  label: string;
  category: string;
  lore_entry_count: number;
  action: string;
  conflict_reason: string;
  existing_label: string;
  existing_is_builtin: boolean;
};

export type WorldTemplateBundlePreview = {
  package_count: number;
  create_count: number;
  overwrite_count: number;
  recreate_count: number;
  blocked_count: number;
  duplicate_ids: string[];
  replace_all_custom_templates: boolean;
  will_delete_template_ids: string[];
  warnings: string[];
  items: WorldTemplateBundlePreviewItem[];
};

export type SystemStatus = {
  database_ready: boolean;
  builtin_tts_ready: boolean;
  cloning_tts_ready: boolean;
  builtin_voice_count: number;
  python_version: string;
  cloning_status_message: string;
  recommended_flow: string[];
};

export type MacroItem = {
  macro: string;
  label: string;
  description: string;
};

export type VoiceServiceConfig = {
  mode: string;
  external_base_url: string;
  external_api_key: string;
  clone_endpoint: string;
  timeout_seconds: number;
  enabled: boolean;
};

export type ApiChannel = {
  id: string;
  label: string;
  provider: string;
  base_url: string;
  api_key: string;
  model_name: string;
  purpose: string;
  enabled: boolean;
};

export type ProviderOption = {
  id: string;
  label: string;
  base_url: string;
};

export type PurposeOption = {
  id: string;
  label: string;
};

export type LocalConfig = {
  max_upload_mb: number;
  memory_compact_threshold: number;
  default_world_template_id: string;
  default_narrator_enabled: boolean;
  default_choice_generation_enabled: boolean;
  default_anti_cheat_enabled: boolean;
  max_auto_speakers: number;
  public_text_api_key: string;
  public_text_base_url: string;
  public_text_model: string;
  public_image_api_key: string;
  public_image_base_url: string;
  public_image_model: string;
  public_voice_api_key: string;
  public_voice_base_url: string;
  public_voice_model: string;
  allow_session_think_max?: boolean;
  think_max_model?: string;
};

export type PublicApiProbeChannel = 'text' | 'image' | 'voice';

export type PublicApiProbeAttempt = {
  base_url: string;
  ok: boolean;
  error: string | null;
  detail: string | null;
};

export type PublicApiProbeResult = {
  ok: boolean;
  channel: string;
  error: string | null;
  detail: string | null;
  warnings: string[];
  meta: Record<string, unknown> | null;
  used_base_url?: string | null;
  attempts?: PublicApiProbeAttempt[];
};

export type JobRun = {
  id: number;
  job_type: string;
  status: string;
  scope: string;
  target_id: number | null;
  input_json: Record<string, unknown>;
  output_json: Record<string, unknown>;
  error_message: string;
  created_at: string;
  updated_at: string;
  started_at: string | null;
  finished_at: string | null;
};

export type WorldJobSummary = {
  request_version?: number | null;
  completed_steps?: number | null;
  stage_label?: string | null;
  id: number;
  job_type: string;
  status: string;
  label: string;
  error_message: string;
  result_version: number | null;
  created_at: string;
  finished_at: string | null;
};

export type TokenUsageStats = {
  system_prompt_tokens: number;
  character_tokens: number;
  memory_tokens: number;
  lore_tokens: number;
  encyclopedia_tokens?: number;
  history_tokens: number;
  total_tokens: number;
  model_context_limit: number;
  remaining_tokens: number;
};

export type Expression = {
  id: number;
  expression: string;
  label: string;
  image_path: string;
  sort_order: number;
};

export type MemorySegment = {
  id: number;
  session_id: number;
  branch_id: string;
  segment_index: number;
  start_message_id: number;
  end_message_id: number;
  summary: string;
  key_facts: string[];
  key_characters: string[];
  emotional_tone: string;
  created_at: string;
};

export type MemoryCorrection = {
  id: number;
  session_id: number;
  branch_id: string | null;
  content: string;
  source_message_id: number | null;
  created_at: string;
  updated_at: string;
};

export type EventNode = {
  id: number;
  session_id: number;
  character_id: number | null;
  branch_id: string;
  parent_event_id: number | null;
  event_type: string;
  title: string;
  description: string;
  importance: number;
  message_id: number | null;
  resolved: boolean;
  created_at: string;
};

export { ENCYCLOPEDIA_META_TOP_KEYS, encyclopediaMetaKeysForType } from './encyclopediaMetaShapes';
export type { EncyclopediaMetaBySchema } from './encyclopediaMetaShapes';

export interface ModelPlatform {
  id: string;
  name: string;
  base_url: string;
  api_key: string;
  models: string[];
  selected_model: string;
}
export interface ModelCatalog { version: number; active_id: string | null; platforms: ModelPlatform[] }
export interface ModelSelection { platform_id: string; model: string }
export interface ModelChoice { version: number; selection: ModelSelection | null }
